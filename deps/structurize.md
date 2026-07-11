# Structurize — dependency reference

Pinned to **structurize 1.0.830-1.21.1** (`libs/structurize-1.0.830-1.21.1.jar`).
Package root `com.ldtteam.structurize`. This is MineColonies' foundation lib; we
use it (via a MineColonies handler subclass) to place themed MineColonies
schematics for rival-colony settlements. All claims `[READ]`.

Consumer: [`RivalColonies.java`](../src/main/java/com/example/examplemod/RivalColonies.java)
(`placeBuilding` ~L673-689, `isPackBlueprintsReady` ~L662-671). See
[docs/rival-colony-investigation.md](../docs/rival-colony-investigation.md).

## Our placement path (this IS the intended flow)

```java
Blueprint bp = StructurePacks.getBlueprint(packDisplayName, "sub/path.blueprint",
                                           level.registryAccess());   // SYNC
if (bp == null) { /* log pack+path, defer */ }
var handler = new CreativeBuildingStructureHandler(level, centerPos, bp,
                                                   RotationMirror.NONE, /*fancy*/true);
var placer  = new StructurePlacer(handler);
Manager.addToQueue(new PlaceStructureOperation(placer, /*@Nullable*/ player));
```

`CreativeBuildingStructureHandler` is **MineColonies'**
(`com.minecolonies.api.util.CreativeBuildingStructureHandler`), subclassing
Structurize's `placement.structure.CreativeStructureHandler`. We keep it (not
the Structurize-native helper below) because it applies MC's per-building block
substitution / domum-ornamentum parity for MC blueprints.

## Public API [READ]

### `storage.StructurePacks` (static facade)
Blueprint loaders come as **SYNC / ASYNC-Future twins**.
- SYNC (blocks on `waitUntilFinishedLoading()`, reads NBT off disk):
  `@Nullable Blueprint getBlueprint(String packId, String subPath,
  HolderLookup.Provider)` — **subPath must include the `.blueprint` extension**
  (this loader does NOT append it; only the `findBlueprint`-by-name family
  strips/matches it). ← **WE USE THIS.** Also `getBlueprint(pack, Path, …)`,
  `getBlueprints(...)`, `findBlueprint(...)`, `getCategories(...)`.
- ASYNC = `CompletableFuture.supplyAsync(() -> <sync call>, IOPool.getExecutor())`:
  `getBlueprintFuture(...)` etc. ⚠ **the future is NOT resolved on return** — the
  trap below.
- Pack registry: `@Nullable StructurePackMeta getStructurePack(String key)` —
  **key = pack DISPLAY NAME** (`StructurePackMeta.getName()` = JSON `name`);
  `hasPack(key)`; `waitUntilFinishedLoading()` (blocks on a `ManualBarrier` until
  server pack-indexing is done).

### `blueprints.v1.Blueprint`
Geometry (`getSizeX/Y/Z`, `getStructure():short[][][]`, `getTileEntities`,
`getEntities`), keys (`get/setName/FileName/FilePath/PackName`), transform
(`setRotationMirror(RotationMirror, Level)`, `getPrimaryBlockOffset()`), reads
(`getBlockState(BlockPos)`, `getBlockInfoAsList`).

### `management.Manager` (static)
- `addToQueue(ITickedWorldOperation op)` — appends to `scanToolOperationPool`
  (a LinkedList). ← **WE USE THIS.**
- `onWorldTick(ServerLevel)` — **THE pump**; drains ≤ `maxOperationsPerTick`/tick,
  calls `op.apply(world)`, pops on `true`.

### `operations.PlaceStructureOperation`
`new PlaceStructureOperation(@NotNull StructurePlacer placer, @Nullable Player)`.
⚠ ctor dereferences `placer.getHandler().getBluePrint().getName()` →
**the blueprint must be non-null at construction** (null → NPE). `apply` is a
5-phase state machine (0 can't-float solids, 1 weak-solid, 2 clear-water, 3 any
solid, 4 spawn entities).

