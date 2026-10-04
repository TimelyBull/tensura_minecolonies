# Assassins

A colony that is both low-[reputation](colony-reputation.md) and unhappy can
breed an assassin from among your own named citizens. When you are vulnerable it
attacks you. If it kills you, it steals half your EP and copies your skills. The
system can be turned off in the [config](../reference/config.md)
(`enableAssassins`).

## How An Assassin Forms

While a colony's reputation is below Wary **and** its average happiness is
under 4, one of its named citizens builds resolve at +1 per day. At resolve 2
it becomes a hidden assassin (lurking). At resolve 4 it is armed and ready to
strike. If the colony's happiness recovers before then, that resolve decays
again (−1 per day) and the plot defuses.

Only **one assassin per colony, ever** — once a colony has produced one, it
won't breed another.

## Detecting It

If you have the **Great Sage** skill, a hidden assassin shows a red
**"Assassin"** label above the citizen it has chosen. Without Great Sage,
there's no visible tell.

## Defusing It

Raise the colony's happiness back up. While the plot is still forming,
recovering happiness above the threshold clears its resolve and ends the plot.

## The Strike

Once armed, the assassin strikes the next time you're vulnerable — when
you're at low health (35% or less), sleeping, wearing no armour, at the start
of the Harvest Festival, or just after a prestige reset. The citizen manifests as its
Tensura body and attacks you as a boss: a boss health bar plus roughly **3× its
normal health**, **2.5× damage**, and a bit more speed. While the plot is
active, that colony's citizens won't trade or assist through the mod's menus.

## Power Theft And Reclaim

If the assassin **kills you**, it steals from you:

- **Half your EP** — taken from your maximum magicule and aura, and given to
  the assassin (so it fights using your stolen power). The loss is a fixed
  amount, half of what you had when it killed you: it stays through later
  deaths and relogs, and EP you earn afterwards is not reduced. Your skills are
  **copied** to it (you keep your own). Resistances and passives it copies
  work automatically, and it actively casts a selection of offensive skills.
- **Reclaim:** kill the assassin boss and your stolen EP returns in full. If
  you were offline when it died, the reclaim applies on your next login.

Only the colony's owner is stolen from; another player it kills loses nothing.
Using a full character reset scroll cancels the theft, and nothing is returned
afterwards.

Once it has turned, the assassin no longer answers to you: it can't be sent
home from the roster, and if it was defending the colony in a raid it stops
fighting raiders.

Both the assassin and the citizen it came from are gone once the assassin is
killed.
