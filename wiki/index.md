# Tensura × MineColonies

A NeoForge mod that integrates **Tensura: Reincarnated** with
**MineColonies**. Tensura monsters (goblins, orcs, dwarves, lizardmen) can
join your colony as citizens, and the Tensura world's factions track their
standing with you and can be allied with or attacked.

!!! note "What this guide covers"
    These pages explain what each feature does and how to use it — not how
    it's coded. New players should start with
    [Getting Started](getting-started.md).

## Core concept: two bodies, one identity

A named Tensura monster can exist in either of two roles, and you switch it
between them on demand (for a magicule cost):

- **At your side** — a normal Tensura creature: it fights, evolves, has
  Existence Points (EP) and skills, and takes your commands.
- **In your colony** — a MineColonies citizen: it holds a job, raises work
  skills, and lives in a house.

Switching roles preserves the same identity — name, progress, and creature
type carry across. Every other system is built on this swap.

## Systems

<div class="grid cards" markdown>

-   :material-account-group: **Races & Citizens**

    Name a goblin, orc, dwarf, or lizardman to add it to your colony as a
    citizen. Each race has its own appearance and starting work-skill bias.

    [:octicons-arrow-right-24: Races & Citizens](features/races-citizens.md)

-   :material-thumbs-up-down: **Colony Reputation**

    A per-colony score that rises and falls with how you run the colony; low
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

-   :material-castle: **Rival Colonies**

    Discover a faction settlement, declare war, and bring a war party to
    defeat its garrison and boss; winning grants citizens, a skill, and loot.

    [:octicons-arrow-right-24: Rival Colonies](features/rival-colonies.md)

</div>

Two more systems: a [Harvest Festival](features/harvest-festival.md) that
grants periodic bonuses to a healthy colony, and
[Assassins](features/assassins.md) that factions send against colonies that
have wronged them.

## Two ways to deal with factions

| | Diplomacy | Rival Colonies |
|---|---|---|
| **Method** | Open relations, complete deals | Discover a settlement, declare war |
| **Progression** | Diplomacy → Alliance → Covenant | Win the assault (kill the boss + ≥60% of the garrison) |
| **Rewards** | Buffs, trade caravans, gifts, a faction skill | Citizens, the faction's skill, loot |
| **Cost / risk** | Slow; relations decay or shatter | A combat fight; warring an allied faction scales its garrison up |

Both are optional. The entire faction layer can be disabled in the
[config](reference/config.md).

---

!!! tip "New here?"
    See [Getting Started](getting-started.md), or open any system above.
