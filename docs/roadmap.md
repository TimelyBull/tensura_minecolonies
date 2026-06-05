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

**Option B chosen:** named goblin stays a Tensura subordinate AND becomes a
MineColonies citizen (not converted or removed).

- [x] Task 1 — Find Tensura naming event (`TensuraEntityEvents.NAMING_EVENT` confirmed)
- [x] Task 1.5 — Add Architectury + ManasCore sub-modules to compile classpath
- [x] Task 2 — Register naming listener, log goblin name; detect colony at goblin's position
- [ ] Task 3 — Detect colony at goblin's location
- [ ] Task 4 — Register goblin as citizen while keeping it a Tensura subordinate
  *(needs investigation: can MineColonies associate citizen data with a
  non-`EntityCitizen` entity?)*

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
