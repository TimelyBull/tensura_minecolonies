# Rival Colonies

Rival colonies are settlements belonging to the Tensura
[factions](world-reputation.md). You can discover one, declare war on it,
march in with a war party, defeat its garrison and boss, and conquer it for
rewards. It's the combat counterpart to [diplomacy](diplomacy.md), and runs
behind the same [config](../reference/config.md) switch (`enableFactionSystem`),
which is **off by default** — set it to `true` to enable rival colonies. While
it's off, no settlements generate.

## Settlements

Physical factions generate settlements in the world. There are two kinds,
which play the same way:

- **Faction towns** — generated clusters of buildings for the five town
  factions (Luminous, Falmuth, Leon, the Eastern Empire, the Jura-Tempest Federation), each themed to its faction.
- **Dwargon villages** — Dwargon uses existing Tensura dwarven villages
  rather than a generated town; some of them become Dwargon settlements,
  anchored by Gazel.

Each settlement has an **anchor boss** (the faction's champion) and a
**garrison** of faction-themed defenders. How many settlements exist is set
by the [config](../reference/config.md) (`rivalSettlementMode`: all / some /
none).

## Discovery

You discover a settlement by coming within ~80 blocks of its centre. Once
discovered, you get a notice and can declare war on it from the roster.

## Declaring war

Open the roster (`G`) and the **Wars** tab. It lists the settlements you've
discovered, each with a **Declare War** button. Pressing it opens a picker:
choose up to **15** of your at-your-side monsters as a war party, then
confirm. You and the chosen party are teleported into the settlement, the
garrison turns hostile, and the assault begins. Your pre-war location is
remembered for the trip home.

## The garrison

The garrison is the settlement's defenders plus its anchor boss. Its size
and strength scale with the boss's power — a stronger faction champion means
more, tougher defenders. Defenders are tethered to the settlement (they
won't chase you far from it).

If you leave a settlement without conquering it, its garrison is restored
and its boss healed for next time — each assault starts fresh.

## Winning the assault

You **conquer** a settlement when you have:

- **killed its boss**, and
- **killed at least 60% of its garrison.**

The **Wars** tab swaps Declare War for a **Retreat** button during an
assault. Retreating teleports you (and your surviving party) home and resets
the garrison. Dying or logging out mid-assault counts as a retreat — you're
returned home on respawn/login and the garrison resets.

## Conquest rewards

When you win, the settlement is sacked and you receive:

- **Citizens** — 10–20 faction-themed colonists added to your existing
  colony, arriving trained in skills that fit the faction (Dwargon sends
  Strength-heavy miners, the Jura-Tempest Federation sends Knowledge-heavy sages,
  and so on). If your colony is at its housing cap, as many as fit are added and
  the rest are noted.
- **The faction's skill** — you're granted that faction's signature Tensura
  skill, the same one its hardest [diplomacy](diplomacy.md) deal would give.
- **Loot** — chests at the settlement, filled from that faction's own
  reward pool.

A conquered settlement becomes a permanent **ruin**: the buildings remain,
the boss is gone, the garrison is cleared and won't return, and it can't be
warred again. Conquest doesn't found a second colony — the rewards go to the
colony you already have.

## Betrayal: warring an ally

If you declare war on a faction you have [diplomatic relations](diplomacy.md)
with, two things happen:

1. **The relationship shatters** — your standing crashes and relations
   collapse to nothing (lent citizens come home, deals cancel).
2. **The garrison is scaled up** as a betrayal penalty, by the depth of the
   bond you broke:

   | Relationship betrayed | Garrison strength | Defender skills |
   |---|---|---|
   | Diplomacy | ×1.25 | — |
   | Alliance | ×1.6 | + damage resistance |
   | Covenant | ×2.0 | + resistance, self-healing, the faction's own skill |

So betraying a deep ally means a markedly harder fight than attacking a
faction you were never friendly with. (A retaliatory siege from a betrayed
faction is [planned but not yet in the game](../roadmap.md).)
