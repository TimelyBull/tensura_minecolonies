# Raid system — investigation + v1 as-built record

**Status:** v1 built (custom raid event + reputation trigger + magicule
barrier block). Lore raid variants (Carrion's beastmen, clowns/Charybdis,
angel raid), multi-wave sieges, building damage, raiders physically
attacking the barrier block, dome rendering, and raid loot are all
DEFERRED.

Class references confirmed by `javap` against the jars in `libs/`.

---

## Investigation findings (what shaped the design)

### #1 PIVOTAL — MineColonies' native raid system: scheduler closed, event framework open

**How MC raids work (1.1.1319):**
- Per-colony `RaidManager` (`IRaiderManager`): decides `willRaidTonight`
  from colony size / days-since-raid / difficulty, picks the raid type
  **hardcoded by biome** (bytecode shows direct `new BarbarianRaidEvent` /
  `PirateRaidEvent` / `NorsemenRaidEvent` / `EgyptianRaidEvent` /
  `AmazonRaidEvent` / `DrownedPirateRaidEvent` calls — no registry
  lookup), computes a spawn point, creates a raid **event**.
- Events live in the colony's `IEventManager` — **`addEvent(IColonyEvent)`
  is public API.** Events NBT-persist and rehydrate through a real
  NeoForge registry: `minecolonies:colonyeventtypes`
  (`ColonyEventTypeRegistryEntry` = deserializer TriFunction +
  type id + `isRaidEvent` flag; key constant
  `CommonMinecoloniesAPIImpl.COLONY_EVENT_TYPES` is public static).
  **Third-party mods can register their own event types.**
- Land raids (`HordeRaidEvent`) track mobs in normal/archer/boss maps,
  show a `ServerBossEvent` raid bar, spawn via `spawnHorde`, receive
  `onUpdate()` per colony tick + `onNightFall()`, resolve through
  `onFinish()` / `RaidManager.onRaidEventFinished`.
- MC raiders are `AbstractEntityMinecoloniesRaider` subclasses with
  bespoke AI (`RaiderWalkAI` walks event waypoints, `RaiderMeleeAI` /
  `RaiderRangedAI` fight). That AI is welded to MC's raider hierarchy.
- **Key coupling find:** `RaidManager.isRaided()` scans the event manager
  for any active `IColonyRaidEvent`. A custom event implementing
  `IColonyRaidEvent` therefore activates MC's colony-wide raid behaviors
  (citizens flee/hide, raid state) **for free**, without MC raider mobs.

**Decision:** extend MC's raid *event framework* — custom
`TensuraRaidEvent implements IColonyRaidEvent`, registered in
`minecolonies:colonyeventtypes`, injected via
`colony.getEventManager().addEvent(...)`, with **our own trigger
scheduler and Tensura mobs**. MC's raider entities, raider AI, and the
biome-hardcoded scheduler are the closed parts; we don't use them.
`IRaiderManager` still serves as a utility library:
`calculateSpawnLocation()`, `getColonyRaidLevel()`,
`calculateRaiderAmount(int)`.

### #2 Spawning + threatening the colony — mostly already built

