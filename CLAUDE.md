# Tensura MineColonies Integration

A NeoForge 1.21.1 mod that bridges **Tensura: Reincarnated** and **MineColonies**. The first feature is allowing a named Tensura goblin to join a MineColonies colony as a citizen.

The developer is a beginner — explain concepts clearly, avoid jargon without explanation, and prefer simple and explicit code over clever abstractions.

## Project layout

```
MDK-1.21.1-ModDevGradle-main/
  build.gradle              # Gradle build config (ModDevGradle 2.0.141)
  gradle.properties         # Mod metadata and version pins
  libs/                     # Local mod JARs (compile + runtime deps)
  src/
    main/
      java/com/example/examplemod/
        ExampleMod.java       # Main mod entrypoint (@Mod class)
        ExampleModClient.java # Client-only entrypoint
        Config.java           # NeoForge config (placeholder, MDK default)
      resources/
        assets/examplemod/lang/en_us.json
      templates/
        META-INF/neoforge.mods.toml   # Mod metadata template (expanded by Gradle)
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

## Current feature: goblin citizen

**Goal:** when a player right-clicks a named Tensura goblin near a MineColonies colony, the goblin joins that colony as a citizen.

Relevant APIs to research:
- Tensura: look for the goblin entity class (`IGoblin` or similar) and how named entities are detected
- MineColonies: `IColonyManager`, `IColony`, `ICitizenData` — the colony manager can find a colony by world position, and colonies expose a method to add or register new citizens