### `placement.StructurePlacer`
`new StructurePlacer(IStructureHandler)`. `isReady()` delegates to the handler.

### `api.RotationMirror` (enum, 8 values)
`NONE, R90, R180, R270, MIR_NONE, MIR_R90, MIR_R180, MIR_R270`. We pass `NONE`.

## Core systems & invariants [READ]

- **Placement is a multi-tick operation queue, not instant.** `addToQueue`
  enqueues; `Manager.onWorldTick` (on Structurize's `LevelTickEvent.Pre`
  subscriber, ServerLevel only) drains a capped number per tick. Don't assume the
  structure exists the tick you queue it.
- **Server-thread + ServerLevel only.** Never touch the queue client-side.
- `PlaceStructureOperation.apply` guards on `placer.isReady()` **and dimension
  match** (`handler.getWorld().dimension() == world.dimension()`), else returns
  false and retries next tick (never dropped). Don't reuse a handler across
  dimensions.
- **No "structure placed / building complete" event exists.** Post-place logic
  requires overriding `IStructureHandler.onCompletion()` (subclass), not a
  listener. We don't need one (boss/garrison are spawned separately).

## Data format [READ]

- Pack root = a directory with `pack.json` (`StructurePackMeta`), requires
  `packFormat == 1`, keyed by display name.
- Blueprint files are `*.blueprint` = gzip NBT (`NbtIo.readCompressed`). The sync
  `getBlueprint(pack, subPath, provider)` resolves `subPath` against the pack
  path verbatim — the string must carry `.blueprint`.

## ⚠ THE gotcha: sync-vs-async loading trap [READ — root cause confirmed]

`AbstractStructureHandler.hasBluePrint()` returns `blueprint != null`. If you
build the handler with the **Future** ctor, `blueprint` stays null until
`getBluePrint()` runs AND `future.isDone()`. MC's async
`CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(...)` checks
`hasBluePrint()` up front — with an unresolved future that's false, so it
**silently no-ops and never queues.** This is exactly the bug commented in
`RivalColonies` ~L645-671.

**Fix (what we do, and it's correct):** load the blueprint **synchronously** via
`StructurePacks.getBlueprint(pack, path, level.registryAccess())` and build the
handler with the **Blueprint** ctor, so `hasBluePrint()`/`isReady()` are true
immediately, then `Manager.addToQueue`. `isPackBlueprintsReady()` is a sensible
belt that probes a known blueprint and defers generation during the mid-reload
window where the pack isn't yet indexed.

Also: `getBlueprint`/`findBlueprint`/`getBlueprints`/`getCategories` all call
`waitUntilFinishedLoading()` first — fine on a generation tick, but they can
block; avoid on startup-latency paths.

## What we consume ↔ available-but-unused

- **Consumed (correct):** `StructurePacks.getBlueprint(...)` SYNC ·
  `getStructurePack(...)` · MC `CreativeBuildingStructureHandler(...,Blueprint,
  NONE,true)` · `new StructurePlacer` · `Manager.addToQueue(new
  PlaceStructureOperation(...))` · `RotationMirror.NONE`.
- **Available-but-unused:** `StructurePlacementUtils.loadAndPlaceStructureWithRotation(Level,
  Blueprint, BlockPos, RotationMirror, boolean fancy, ServerPlayer)` — a
  Structurize-native one-call helper doing our whole handler→placer→addToQueue
  sequence, taking a **resolved Blueprint** (so it's trap-safe). Would collapse
  our boilerplate for **non-MC** packs; keep MC's handler for MC blueprints.
  Also unused: `findBlueprint(pack, Predicate)` / `getBlueprints(pack, folder)`
  for data-driven building selection (we hardcode paths today).
- **Where our code fights intended usage:** it doesn't — sync-load + Blueprint
  ctor + addToQueue IS the intended flow; the only friction was MC's async
  helper, correctly bypassed.