- **Raiders → citizens:** the existing hostility work
  (`tensuraHostileToCitizens` gamerule, see
  [hostile-mob-targets-citizens.md](hostile-mob-targets-citizens.md))
  already makes innately-hostile Tensura mobs target citizens. Belt and
  braces: a per-second target assist sets the SmartBrain `ATTACK_TARGET`
  (via SmartBrainLib's `BrainUtils.setTargetOfEntity`) to the nearest
  citizen when a raider has no target.
- **Guards → raiders:** `CompatibilityManager.discoverMobs()` auto-lists
  every `MobCategory.MONSTER` entity type in guard-tower attack lists —
  Tensura registers **54 monster types as `MobCategory.MONSTER`**
  (verified in `MonsterEntityTypes` bytecode). Guards engage natively;
  MC's ThreatTable handles retaliation.
- **Pathing:** Tensura mobs won't run MC's `RaiderWalkAI`; instead the
  proven `SubordinatePatrol` technique — feed the brain's vanilla
  `WALK_TARGET` memory each pass — steers raiders to the barrier /
  town hall. No mixin.
- **Spawning:** envoy/population pattern — `EntityType.create` +
  `finalizeSpawn(SPAWN_EGG)` + `setPersistenceRequired` +
  `addFreshEntity` at `IRaiderManager.calculateSpawnLocation()`
  (`EntityUtils.getSpawnPoint` fallback).

### #3 PIVOTAL — magicule barrier block: feasible as a field effect

Tensura has **no reusable barrier block** (its `BarrierMagic` /
`MagicWallMagic` are player skills), so the block is ours. The
simplification that keeps v1 cheap: **the barrier is a per-tick field
effect, not physical blocks** — no ghost-block tricks, no collision
hacks, no mixins.

- Block + BlockItem registration already proven in-repo
  (`EXAMPLE_BLOCK` via `DeferredRegister.Blocks`); BlockEntity is one
  standard `DeferredRegister<BlockEntityType<?>>`.
- BlockEntity holds `storedMagicule` (NBT-persisted) + server ticker.
- Field: while fueled AND a raid is active, a sphere radius R around
  the block; RAID-tagged entities crossing the shell are position-
  clamped + velocity-zeroed (same direct-entity-driving as the swap
  sink/rise animations — clamp to avoid jitter).
- **EP-scaled contact drain:** each raider pressing the shell drains
  magicule proportional to ITS EP (read from `IExistence`, same read
  as the roster/cost-gate) × a tuning coefficient. Stronger mob =
  faster drain. Literal block-attacking deferred.
- Refuel: sneak-right-click channels the player's own magicule (the
  read/write used everywhere in the swap-cost code); right-click with
  Tensura's `low/medium/high_quality_magic_crystal` items (verified in
  `TensuraMobDropItems`) for fixed amounts.
- Falls at 0: field off, sound + advisory; refuel mid-raid re-raises.
- Visual v1: particle shell (sampled sphere points).

### #4 Trigger via reputation — trivial

`ReputationManager.getReputation/isBelow` is exactly the read the
scheduler needs. Hook: the existing 1-second server-tick pass (where
the envoy scheduler runs). Nightfall detection per dimension via
`getDayTime() % 24000` crossing (same idiom as merchant dawn-restock,
inverted). Chance scales with the reputation deficit; per-colony
cooldown persisted in a small `RaidSavedData`; "one active raid" gate
by scanning `getEventManager().getEvents()`.

### #5 Consequences — existing plumbing

- **Loss:** raiders kill citizens — death handling complete
  (`CitizenDiedModEvent` cleanup; MC decrements its roster; mob kills
  correctly do NOT hit the player-attributed `CITIZEN_KILLED`
  reputation mover). Building damage deferred.
- **Victory** (all raiders dead before the timer):
  `modifyReputation(colony, +reward, RAID_REPELLED)` — the
  `ReputationReason` extension seam reserved by the reputation build.
