# Tensura MineColonies Integration

A NeoForge 1.21.1 mod that bridges **Tensura: Reincarnated** and **MineColonies**. The first feature is allowing a named Tensura goblin to join a MineColonies colony as a citizen.

The developer is a beginner — explain concepts clearly, avoid jargon without explanation, and prefer simple and explicit code over clever abstractions.

## Project planning

Planning documents live in `docs/` and should be kept current as work progresses:

- [docs/roadmap.md](docs/roadmap.md) — milestone checklist and feature tiers; update task status as work completes
- [docs/decisions.md](docs/decisions.md) — record of key architectural and design decisions with reasoning; add new entries when a non-obvious choice is made
- [docs/dependencies.md](docs/dependencies.md) — exact jar versions in `libs/` and confirmed registry IDs; update when new deps are added

## Project layout

```
MDK-1.21.1-ModDevGradle-main/
  build.gradle              # Gradle build config (ModDevGradle 2.0.141)
  gradle.properties         # Mod metadata and version pins
  libs/                     # Local mod JARs (compile + runtime deps; gitignored)
  src/
    main/
      java/com/example/examplemod/
        ExampleMod.java                # Main mod entrypoint (@Mod class).
                                       # Hosts the NAMING_EVENT listener, swap
                                       # helpers (sendGoblinToColony / summonGoblin),
                                       # cost gate, server tick handler, death hooks.
        ExampleModClient.java          # MDK default client entrypoint (legacy).
        Config.java                    # NeoForge config (MDK placeholder).
        GoblinIdentitySavedData.java   # Server-global SavedData. GoblinIdentity
                                       # records (identityId, citizenId, colonyId,
                                       # goblinUUID, mode, entitySnapshot,
                                       # ownerPlayerUUID) and PendingGoblin pool.
        Networking.java                # NeoForge payload registration + S2C / C2S
                                       # records: RequestRoster / RosterResponse,
                                       # ActOnIdentity, OpenCollapseConfirm,
                                       # ConfirmCollapse. Server handlers.
        ClientEvents.java              # @OnlyIn(CLIENT). Keybind (G), installs
                                       # rosterClientHandler + confirmCollapseClientHandler.
        ClientRosterHandler.java       # @OnlyIn(CLIENT). Opens/refreshes RosterScreen
                                       # on roster response, handles ConfirmCollapseScreen
                                       # parent-refresh case.
        RosterScreen.java              # @OnlyIn(CLIENT). Goblin roster UI.
        ConfirmCollapseHandler.java    # @OnlyIn(CLIENT). Routes collapse-confirm
                                       # payload to the layered Screen.
        ConfirmCollapseScreen.java     # @OnlyIn(CLIENT). Warning dialog when a
                                       # swap would deplete the player's magicule.
      resources/
        assets/examplemod/lang/en_us.json   # Keybind + UI translations
      templates/
        META-INF/neoforge.mods.toml         # Mod metadata template (Gradle-expanded)
```

## Key identifiers

| Property | Value |
|---|---|
| Mod ID | `tensura_minecolonies` |
| Display name | Tensura MineColonies Integration |
| Version | 0.0.1 |
| Java package | `com.example.examplemod` (needs renaming — see below) |
| Group ID | `com.example.examplemod` (needs renaming) |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Java | 21 |

## Dependencies (all in libs/)

**Compile + runtime** (APIs this mod codes against):
- `minecolonies-1.1.1319-1.21.1.jar`
- `structurize-1.0.830-1.21.1.jar`
- `tensura-neoforge-2.0.1.0.jar`
- `architectury-13.0.8-neoforge.jar`
- `geckolib-neoforge-1.21.1-4.8.4.jar`
- `manascore-neoforge-4.0.0.2.jar`
- `SmartBrainLib-neoforge-1.21.1-1.16.11.jar`
- `TerraBlender-neoforge-1.21.1-4.1.0.8.jar`

**Runtime only** (transitive deps, not coded against):
- `blockui-1.0.209-1.21.1.jar`
- `domum-ornamentum-1.0.231-main.jar`
- `multipiston-1.2.58-1.21.1.jar`
- `towntalk-1.2.0.jar`

## Build and run

```
# Run the game client with all mods loaded
./gradlew runClient

# Build the mod JAR
./gradlew build   # output: build/libs/tensura_minecolonies-0.0.1.jar

# Regenerate data (resources produced by data generators)
./gradlew runData
```

## Known housekeeping debt

The MDK default package (`com.example.examplemod`) and class names (`ExampleMod`, `ExampleModClient`, `Config`) have not been renamed yet. When writing new code, start new classes in a proper package like `com.tensura.minecolonies` and migrate the existing files when convenient. Do not block feature work on this rename.

## Current feature status (Milestone 3 vertical slice)

The "two bodies, one identity" design is largely implemented end-to-end.
See `docs/roadmap.md` for stage-by-stage detail; `docs/decisions.md` for
the design rationale and design-choice history.

**Complete:**
- Stage 1b — pending pool (goblins named before any colony exists are
  promoted on `ColonyCreatedModEvent`).
- Stage A — naming creates `CitizenData` + count without spawning a body.
- Stage B — sneak-right-click send-to-colony swap; full item transfer.
- Stage C1 — `/summongoblin <name>` command brings the goblin back.
- Stage C2a/b — keybind-opened roster Screen (default `G`), two-way
  toggle, ConfirmCollapseScreen for the forced-collapse overspend prompt.
- Stage D — death hooks (`LivingDeathEvent` + `CitizenDiedModEvent`).
- Stage D2 — magicule cost gate (target.EP × 0.25), shared chokepoint.
- Stage D3 — stat sync (bump destination max attributes, then absolute
  copy of aura/magicule/spiritualHealth/HP plus flat copy of counters
  and destiny flags).
- Stage E — Tensura `MagicCircle` visuals (SPACE variant, 3× size,
  spinning), 2-second delay, sink-and-rise animations with X/Z lock and
  fall-damage prevention, refund-on-abort.

**Pending:**
- Stage F — goblin renderer for the in-colony citizen body. Currently
  the citizen looks like a default colonist; the firm end-product
  requirement is for it to render as a goblin (cf. "Colonies Maid
  Citizen" mod technique). Deliberately deferred until all the
  mechanics are proven.

**Open future-work notes recorded in decisions.md:**
- Multi-colony policy for pending pool drain and colony lookup.
- Town hall citizen-type menu (choose race at colony creation).
- Advisory messages gated by Tensura's Great Sage skill.
- Equalisation between Tensura stats and MineColonies citizen skills.
