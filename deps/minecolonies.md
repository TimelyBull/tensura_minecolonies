# MineColonies — dependency reference

Pinned to **minecolonies 1.1.1319-1.21.1** (`libs/minecolonies-1.1.1319-1.21.1.jar`).
Packages `com.minecolonies.{api,apiimp,core}`. All claims `[READ]` from decompiled
source unless marked `[INFERRED]`. Foundation lib Structurize has its own file:
[structurize.md](structurize.md).

**Bus reminder:** colony/citizen/building events ride **MineColonies' own bus**,
not NeoForge — see [README.md](README.md#the-one-cross-cutting-fact-three-substrates-dont-mix-them).

---

## 1. Registries & content

Synced NeoForge registries are exposed as `ResourceKey<Registry<…>>` constants on
`apiimp.CommonMinecoloniesAPIImpl` (L54-71). To add content, `DeferredRegister.create(<key>, yourModid)`:

| Registry key | Entry type | Notes |
|---|---|---|
| `COLONY_EVENT_TYPES` | `ColonyEventTypeRegistryEntry` | **we register into this** (raid event) |
| `BUILDINGS` | `BuildingEntry` | huts; built via `BuildingEntry.Builder` |
| `JOBS` | `JobEntry` | professions |
| `RECIPE_TYPE_ENTRIES` | `RecipeTypeEntry` | crafter recipe types |
| `RESEARCH_REQUIREMENT_TYPES` / `RESEARCH_EFFECT_TYPES` | — | research tree |
| `INTERACTION_RESPONSE_HANDLERS` | — | citizen dialogue (we deliberately avoid, §8) |
| `HAPPINESS_*`, `GUARD_TYPES`, `QUEST_*`, `EQUIPMENT_TYPES` | — | unused |

Vanilla building string IDs: `api.colony.buildings.ModBuildings`
(`LIBRARY_ID="library"`, `HOME_ID="residence"`, `BUILDER_ID`, `MINER_ID`…);
holders are `DeferredHolder<BuildingEntry,…>`, compared like
`building.getBuildingType() == ModBuildings.library.get()`.

MC's own colony-event types register in
`apiimp.initializer.ModColonyEventTypeInitializer` (Pirate/Barbarian/Egyptian/
Amazon/Norsemen) — the exact pattern our raid registration mirrors.

## 2. Public API & extension points

### Colony / citizen access (we consume heavily)
- `IColonyManager` (via `IMinecoloniesAPI.getInstance().getColonyManager()`):
  `getIColonyByOwner(level, uuid)`, `getColonyByDimension`, `getClosestIColony`.
- `IColony`: `getID()`, `getDimension()`, `getServerBuildingManager()`
  (`.hasTownHall()`, `.getTownHall().getPosition()`, `.getBuildings()`),
  `getCommonBuildingManager()` (dimension-agnostic building lookup),
  `getCitizenManager()`, `getRaiderManager()`, `getEventManager()`.
- `ICitizenManager`: `getMaxCitizens()`, `createAndRegisterCivilianData()`,
  `spawnOrCreateCivilian(...)`, `getCivilian(id)`, `removeCivilian(...)`,
  `resurrectCivilianData(...)`. **The generic "Civilian" API is why our
  minecolonies floor is 1.1.1319** — see [docs/dependencies.md](../docs/dependencies.md).
- `ICitizenData` (persistent authoritative record): `getId()`, `getName()`,
  `getEntity():Optional<AbstractEntityCitizen>`, `getColony()`,
  `getCitizenSkillHandler()`, `incrementLevel()`, happiness API.
- `ICitizenSkillHandler` + the 11-value `Skill` enum (Athletics, Dexterity,
  Strength, Agility, Stamina, Mana, Adaptability, Focus, Creativity, Knowledge,
  Intelligence — `Intelligence` has null pairings). We layer race bias on this;
  see [docs/lizardman-dwarf-and-skills.md](../docs/lizardman-dwarf-and-skills.md).
- `StaticHappinessModifier` — per-citizen happiness (we add a 0.5 named-race
  modifier). Colony-wide alternative `injectModifier` is unused.
- `IBuilding` reads: `getBuildingLevel()` (int; 0 = none/under construction),
  `getBuildingType()` (→ `BuildingEntry`), `getSchematicName()` (stable type
  string, distinct from registry name), `getPosition()`.

### The colony-event / raid-event contract (we IMPLEMENT it)
Chain: `IColonyRaidEvent → IColonyEntitySpawnEvent → IColonySpawnEvent →
IColonyEvent (extends INBTSerializable<CompoundTag>)`. Implemented by
[`TensuraRaidEvent`](../src/main/java/com/example/examplemod/TensuraRaidEvent.java).
- `IColonyEvent` required: `getStatus/setStatus(EventStatus)`, `int getID()`,
  `ResourceLocation getEventTypeID()`, `setColony(IColony)`; default
  `onUpdate/onStart/onFinish/onTileEntityBreak/onNightFall`; `serialize/deserializeNBT`.
- `IColonySpawnEvent`: `setSpawnPoint/getSpawnPos`. `IColonyEntitySpawnEvent`
  (default): `getEntities()`, `registerEntity/unregisterEntity`, `onEntityDeath`.
- `IColonyRaidEvent`: `getNormalRaiderType/getArcherRaiderType/getBossRaiderType`,
  `addSpawner(BlockPos)`, `getWayPoints()`; `isRaidActive()` = PROGRESSING||PREPARING.
- `EventStatus` (ordinal-stable): `STARTING, PREPARING, PROGRESSING, WAITING,
  DONE, CANCELED`.
- Registration: `new ColonyEventTypeRegistryEntry(TriFunction<IColony,CompoundTag,
  Provider,IColonyEvent> loader, ResourceLocation id, boolean isRaidEvent)`.
  **`isRaidEvent=true` is what makes `RaiderManager.isRaided()` count our event
  (and thus MC citizen flee/hide fire).** The 2-arg ctor defaults it false. Our
  loader arg order `(colony, tag, provider)` matches the TriFunction. Registered
  in `ExampleMod` (~L293-298) via `DeferredRegister` on the mod bus.

### GUI bases (we extend / hook — BlockUI, not vanilla widgets)
- `core.client.gui.AbstractWindowSkeleton extends BOWindow implements
  ButtonHandler` — `registerButton(String id, Runnable|Consumer<Button>)`,
  `findPaneByID`/`findPaneOfTypeByID`, paging (`switchView`). Our
  roster/diplomacy/race-picker windows extend it.
- `core.client.gui.citizen.AbstractWindowCitizen extends AbstractWindowSkeleton`
  — fields `protected final IColonyView colony`, `protected final
  ICitizenDataView citizen` (**no public getter — we reflect the `citizen`
  field**). Tab ids wired in the ctor: `mainTab/Icon, requestTab, inventoryTab,
  happinessTab, familyTab, debugTab(dev), jobTab(hidden if work building ==
  library)`. Sub-windows (`MainWindowCitizen`, `FamilyWindowCitizen`, …) each
  `new …(citizen).open()` build a **fresh** window, so an injected tab must be
  re-added on each build. Tab bg `minecolonies:textures/gui/modules/
  tab_left_side{1,2,3}.png` (32×26), icon 20×20 at `(5, tabY+3)`. This is what
  [`CitizenTradeButtonHandler`](../src/main/java/com/example/examplemod/CitizenTradeButtonHandler.java)
  does via `ScreenEvent.Init.Post`.

### Request system (available, unused)
`api.colony.requestsystem` — `manager.IRequestManager`,
`resolver.IRequestResolver` (+ queued/retrying/player variants + factory +
provider), `request.IRequest`, `requestable.*`, `StandardFactoryController`. The
intended door for citizen item-fetching/logistics. We build nothing on it.

## 3. Events (MineColonies bus)

Get the bus: `IMinecoloniesAPI.getInstance().getEventBus()` (impl
`DefaultEventBus`). Subscribe: `.subscribe(ConcreteEvent.class, handler)`.
**Dispatch is exact-class (`post` keyed on `event.getClass()`)** — a superclass
subscription won't catch subclasses. Events are **not cancellable**, carry no
result, run server-thread, and handler exceptions are swallowed+logged.

| Event (`api.eventbus.events...`) | Payload / accessors | Fires |
|---|---|---|
| `ColonyCreatedModEvent` | colony | `CreateColonyMessage.onExecute` (~L111) |
| `ColonyDeletedModEvent` | colony | `ColonyManager` (~L160) |
| `CitizenAddedModEvent` | colony, citizen, **`CitizenAddedSource`** | 5 sources; **top-up = INITIAL** (`CitizenManager.onColonyTick` ~L477) |
| `CitizenDiedModEvent` | colony, citizen, **`DamageSource`** | `EntityCitizen.die` (~L1198) — **carries DamageSource but NOT the killer** |
| `CitizenRemovedModEvent` | id, colony, reason | fires on ANY removal incl. unload |
| `CitizenJobChangedModEvent` | — | job assignment |
| `BuildingConstructionModEvent` | `getBuilding()`, `getColony()`, **`getWorkOrder()`** → `getWorkOrderType()` (`BUILD/UPGRADE/REPAIR/REMOVE`) | build/upgrade complete (`AbstractEntityAIStructureWithWorkOrder` + `RegisteredStructureManager`) |
| `BuildingAddedModEvent` / `BuildingRemovedModEvent` | building | placement/removal |

Two consequences we rely on:
- **`CitizenDiedModEvent` lacks the killer**, so player-kill reputation uses the
  NeoForge `LivingDeathEvent` instead (see
  [docs/reputation-system.md](../docs/reputation-system.md)).
- No research/job/request mod-events exist in `api.eventbus.events` [INFERRED];
  research completion is observed via the research manager, not a fired event.

## 4. Core systems & invariants

- **THE invariant: `ICitizenData` (persistent, authoritative) is decoupled from
  `AbstractEntityCitizen` (transient world entity).** `getEntity()` returns
  `Optional` — empty whenever the citizen's chunk is unloaded. **Guard every
  `.get()`.** `createAndRegisterCivilianData()` creates data with **no entity and
  no event**; the entity is spawned separately and bound by UUID.
- **One colony per player per dimension.** `CreateColonyMessage` guards on
  `getIColonyByOwner(...) == null`; `createColony` is the sole creation path. MC
  is architecturally single-colony — this is why our conquest payoff adds
  citizens to the *existing* colony rather than founding a second one (Stage-D
  decision; see [docs/rival-colony-investigation.md](../docs/rival-colony-investigation.md)).
- **Reproduction/growth fires no event.** `ReproductionManager.trySpawnChild()`
  calls `createAndRegisterCivilianData()` directly;
  `CitizenAddedModEvent.CitizenAddedSource.BORN` is declared in the enum **but
  never posted**. This is why
  [`ReproductionManagerMixin`](../src/main/java/com/example/examplemod/mixin/ReproductionManagerMixin.java)
  (`@WrapOperation` on that `createAndRegisterCivilianData` call) is mandatory,
  not optional — it's the only interception point for born children.
- **Visitor gotcha.** `registerCivilian` instantly discards any entity with
  `civilianID == 0` or a UUID mismatch — why envoy `VisitorCitizen`s must be
  registered through `VisitorManager` (see [docs/envoy-system.md](../docs/envoy-system.md)).
- **Event lifecycle runs on COLONY-tick cadence** via `EventManager.onColonyTick`
  (per-colony `IEventManager`, `Map<Integer,IColonyEvent>`; `getAndTakeNextEventID`
  never mints 0). Transitions: `DONE → onFinish()` + structure-backup restore +
  remove; `STARTING → onStart()`; `CANCELED → remove`; else `onUpdate()`.
  **To end a raid, set status `DONE`** and let the next colony tick clean up.
  `registerEntity`/`onEntityDeath` only route if the event is an
  `IColonyEntitySpawnEvent` (ours is) — otherwise the spawned entity is
  `remove(DISCARDED)` immediately.
- **`onUpdate` is colony-tick cadence and only while the colony ticks** — don't
  put per-second logic there. Our steering/resolution runs on our own 1 s
  scheduler in [`TensuraRaids.tick`](../src/main/java/com/example/examplemod/TensuraRaids.java).

## 5. Data-driven surface

MC data listeners (`core.datalistener.*`, `SimpleJsonResourceReloadListener`,
read `data/<ns>/<folder>/*.json`): `crafterrecipes` (`type` ∈
recipe/recipe-multi-out/remove/remove-input → `CustomRecipeManager`),
`researches` (top-level prop `branch`), `citizennames`, `visitors`,
`colony/quests`, `colony/recruitment_items`, `colony/diseases`, `study_items`,
`compatibility`. **Buildings/huts are NOT plain JSON** — code `BuildingEntry` +
structure-pack `.blueprint` files (loaded via Structurize `StructurePacks`, see
[structurize.md](structurize.md)). Loot: `api.loot` (`ModLootTables`, custom
conditions `ResearchUnlocked`/`EntityInBiomeTag`). We ship no MC datapack JSON
(only lang keys + our `trade.png` icon + our own structures).

## 6. Capabilities & attachments

None in scope. MC persists building/event/research state through **colony NBT**
(`IColonyEvent.serializeNBT`), not NeoForge attachments. The colony-event
registry is client-synced (`syncedRegistry`).

## 7. Gotchas (this version)

1. ✅ **Colony-event persistence bug — FIXED (2026-07-26) by
   [`EventManagerMixin`](../src/main/java/com/example/examplemod/mixin/EventManagerMixin.java).**
   Originally a non-`minecolonies` raid event did NOT survive save/reload:
   `core.colony.managers.EventManager.readFromNBT` hardcodes
   `new ResourceLocation("minecolonies", tag.getString("name"))` and `writeToNBT`
   stores only `getEventTypeID().getPath()` (path, no namespace), so our
   `tensura_minecolonies:tensura_raid` missed the registry lookup →
   `"Event is missing registryEntry!"` → the in-progress raid was silently
   dropped on reload (boss bar, tracked raider set, `isRaided()` flee state all
   cleared). The fix is a MixinExtras `@WrapOperation` on the
   `Registry.get(ResourceLocation)` call at offset 88 of `readFromNBT`: when the
   forced-`minecolonies` lookup returns null it recovers the event type by
   matching the stored PATH across the whole colony-event registry (paths are
   unique; a normally-resolving `minecolonies` event never reaches the scan).
   Read-side only — no on-disk format change, fixes existing and future saves.
   **Still verify in-game** (start a raid, save & quit to title, reload, confirm
   the raid + boss bar resume).
2. **Colony-event types must register on the MOD bus** via `DeferredRegister`
   before registry freeze — our ctor does it.
3. **MC event bus ≠ NeoForge bus**; call `IMinecoloniesAPI.getInstance()` after
   the MC API is up (we do it in `commonSetup`). `@SubscribeEvent` never fires
   for MC events.
4. `onUpdate` is colony-tick cadence — see §4.
5. The citizen window is rebuilt on every tab switch — re-add injected panes on
   each `Init.Post`, guarded by `findPaneByID(TAB_ID)`.
6. The reflected `"citizen"` field on `AbstractWindowCitizen` is version-fragile
   — fail closed if it's absent.
7. `isRaidEvent=true` is mandatory for flee semantics (2-arg ctor defaults false).
8. BlockUI-only: `BOScreen` won't render/route vanilla widgets and clips
   off-window children — use an in-window `ButtonImage` + `registerButton`.
9. **`IRaiderManager.calculateSpawnLocation()` returns null in common cases —
   callers MUST supply a perimeter-safe fallback.** [READ, bytecode
   `core.colony.events.raid.RaidManager`] When it succeeds it is genuinely
   perimeter-safe: averages the LOADED buildings, picks a random direction
   ~500 blocks out, walks outward from the edge-most building in 16-block
   steps, and only accepts points passing `isValidSpawnPoint` (≥ 35 blocks
   from EVERY leveled building; more per level for guard towers ×7, homes ×4,
   town hall ×8). Null paths: no loaded buildings; `getBestBuilding` toward
   the direction returns null; all 8 direction attempts fail the solid-ground
   `findAround` (and `skyRaiders` is off). A colony-center-offset fallback
   put raid waves inside houses (bug report 2026-07-10) — the correct
   fallback is the claimed-border march (`TensuraRaids.computeEdgeSpawnPos`).
   `isValidSpawnPoint(Collection<IBuilding>, BlockPos)` is public static and
   reusable.

## 8. What we consume ↔ available-but-unused

- **Consumed correctly:** `IColonyRaidEvent` + `ColonyEventTypeRegistryEntry` +
  `COLONY_EVENT_TYPES` (build ON the framework — but see the §7.1 persistence
  bug); `BuildingConstructionModEvent` on the MC bus (reputation +
  [diplomacy](../docs/diplomacy.md) deal fulfillment); building/citizen reads;
  `AbstractWindowSkeleton`/`AbstractWindowCitizen` tab injection.
- **Available-but-unused (know it exists so MC source needn't be reopened):**
  the whole **request system** (citizen logistics — we reimplement none, which is
  fine); `BuildingEntry.Builder`/`JobEntry`/research datapack/recipe registry (if
  we ever add a hut/job/research); colony-wide `injectModifier`. **MC's own
  `IRaiderManager`** has nightfall raid scheduling + difficulty scaling; note we
  ALREADY use its utilities (`calculateSpawnLocation`, `willRaidTonight` — see
  [docs/raid-system.md](../docs/raid-system.md)) and deliberately run our own
  reputation-tier-triggered scheduler in
  [`TensuraRaids`](../src/main/java/com/example/examplemod/TensuraRaids.java).
  Whether any of that could hand back to MC's machinery without losing the
  rep-tier trigger is an open investigation — tracked in decisions.md →
  "Available-but-unused upstream surface (adoption index)" (entry 3).
- **Justified reimplementation (not a fight):** we deliberately avoided
  `INTERACTION_RESPONSE_HANDLERS` for envoy dialogue because
  `IInteractionResponseHandler` is hard-bound to `ICitizenData`; we use a custom
  `EnvoyDialogueScreen` instead ([docs/envoy-system.md](../docs/envoy-system.md)).
- **Where we brush intended usage:** the raid persistence bug (§7.1) was an
  undocumented MC namespace limitation (now worked around by `EventManagerMixin`,
  read-side path recovery — not a fight); the trade tab reflects the `citizen`
  field because MC exposes no public accessor (acceptable, fragile).