- Timeout: remaining raiders despawn with the envoy poof pattern;
  no reputation change (it's already low).

---

## v1 as-built

### `TensuraRaidEvent` (implements `IColonyRaidEvent`)

- Registered as `tensura_minecolonies:tensura_raid` in
  `minecolonies:colonyeventtypes` via a `DeferredRegister` against
  `CommonMinecoloniesAPIImpl.COLONY_EVENT_TYPES` (entry flag
  `isRaidEvent = true` → `RaidManager.isRaided()` sees it → citizen
  flee/hide behavior activates).
- Tracks raider UUIDs (entities resolved live via
  `ServerLevel.getEntity(uuid)`), total spawned, an end tick
  (game-time), spawn position, and `EventStatus`. All NBT-persisted;
  the registry entry's deserializer restores a mid-raid event across
  save/reload (raid mobs persist via their own NBT + `RAID_TAG`
  attachment).
- `ServerBossEvent` raid bar — progress = alive/total, shown to players
  within range of the colony center, removed on resolve.
- Resolution (driven by the per-second pass, not colony ticks):
  all raiders dead → VICTORY (`+REP_RAID_REPELLED` via
  `modifyReputation(..., RAID_REPELLED)`, owner message); end tick
  passed → TIMEOUT (remaining raiders poof-despawned, neutral message).
  `onFinish` clears the bar and records the resolve tick for the
  cooldown.

### `RaidTag` attachment

`(colonyId, eventId)` NBT-persisted attachment (sibling of
`ENVOY_TAG`), stamped on every raid mob. It is the universal "is this a
raider" check used by steering, the barrier pushback/drain, and the
death bookkeeping — and it survives reload, which is what re-links
loaded mobs to their rehydrated event.

### Scheduler + trigger (`TensuraRaids.tick`, 1 s cadence)

Two passes per tick over each level's colonies:
1. **Drive active raids:** resolve each raider UUID; drop dead ones;
   target-assist (`BrainUtils.setTargetOfEntity` → nearest citizen
   within range when no current target); steer via `WALK_TARGET`
   toward the nearest ACTIVE barrier (if any) else the colony center;
   update the boss bar; resolve victory/timeout.
2. **Nightfall trigger:** per-dimension day-time rollover into night →
   for each colony: reputation tier below NEUTRAL → chance roll
   (WARY 15%, PASSIVEAGGRESSIVE 30%, HOSTILE 50% per night), gated by
   the persisted per-colony cooldown (`RaidSavedData`, 3 in-game days)
   and a no-active-raid scan. Trigger → spawn point from
   `IRaiderManager.calculateSpawnLocation()` (fallback
   `EntityUtils.getSpawnPoint`, then offset from center), construct
   event with `getAndTakeNextEventID()`, `addEvent`.

**Wave scaling:** base count = `calculateRaiderAmount(getColonyRaidLevel())`,
multiplied by `1 + deficit` where deficit = how far reputation sits
below the NEUTRAL floor (0..1) — clamped to [3, 12]. Roster tier by
colony raid level:

| Colony raid level | Roster (MONSTER types) |
|---|---|
| < 10 | Giant Ant, Black Spider |
| 10–19 | Hound Dog, Evil Centipede, Direwolf |
| ≥ 20 | Knight Spider, Blade Tiger, Evil Centipede |

(Divergence from the investigation sketch: Tensura has **no Ogre
entity** — the top tier uses Knight Spider / Blade Tiger instead.)

### Magicule barrier block (`magicule_barrier`)

- `BarrierBlock` (+ BlockItem, creative tab) + `BarrierBlockEntity`
  (`DeferredRegister<BlockEntityType<?>>`).
- Tank: `storedMagicule` / `BARRIER_CAPACITY` (100,000), NBT-persisted.
- Field active while `storedMagicule > 0` AND a raid is active for the
  colony at the block. Radius `BARRIER_RADIUS` (16). Each tick,
  RAID-tagged mobs inside the shell are clamped back to the surface
  with zeroed velocity.
- **EP-scaled drain:** each raider within the contact band drains
  `EP × BARRIER_DRAIN_COEFFICIENT_PER_SECOND / 20` per tick.
  **Coefficient = 0.02** (each raider drains 2% of its own EP per
  second) — e.g. a 3,000-EP wave mob drains 60/s; an 8-mob wave of
  those empties a full barrier in ~3.5 minutes of constant press.
  Tuning knob, clearly named.
- Blocked raiders' `WALK_TARGET` is steered at the barrier block, so
  they visibly cluster on the shell.
- Refuel: sneak-right-click (empty hand) channels up to 2,500 player
  magicule per click; right-click with magic crystals — low 2,500 /
  medium 10,000 / high 40,000.
- Depletion: field off + glass-break sound + advisory to nearby
  players; refueling re-raises it instantly.
- Visual: `END_ROD` particle shell sampled once per second while
  active.

### Consequences wiring

- Victory reward `REP_RAID_REPELLED = +8.0` through
  `ReputationManager.modifyReputation` (new `ReputationReason.RAID_REPELLED`).
- Citizen deaths during a raid flow through the existing death
  handling untouched.

### Debug

`/tensuraraid` (op) — force-starts a raid on the executing player's
colony immediately, bypassing nightfall/chance/cooldown (mirrors
`/spawnenvoy`). `/tensuraraid end` resolves the active raid as a
timeout for cleanup.

### Post-v1 playtest fixes (2026-06-09)

Three fixes from the first in-game raid test:

1. **Barrier channel is now plain right-click (empty hand), not
   sneak-right-click.** Vanilla's interaction pipeline skips block
   interactions entirely when a sneaking player holds ANY item in either
   hand (`ServerPlayerGameMode` checks `isSecondaryUseActive` before the
   block sees the click) — with a torch/shield in the offhand the channel
   never fired. Plain right-click is the reliable path; the fill readout
   rides along in every message, replacing the old status-only click.
2. **Target assist searches the colony's citizen ROSTER, not a 24-block
   AABB.** Small colonies put citizens outside the old scan radius for
   the entire approach, so raiders never aggroed. Now: nearest LOADED
   citizen from `colony.getCitizenManager().getCitizens()` within 64
   blocks, written to BOTH the SmartBrain `ATTACK_TARGET` memory AND
   vanilla `Mob#setTarget` — Tensura's target-invalidation predicate
   (`ISubordinate.shouldTarget`) short-circuits TRUE when
   `mob.getTarget() == target`, so the vanilla write is what stops the
   brain dropping a citizen it wouldn't normally treat as prey.
   Re-asserted every second by the steering pass.
3. **Raiders visibly attack the barrier.** While pressing the shell:
   face the block + swing once a second with CRIT particles at the
   impact point on the shell, plus a wooden-door-pounding knock every
   2 s while anything presses. Pure presentation — the EP-scaled drain
   IS the damage; literal block HP stays deferred.

### Barrier rework (2026-06-10) — square wall render, square field, spawn prevention

1. **Square wall render replaces the particle shell.** A client
   `BlockEntityRenderer` (`BarrierFieldRenderer`) draws four translucent
   textured quads (the player-supplied energy-field texture,
   `textures/block/barrier_field.png`, reconstructed to 16×16 with true
   alpha) at the footprint edges, double-winded so visible from both
   sides, fullbright, tiled per 4 blocks, spanning Y −4..+12 around the
   block. **Vertex alpha scales with the synced fill ratio**
   (0.10 near-empty → 0.85 full, on a ~65%-alpha texture) — the wall IS
   the at-a-glance strength readout. Renders whenever the barrier holds
   magicule (confirmed choice; the pushback field itself remains
   raid-only). `storedMagicule` syncs to clients via
   `getUpdateTag`/`getUpdatePacket` + a once-per-second
   `sendBlockUpdated` when the value moves >0.5% of capacity.
2. **The field is now a SQUARE (Chebyshev) footprint** — half-extent
   `BARRIER_RADIUS` (16), shared verbatim by the pushback/drain, the
   wall render, and the spawn prevention
   (`BarrierBlockEntity.isWithinFootprint` is the single definition).
   Pushback clamps through the NEAREST face. Replaces the v1 cylinder
   so the render and the functional area match exactly.
3. **Hostile-only spawn prevention inside a FUELED barrier** (confirmed
   choice: fuel required — an empty barrier protects nothing).
   - **Classification finding:** `MobCategory.MONSTER` is NOT a valid
     "only hostile" signal — Tensura registers goblins/orcs/lizardmen
     as MONSTER despite being passive/passive-aggressive. The correct
     classifier is Tensura's own curated **`tensura:hostile_monster`
     entity-type tag**: vanilla attacks-on-sight monsters
     (zombie/skeleton/creeper/…, with neutral mobs like endermen
     deliberately absent) plus Tensura's genuine hostiles
     (black spider, knight spider, daemons, orc lord/disaster, …) and
     none of the nameable races. Same tag the patrol targeting already
     trusts. Data-driven — a datapack can adjust it.
   - **Hook:** `MobSpawnEvent.PositionCheck` (NATURAL +
     CHUNK_GENERATION spawn types) → `Result.FAIL` when the type is
     tagged and (x, z) is inside any fueled barrier's square.
   - **Raids unaffected by construction:** raid mobs spawn via direct
     `EntityType.create + addFreshEntity`, which never posts
     PositionCheck — and they spawn at the colony perimeter regardless.
4. **Roster UI fix:** the reputation header line was truncating long
   tier names ("Passive-Aggressive · NN"). `repLine` widened to 126 px
   at textscale 0.85 (colony name narrowed to 106 px) — all six tier
   descriptors + value now fit.

### Deferred (explicit)

Lore variants (Carrion beastmen / clowns / Charybdis / angel), multi-wave
sieges, building/area damage, raiders physically damaging the barrier
block, translucent dome rendering, raid loot, per-raid-type barriers.
