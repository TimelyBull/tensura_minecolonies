# Dependency reference (`deps/`)

Standing, source-grounded reference for the mods this compat layer builds on, so
routine work never has to re-investigate them from zero. Each file follows one
shape per entry: **upstream system → canonical class path → how/whether WE use
it (with our file) → the correct pattern to extend it → the governing
constraint.**

These are pinned to the exact jar versions in `libs/` (see
[docs/dependencies.md](../docs/dependencies.md) for the version ledger + declared
ranges). If a jar version changes, re-verify before trusting a claim here.

| File | Mod | Version | What it covers |
|---|---|---|---|
| [minecolonies.md](minecolonies.md) | MineColonies | 1.1.1319-1.21.1 | colony/citizen model, custom event bus, colony-event/raid contract, buildings/jobs/research/requests, BlockUI GUI hooks |
| [tensura.md](tensura.md) | Tensura: Reincarnated (+ ManasCore) | 2.0.1.0 / 4.0.0.2 | EP/magicule/aura, skills, races, attributes, entity/subordinate/merchant hierarchy, rendering, Architectury events |
| [nightmares-utils.md](nightmares-utils.md) | Nightmare's Tensura Utils | 0.1.2 | the `sentient` mob-skill autocaster and its invariants |
| [structurize.md](structurize.md) | Structurize | 1.0.830-1.21.1 | schematic/blueprint loading + placement (MC's foundation lib) |

## The one cross-cutting fact: THREE substrates, don't mix them

The #1 latent-bug risk in this codebase is confusing which event bus / registry
system a mod uses. There are three, and our code touches all of them:

| Substrate | Used by | Subscribe / register with |
|---|---|---|
| **NeoForge** | our mod, vanilla MC | `@SubscribeEvent` / `NeoForge.EVENT_BUS`; `DeferredRegister.create(Registries.X, modid)` |
| **MineColonies** custom bus | colony/citizen/building events | `IMinecoloniesAPI.getInstance().getEventBus().subscribe(EventClass.class, handler)` — **NOT** `@SubscribeEvent`. Dispatch is **exact-class** (a superclass subscription won't catch subclasses), events are **not cancellable**, run server-thread, and handler exceptions are swallowed+logged. |
| **Architectury** | ALL of Tensura + ManasCore | `SomeEvent.EVENT.register(listener)`, fired via `.invoker()`. Cancellable ones return `EventResult` and mutate payload through `Changeable<T>`. Content via Architectury `DeferredRegister`/`RegistrySupplier`. |

When you add a listener, first decide which bus the event lives on. NeoForge
`@SubscribeEvent` will **silently never fire** for a MineColonies or Tensura
event.

## How the source behind these docs was obtained

None of the three mods ship sources — only compiled `.class` (readable, because
NeoForge dev uses official Mojang mappings). The findings here were produced by
decompiling the `libs/` jars with **Vineflower 1.10.1** (cached in the Gradle
modules dir). To regenerate a readable source tree for any dependency:

```
VF=$(find ~/.gradle/caches -name 'vineflower-*.jar' | head -1)
java -jar "$VF" -dgs=1 libs/<jar-name>.jar <output-dir>
```

Claims are marked `[READ]` (verified in decompiled source) or `[INFERRED]`
(deduction to verify) in the per-file notes. Decompiled generics/lambdas are
sometimes reconstructed — treat exact generic signatures as approximate.

See also: [docs/decisions.md](../docs/decisions.md) → "Dependency reference set
(`deps/`)" for why this lives here.
