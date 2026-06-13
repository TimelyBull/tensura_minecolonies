# World Reputation & Factions

Each Tensura faction tracks a separate standing toward you, from 0 to 100.
Standing drives [diplomacy](diplomacy.md), [rival-colony](rival-colonies.md)
behaviour, and faction events. It's per-player and separate from
[colony reputation](colony-reputation.md). View it with `/worldrep`.

The whole faction layer can be turned off in the
[config](../reference/config.md) (`factionSystemEnabled`).

## Standing tiers

| Standing | Tier |
|---|---|
| 0–19 | Hostile |
| 20–39 | Wary |
| 40–59 | Neutral |
| 60–79 | Friendly |
| 80–100 | Allied |

## Disposition: how a faction starts

A faction's standing is its **base disposition** (how it feels about your
kind) plus the standing you've **earned** through your actions. The base is
computed live from whether you're currently on the **human side** or the
**majin side**:

- Most factions start Neutral toward both.
- The **Holy bloc** (Luminous and Falmuth) starts Wary toward humans and
  **Hostile** toward majin — they hate monsters.
- The monster nations (Tempest, Jura) are slightly warmer to majin.

Because the base is live, **changing your race changes the world's posture
immediately** — walk the demon-lord path and the Holy bloc turns colder
without any other action. See the [Factions reference](../reference/factions.md)
for the full disposition table.

## What moves standing: marked bosses

Standing moves chiefly by **killing a faction's bosses** — but only
**marked** bosses count.

- A **marked** boss is one a faction deliberately sent (in a faction event,
  raid, or settlement). It carries a coloured name showing which faction it
  belongs to, so you can see before you swing that the kill will have
  consequences.
- A **wild** or player-summoned boss of the same type is **not** marked.
  Killing it has no faction consequences (it still gives the normal colony
  and progression rewards). Killing ordinary mobs never affects standing.

When you kill a marked boss, the effect fans out by the boss's importance:

- the boss's **faction drops** (a keystone boss is a large drop, a minor one
  small),
- that faction's **allies drop** a little,
- that faction's **enemies rise** a little.

So killing a Holy-bloc champion angers Luminous and Falmuth but pleases
Clayman, and vice versa. Attacking (not killing) a marked boss applies a
small drop to its own faction only.

## Notoriety

Beyond per-faction standing, you have an overall **notoriety** — a single
0–100 measure of how threatening the world finds you, blended from how
hostile factions are toward you, your power (EP), demon-lord status, and how
poorly you treat your colonies. It's shown in `/worldrep`. (It's a readout
for now; it feeds escalating-threat features planned for later.)

## Physical vs. abstract factions

Factions come in two kinds:

- **Physical** factions have a real presence you can find and attack — they
  generate [rival settlements](rival-colonies.md) (Luminous, Falmuth, Shizu,
  Leon, Otherworlders, the Jura Alliance, and Dwargon).
- **Abstract** factions (Tempest, Carrion, Milim, Clayman) have no settlement
  to assault; you interact with them only through standing, events, and
  [diplomacy](diplomacy.md).

See the [Factions reference](../reference/factions.md) for the full list.
