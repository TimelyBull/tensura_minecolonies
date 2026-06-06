# Project Roadmap

## Milestone 0 — Toolchain ✅
JDK 21, IntelliJ, Git, GitHub repo set up.

## Milestone 1 — Blank mod builds and runs ✅
MDK-based NeoForge mod compiles, loads in-game, and appears in the mod list.

## Milestone 2 — Dependency mods loading in dev ✅
MineColonies, Tensura, ManasCore (including sub-modules), Architectury, GeckoLib,
TerraBlender, SmartBrainLib, Structurize, and their runtime deps all load cleanly
via `libs/` jars in the dev environment.

## Milestone 3 — Vertical slice: goblin joins colony 🔄 IN PROGRESS

**Finalized design: "Two bodies, one identity, one materialized at a time."**
See `docs/decisions.md` for the full design rationale. Earlier dual-tracking and
spawn-suppression approaches are abandoned.

### Stage 1b — Pending pool for pre-colony naming ✅ COMPLETE
Goblins named before any colony exists are queued in `GoblinIdentitySavedData.pending`
and persist across save/reload. On `ColonyCreatedModEvent`, every still-alive
pending goblin is promoted to a real `ICitizenData` (count goes up) via the
same path normal naming uses. Stale entries (dead goblin) are dropped both
proactively (via the goblin-death hook) and at promotion time. Promoted
identities are full `GoblinIdentity` records — fully compatible with the
existing send/summon/death paths.

- [x] `PendingGoblin` record (identityId, name, goblinEntityUUID) saved/loaded
      in `GoblinIdentitySavedData`
- [x] Naming-time capture in the no-colony branch
- [x] `ColonyCreatedModEvent` subscription drains the pool
- [x] Stale check via `server.getAllLevels()` UUID lookup
- [x] Goblin-death hook also removes pending entries

### Stage A — Name → CitizenData + count, no body ✅ COMPLETE
Naming a goblin (server-side, when a colony exists) immediately creates a
`CitizenData` entry and increases the colony's citizen count. No `EntityCitizen`
is spawned. The goblin stays alive at the player's side as a Tensura subordinate.

- [x] Find Tensura naming event (`TensuraEntityEvents.NAMING_EVENT`)
- [x] Add Architectury + ManasCore sub-modules to compile classpath
- [x] Register naming listener; detect colony at goblin's position
- [x] Call `createAndRegisterCivilianData()`, set citizen name, log count
- [x] Store persistent identity (saved data linking goblin UUID → citizen ID)
- [x] Prevent `EntityCitizen` auto-spawn — `startTravellingTo(..., MAX_VALUE)`
      immediately after naming suppresses `updateEntityIfNecessary`'s respawn loop

### Stage B — Send-to-colony swap (ugly colonist) ✅ COMPLETE
Player triggers send. Goblin dissolves → default `EntityCitizen` materializes in
colony at a valid spawn point. No goblin renderer yet — default colonist appearance
is intentional at this stage.

- [x] Trigger: sneak-right-click named goblin with empty hand
- [x] Snapshot `IExistence` into `SavedData` before discarding goblin
- [x] `finishTravellingFor` + `spawnOrCreateCivilian(data, level, [townHallPos], force=true)`
- [x] `goblin.discard()` — swap removal, NOT death (no `LivingDeathEvent`)
- [x] Mode flag updated to `IN_COLONY` in `GoblinIdentitySavedData`

### Stage C — Summon swap + roster keybind menu 🔄 IN PROGRESS

**C1 — Core summon mechanic** ✅ COMPLETE
Placeholder trigger: `/summongoblin <name>` command. Looks up an `IN_COLONY`
identity by citizen name, discards the `EntityCitizen` (count unchanged),
re-marks travelling, and reconstructs a complete Tensura goblin from a
full-entity NBT snapshot.

