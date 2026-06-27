# Dependency Versions

All jars live in `libs/` and are NOT committed to git. If you need to reproduce
the build, source these versions manually from CurseForge / Modrinth / GitHub.

## Platform

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Java | 21 |
| ModDevGradle | 2.0.141 |
| Parchment mappings | 2024.11.17 for MC 1.21.1 |

## Compile + runtime dependencies

| Jar file | Version | Notes |
|---|---|---|
| `minecolonies-1.1.1319-1.21.1.jar` | 1.1.1319 | Full jar (2808 classes, 848 in `api/`) — not an API-only artifact |
| `structurize-1.0.830-1.21.1.jar` | 1.0.830 | Required by MineColonies |
| `tensura-neoforge-2.0.1.0.jar` | 2.0.1.0 | Full mod jar |
| `architectury-13.0.8-neoforge.jar` | 13.0.8 | Full mod jar — contains `dev.architectury.event.*` API |
| `geckolib-neoforge-1.21.1-4.8.4.jar` | 4.8.4 | Required by Tensura for animations |
| `manascore-neoforge-4.0.0.2.jar` | 4.0.0.2 | Main ManasCore jar — embeds sub-modules via JiJ |
| `manascore-architectury-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-attribute-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-command-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-config-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-inventory-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-keybind-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-network-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module — contains `Changeable` used by `NAMING_EVENT` |
| `manascore-race-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-skill-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `manascore-storage-neoforge-4.0.0.2.jar` | 4.0.0.2 | Sub-module, extracted for compile classpath |
| `SmartBrainLib-neoforge-1.21.1-1.16.11.jar` | 1.16.11 | Required by Tensura for AI |
| `TerraBlender-neoforge-1.21.1-4.1.0.8.jar` | 4.1.0.8 | Required by Tensura for biome blending |
| `nightmareutils-0.1.2.jar` | 0.1.2 | Nightmare's Tensura Utils. Provides `dev.shadowako.nightmareutils.api.NightmareUtilsApi` — the mob-skill autocaster that drives bone-golem + assassin spell use. Integrated through the PUBLIC API ONLY (author granted permission; no mixins into their classes). Declared `required` in `neoforge.mods.toml` (`modId="nightmareutils"`, `[0.1,)`). |

## Runtime-only dependencies

These are not coded against directly — they are transitive requirements of
MineColonies that must be present for the game to run.

| Jar file | Version |
|---|---|
| `blockui-1.0.209-1.21.1.jar` | 1.0.209 |
| `domum-ornamentum-1.0.231-main.jar` | 1.0.231 |
| `multipiston-1.2.58-1.21.1.jar` | 1.2.58 |
| `towntalk-1.2.0.jar` | 1.2.0 |

## Declared dependency ranges (neoforge.mods.toml)

Required dependencies the mod declares at runtime (modId → versionRange). Floors
= the versions in `libs/` (the exact build target); an incompatible version is
refused at load with a clear message instead of crashing mid-game. The modIds
are the authoritative ones read from each dependency jar's own `mods.toml`.

| modId | versionRange | Why this floor |
|---|---|---|
| `neoforge` | `${neo_version_range}` | platform |
| `minecraft` | `${minecraft_version_range}` | platform |
| `minecolonies` | `[1.1.1319,)` | the generic "Civilian" citizen-manager API (`spawnOrCreateCivilian`/`getCivilian`/`removeCivilian`/`createAndRegisterCivilianData`) doesn't exist before this; older builds (e.g. 1.1.1281) `NoSuchMethodError` mid-game |
| `structurize` | `[1.0.830,)` | schematic placement APIs (StructurePacks) |
| `blockui` | `[1.0.209,)` | citizen-window tab injection extends BlockUI types |
| `domum_ornamentum` | `[1.0.231,)` | decorative blocks in placed schematics |
| `tensura` | `[2.0.1.0,)` | races / entities / storages |
| `manascore` | `[4.0.0.2,)` | Tensura's skill/storage/attribute library |
| `nightmareutils` | `[0.1,)` | mob-skill autocaster (public API) |

⚠ The true MINIMUM compatible MineColonies is unverified — 1281 is known broken,
1319 known good; the exact build where the Civilian API landed wasn't bisected
(no 1281 jar to inspect). Lower the `minecolonies` floor only if an older build
is tested working.

## Confirmed registry IDs

| Entity | Registry ID |
|---|---|
| Tensura goblin | `tensura:goblin` |
