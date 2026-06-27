# Investigation: the rival-colony arc — the two pivotal unknowns

> **WORLDGEN-STRUCTURE REWORK — IN PROGRESS (Option C; 2026-06-26).** The old
> proximity "instant block-stamp near the player" placement is being replaced by
> real data-driven worldgen structures (so settlements are `/locate`/`/place`-
> able, generate AHEAD of the player, and respect structure-blocking mods). See
> the staged plan; status so far:
> - **Stage 0 (de-risk spike) — DONE + IN-GAME VERIFIED.** Proved the existing
>   `generateColony` blueprint+boss+garrison pipeline works when driven from a
>   populate hook, config-gated at populate time. Surfaced + fixed two real bugs:
>   a world load/save HANG (an off-thread `ChunkEvent.Load` auto-trigger —
>   removed) and an empty-town race (Structurize pack index not ready — fixed by
>   the `isPackBlueprintsReady` guard in `generateColony`). All 5 town packs
>   verified generating buildings cleanly in-game.
> - **Stage 1 (one real structure) — DONE + IN-GAME VERIFIED.** Registered a
>   custom `StructureType` + `StructurePieceType` (`FactionStructures`) and a
>   minimal `FactionAnchorStructure`/`FactionAnchorPiece` (gold-marker), plus
>   data JSON (`worldgen/structure` + `worldgen/structure_set` random_spread +
>   `tags/worldgen/biome/has_structure`) for ONE faction (Falmuth). Confirmed
>   `/place`, `/locate` (found a natural one 498 blocks away), no errors.
> - **Stage 2 (wire populate to the structure) — BUILT, pending in-game test.**
>   `tickWorldgenSettlements` (server-thread, ambient) calls the de-spiked
>   `populateSettlementAt` → `generateColony` (config gate + pack-readiness +
>   double-populate guard by start-center). The `/factiongen` command is retained
>   as a debug tool but is no longer the real trigger.
>   - **Detection v1 used `getStructureAt(playerPos)` — FAILED in-game** (no
>     populate). That requires the player INSIDE the start's bounding box; the
>     marker BB is tiny, so 13 blocks away never matched (and widening the BB
>     only affects newly-generated anchors). **Detection v2 (current):** scan the
>     loaded chunks within `WORLDGEN_DETECT_CHUNK_RADIUS` (4 ≈ 64 blocks) of the
>     player via `getChunkNow(...).getStartForStructure(structure)` — this is
>     BB-INDEPENDENT (finds the start in its origin chunk) and works for
>     already-generated anchors. The marker piece BB was reverted to small.
> - **Stage 3 (all town factions + themed biomes) — BUILT, pending in-game test.**
>   Added worldgen structure + structure_set (unique salt) + themed biome tag for
>   leon (savanna/desert/badlands), tempest (jungle/forest/swamp), eastern_empire
>   (taiga family), luminous (snowy_plains/ice_spikes); Falmuth's tag narrowed to
>   plains/sunflower_plains/meadow so themes don't all overlap. All five share the
>   one `faction_anchor` structure TYPE + piece (only JSON + a `WORLDGEN_STRUCTURES`
>   map entry per faction — no new Java per faction). Dwargon stays on Tensura's
>   own `dwarf_village` via `tickDwarvenVillages` (its populate ADOPTS the village
>   rather than stamping MC blueprints, so it can't share `populateSettlementAt`).
>   ⚠ Cross-faction spacing is NOT enforced (each faction is its own structure_set
>   grid with a distinct salt — same-faction spacing holds, but two different
>   factions can land near each other); revisit in Stage 6 if needed.
> - **Stage 4 (retire old proximity scatter) — BUILT, pending in-game test.**
>   Worldgen is now the ONLY natural settlement source: removed the per-day
>   per-player scatter block + its helpers/constants (`scatterNear`,
>   `tooCloseToExisting`, `randomPhysicalFaction`, `NATURAL_GEN_*`,
>   `MIN_SETTLEMENT_SPACING`, `MAX_NATURAL_SETTLEMENTS`, `lastNaturalDay`) and the
>   dead `generate()` wild/colony wrapper. `RIVAL_NATURAL_GEN` repurposed: true =
>   worldgen anchors auto-populate; false = anchors stay dormant markers (debug
>   command only). ⚠ BEHAVIOR CHANGE: TOWN anchors now ALWAYS populate as a
>   COLONY — the wild-boss-alone variant + `RIVAL_SETTLEMENT_SOME_CHANCE` no
>   longer apply to town natural-gen (`RIVAL_SETTLEMENT_MODE` NONE still disables;
>   the wild/colony roll survives only for Dwargon dwarf villages). Discovery /
>   declare-war / conquest are unchanged (they key off the `Settlement` record,
>   identical regardless of generation source) — needs an in-game war-cycle test
>   against a worldgen settlement.
>   - **FIX (2026-06-26): Declare-War teleported the player BENEATH the world.**
>     `declareWar`/`partyArrivalPos` read `getHeightmapPos` on the settlement
>     chunk, but declaring war from afar leaves it unloaded → the heightmap reads
>     the bottom of the world. Now force-loads the destination chunk and falls
>     back to the settlement's stored surface Y on a void read. ⚠ Warfare REWARDS
>     (`ConquestPayoff`) still need a rebalance pass — see docs/future-ideas.md.
> - **Stage 5 (cleanup + drop the spike) — DONE.** Deleted `FactionStructureSpike`
>   (the `/factiongen` command) + its registration, the four `spike*` methods in
>   RivalColonies, and the `pendingPopulates` map + accessors + persistence in
>   SettlementSavedData. KEPT the production survivors: `populateSettlementAt`,
>   `tickWorldgenSettlements`, and the `populatedStarts` double-populate guard
>   (NBT key still `spikePopulated` for save-compat). `/rivalcolony spawn` remains
>   the debug spawn. **Migration:** old saves need none — pre-rework settlements
>   stay in `SettlementSavedData` and still discover/war/conquer (they key off the
>   `Settlement` record, generation-source-agnostic); the old `spikePending` NBT
>   key is ignored on load; the old proximity-gen leftovers are gone. New
>   worldgen structures only appear in NEWLY generated chunks (expected for any
>   structure mod — explored areas are untouched).
> - **THE WORLDGEN REWORK (Stages 0–5) IS FUNCTIONALLY COMPLETE.** Remaining is
>   polish/balance, tracked in docs/future-ideas.md:
>   - **Stage 6 — settlement placement polish: IMPLEMENTED (2026-06-27), pending
>     more visual tuning.** Part A = one flat plateau; reworked on user feedback
>     to **Part B — terrain-FOLLOWING**: `groundSurfaceY` (scans past trees to
>     true ground — fixes tree-height; also used by findBuildableCenter/
>     surfaceRange), per-building local Y (hillside drape, not one plane), and a
>     per-building biome-matched graded pad (`levelBuildingPad`). REMAINING: tune
>     skirt/headroom/pad-half by eye; steep sites can step hard between buildings;
>     optional cross-faction spacing. See docs/future-ideas.md.
>   - **Warfare rewards rebalance** (`ConquestPayoff`) — user-requested.


> **FACTION MERGE (2026-06-21):** `tempest` and `jura_alliance` are now ONE
> faction, **"Jura-Tempest Federation"** (id `tempest`). Where the stage
> tables below list **Jura Alliance** as the physical forest faction (anchor
> Shin Ryusei, "Jungle Treehouse" pack, Tempest Serpent / Goblin / Lizardman
> / Slime garrison, 18 "sages" conquest levy) and **Tempest** as an abstract
> non-settling faction, that body now belongs to the merged **Jura-Tempest Federation** under the id `tempest`. The abstract list is now just Carrion,
> Milim, Clayman. Old-save Jura settlements migrate to `tempest` on load. See
> `docs/faction-model.md` for the full merge record.

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
- **Gating:** the whole layer no-ops when `enableFactionSystem` is off
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
  - **PACK-LOAD TIMING RACE (2026-06-26, in-log finding + fix).** The paths
    are correct, but Structurize registers/indexes packs ASYNC on a worker
    thread (and reloads them on relog). Generating a settlement BEFORE the
    index is ready 404s every building (`NoSuchFileException`) → an empty
    husk town. Observed: Leon/Caledonia placed 0–3 of 10 buildings when
    generation ran ~2 min after world load, but all 10 cleanly later /
    post-relog. **Fix:** `generateColony` now calls `isPackBlueprintsReady`
    (probes the town-hall blueprint) and DEFERS (returns null) if the pack
    isn't ready — callers leave any pending marker so a retry / next attempt
    succeeds once it settles. This matters for the worldgen rework: the
    first-load populate must not run before the pack index is ready (the
    guard covers it).

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
`enableFactionSystem`. Code: `RivalColonies` (garrison block) +
`GarrisonTag` + the new `Settlement` fields.

## Per-faction defender rosters

Themed `EntityType[]` per physical faction (the `TensuraRaids.rosters()`
shape), drawn from mobs already in the mod. The anchor BOSS (Stage A,
marked) is part of the garrison — these are the rank-and-file around it.
Abstract factions (Tempest, Carrion, Milim, Clayman) have no settlement,
so no roster.

Defender rosters (CURRENT — 2026-06-21):

| Faction | Anchor boss | Defender roster |
|---|---|---|
| Luminous | Hinata Sakaguchi | Falmuth Knight (Holy-Knight soldiers — the boss Hinata is the only named unit) |
| Falmuth | Folgen | Falmuth Knight, Kirara Mizutani, Kyoya Tachibana, Shogo Taguchi |
| Shizu | — (deprecated, no roster) | — |
| Leon | Ifrit | Lesser Daemon, Greater Daemon, Salamander |
| Eastern Empire | Mai Furuki | Falmuth Knight ×2 (rank-and-file), Shin Ryusei, Mark Lauren, Shinji Tanimura |
| Jura-Tempest Federation | Slime | Goblin, Lizardman |
| Dwargon | Gazel Dwargo | Dwarf |

**Roster rework (2026-06-21):**
- **Bone Golems REMOVED from every roster.** Golems are player-POSSESSED
  (owned `TamableAnimal`) constructs and refuse to attack their owner, so they
  can't serve as enemies. Replacements: Leon → daemons (Lesser/Greater) +
  Salamander; Eastern Empire → Falmuth Knight rank-and-file (×2 weight) under
  the three lieutenants; Luminous → pure Falmuth Knights (Kyoya was moved out —
  he belongs to Falmuth's roster, where he already is).
- **Garrison targeting fix.** Nearly every defender is a SmartBrainLib
  `NeutralMob` (`PlayerLikeEntity` / `TensuraMerchantEntity` →
  `TensuraHumanoidEntity` → `TensuraTamableEntity`), whose brain drops any
  hostile target it isn't ANGRY at. `steerGarrisonToInvaders` now also sets the
  NeutralMob persistent-anger target + timer on the invader, so defenders
  actually fight (and their autocaster, which reads `getTarget()`, fires).
  Golems are the one type this can't fix — owned mobs won't attack their owner.
- **One-of-each unique.** Round-robin spawn (`roster[i % len]`) duplicated named
  Otherworlders when count > roster length (two Kyoyas). `pickGarrisonType`
  caps uniques (Kirara/Kyoya/Shogo/Mark Lauren/Shinji/Shin Ryusei) at one per
  garrison, substituting a repeatable troop; `resetGarrison` seeds the tracker
  with still-alive uniques. Generic troops repeat freely.

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

**Rimuru (Tempest anchor) interaction (2026-06-24).** The Jura-Tempest anchor
Slime is buffed to demon-lord tier in `buffRimuruBoss` (called from
`spawnAnchorBoss`, BEFORE `spawnGarrison` reads the boss EP). It SETS the boss's
magicule cap to 100,000 and aura cap to 10,000 and fills the current pools to
those caps, so `readBossEP` = magicule + aura ≈ **110,000** when the garrison is
raised. Through the table above: scale = √(110k/5k) = **4.69** → **count = 20
(hits MAX_COUNT)**, **stat× ≈ 2.85**. So Rimuru's settlement always fields the
max-size garrison at ~×2.85 (intended — a capital with a strong warband). Two
non-obvious points this surfaced: (1) the boss itself never receives `stat×`
(only rank-and-file do — the boss only gets its own `buffRimuruBoss` stats), so
no triple-stack; (2) `GARRISON_STAT_FACTOR_MAX = 4.0` is effectively unreachable
— with `SCALE_MAX = 6.0`, the max `stat×` is `1+(6−1)×0.5 = 3.5`. The old
`SLIME_BOSS_BUFF ×8` was removed (replaced by `buffRimuruBoss`).

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

> **TODO (user-reported 2026-06-26) — tether is too loose during combat.**
> Because `tickGarrison` yields to native combat, a defender that aggros
> chases its target far outside the settlement (Leon's Ifrits/Salamanders
> were observed blasting terrain well beyond the town). The future fix is a
> HARD leash that returns / disengages a defender pulled past a max radius
> regardless of its target, so faction mobs stay near their settlement even
> while fighting. Full note in `docs/future-ideas.md` (rival-colony
> deferred follow-ons). Applies to worldgen-placed settlements too.

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
persists in `SettlementSavedData` across reload; `enableFactionSystem`
off → no garrison; build green.

Deferred to C–E: the discovery/Declare-War/teleport-assault loop that
DRIVES the assault and calls `beginAssault`/`resetGarrison` (C), the
conquest→rewards/husk payoff that consumes `isConquestEligible` (D), and
betrayal scaling (E).

---

# STAGE C — discovery + Declare-War + the teleport-assault loop (as-built, 2026-06-13)

The loop that DRIVES the Stage-B garrison: discover → Declare War →
teleport in with a war party → fight → WIN (B's `isConquestEligible`) or
RETREAT/die/logout → teleport back + B's `resetGarrison`. C ENDS at
"conquest-eligible reached" (Stage D does the payoff) and "incomplete →
reset". All behind `enableFactionSystem`. Code: `RivalColonies`
(Stage-C block) + new `Settlement` fields + the Wars UI (`WindowWarList`
/ `WindowWarPicker`) + `Networking` (OpenWarPayload / WarActionPayload).

## Discovery (proximity, per-player)

`tickDiscovery` (per-second, the dwarven-village/envoy per-player pass
shape): any player within **`DISCOVERY_RANGE` = 80 blocks** of a
settlement center is added to that settlement's `discoveredBy` set (the
per-player Declare-War unlock) with a one-time chat notice. Persisted in
the settlement record.

## Declare War + the war-party teleport

- **UI:** a **Wars** button on the roster footer (beside Diplomacy) →
  `WindowWarList` (the player's discovered settlements; each row's button
  is **Declare** or **Retreat** — the snapshot-flag button-swap idiom
  driven by `canDeclare`/`canRetreat`). Declare → `WindowWarPicker` (the
  15-slot `WindowLendPicker` shape) listing the player's **loaded Tensura
  subordinates** (owned `ISubordinate` mobs found via
  `SubordinateHelper.getSubordinateOwnerUUID`). March confirms.
- **`declareWar(player, settlementId, partyEntityIds)`** (the spine):
  validate discovered/not-conquered/not-already-assaulted → record
  `assaultingPlayer` + `assaultOrigin` (+ `assaultOriginDim`) → B's
  `beginAssault` (snapshots `defenderCountAtStart` from the persisted
  `garrisonUuids` roster — robust when the settlement chunks aren't
  loaded yet — zeroes kills, sets ASSAULTED) → teleport the player to the
  settlement edge (`ServerPlayer.teleportTo(ServerLevel,…)`, handles the
  cross-dimension trip) → teleport each chosen subordinate in
  (`Entity.teleportTo(ServerLevel,…)`, set aggressive), recording their
  UUIDs in `warParty` → `steerGarrisonToInvaders` turns the garrison
  hostile. **War-party source = loaded owned subordinates** (a deliberate
  simplification — the RaceIdentity bulk-summon path stays available for a
  future "summon absent subordinates first" polish).
- **Cap:** `WAR_PARTY_CAP = 15`. A solo assault (empty party) is allowed.

## The assault drive + resolution

`tickAssaults` (per-second) drives every ASSAULTED settlement:
- resolve the assaulting `ServerPlayer` (offline → the logout handler
  owns it; skip);
- **WIN** — `isConquestEligible(s)` true → `resolveWin`: bring the party
  + player home, set `conquestReached = true` (Stage D hooks this), back
  to IDLE. The garrison is NOT reset — it's been beaten;
- else **re-assert** garrison targeting on the invaders
  (`steerGarrisonToInvaders` — the raid `steerRaider` dual-write
  `BrainUtils.setTargetOfEntity` + `mob.setTarget`, inverted onto
  player + party; yields while a member has a live target).

**RETREAT** (Retreat button / `resolveRetreat`): bring party + player
home → B's `resetGarrison` (respawn defenders + heal/revive boss) → IDLE.

**DEATH / LOGOUT** (`onAssaultingPlayerDown`, wired into `onLivingDeath`
+ `PlayerLoggedOutEvent`): treated as retreat — reset the garrison +
party home now, but the player can't be teleported, so set
`pendingReturn` and keep the origin. **Return** (`onPlayerReturn`, wired
into `PlayerRespawnEvent` + `PlayerLoggedInEvent`): teleport the player
to the stored origin and clear the assault link.

## ⚠ THE TRACKED RISK — post-reset boss re-resolution (handled)

`resetGarrison` may **revive the boss as a NEW entity (new UUID)** when
the boss died in an incomplete assault. Stage C never caches the old
`bossUuid` across a reset: `resetGarrison` writes the fresh uuid into
`s.bossUuid`, and every later read goes through `resolveBoss(level, s)`.
`resolveRetreat` calls `resetGarrison` and only then logs `s.bossUuid`
(the fresh value). Confirmed: no path in C reuses a pre-reset boss
reference.

## Persistence

`assaultingPlayer`, `assaultOrigin`, `assaultOriginDim`, `warParty`,
`conquestReached`, `pendingReturn` all round-trip in NBT
(`SettlementSavedData`), so a player logging out mid-assault returns
correctly on next login, and the assault state survives reload.

## Debug (Stage C is testable without the UI)

- `/rivalcolony declare <id>` — declare war with the player's loaded
  subordinates as the party (no picker), teleport in.
- `/rivalcolony win <id>` — force the WIN resolution (sets boss-dead +
  kills past 60%, resolves, teleports home; payoff = Stage D).
- `/rivalcolony retreat [id]` — force the RETREAT resolution (teleport
  home + garrison reset + boss re-resolve); no id = the current assault.
- `/rivalcolony garrison <id>` now also reports discovery + assault state
  + `conquestReached`.

Deferred to D–E: the conquest PAYOFF (citizens to the existing colony +
the boss's Covenant skill + loot chests + the DEFEATED-HUSK conversion)
that consumes `conquestReached`/`isConquestEligible` (D); betrayal
scaling (E).

---

# STAGE D — the conquest PAYOFF (as-built, 2026-06-13)

The climax: when an assault is WON (Stage C's `resolveWin`, gated by
Stage B's `isConquestEligible` — boss dead AND ≥60% defenders killed),
the player gets three rewards and the settlement becomes a permanent
defeated husk. **NO second colony** — that mechanic is RETIRED (DESIGN
CHANGE 2; MineColonies is one-player-colony by design). Behind
`enableFactionSystem`. Code: `ConquestPayoff` (the sole-door payoff) +
`DealSpec.covenantSkillFor`/`factionRewardPool` + the `resolveWin` hook.
Structure-type-agnostic (town husks and dwarven-village husks identical).

## 1. Citizen levy → the player's EXISTING colony (per-faction profiles)

10–20 faction-themed citizens added via the lend-return path
(`createAndRegisterCivilianData` + `getCitizenSkillHandler().
incrementLevel(skill, n)` + `spawnOrCreateCitizen`). Per-faction
`CitizenProfile` (count + a themed skill pair) — flavour over balance
(citizens are non-combat), all named/tunable in `ConquestPayoff`:

| Faction | Count | Primary (+lvls) | Secondary (+lvls) | Theme |
|---|---|---|---|---|
| Dwargon | 15 | Strength +25 | Stamina +12 | miners/smiths |
| Falmuth | 16 | Stamina +22 | Strength +14 | soldiers |
| Luminous | 12 | Mana +22 | Knowledge +14 | clergy-mages |
| Shizu | 10 | Mana +22 | Focus +12 | fire-mage pupils |
| Leon | 12 | Strength +22 | Mana +14 | demon retainers |
| Otherworlders | 13 | Adaptability +20 | Creativity +12 | specialists |
| Jura Alliance | 18 | Knowledge +22 | Intelligence +14 | sages |

(Default for any unmapped physical faction: 10, Stamina/Adaptability.)

**TRACKED RISK — housing overflow (handled):** the levy respects the
colony's capacity. `headroom = max(0, getMaxCitizens() −
getCurrentCitizenCount())`; it adds `min(profileCount, headroom)` and
REPORTS the unhoused remainder to the player ("N more could not be
housed — your colony is at capacity X/Y. Expand to take more.") — never
crashes, never silently drops. **No-colony edge case:** if the player
owns no colony (no town hall), the levy is SKIPPED with a notice; the
skill + loot rewards still apply (skill to the player, loot at the ruin).

## 2. The boss's Covenant skill — granted by FORCE

`DealSpec.covenantSkillFor(factionId)` returns the faction's capstone
skill supplier (the same `SKILL_REWARDS` entry the diplomatic Covenant
route grants), and `DiplomacyManager.grantSkillReward` (made
package-visible) applies it idempotently: lack→learn, have+resistance→
no-op, have+upgradable→master, have+maxed→pure-magisteel fallback. So
conquering a faction grants its Covenant skill WITHOUT the Covenant
milestone — taking it by force.

## 3. Loot chest(s) from the faction's reward catalog

`DealSpec.factionRewardPool(factionId)` gathers every reward ItemStack
across that faction's catalog deals (the diplomatic-route rewards), so a
warlord earns ≈ what a diplomat would. 6–12 stacks drawn (with
replacement, so a small pool still fills) into 1–2 vanilla chests placed
at the town center (`Blocks.CHEST` + the `Container` block entity).

## 4. Defeated-husk conversion (permanent, inert)

`convertToHusk`: surviving defenders (up to 40% may remain) are POOF-
discarded, `garrisonUuids` cleared, `bossUuid` nulled (the anchor is gone
for good), `conquered = true`, `assaulted = false`. `conquestReached`
stays true (it recorded the WIN). **Buildings REMAIN** — a sacked ruin.
A conquered settlement is **excluded from all garrison/assault logic**:
- `declareWar` rejects it ("already a conquered husk");
- `buildWarListTag` sets `canDeclare = false` (no re-discovery-to-war);
- `tickGarrison`, `beginAssault`, `resetGarrison` all early-return on
  `s.conquered` (no garrison tick, no respawn, no re-assault).

## The world-rep consequence — composes, no double-apply

The marked boss's death DURING the assault already routed through the
Layer-1 two-sided marked-kill fan-out (faction standing down, its enemies
up) via `onLivingDeath` → the existing `FactionMarkTag` handling. Stage D
does **not** touch reputation — it layers the citizen/skill/loot/husk
payoff on top of the ripple that already fired. Confirmed: no D path
calls a world-rep mover.

## Testing

`/rivalcolony declare <id>` then `/rivalcolony win <id>` (or fight to
60%+boss-dead) → the player receives the faction levy in their existing
colony (Dwargon = high-Strength miners, etc.), the faction's Covenant
skill, and loot chest(s) at the ruin; the settlement flips to a husk
(buildings remain, `conquered` set, garrison cleared, excluded from
re-war); the boss-kill world-rep fan-out fired during the fight; overflow
is reported; no second colony is created; `enableFactionSystem` off →
no payoff. Build green.

Deferred to E: betrayal scaling (a Covenant ally betraying you escalates
the settlement's garrison/boss — extends B/C, consumes the diplomacy
tier read).

---

# STAGE E — betrayal scaling (as-built, 2026-06-13) — the arc is COMPLETE A–E

A thin add over B/C: declaring war on a faction the player has DIPLOMATIC
relations with (OPEN/PACT/COVENANT) scales the garrison UP as a betrayal
punishment — the deeper the ally, the harder the fight — AND the
war-declaration standing crash SHATTERS the relationship via the existing
below-WARY collapse. Behind `enableFactionSystem`. Code: `RivalColonies`
(betrayal block in `declareWar` + `ensureBetrayalBuffed`) + new
`Settlement.betrayalFactor`/`betrayalTier` + `WorldRepReason.WAR_DECLARED`.

## Betrayal detection + tier multipliers (⚠ BALANCE GUESSES)

`declareWar` reads `DiplomacyManager.getState(player, faction)`. The tier
sets an EXTRA garrison stat multiplier ON TOP of B's boss-EP stat-bump
(separate modifier ids → the two ADD modifiers stack):

| Relationship | Multiplier | Defender skills |
|---|---|---|
| NONE (no relations) | ×1.0 | — (not a betrayal) |
| OPEN (Diplomacy) | ×1.25 | — (stats only) |
| PACT (Alliance) | ×1.6 | Physical Attack Resistance |
| COVENANT | ×2.0 | Physical Attack Resistance + Self-Regeneration + the faction's own capstone skill |

All named/tunable in `RivalColonies` (`BETRAYAL_MULT_*`); first guesses,
flag for the polish playtest.

## Application (stacks on B; covers late-loaders)

`ensureBetrayalBuffed(mob, s)` applies the multiplier via the assassin
`multiplyAttribute` idiom over MAX_HEALTH / ATTACK_DAMAGE / MAX_MAGICULE /
MAX_AURA using SEPARATE `BETRAYAL_*` modifier ids, so it stacks on B's
`GARRISON_*` bump rather than replacing it. Idempotent (checks the
betrayal HP modifier). Called per-tick from `steerGarrisonToInvaders`, so
defenders that load in after the player teleports (war declared from afar)
get buffed the first time they're seen. On `resetGarrison` (retreat),
`stripBetrayalBuff` removes the betrayal modifiers from survivors so the
next assault re-derives the factor cleanly; `clearAssaultState` zeroes
`betrayalFactor`/`betrayalTier` (betrayal is per-assault).

## Defender skills — PASSIVE / RESISTANCE (no cast driver)

Granted via the Covenant `learnSkill` path applied to the mob
(`SkillAPI.getSkillsFrom(mob).learnSkill(...)`, idempotent). Chosen to be
**passive/resistance** skills that take effect just by being learned —
Physical Attack Resistance (damage reduction) and Self-Regeneration
(sustain) — plus the faction's own capstone at COVENANT. This deliberately
avoids an active-cast driver for a whole garrison (heavy for many mobs);
the skills make the defenders tankier/stickier without needing AI to fire
them. (A future polish could route active skills through the assassin
cast-driver for the boss specifically.)

## Standing consequence — composes automatically (no new collapse code)

`declareWar` writes `WorldReputationManager.modifyStanding(...,
BETRAYAL_STANDING_CRASH = −1000, WAR_DECLARED)`, clamping effective
standing to 0 (well below the WARY floor). The diplomacy `checkCollapse`
pass (per-second, derived purely from Layer-1 standing) then shatters any
relations to NONE next tick — the SAME mechanism as the Orc-Disaster
clamp, no betrayal-specific collapse code. The betrayal-scaled garrison is
the *punishment*; the relationship shatter is the *consequence*. War is
always declared hostile (the crash fires even against a NEUTRAL faction;
the collapse simply no-ops when there were no relations).

## Testing

`/rivalcolony declare <id>` against: a NEUTRAL/hostile faction → ×1.0 (no
betrayal buff); a DIPLOMACY faction → ×1.25; an ALLIANCE faction → ×1.6 +
physical resistance on defenders; a COVENANT faction → ×2.0 + resistance +
regen + capstone (brutal). `/rivalcolony garrison <id>` shows the betrayal
line. The relationship collapses to NONE within a second (watch
`/diplomacy`). The betrayal factor stacks on B's boss-EP scaling.
`enableFactionSystem` off → no war at all. Build green.

## Siege connection (future, NOT built)

Betraying a deep ally sets up the deferred SIEGE idea — a betrayed faction
launching a retaliatory super-raid on the player's colony. Recorded in
docs/future-ideas.md (Sieges — broken-alliance super-raids); Stage E is
the trigger condition that a future siege pass would consume.

---

# THE RIVAL-COLONY ARC IS COMPLETE (A–E)

- **A** — settlement generation (faux-towns + Dwargon dwarf-villages).
- **B** — the garrison (boss-EP-scaled defenders, persistence/reset, the
  60%-win tracking).
- **C** — discovery + Declare-War + the teleport-assault loop.
- **D** — the conquest payoff (citizens + Covenant skill + loot + husk).
- **E** — betrayal scaling (tier-scaled garrison + relationship shatter).

Remaining deferred (their own future passes / the polish playtest): PvP
colony raiding, the SIEGE system (broken-alliance super-raids, which E
sets up), the "summon absent subordinates first" war-party polish, and
all payout/balance tuning of the flagged BALANCE-GUESS constants.

---

# BATCH FIXES + bone-golem combat (as-built, 2026-06-13, v0.1.0)

A maintenance + feature batch on the rival-colony arc and diplomacy entry.

## Generation quality (#2 / #3 / #5)
- **Site selection** (`findBuildableCenter`): samples `SITE_SAMPLES` (8)
  candidate centers within `SITE_SCATTER` (24) blocks and keeps the flattest
  (min `surfaceRange` over the footprint), stopping early at
  `SITE_FLAT_ENOUGH` (4). Rejects cliff/steep spots; never fails (falls back).
- **Foundation leveling** (`levelPad`): before each building places, a flat
  pad is laid — solid ground at baseY−1 + `PAD_CLEAR_HEIGHT` (12) cleared
  above — over `BUILDING_PAD_HALF` (12). Buildings sit flush, not half off a
  ledge. Tradeoff: a flat platform is carved (terrain scarring); honest
  practical version, not full terrain-fitting.
- **Coplanar placement**: every building places at the chosen center's base
  Y (not per-building heightmap), so slopes can't terrace the town.
- **#3 spacing**: `GRID` widened 22 → 32 so buildings can't intersect.
- **#5 hut strip**: `queueHutStrip` records the footprint; `tickHutStrips`
  runs `HUT_STRIP_DELAY_TICKS` (100) after generation (placement is async)
  and replaces every `AbstractBlockHut` in each building's footprint with
  stone bricks. Settlements are decorative — conquest is rewards-only, so the
  huts were vestigial; nothing in garrison/war/conquest reads them.

## Defenders (#4 / #7)
- **#4 Clone fix**: `CloneEntity` (the copy-skill entity) renders with the
  missing-texture skin when spawned without a source to copy. Removed from
  the Luminous + Otherworlder rosters (Luminous → Falmuth Knight / Kyoya /
  Bone Golem; Otherworlders → Shogo / Mark Lauren / Shinji / Kirara).
- **#7 War highlight**: `steerGarrisonToInvaders` sets each loaded garrison
  member glowing during an assault; `clearGarrisonGlow` removes it on every
  resolve path (win / retreat / death-logout).

## #9 Bone golem combat AI
- **Element at spawn** (`assignBoneGolemElement`): a bone golem learns ONE
  random element's attack skill — darkness=Black Flame, wind=Voice Cannon
  (sonic), earth=Earth Manipulation, fire=Heat Wave, water=Water Blade. (The
  first four cast cleanly as mob spells; Earth Manipulation is the only earth
  attack available and is less battle-tested as a mob cast — the driver
  try/catches.)
- **Cast driver — now Nightmare's Tensura Utils (reworked 2026-06-17):**
  the hand-built `driveBoneGolemCast` has been DELETED and replaced by a
  single registration against the public
  `NightmareUtilsApi.registerReflectiveManascoreAutocaster(...)`
  (`RivalColonies.registerBoneGolemAutocaster()`, called once at common
  setup). Predicate = `mob.getType() == BONE_GOLEM`; skill filter =
  `BONE_GOLEM_CASTABLE` (the 5 element ids only — never autocasts a golem's
  other innate skills); cooldown = `BONE_GOLEM_CAST_COOLDOWN_TICKS` (80 /
  4 s — ⚠ BALANCE GUESS). The lib reads `mob.getTarget()`, which
  `steerGarrisonToInvaders` keeps locked onto an invader during an assault,
  so casting fires ONLY in combat (no target → no cast). PUBLIC-API-ONLY:
  no mixins into the lib. `assignBoneGolemElement` (one element skill per
  golem at spawn) is UNCHANGED.
- **Faction lore-power → skill mastery** (`boneGolemMasteryFraction`, ⚠
  tunable): apex (Milim) 1.0 · demon lords/great powers (Leon, Carrion,
  Luminous, Clayman) 0.8 · strong realms (Dwargon, Shizu, Otherworlders)
  0.6 · others (Falmuth, Jura, Tempest) 0.4. Mastery = fraction × maxMastery.
- Hostility/targeting reuses the existing `steerGarrisonToInvaders` lock —
  golems are hostile to the player + war party like any defender.

## Envoy prereqs (diplomacy — see also docs/diplomacy.md)
- **#1a inbound**: a faction won't reach out until the colony has
  `INBOUND_BUILDING_COUNT_REQ` (4) buildings and one at
  `INBOUND_BUILDING_LEVEL_REQ` (2)+ — added to the existing inbound bars.
- **#1b outbound**: the dispatched subordinate's EP threshold is now scaled
  inverse to faction friendliness — 5,000 floor (Tempest/Jura) up to 15,000
  (Luminous). All `ENVOY_EP_THRESHOLD` values tunable.
