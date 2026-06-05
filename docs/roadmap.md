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

### Stage A — Name → CitizenData + count, no body 🔄 IN PROGRESS
Naming a goblin (server-side, when a colony exists) immediately creates a
`CitizenData` entry and increases the colony's citizen count. No `EntityCitizen`
is spawned. The goblin stays alive at the player's side as a Tensura subordinate.

- [x] Find Tensura naming event (`TensuraEntityEvents.NAMING_EVENT`)
- [x] Add Architectury + ManasCore sub-modules to compile classpath
- [x] Register naming listener; detect colony at goblin's position
- [x] Call `createAndRegisterCivilianData()`, set citizen name, log count
- [ ] Store persistent identity (saved data linking goblin UUID → citizen ID)
- [ ] Prevent `EntityCitizen` auto-spawn for these citizens (resolved by design:
      no body at naming time; CitizenData has no entity, spawn loop won't fire
      until a body is explicitly materialized in Stage B)

### Stage B — Send-to-colony swap (ugly colonist) ⬜ PENDING
Player triggers send. Goblin dissolves → default `EntityCitizen` materializes in
colony at a valid spawn point. No goblin renderer yet — default colonist appearance
is intentional at this stage.

- [ ] Implement send trigger (command or right-click item, TBD)
- [ ] Despawn goblin entity at player's side
- [ ] Call `spawnOrCreateCivilian()` to materialize the citizen body
- [ ] Link the citizen body back to our saved identity data

### Stage C — Summon swap + roster keybind menu ⬜ PENDING
Keybind opens a roster screen listing the player's named entities. Selecting one
dissolves the citizen in the colony and materializes the goblin at the player's side.

- [ ] Implement roster screen (basic list of named-entity identities)
- [ ] Implement summon trigger from roster
- [ ] Despawn `EntityCitizen` in colony
- [ ] Materialize goblin entity at player's position

### Stage D — Identity persistence across swaps and save/reload ⬜ PENDING
The saved identity (name, citizen ID, goblin UUID, current mode) survives world
save/reload and repeated send/summon cycles.

- [ ] Implement saved data (`SavedData` subclass)
- [ ] Verify identity survives server restart
- [ ] Verify citizen count stays correct across swaps

### Stage E — Magic circle visuals ⬜ PENDING
Add dissolve/materialize visual effects (particles, sound, brief animation) to
the send and summon transitions.

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
