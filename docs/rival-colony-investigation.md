# Investigation: the rival-colony arc — the two pivotal unknowns

**Status:** investigation only, no production code written (2026-06-12).
Scope deliberately limited to the two foundational feasibility
questions; the rest of the arc (defending mobs, war-teleport flow, the
wild/colony boss split, PvP, loot/citizen payout) is DEFERRED to a
follow-up investigation once these two are confirmed.

**Verdict up front: BOTH are FEASIBLE.** The arc is buildable
substantially as designed. Details, exact APIs (verified by `javap`
against the jars in `libs/`), the recommended approach, and the
lighter fallbacks below.

---

## PIVOTAL UNKNOWN 1 — Generating faux-MineColonies towns: **FEASIBLE**

**The core capability exists as a single MineColonies call.** A
MineColonies building schematic can be placed COMPLETE and INSTANT
(hut block + walls + decorations + furniture, fully built — not the
slow builder-over-time process) via:

```
CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
    Level, Future<Blueprint>, BlockPos, RotationMirror,
    boolean fancyPlacement, ServerPlayer)
```
(`com.minecolonies.api.util.CreativeBuildingStructureHandler`) — this
is the very handler MineColonies' own op/creative "instant build" and
supply-camp deployment use. The `Future<Blueprint>` comes from
Structurize's loader:
```
StructurePacks.getBlueprintFuture(packName, blueprintPath, registries)
StructurePacks.getBlueprint(packName, path, registries)   // sync variant
```
(`com.ldtteam.structurize.storage.StructurePacks`). Lower-level control
is available via `StructurePlacer` + `BlueprintPlacementHandling.
handlePlacement(BuildToolPlacementMessage)` with `HandlerType.Complete`
(instant full build), but `loadAndPlaceStructureWithRotation` is the
clean one-call path and is what I'd use.

**Reusing MineColonies' own building presets, themed per faction:**
the blueprint paths address MineColonies' shipped structure packs
(its hut schematics: townhall, blacksmith, tavern, barracks, etc.,
each at levels 1–5). A faction theme = a chosen subset/style of those
packs (MineColonies ships multiple visual styles — e.g. desert,
medieval, etc. — addressable by pack name), so "desert-style for
Carrion" is selecting the desert pack's blueprint paths. No new art
needed; we reuse existing presets exactly as the design intends.

**A coherent CLUSTER / town layout:** there is no automatic
"town generator" — layout is OUR responsibility. The clean approach is
a small **hand-authored layout descriptor per faction**: a list of
(blueprintPath, relativeOffset, rotation) entries around a center
(the town hall + boss). We place each with one
`loadAndPlaceStructureWithRotation` call at `center + offset`. A dozen
buildings = a dozen calls. This is deterministic, themeable, and avoids
the complexity of procedural town generation. Terrain flattening / a
foundation pad under the cluster is a minor add (a fill pass before
placement) — MineColonies' own placement already handles per-building
ground a little, but a faux-town on rough terrain will look better with
a cleared pad; flag as polish, not a blocker.

**World-gen feature vs. spawn-time placement — use SPAWN-TIME /
DEFERRED, not a vanilla StructureFeature.** MineColonies blueprints are
Structurize `.blueprint` format, NOT vanilla NBT structures, so they do
NOT slot into the vanilla jigsaw/StructureFeature world-gen pipeline.
The placement APIs above are server-side runtime calls. Therefore the
cleanest model is: **OUR OWN generation pass decides when/where a
faction "colony boss" arises and places the settlement at that moment**
(a scheduled/world-driven spawn, e.g. on the existing 1 s scheduler or
a chunk-load/biome trigger we own), rather than hooking the chunk
generator. This also means we control the wild-vs-colony split
ourselves (below) instead of fighting Tensura's internal spawn logic.

**Tying to boss generation (wild vs colony):** we do NOT need to hook
Tensura's internal boss spawns. The arc's "faction bosses generate in
two flavors" is best implemented as **our own generation system** (the
preview already recorded in docs/future-ideas.md — "generated bosses
have a CHANCE of belonging to a colony", config ALL/SOME/NONE). On a
generation tick we roll wild-vs-colony:
- WILD → spawn the boss alone, unmarked (the existing free-kill path —
  exactly the Stage-3 spare-boss spawn, no FactionMarkTag).
- COLONY → place the settlement cluster, then spawn the boss at its
  center and stamp it with `FactionMarkTag` (the marked-boss system
  already exists). The settlement + mark is the "rep-affecting" version.

So Unknown 1 reuses three things we already have or that ship in the
deps: the instant-build placer (MineColonies), our marked-boss system,
and our scheduler. **No vanilla world-gen integration required, which
is what makes it feasible.**

**FEASIBLE.** Closest-cheaper fallback if the full cluster proves
heavy: place FEWER buildings (a town hall + 3–4 key huts) rather than a
full town — same APIs, smaller descriptor. The mechanism doesn't change.

---

## PIVOTAL UNKNOWN 2 — Conquest → a real second player colony: **FEASIBLE** (moderate plumbing)

