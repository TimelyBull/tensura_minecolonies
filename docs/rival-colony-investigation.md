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
