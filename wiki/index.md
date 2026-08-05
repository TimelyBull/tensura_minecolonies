# Tensura × MineColonies

A NeoForge mod that integrates **Tensura: Reincarnated** with
**MineColonies**. Tensura monsters (goblins, orcs, dwarves, lizardmen) can
join your colony as citizens, and the Tensura world's factions track their
standing with you and can be allied with or attacked.

## Core Concept: Two Bodies, One Identity

A named Tensura monster can exist in either of two roles, and you switch it
between them on demand (for a magicule cost):

- **At your side** — a normal Tensura creature: it fights, evolves, has
  Existence Points (EP) and skills, and takes your commands.
- **In your colony** — a MineColonies citizen: it holds a job, raises work
  skills, and lives in a house.

Switching roles preserves the same identity — name, progress, and creature
type carry across.

## Systems

<div class="grid cards" markdown>

-   :material-account-group: **Races & Citizens**

    Name a goblin, orc, dwarf, or lizardman to add it to your colony as a
    citizen. Each race has its own appearance and starting work-skill bias.

    [:octicons-arrow-right-24: Races & Citizens](features/races-citizens.md)

-   :material-thumbs-up-down: **Colony Reputation**

    A per-colony score that rises and falls with how you run the colony. Low
    reputation triggers monster raids.

    [:octicons-arrow-right-24: Colony Reputation](features/colony-reputation.md)

-   :material-flag: **World Reputation & Factions**

    Each Tensura faction tracks a standing value toward you that changes with
    your actions — chiefly killing its marked bosses.

    [:octicons-arrow-right-24: World Reputation & Factions](features/world-reputation.md)

-   :material-sword-cross: **Raids & Barriers**

    Low colony reputation sends monster raids at night. The barrier is a
    buildable, magicule-fueled block that blocks and damages raiders.

    [:octicons-arrow-right-24: Raids & Barriers](features/raids-barriers.md)

-   :material-handshake: **Diplomacy**

    Open relations with a faction and complete deals to climb Diplomacy →
    Alliance → Covenant, unlocking rewards at each tier.

    [:octicons-arrow-right-24: Diplomacy](features/diplomacy.md)

-   :material-castle: **Warfare**

    Discover a faction settlement, declare war, and bring a war party to
    defeat its garrison and boss. Winning grants citizens, a skill, and loot.

    [:octicons-arrow-right-24: Warfare](features/warfare.md)

</div>

A few more systems: [Colony Protection](features/colony-protection.md) that
keeps Tensura's block-breaking from wrecking your builds,
[Harvest Festival](features/harvest-festival.md) bonuses that give your colony a
one-time work-skill boost, and [Assassins](features/assassins.md) that a mistreated
colony can breed from its own citizens.

## Two Ways To Deal With Factions

| | Diplomacy | Warfare |
|---|---|---|
| **Method** | Open relations, complete deals | Discover a settlement, declare war |
| **Progression** | Diplomacy → Alliance → Covenant | Win the assault (kill the boss + ≥60% of the garrison) |
| **Rewards** | Buffs, trade caravans, gifts, a faction skill | Citizens, the faction's skill, loot |
| **Cost / risk** | Slow — relations decay or shatter | A combat fight — warring an allied faction scales its garrison up |

Both are optional. The entire faction layer can be disabled in the
[config](reference/config.md).

---

!!! tip "New Here?"
    See [Getting Started](getting-started.md), or open any system above.