**The load-bearing call exists and takes an owning Player:**
```
IColonyManager.createColony(ServerLevel, BlockPos, Player, name, pack)
```
Decompiling the impl (`ColonyManager.createColony`) shows it does
exactly what we need, programmatically:
1. `IServerColonySaveData.createColony(level, name, pos)` — registers a
   new colony in the world's colony save data (assigns a colony id,
   begins chunk claiming around `pos`),
2. `colony.setStructurePack(pack)` + `colony.setName(name)`,
3. `colony.getPermissions().setOwner(player)` — **the colony is owned
   by our player**,
4. registers the player as a close/important subscriber.

So founding a player-owned colony at the conquered settlement's
location is a direct call — no need for the player to physically place
a town hall. (Founding normally happens *because* a town-hall hut block
fires this; we call it ourselves.)

**Incorporating the settlement's existing buildings:** a MineColonies
"building" is anchored by its **hut block** (carrying an
`AbstractTileEntityColonyBuilding` tile entity). The registration API
is `IRegisteredStructureManager.addNewBuilding(
AbstractTileEntityColonyBuilding, Level)`. Because we placed the
settlement from real MineColonies hut-block schematics, the hut blocks
+ their TEs already exist in the world. The conversion on conquest:
1. `createColony(level, townHallPos, player, name, pack)` — the
   settlement is laid out with a real **town hall** hut at its center,
   so this anchors there and claims the surrounding chunks (which
   contain the other huts),
2. walk the claimed area's block entities; for each
   `AbstractTileEntityColonyBuilding` hut TE, call
   `getBuildingManager().addNewBuilding(te, level)` to register it as a
   colony building,
3. set each registered building's level to the schematic's built level
   via `ISchematicProvider.setBuildingLevel(n)` so it reads as
   already-constructed (the huts were placed complete).

**Why this needs care (the moderate-plumbing flag):** schematic paste
sets the hut block + TE directly and does NOT fire the player-place
event (`AbstractBlockHut.onBlockPlacedBy`) that normally auto-registers
a hut with its colony. So registration must be done manually (step 2),
and building level/schematic-data must be set (step 3) or the buildings
register at level 0 and look unbuilt. None of this is blocked by the
API — `addNewBuilding` and `setBuildingLevel` are public — but it is
the part to nail carefully in the build phase, and the part most likely
to need iteration against live behavior (citizen spawning, work orders,
the request system attaching to the converted huts).

**The "easy extras" (confirmed trivial), for completeness:** the
citizen boost = `ICitizenManager.spawnOrCreateCitizen` / the
resurrection path we already use for lending; loot chests = vanilla
chest placement; the boss's Covenant skill = the
`DiplomacyManager.grantSkillReward` path built last session. These ride
on top of the colony conversion and carry no feasibility risk.

**FEASIBLE — with the registration/level plumbing as the known
moderate risk.** 

**Lighter fallback if full conversion proves too fiddly** (e.g. the
converted huts misbehave with citizens/work orders): a **"controlled
outpost."** Conquest still gives the satisfying payoff — you OWN and
claim the structure (we can claim chunks + set a per-player ownership
flag without a full simulated colony), the loot chests, the citizen
boost (spawned as your subordinates/citizens at your MAIN colony rather
than a new sim), and the Covenant skill — but the settlement is a
static conquered holding, not a second fully-simulated MineColonies
colony. This delivers the conquest fantasy (a second base, spoils,
power) while sidestepping the deepest MineColonies-internal coupling.
Recommend building toward the FULL colony but keeping the outpost form
as the de-risking fallback if step 2/3 fight back in testing.

---

## Recommendation

**Proceed to the full arc design/investigation.** Both pivotal
unknowns are feasible with APIs that already exist in the deps —
Unknown 1 via MineColonies' own instant-build placer (no vanilla
world-gen needed), Unknown 2 via `createColony` + manual hut
registration. Neither forces a fundamental reshape of the arc.

The one item to carry into the next phase as a tracked risk is
**Unknown 2 step 2/3** (registering pre-placed huts as functioning
colony buildings at the right level) — design the full arc assuming the
FULL second-colony conversion, but treat the "controlled outpost" as
the pre-validated fallback so the conquest payoff is guaranteed either
way.

**Still deferred (the next investigation, once design begins):**
defending mobs / settlement garrison, the Declare-War teleport-in flow,
the wild/colony generation split tuning (the ALL/SOME/NONE config
preview), boss-as-settlement-anchor specifics, PvP considerations, and
the loot/citizen/skill payout balance. None were investigated here by
instruction.

### Key APIs (for the build phase)
- Place complete building: `CreativeBuildingStructureHandler.
  loadAndPlaceStructureWithRotation(level, blueprintFuture, pos,
  RotationMirror, false, serverPlayer)`
- Load blueprint: `StructurePacks.getBlueprintFuture(pack, path,
  registries)`
- Found colony: `IColonyManager.getInstance().createColony(level, pos,
  player, name, pack)`
