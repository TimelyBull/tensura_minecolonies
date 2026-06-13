# Assassins

A colony that is both low-[reputation](colony-reputation.md) and unhappy can
breed an assassin from among your own named citizens. The assassin waits for
a moment of weakness, attacks you, and — if it kills you — steals your power.
The system can be turned off in the [config](../reference/config.md)
(`enableAssassins`).

## How an assassin forms

While a colony's reputation is below Wary **and** its average happiness is
low, one of its named citizens slowly builds resolve over several days. Past
a threshold it becomes a hidden assassin, ready to strike. If the colony's
happiness recovers before then, that resolve decays and the plot defuses.

Only **one assassin per colony, ever** — once a colony has produced one, it
won't breed another.

## Detecting it

If you have the **Great Sage** skill, a hidden assassin shows a red
**"Assassin"** label above the citizen it has chosen. Without Great Sage,
there's no visible tell.

## Defusing it

Raise the colony's happiness back up. While the plot is still forming,
recovering happiness above the threshold clears the assassin's resolve and
ends the plot — fix the colony and the danger passes.

## The strike

Once armed, the assassin strikes the next time you're vulnerable — when
you're at low health, sleeping, wearing no armour, at the start of a
festival, or just after a prestige reset. The chosen citizen drops its
disguise, manifests as its Tensura body, and attacks you as a boss (with a
boss health bar and boosted stats). While the plot is active, that colony
also gives you the cold shoulder — its citizens won't trade or assist
through the mod's menus.

## Power theft and reclaim

If the assassin **kills you**, it steals from you:

- **Half your EP** — taken from your maximum magicule and aura, and given to
  the assassin (so it fights using your stolen power). Your skills are
  **copied** to it (you keep your own); resistances and passives it copies
  work automatically, and it actively casts a selection of offensive skills.
- **Reclaim:** kill the assassin boss and your stolen EP returns in full. If
  you were offline when it died, the reclaim applies on your next login.

Both the assassin and the citizen it came from are gone once the assassin is
killed.
