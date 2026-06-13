# Getting Started

## Requirements

This is a NeoForge mod for **Minecraft 1.21.1**. It requires both base mods
(and their own dependencies) installed alongside it:

| Mod | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233+ |
| Tensura: Reincarnated | 2.0.1.0 |
| MineColonies | 1.1.1319 |

MineColonies also pulls in Structurize, BlockUI, Domum Ornamentum, and
MultiPiston; Tensura pulls in ManasCore, GeckoLib, SmartBrainLib, and
TerraBlender. Install the full set your launcher/modpack lists for each.

## Installation

1. Install NeoForge 1.21.1.
2. Drop this mod's jar into `mods/` along with Tensura, MineColonies, and
   all of their dependencies.
3. Launch. If a dependency is missing, the loader names it on the crash
   screen.

## How the mod works

The mod connects a named Tensura monster to a MineColonies citizen. The
same creature can take either role and switch between them:

- **At your side** — a Tensura creature with Existence Points (EP), skills,
  and combat behaviour.
- **In your colony** — a MineColonies citizen with a job and work skills.

Switching costs magicule but preserves the creature's identity (name,
progress, type). See [Races & Citizens](features/races-citizens.md) for the
full swap.

## First steps

1. **Found a colony** the normal MineColonies way (place a Town Hall from
   the Supply Camp/Ship, then a Builder's Hut).
2. **Name a Tensura monster** — a goblin, orc, dwarf, or lizardman — using
   Tensura's naming. A named monster becomes one of your subordinates.
3. **Send it to your colony** to add it as a citizen, or keep it at your
   side. Use the roster (press `G`) to manage your named monsters and move
   them between roles.

From there, the colony earns [reputation](features/colony-reputation.md),
the Tensura [factions](features/world-reputation.md) start tracking you, and
you can pursue [diplomacy](features/diplomacy.md) or
[conquest](features/rival-colonies.md). The whole faction layer is optional
and can be disabled in the [config](reference/config.md).