- Register a hut as a building: `colony.getServerBuildingManager()
  .addNewBuilding(abstractTileEntityColonyBuilding, level)`
- Set built level: `building.setBuildingLevel(n)` (ISchematicProvider)
- Mark the colony boss: the existing `FactionMarkTag` / marked-boss
  system; WILD bosses stay unmarked (free kill).

---

# STAGE A — BUILT (2026-06-12): settlement generation

The structural foundation is built. As-built record:

- **Files:** `RivalColonies` (driver: anchor/pack maps, shared layout,
  placement, wild/colony split, natural pass, debug), `Settlement`
  (the mutable per-settlement record with B–E seams), and
  `SettlementSavedData` (sole-door storage). Config: `SettlementMode
  {ALL, SOME, NONE}` (`rivalSettlementMode`, default SOME) +
  `rivalSettlementSomeChance` (0.5) + `rivalNaturalGeneration` (true).
  Debug: `/rivalcolony spawn|wild <faction>` + `/rivalcolony list`.
- **Placement (verified path):** each building =
  `StructurePacks.getBlueprintFuture(packDisplayName, path,
  registries)` → `CreativeBuildingStructureHandler.
  loadAndPlaceStructureWithRotation(level, future, pos,
  RotationMirror.NONE, true, placer)` (queues a server-side ticked
  PlaceStructureOperation — instant-ish complete build). The pack key
  is the StructurePacks DISPLAY name.
- **Shared layout, themed by pack:** one ~10-building footprint
  (townhall + builder + tavern + blacksmith + library + barracks + 4
  residences) on a 22-block grid, using pack-relative paths present in
  EVERY candidate pack (verified) — only the PACK differs per faction.
  The town hall anchors the center; the boss spawns there.
- **PER-FACTION PACK / ANCHOR (physical = has anchor mob):**
  | Faction | Anchor boss | MineColonies pack (theme) |
  |---|---|---|
  | Luminous | Hinata Sakaguchi | Ancient Athens (holy marble) |
  | Falmuth | Folgen | Fortress (militaristic) |
  | Shizu | Shizu | Pagoda (Japanese) |
  | Leon | Ifrit | Caledonia (grand keep) |
  | Otherworlders | Mai Furuki | Space Wars (sci-fi) |
  | Jura Alliance | Shin Ryusei | Jungle Treehouse (forest) |
  | Dwargon | Gazel Dwargo | *(no pack — see DESIGN CHANGE 1: uses existing dwarf villages)* |
- **ABSTRACT (no anchor → never settle, per the brief):** Tempest,
  Carrion, Milim, **Clayman** (his orcs roam as calamities — the
  marked-boss anchors for the world-rep system are separate from
  settlement anchors; Clayman has no settlement anchor by design). The
  abstractness is reported by `/rivalcolony spawn clayman` ("ABSTRACT
  — no anchor mob, no settlement").
- **Wild/colony split:** `RivalColonies.generate` rolls per config —
  ALL → colony, NONE → wild boss only (no settlement; layer off), SOME
  → `rivalSettlementSomeChance` colony else wild. COLONY = settlement
  cluster + boss MARKED via the existing `FactionMarkTag` (rep-
  affecting); WILD = anchor boss alone, UNMARKED (the Stage-3 spare-boss
  free-kill behavior — Layer-1 movers ignore it).
- **Generation pass = OURS, not vanilla world-gen** (per Phase 1):
  `RivalColonies.tick` on the shared 1 s scheduler rolls a rare daily
  per-player chance (`NATURAL_GEN_CHANCE_PER_DAY` 0.05) to seed a
  settlement ~220 blocks from a player, min-spacing 400, capped at 12,
  config-gated; `rivalNaturalGeneration=false` makes it debug-command-
  only. All constants named/tunable.
- **Gating:** the whole layer no-ops when `factionSystemEnabled` is off
  (every entry point checks).
- **Placement BUGFIX (2026-06-13).** The first `/rivalcolony spawn`
  placed the boss but NO buildings. Diagnosed (bytecode, no live log):
  TWO silent failures, both now fixed —
  1. **Async future.** `getBlueprintFuture` runs `supplyAsync` on a
     background IO thread; `loadAndPlaceStructureWithRotation` checks
     `hasBluePrint()` (reads the resolved-`blueprint` field, NOT the
     future) synchronously right after construction — the future isn't
     done, so it's false, and the method's `else` branch only LOGS a
     warning (the only placement path is the `if (hasBluePrint())`
     branch). Nothing placed.
  2. **Missing `.blueprint` extension.** `getBlueprint` resolves
     `packRoot.resolve(getNormalizedSubPath(path))`, and the normalizer
     only swaps separators — it does NOT append the extension. The
     Stage-A paths ("fundamentals/townhall1") pointed at nonexistent
     files → null blueprint anyway.
  - **The pack KEY was correct all along** — `packMetas` is keyed by
     `StructurePackMeta.getName()` = the pack.json `"name"` (display
     name "Stalactite Caves" etc.), verified by decompiling the loader.
  - **Fix:** `placeBuilding` now loads SYNCHRONOUSLY via
    `StructurePacks.getBlueprint(pack, path, registries)` (path INCLUDES
    `.blueprint`), builds the handler via its public BLUEPRINT ctor
    (`new CreativeBuildingStructureHandler(level, pos, blueprint,
    RotationMirror.NONE, true)` → `hasBluePrint()` true), and queues
    placement through `Manager.addToQueue(new PlaceStructureOperation(
    placer, player))` (Structurize ticks it; places over a few ticks).
    A null blueprint logs the exact pack/path that failed.
  - **Verified:** all 7 building sub-paths
    (townhall/builder/tavern/residence + blacksmith/library/barracks)
    exist WITH `.blueprint` in ALL 7 physical-faction packs
    (truedwarven, ancientathens, fortress, pagoda, caledonia, spacewars,
    jungle) — the shared-footprint assumption holds across factions.

Stages B–E (garrison, discovery/war, conquest, betrayal) extend
the `Settlement` record's reserved seams (garrisonUuids,
defenderCountAtStart, assaultingPlayer, assaultOrigin, discoveredBy,
conquered) — already persisted, unused in A.

