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
  | Dwargon | Gazel Dwargo | Stalactite Caves (dwarven) |
  | Falmuth | Folgen | Fortress (militaristic) |
  | Shizu | Shizu | Pagoda (Japanese) |
  | Leon | Ifrit | Caledonia (grand keep) |
  | Otherworlders | Mai Furuki | Space Wars (sci-fi) |
  | Jura Alliance | Shin Ryusei | Jungle Treehouse (forest) |
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
- **⚠ Tracked risk (verify in-game):** the pack key passed to
  `getBlueprintFuture` is the display name ("Stalactite Caves" etc.);
  if MineColonies registers its packs under a different key, placement
  logs a failure and no blocks appear — a one-line swap to the folder
  id (`truedwarven`) if so. This is the single thing to confirm on the
  first `/rivalcolony spawn`.

Stages B–E (garrison, discovery/war, conquest→colony, betrayal) extend
the `Settlement` record's reserved seams (garrisonUuids,
defenderCountAtStart, assaultingPlayer, assaultOrigin, discoveredBy,
conquered) — already persisted, unused in A.

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
- **Stage D — Conquest → colony.** The Phase-1 conversion
  (`createColony` + hut registration — the FLAGGED moderate risk; keep
  the "controlled outpost" fallback ready) + loot chests + the citizen
  boost (#4) + the boss's Covenant skill grant.
- **Stage E — Betrayal scaling.** A thin add over B/C: tier-read +
  extra multiplier + defender skill grants + the WAR_DECLARED standing
  crash. Last because it depends on B (garrison) and C (declare flow)
  existing.

**Recommendation: proceed to build, Stage A first.** No deferred piece
is infeasible; all reuse verified call sites. Carry forward the tracked
risks (Phase-1 hut-registration in Stage D; boss EP-restore + defender
tether in Stage B; war-party summon-cost + body-mode resolution in
Stage C; citizen-cap overflow in Stage D). PvP, payout balance, and the
siege system remain explicitly deferred to their own later passes.