- [x] Despawn `EntityCitizen` in colony (`discard()`, not `die()`)
- [x] Re-suppress respawn loop via `startTravellingTo`
- [x] Full-entity NBT roundtrip — send-side: `goblin.save(tag)` captures
      type, position, attributes, inventory, appearance, EvoState, and all
      Tensura storages (via ManasCore mixin's `"ManasCoreStorage"` subtag).
      Summon-side: `EntityType.create(tag, level)` reconstructs the entity,
      then `setUUID(random)` + `moveTo(playerPos)` before `addFreshEntity`.
      Replaces the earlier `IExistence`-only snapshot which lost evolution,
      stats, inventory, and appearance.
- [x] Update reverse map with the new goblin entity UUID
- [x] Item transfer (Option B — citizen as source-of-truth):
      send copies goblin armor/hands into `InventoryCitizen`
      (`forceArmorStackToSlot` + free-slot scan + `setHeldItem`), strips
      `HandItems`/`ArmorItems` from snapshot; summon reads citizen inventory
      back onto the goblin via `setItemSlot`. Overflow (citizen's 27 main
      slots vs goblin's 6 equipment slots) drops at the triggering player's
      position with a chat notice. Citizen inventory cleared after summon
      to avoid phantom carryover between swaps. `ItemStack.copy()` preserves
      full component data (enchants, custom names, durability, Tensura item data).

**C2 — Roster keybind menu** 🔄 IN PROGRESS

*C2a — Networking round-trip (proves the plumbing before the GUI)* ✅ COMPLETE
- [x] Add `ownerPlayerUUID` to `GoblinIdentity` and `PendingGoblin`
      (set from `player.getUUID()` at naming time; matches
      `IExistence.permanentOwner` set by Tensura's `submitNaming`)
- [x] Client keybind: default `G` (unbound in vanilla 1.21.1),
      registered via `RegisterKeyMappingsEvent` in a `@OnlyIn(CLIENT)` class
      initialised conditionally from the mod constructor
- [x] C2S `RequestRosterPayload` (empty), S2C `RosterResponsePayload`
      with `List<RosterEntry(identityId, name, modeByte)>`
- [x] Registered via `RegisterPayloadHandlersEvent` + `PayloadRegistrar`
- [x] Server handler filters identities by exact `ownerPlayerUUID` match,
      resolves citizen names via `colony.getCitizenManager().getCivilian(...).getName()`,
      replies via `PacketDistributor.sendToPlayer`
- [x] Client handler logs each entry (name + mode) — no Screen yet

*C2b — Roster Screen (two-way toggle)* ✅ COMPLETE
- [x] `RosterScreen` (plain `Screen` subclass) with `ObjectSelectionList<RosterRow>`
- [x] Row layout: name on left, status on right
      (green "At your side" for SUBORDINATE / gold "In colony" for IN_COLONY)
- [x] Empty-state: centred italic gray "No named goblins yet."
- [x] Click → C2S `ActOnIdentityPayload(identityId)` → server reads
      authoritative mode → routes to existing `sendGoblinToColony` or `summonGoblin`
- [x] Server re-sends roster after every action; client refreshes the
      open Screen in place (no reopen)
- [x] Keybind switched to `KeyConflictContext.IN_GAME` so it doesn't toggle
      while the Screen is open
- [x] Failure paths surface via green-italic chat advisory:
      no-colony, citizen-missing, goblin-not-loaded, not-your-goblin,
      and the chunk-not-loaded send failure
- [x] `sendOverflowNotice` now routes through a generic `sendAdvisoryNotice`
      helper — single chokepoint for the future Great Sage skill gate
- [x] `/summongoblin` remains as fallback

### Stage D — Identity persistence + death hooks 🔄 IN PROGRESS
The saved identity (name, citizen ID, goblin UUID, current mode) survives world
save/reload and repeated send/summon cycles. Death cleanup removes the identity
permanently — no revival, matches the "death reflects in the colony" rule.

- [x] Implement saved data (`GoblinIdentitySavedData` — built in Stage B)
- [x] Death hook A — goblin dies as subordinate: `LivingDeathEvent` (NeoForge
      bus) → `colony.getCitizenManager().removeCivilian(citizenData)` drops
      count, then `saved.removeIdentity` cleans the SavedData record
- [x] Death hook B — citizen dies in colony: `CitizenDiedModEvent`
      (MineColonies bus via `IMinecoloniesAPI.getInstance().getEventBus()`).
      Count is already handled by MineColonies' own `EntityCitizen.die()`;
      handler only cleans the SavedData record
- [x] Swap safety verified by construction: `discard()` never fires either
      death path; `EntityCitizen.discard()` posts `CitizenRemovedModEvent`
      (which we do not subscribe to), not `CitizenDiedModEvent`
- [ ] Verify identity survives server restart
- [ ] Verify citizen count stays correct across swaps

### Stage D2 — Magicule cost for swaps ✅ COMPLETE
Each send/summon costs the player magicule = target's current EP × 0.25,
read from the live body's IExistence. One shared chokepoint
(`chargeOrPrompt`) used by the menu, `/summongoblin`, and sneak-right-click —
no path bypasses the cost.

- [x] `readExistence(LivingEntity)` helper via ManasCore StorageHolder mixin
- [x] Cost = `targetExistence.getEP() * 0.25`
- [x] Sufficient → deduct from `playerExistence.getMagicule()`, run action
- [x] Insufficient → S2C `OpenCollapseConfirmPayload` opens the warning Screen
- [x] `ConfirmCollapseScreen` layered on top of `RosterScreen` (or no parent
      if triggered from command/sneak-click). Explicit wording about
      defenceless + could die. Cancel = no packet, no change. Proceed sends
      C2S `ConfirmCollapsePayload`
- [x] On Accept: `setMagicule(0)` exactly, never negative; Tensura's natural
      `handleSleepMode` tick detects depletion and enters Sleep Mode through
      its full pipeline (event fires, attribute applied, magicule reset to 1.0,
      unride, flying disabled). We do NOT suppress `ENTER_SLEEP_MODE_EVENT`
- [x] Sneak-click and `/summongoblin` rerouted through `handleMenuAction` so
      the chokepoint is enforced once for all entry points

### Stage D3 — Stat sync across the swap ✅ COMPLETE
Field-by-field copy of the IExistence stat subset (aura, magicule,
spiritualHealth, gainedEP, soulPoints, humanKill, alignment +
originalAlignment, the five destiny flags, targetNeutralList) plus
absolute HP copy. To avoid `MagiculePoisonEffect` killing the citizen
(which has zero default max for the energy attributes), the destination
body's max attributes are first boosted to the source's via a tracked
`AttributeModifier`. See `docs/decisions.md` → "Energy pool scale
mismatch between goblin and citizen bodies".

- [x] `copyStats(IExistence src, IExistence dst)` — absolute aura/magicule
      copy (NOT setEP — preserves imbalance), `clearNeutralTargets` +
      re-add for the list, `markDirty` at the end
- [x] `bumpBodyMaxAttributes(dst, src)` — adds a tracked permanent
      `AttributeModifier(SWAP_ENERGY_BOOST_ID, delta, ADD_VALUE)` lifting
      `MAX_AURA` / `MAX_MAGICULE` / `MAX_SPIRITUAL_HEALTH` / vanilla
      `MAX_HEALTH` on the destination up to the source's values so
      absolute copy is safe and round-trips cleanly
- [x] `copyHealthAbsolute(src, dst)` — `dst.setHealth(min(src.health,
      dst.maxHealth))`, defensive skip if source already dead
- [x] Send: stat copy goblin → citizen after item transfer, before
      `goblin.discard()`. Both bodies alive at the copy.
- [x] Summon: citizen discard deferred until AFTER goblin `addFreshEntity`.
      Order: reconstruct → setUUID → moveTo → applyInventory →
      `bumpBodyMaxAttributes` + `copyStats` + `copyHealthAbsolute` →
      `addFreshEntity` → discard citizen body
- [x] Magicule-cost round-trip is symmetric (EP carries across, citizens
      can actively gain/spend during colony service).

### Stage E — Magic circle visuals + delayed swap ✅ COMPLETE
The swap is fully dramatic: cost charged up front, big spinning Tensura
`MagicCircle` entities (SPACE variant, 3× size) at both ends, dissolve
body sinks through its circle into the ground, ~2s pause, materialize
body emerges from its circle, all positions locked at queue time.

**Timeline (constants):**
- `SWAP_DELAY_TICKS = 40` (2.0s — dissolve sink phase)
- `RISE_DURATION_TICKS = 20` (1.0s — materialize emergence)
- `CIRCLE_DURATION_TICKS = 80` (4.0s — covers sink + delay + rise + afterglow)
- `CIRCLE_SIZE = 3.0f` (3× default MagicCircle visual scale)
- `SINK_DEPTH = 3.0` blocks (dissolve body's drop)
- `RISE_START_OFFSET = 2.0` blocks (materialize body's underground spawn)

**Visuals:**
- [x] `new MagicCircle(level, player)` + `setVariant(SPACE)` + `setSpinning(true)`
      + `setSize(3.0f)` + `refreshDimensions()` + `setPos` + `addFreshEntity`.
      Standard entity replication; no custom packets.
- [x] Both ends spawn a circle: dissolve at the live body's current
      position; materialize at the locked destination (town hall pos for
      send, `summonMaterializePos(player)` for summon — see below).
- [x] Discard timer: `pendingCircles` list drained by `ServerTickEvent.Post`.
- [x] Diagnostic log on each circle spawn: position, caster, `addFreshEntity` result.

**Delay + position lock:**
- [x] Swap delay 40 ticks. Action queued in `pendingSwaps` with
      `(playerUUID, identityId, expectedMode, expectedGoblinUUID,
       magiculePaid, executeAtTick, materializePos)`.
- [x] `materializePos` captured at queue time so the materialize body
      appears where the circle is, regardless of player movement during
      the delay.
- [x] Summon materialize position: `summonMaterializePos(player)` —
      `player.position() + 3 × horizontal(lookAngle)` at the player's
      feet Y. Always 3 blocks ahead horizontally regardless of pitch, so
      the circle never spawns inside the ground when the player looks
      downward. Falls back to yaw direction for nearly-vertical looks.

**Sink and rise animations:**
- [x] `VerticalMovement` record: `(entityUUID, dim, lockX, lockZ, startY,
       targetY, startTick, endTick, clearInvulnerableOnEnd)`.
- [x] Tick handler interpolates Y linearly, locks X/Z via setPos each
      tick (prevents AI walking the body out of its circle), zeroes
      `deltaMovement` (no residual velocity), zeroes `fallDistance` (no
      accumulated fall damage on invulnerability clear).
- [x] Dissolve body: `setInvulnerable(true)` + sink from current Y to
      `currentY − SINK_DEPTH` over the delay. No need to clear invuln
      (body discarded at execute).
- [x] Materialize body: `markMaterializedBody(level, body, surfaceY)` —
      lowers body to `surfaceY − RISE_START_OFFSET`, marks invulnerable,
      queues rise over `RISE_DURATION_TICKS`. `clearInvulnerableOnEnd = true`
      so the body is normal again post-rise.
- [x] Order in tick handler: circles → vertical movements → swaps. Dissolve
      body's final sink position settles in the same tick the swap
      discards it.

**Cost handling:**
- [x] Cost charged **up front** in `chargeOrPrompt` (sufficient path) or
      `handleConfirmCollapse` (forced-collapse path). Never deferred.
- [x] Re-validate at execute time: identity exists, mode unchanged,
      goblin UUID unchanged (for SUBORDINATE), target body alive and
      resolvable. Any fail → advisory + `refundMagicule` capped at
      `EnergyHelper.getMaxMagicule(player)`.
- [x] Player-disconnect during delay: log, skip refund (can't safely
      modify offline data).

### Stage F — Goblin renderer (deferred polish) ⬜ PENDING
Override `EntityCitizen` rendering for goblin-citizens so they appear as goblins
in the colony, not default colonists. Reference: "Colonies Maid Citizen" mod.
**This is a firm end-product requirement, deliberately deferred until Stages A–E
are proven working.**

---

## Future Features

### Tier 1 (after vertical slice works)
- Race-restricted jobs
- Tensura-specific jobs: ore processing, weaponsmith, shop restocking
- Citizen skill leveling tied to Tensura skill system
- Boss Goblin → colony conversion
- Quest / skill-unlock rewrites

### Tier 2 (later)
- Reputation system
- Money / bank / vault
- Territory / barrier raids
- Retainer skill-bestowal

### Tier 3 (much later)
- Crime / police / disguise system
- Assassin system
- Dual reclaim mechanics
- Independent settlement leveling
- Angel raid