---

# DESIGN CHANGES to the rival-colony arc (2026-06-13)

Two adjustments applied to Stage A before B–E build on its assumptions.

## CHANGE 1 — Dwargon uses existing Tensura DWARVEN VILLAGES (not a generated town)

Gazel already spawns in Tensura's `tensura:dwarf_village` jigsaw
worldgen structures, so generating a *separate* MineColonies faux-town
for Dwargon was redundant. Dwargon is now a **DWARVEN_VILLAGE-type**
settlement that ADOPTS existing dwarf villages.

- **New `Settlement.StructureType` field:** `MINECOLONIES_CLUSTER` (the 6
  town factions — generated faux-towns) vs `DWARVEN_VILLAGE` (Dwargon —
  an adopted in-world village). Persisted in NBT with a legacy fallback
  to `MINECOLONIES_CLUSTER`. B/C stages operate on **center + anchor
  boss**, so they're structure-type-agnostic; only generation (A) and
  building-registration differ.
- **The generation HOOK — runtime detection, not chunk-gen.** Dwarf
  villages are data-driven jigsaw structures; there's no clean chunk-gen
  callback to hook. So Dwargon registration is RUNTIME, reusing the
  envoy system's proven pattern: `RivalColonies.tickDwarvenVillages`
  polls every tick for any player whose `blockPosition()` falls inside a
  dwarf village via `level.structureManager().getStructureAt(pos,
  dwarfVillageStructure).isValid()` (structure resolved from the registry
  by `tensura:dwarf_village`, null-guarded for Tensura-disabled). The
  FIRST time a village is seen, the wild/colony split is rolled ONCE for
  it; the village center (`StructureStart.getBoundingBox().getCenter()`)
  is recorded in `SettlementSavedData.evaluatedVillages` (a persisted
  `Set<Long>`) so it's never re-rolled on revisit.
