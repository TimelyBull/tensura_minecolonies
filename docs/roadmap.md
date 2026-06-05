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
re-marks travelling, spawns a fresh Tensura goblin at the player's position
with `IExistence` restored from snapshot, and updates the reverse map with the
new goblin UUID.

- [x] Despawn `EntityCitizen` in colony (`discard()`, not `die()`)
- [x] Re-suppress respawn loop via `startTravellingTo`
- [x] Spawn fresh goblin at player position
- [x] Restore `IExistence` via `ExistenceStorage.load(snapshot)` +
      `setCustomName` for the nameplate
- [x] Update reverse map with the new goblin entity UUID

**C2 — Roster keybind menu** ⬜ PENDING
Replace the command trigger with a proper UX: keybind opens a screen listing
the player's named entities; selecting one fires summon.

- [ ] Client keybind + C2S "request roster" packet
- [ ] Server handler returns S2C list of player's identities
- [ ] Client `Screen` subclass with selectable list
- [ ] C2S "summon this identity" packet → server runs summon logic

### Stage D — Identity persistence across swaps and save/reload ⬜ PENDING
The saved identity (name, citizen ID, goblin UUID, current mode) survives world
save/reload and repeated send/summon cycles.

- [x] Implement saved data (`GoblinIdentitySavedData` — built in Stage B)
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