- **`registerDwarvenVillage`:** on a COLONY roll, finds a Gazel already
  inside the village bounds (`getEntitiesOfClass(Mob, villageAABB,
  type==GAZEL && alive)`) — or spawns one at the center if absent — and
  MARKS him as the anchor boss via the existing `WorldReputationManager.
  markBoss(boss, "dwargon", "rival_colony", true)`. `structureType =
  DWARVEN_VILLAGE`, `packName = ""`, `buildingPositions` empty (the
  village's own buildings stand). A WILD roll just records the village as
  evaluated and leaves it plain.
- **Natural TOWN gen excludes Dwargon:** `randomPhysicalFaction` now
  draws from `PACKS.keySet()` (the 6 town factions) — Dwargon never
  scatters a faux-town. `ANCHORS` still holds all 7 (Gazel included) so
  `isPhysical` and boss spawning work; `PACKS`/`isTownFaction` is the 6.
- **Debug:** `/rivalcolony spawn dwargon` (and `wild dwargon`) now
  require the player to be **standing inside a dwarf village**, marking
  THAT village (else "stand inside a dwarven village…"). The 6 town
  factions still spawn a faux-town 40 blocks ahead as before.

## CHANGE 2 — Conquest does NOT create a second colony (rewards-only)

MineColonies is designed around ONE player colony, so the Phase-1
"conquest → `createColony` + hut-registration" conversion (the flagged
moderate risk) is **REMOVED from the plan**. Conquest is now REWARDS-ONLY
and the settlement becomes a defeated HUSK:

- **KEPT rewards (all to the player's EXISTING colony):** the citizen
  boost (10–20 faction-themed citizens added to the player's current
  colony), the boss's Covenant skill (via `grantSkillReward`), and loot
  chests (from the faction's quest-reward catalog).
- **DROPPED:** `IColonyManager.createColony(...)` + manual hut
  registration. No second player colony is ever founded.
- **DEFEATED HUSK (over razing):** post-conquest the settlement's
  buildings STAY as a sacked ruin — boss dead, defenders cleared, loot
  spawned, `Settlement.conquered = true`, garrison won't respawn. The
  `conquered` flag already exists on the record (persisted, unused in A).
- **Convergence:** because conquest no longer founds a colony, Dwargon
  (DWARVEN_VILLAGE) and the 6 town factions (MINECOLONIES_CLUSTER)
  behave IDENTICALLY at conquest — rewards + husk — regardless of
  structure type. Stage D is structure-type-agnostic.

---

# PHASE 2 — the deferred technical pieces (2026-06-12)

Investigation only, no production code. With both Phase-1 foundations
FEASIBLE, this pass checks the four deferred mechanics. **All four are
FEASIBLE**, each reusing a pattern already shipped in this mod — the
verified call sites are noted so the build phase starts grounded.
Still deferred by instruction: PvP, payout-balance tuning, the siege
system.

## 1. GARRISON (defenders + scaling + persistence) — FEASIBLE

**Composition — confirmed available.** Tensura ships rich
faction-appropriate rosters (verified in `MonsterEntityTypes` +
`HumanEntityTypes`): Falmuth → Falmuth Knight / Folgen / Kirara / Kyoya
/ Shogo; Dwargon → Dwarf / Gazel / War Gnome / Beast Gnome; Clayman →
Orc / Orc Lord / Lesser-Greater-Arch Daemon; Carrion → Giant Bear /
Direwolf / Horned Bear / Blade Tiger / Barghest; Leon/Shizu → Ifrit
Clone / Salamander / Hell Caterpillar / Hell Moth; Tempest/Jura →
Tempest Serpent / Goblin / Lizardman / Slime; Milim → dragon-themed
beasts. A per-faction `EntityType[]` defender table — the exact pattern
as `TensuraRaids.rosters()` — covers this. Spawn uses the raid engine's
proven path (`type.create` + `finalizeSpawn(SPAWN_EGG)` +
`setPersistenceRequired` + a tag), so defenders don't despawn.

**Scale to the BOSS, not the player — confirmed.** Read the boss's EP
via the existing `ExampleMod.readExistence(boss).getEP()` (the same read
`computeColonyStrength` and the raid budget use). Derive a scale factor
from boss EP vs. a baseline, then apply it to each defender with the
EXISTING stat-bump helper — `Assassins.multiplyAttribute(mob, attr, id,
factor)` (stable-id ADD modifier, remove-first, never compounds), over
`TensuraAttributes.MAX_MAGICULE` / `MAX_AURA` + vanilla `MAX_HEALTH` /
`ATTACK_DAMAGE`. Garrison COUNT also scales with boss tier (more/bigger
defenders for a stronger boss). This is the assassin manifestation
buff applied to a squad — no new mechanic.

**PERSISTENCE + reset (the key one) — FEASIBLE via a settlement
SavedData record.** A new `SettlementSavedData` (the established
overworld-SavedData idiom) holds, per settlement: id, faction id,
center pos, structure-pack/theme, the boss UUID, the live defender
UUID set, `defenderCountAtStart`, an `assaultState` (idle / under
assault by player X), `conquered` flag, and the per-player
discovered-by set. On an assault that ends WITHOUT conquest
(retreat / player death / logout):
- surviving defenders are despawned (envoy-poof) and the full garrison
  is RESPAWNED fresh on the next assault (or immediately, lazily),
- the boss is HEALED to full: `boss.setHealth(boss.getMaxHealth())`
  plus restoring magicule/aura via the `ExistenceStorage` setters
  (write side of the EP read — ⚠ confirm the exact restore call in
  build; HP alone is the floor, EP-restore is the polish).
Each assault is therefore a fresh full fight, exactly as designed. The
boss + garrison are tied to the settlement record, not to a transient
event, so they survive save/reload by construction.

**Win condition (boss dead AND ≥60% defenders cleared) — FEASIBLE.**
Snapshot `defenderCountAtStart` when the assault begins; a death hook
on garrison-tagged mobs (the existing `RaidTag`-style death-bookkeeping
pattern, a new `GarrisonTag(settlementId)`) decrements a live count /
tallies kills. Conquest fires when `bossDead && kills >=
ceil(0.6 * defenderCountAtStart)`. All arithmetic on the SavedData
record.

⚠ Tracked risks: (a) the exact EP/magicule restore call for the
boss-heal (HP is trivial; the Tensura EP write is the detail); (b)
keeping defenders tethered to the settlement (reuse the raid
WALK_TARGET tether so they don't wander off and break the count).

## 2. DISCOVERY + DECLARE-WAR FLOW — FEASIBLE

**Discovery (proximity, per-player) — FEASIBLE.** On the existing 1 s
scheduler, for each online player, test distance to each known
settlement center; within a discovery radius → add the player to that
settlement's discovered-by set (SavedData). This is the SAME shape as
`runPerPlayerEnvoyPasses`' dwarven-village containment poll. Unlocks
the per-faction Declare War button on the roster.

**Declare War + war-party teleport — FEASIBLE, reuses three systems.**
- War-party SELECTION: a 15-slot picker UI — the `WindowLendPicker`
  pattern exactly (list the player's subordinate `RaceIdentity`
  records, click to toggle, confirm at the cap).
- TELEPORT IN: the player via `ServerPlayer.teleportTo` (the Drago-Nova
  / Covenant-travel call); each chosen subordinate via the SUMMON path
  (reconstruct/relocate the subordinate body — `summonGoblin`-style
  `EntityType.create` from the identity snapshot, or relocate the live
  body) then `moveTo` the settlement. ⚠ Decide whether war-party summon
  bypasses the normal magicule summon-cost gate (design choice; a war
  muster probably should be free or flat-priced).
- "ALL ENTITIES HOSTILE": stamp garrison mobs with the `GarrisonTag`
  and run the raid target-assist (`Assassins.lockTarget` /
  `BrainUtils.setTargetOfEntity` dual-write) so defenders attack the
  player + party, and the party's subordinates target defenders — the
  raid steering pass, inverted ally-style, already does both halves.

**Round-trip (Declare War ⇄ Retreat) — FEASIBLE.** The roster button
swaps to RETREAT while `assaultState` is active for that player (a
snapshot flag, the Diplomacy-button/`canX` idiom). Retreat or death →
teleport the player + surviving party back to the muster point
(stored origin pos in the assault record), then reset the garrison
(#1). Player logout mid-assault = treated as retreat (the scheduler
notices the player left and resets). The assault origin + party roster
live in the SavedData so the return survives a reload.

⚠ Tracked risks: subordinate bodies come in two modes (IN_COLONY
citizen vs. SUBORDINATE wild body) — the muster must resolve each to a
combat body (the summon path already handles this); and the summon-cost
decision above.

## 3. BETRAYAL SCALING — FEASIBLE

Declaring war on a faction you have relations with scales the garrison
UP by relationship tier. Read the tier from the built diplomacy system:
`DiplomacyManager.getState(level, player, faction)` →
{NONE, OPEN, PACT, COVENANT}. Map to an extra multiplier on the
defender stat-bump (#1) — e.g. OPEN ×1.25, PACT ×1.6, COVENANT ×2.0
(named/tunable) — and GRANT the defenders Tensura skills via the
existing `DiplomacyManager.grantSkillReward` / `learnSkill` path
(deeper ally → resistances + a combat skill), so a Covenant betrayal is
genuinely brutal. Both halves reuse shipped patterns (the assassin
stat-bump + the Covenant skill-grant).

**Composes with the standing system — confirmed.** Declaring war
itself sets the faction HOSTILE by writing standing down through the
sole door (`WorldReputationManager.modifyStanding(..., a large
negative, reason)`), which trips the existing below-WARY collapse —
relations shatter exactly like the Orc-Disaster forced-HOSTILE clamp
(the "Clayman-clamp freebie" from Stage 1). No new shatter logic; the
betrayal flows through the standing spine. (A dedicated
`WorldRepReason.WAR_DECLARED` keeps the log readable.)

## 4. CITIZEN BOOST (conquest aftermath) — FEASIBLE

> **Superseded by DESIGN CHANGE 2 (2026-06-13):** conquest no longer
> founds a second colony. The citizen boost below is now applied to the
> player's EXISTING colony (not a freshly-created one). The reuse paths
> are unchanged — they target whichever colony the conquering player
> already owns.

Conquest grants 10–20 themed citizens. Two reuse paths, both proven:
- spawn fresh citizens via `colony.getCitizenManager()
  .createAndRegisterCivilianData()` (the naming-flow call) and set their
  themed skills with `getCitizenSkillHandler().incrementLevel(skill, n)`
  (the lend-return training call). Dwargon → Strength/Stamina miners,
  Jura → Knowledge/Intelligence, etc. — a per-faction
  (count, skill-profile) table.
- The themed COUNT (10–20) and skill emphasis is one small data table
  per faction, mirroring the deal-table data pattern.

⚠ Tracked risk: the receiving colony's citizen CAP (housing) — like the
lend-return, MineColonies absorbs overflow via happiness, but a 20-
citizen dump may exceed `getMaxCitizens()`; decide whether to raise the
cap, spill across the player's colonies, or stagger arrivals. Edge
handling, not a blocker.

## Recommended BUILD STAGING

Each stage is independently testable; build in order, since later
stages consume earlier records:

- **Stage A — Settlement generation + record.** The wild/colony split
  (config ALL/SOME/NONE, the future-ideas preview), the faux-town
  placement (Phase-1 APIs), `SettlementSavedData`, boss spawn + mark
  (colony) / unmarked (wild). Foundation for everything.
- **Stage B — Garrison + persistence.** Per-faction defender tables,
  boss-EP-scaled stat-bump, the GarrisonTag death-tally + 60% win
  tracking, respawn-on-incomplete + boss-heal reset. Testable via a
  debug "spawn settlement here" command.
- **Stage C — Discovery + Declare-War flow.** Proximity discovery, the
  15-slot war-party picker, teleport in/out, hostility application, the
  Declare⇄Retreat button swap. The player-facing loop.
- **Stage D — Conquest (rewards-only; DESIGN CHANGE 2).** NO colony is
  founded. Rewards go to the player's EXISTING colony: loot chests + the
  citizen boost (#4) + the boss's Covenant skill grant. The settlement
  becomes a DEFEATED HUSK (`Settlement.conquered = true` — buildings
  stay as a sacked ruin, boss dead, defenders cleared, garrison won't
  respawn). Structure-type-agnostic (towns and Dwargon villages alike).
  The dropped `createColony` + hut-registration path retires the
  Phase-1 moderate risk.
- **Stage E — Betrayal scaling.** A thin add over B/C: tier-read +
  extra multiplier + defender skill grants + the WAR_DECLARED standing
  crash. Last because it depends on B (garrison) and C (declare flow)
  existing.

**Recommendation: proceed to build, Stage A first.** No deferred piece
is infeasible; all reuse verified call sites. Carry forward the tracked
risks (boss EP-restore + defender tether in Stage B; war-party
summon-cost + body-mode resolution in Stage C; citizen-cap overflow in
Stage D). The Phase-1 hut-registration risk is RETIRED by DESIGN CHANGE
2 (conquest no longer founds a colony). PvP, payout balance, and the
siege system remain explicitly deferred to their own later passes.

---

# STAGE B — the GARRISON (as-built, 2026-06-13)

The defending force + the persistence/respawn/heal reset + the 60%-win
tracking Stage C will drive. Structure-type-agnostic: identical for
MINECOLONIES_CLUSTER towns and DWARVEN_VILLAGE settlements (it operates
on the settlement's center + anchor boss). All behind
`factionSystemEnabled`. Code: `RivalColonies` (garrison block) +
`GarrisonTag` + the new `Settlement` fields.

## Per-faction defender rosters

Themed `EntityType[]` per physical faction (the `TensuraRaids.rosters()`
shape), drawn from mobs already in the mod. The anchor BOSS (Stage A,
marked) is part of the garrison — these are the rank-and-file around it.
Abstract factions (Tempest, Carrion, Milim, Clayman) have no settlement,
so no roster.

| Faction | Anchor boss | Defender roster |
|---|---|---|
| Luminous | Hinata Sakaguchi | Falmuth Knight, Clone, Bone Golem *(holy construct stand-in — Tensura has no dedicated holy mob)* |
| Falmuth | Folgen | Falmuth Knight, Kirara Mizutani, Kyoya Tachibana, Shogo Taguchi |
| Shizu | Shizu | Ifrit Clone, Salamander, Hell Caterpillar, Hell Moth |
| Leon | Ifrit | Ifrit Clone, Salamander, Arch Daemon, Greater Daemon, Lesser Daemon |
| Otherworlders | Mai Furuki | Clone, Mark Lauren, Shinji Tanimura, Kirara Mizutani |
| Jura Alliance | Shin Ryusei | Tempest Serpent, Goblin, Lizardman, Slime |
| Dwargon | Gazel Dwargo | Dwarf, War Gnome, Beast Gnome |

## Boss-EP scaling formula (⚠ ALL BALANCE GUESSES — need a siege playtest)

Scale to the BOSS, not the player — a strong-boss faction = a tough
settlement. Read `readExistence(boss).getEP()`, then:

```
ratio   = bossEP / GARRISON_BASELINE_EP
scale   = clamp(ratio ^ GARRISON_SCALE_EXPONENT, SCALE_MIN, SCALE_MAX)
count   = clamp(round(GARRISON_BASE_COUNT × scale), MIN_COUNT, MAX_COUNT)
stat×   = min(STAT_FACTOR_MAX, 1 + (scale − 1) × STAT_PER_SCALE)
```

`stat×` is applied to MAX_HEALTH / ATTACK_DAMAGE / MAX_MAGICULE /
MAX_AURA via the assassin's `multiplyAttribute` (stable-id ADD modifier,
remove-first, never compounds; Tensura attrs guarded). The sqrt-ish
exponent dampens EP so a 100× boss → ~10× scale, not 100×.

**Starting values (all NAMED + TUNABLE in `RivalColonies`):**

| Constant | Value | Meaning |
|---|---|---|
| `GARRISON_BASELINE_EP` | 5 000 | boss EP that yields scale 1.0 |
| `GARRISON_SCALE_EXPONENT` | 0.5 | EP-ratio dampening (√) |
| `GARRISON_SCALE_MIN / MAX` | 1.0 / 6.0 | scale clamp |
| `GARRISON_BASE_COUNT` | 6 | defenders at scale 1.0 |
| `GARRISON_MIN_COUNT / MAX_COUNT` | 4 / 20 | count clamp |
| `GARRISON_STAT_PER_SCALE` | 0.5 | stat-mult growth per unit scale |
| `GARRISON_STAT_FACTOR_MAX` | 4.0 | stat-mult cap |
| `GARRISON_FALLBACK_BOSS_EP` | 5 000 | EP when the boss can't be read |
| `GARRISON_WIN_FRACTION` | 0.60 | defenders that must fall for conquest |
| `GARRISON_SPAWN_RADIUS` | 18 | defenders spawn within this of center |
| `GARRISON_TETHER_RADIUS` | 40 | stray-beyond → walked back to center |

These are first guesses with no combat playtest — flag for the polish
pass. Likely tuning levers: BASELINE_EP (how strong "scale 1" feels),
the exponent (curve steepness), and STAT_FACTOR_MAX (per-mob lethality).

## Spawning + tethering

Raid-engine path: `type.create` + `finalizeSpawn(SPAWN_EGG)` +
`setPersistenceRequired` + `GARRISON_TAG(settlementId, isBoss)` +
`addFreshEntity`, scattered within `GARRISON_SPAWN_RADIUS` of center.
UUIDs stored in `Settlement.garrisonUuids`. **Tether:** `tickGarrison`
(per-second, runs whenever the faction system is on — independent of the
natural-gen toggle) feeds a defender's brain `WALK_TARGET` back toward
center if it strays past `GARRISON_TETHER_RADIUS`, yielding to native
combat while it has a live target (the SubordinatePatrol/raid-steer
containment idiom). It also prunes UUIDs whose entity vanished without a
death event (e.g. `/kill`) so the live count stays honest (no tally).

## Persistence + the RESET primitive (the key new mechanic)

Garrison + boss persist in `SettlementSavedData` (the new `Settlement`
fields — `defenderKills`, `bossDead`, `assaulted` — round-trip in NBT;
`garrisonUuids` / `defenderCountAtStart` already did). State machine:
**IDLE** vs **ASSAULTED** (`Settlement.assaulted`).

`resetGarrison(level, s)` — the primitive Stage C calls on an
INCOMPLETE assault (player retreats/dies without conquering):
1. **Revive-or-heal the boss** — if it died (an incomplete assault can
   still have killed the boss, just not ≥60% defenders) respawn a fresh
   MARKED boss at center and update `bossUuid`; else `setHealth(max)` +
   refill magicule/aura via `EnergyHelper.gain*(…, getMax*, NORMAL)`.
2. **Top the garrison back up** to `defenderCountAtStart` (heal
   survivors, respawn the shortfall at the same boss-derived stat scale).
3. Clear `defenderKills` / `bossDead` / `assaulted` → IDLE. Each assault
   starts fresh.

⚠ **Tracked risk — the boss EP/pool restore.** The pool refill goes
through `EnergyHelper.gainMagicule/gainAura(max, NORMAL)`, wrapped in
try/catch (not every anchor has Tensura energy pools — e.g. Bone Golem).
EP itself (the existence stat) isn't drained by combat, so it's not
restored; only HP + the magicule/aura POOLS are. The revive path
(respawn when dead) is the riskier branch — it creates a NEW entity, so
any Stage-C state keyed on the old `bossUuid` must re-read it (Stage C
should call reset, then re-resolve the boss).

## 60%-win tracking (the condition Stage C checks)

`GarrisonTag(settlementId, isBoss)` on every defender AND the boss (the
boss also keeps its `FactionMarkTag` for the Layer-1 fan-out — the two
coexist). `onGarrisonMobDeath` (wired into `ExampleMod.onLivingDeath`,
beside the raid tally): boss death → `bossDead = true`; defender death →
`defenderKills++` and drop the UUID. The check:

```
requiredDefenderKills(s) = ceil(GARRISON_WIN_FRACTION × defenderCountAtStart)
isConquestEligible(s)    = bossDead && defenderKills ≥ requiredDefenderKills(s)
```

The tally counts continuously (so Stage B is testable before the C
assault flow exists); `beginAssault` re-snapshots the live count as the
denominator and zeroes the kills for a clean assault. Stage C drives the
assault that consumes `isConquestEligible`; Stage D (rewards-only +
husk) fires when it returns true.

## Debug (Stage B is testable before Stage C)

- `/rivalcolony spawn <faction>` now raises the garrison with the
  settlement; the success line reports the defender count.
- `/rivalcolony garrison <id>` — full state: alive/start defenders,
  kills/needed, boss HP+EP, assaulted/idle, conquest-eligible.
- `/rivalcolony assault <id>` — begin an assault (the Stage-C entry
  primitive), to test the win check now.
- `/rivalcolony reset <id>` — run the RESET primitive (respawn garrison +
  heal/revive boss).
- `/rivalcolony list` — now shows `garrison alive/start` + ASSAULTED.

Verified: spawn raises a themed, boss-EP-scaled, tethered garrison;
killing defenders increments the tally (GARRISON_TAG); the boss is the
marked anchor and its death sets `bossDead`; the 60% check reads
correctly; reset restores the garrison + heals/revives the boss; state
persists in `SettlementSavedData` across reload; `factionSystemEnabled`
off → no garrison; build green.

Deferred to C–E: the discovery/Declare-War/teleport-assault loop that
DRIVES the assault and calls `beginAssault`/`resetGarrison` (C), the
conquest→rewards/husk payoff that consumes `isConquestEligible` (D), and
betrayal scaling (E).
