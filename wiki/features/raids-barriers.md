# Raids & Barriers

A colony with low [reputation](colony-reputation.md) draws monster raids at
night. The barrier is a buildable block that defends against them.

## Raids

At nightfall, a colony whose reputation is **below Neutral** can be raided.
The chance scales with how low it sits:

| Reputation tier | Raid chance per night |
|---|---|
| Wary | 15% |
| Passive-Aggressive | 30% |
| Hostile | 50% |

There's a cooldown of a few in-game days between raids on a colony, and only
one raid runs at a time. Raise reputation above Neutral and raids stop.

A raid spawns a wave of Tensura monsters at the edge of your colony's
territory; they march in toward your colony and attack citizens. Your
guards engage them. **Win** by killing the whole wave
before the night ends (this raises reputation); if the night ends first, the
survivors withdraw.

### Difficulty

Raid difficulty is set by your colony's **strength** (mostly the total EP of
your citizens, plus its size and development), not by reputation —
reputation only decides *whether* a raid fires. Stronger colonies face
tougher waves, drawn to roughly match and slightly exceed the colony's
strength, in three levels:

- **Level 1** — giant ants, black spiders.
- **Level 2** — hound dogs, evil centipedes, direwolves.
- **Level 3** — knight spiders, blade tigers, evil centipedes.

### Faction events

Beyond ordinary raids, an angered [faction](world-reputation.md) can send a
scripted **lore event**. The one currently in the game is the **Orc
Disaster**: if you provoke the **Moderate Harlequin Alliance** (by killing its
marked bosses) and your standing with it is low, Clayman can march a horde led
by **Geld, the Orc Disaster** on a colony. Killing the lead boss breaks the
horde. (More lore
events are planned — see the [Roadmap](../roadmap.md).)

## The barrier

The barrier is a Barrier Core block you place that projects a protective
**sphere** while it holds magicule fuel. The sphere sinks partway into the
ground. Where it is centred depends on where you place the core:

- **Inside your colony's claimed area:** the sphere is centred on your **town
  hall**, wherever in the claim the core block sits.
- **Outside any colony:** the sphere is centred on the core block itself.

Placing **more than one core inside the same colony** does not create separate
barriers — all the cores merge into **one** barrier around the town hall. The
strongest core (highest tier) sets the panel strength and the top of the size
range, every core's fuel tank pools together, and **each extra core lets you
make the barrier bigger** (see below). Right-clicking any core in the colony
opens the same menu, showing the same fuel, size and layers — there is one
barrier per colony, not one per block, and it is shared by everyone who can use
it. Note that the core block only projects the field; if it sits far from the
town hall it can end up outside its own sphere, unprotected.

### Size

You choose how big the barrier is, between a minimum of **8 blocks** and a
maximum your colony has to earn. The maximum is the strongest core's tier radius,
plus a bonus for every OTHER core in the colony based on that core's tier:

| Extra core | Adds |
|---|---|
| Tier 1 | +2 blocks |
| Tier 2 | +4 blocks |
| Tier 3 | +6 blocks |
| Tier 4 | +8 blocks |

So one tier-4 core alone tops out at 60; add four more tier-4 cores and it
reaches 92. There is a hard ceiling of **128** whatever you build.

Set it in the core menu with the **FIELD SIZE** row: `-` and `+` move it 4
blocks at a time, `MIN` and `MAX` jump to the ends of the range, and the bar
shows where you are within it. Making it smaller keeps the sphere tight around
what actually needs protecting **and costs less fuel to run**; making it bigger
covers more ground but gives attackers more wall to break and burns through your
magicule faster.

If you lose a core the range narrows and the barrier shrinks to fit — but your
chosen size is remembered, so rebuilding the core puts it back where you had
it.

### Panels and holes

The sphere is divided into **24 panels**, each with its own health. Attacks
land on the specific panel being hit: that panel fades through three stages and
then **shatters into a hole** — only that panel, while the rest of the sphere
stays up. Enemies, their arrows, and their spells pass through an open hole;
intact panels block them and shove enemies straight back. A panel's health
depends on the core tier (below).

A broken panel **repairs itself**: 15 seconds after it was last hit it grows
back one stage at a time. Each repair step draws magicule from the core's fuel
pool (it costs exactly the health it restores), so a barrier with no fuel can't
mend its holes until you refuel.

### Tiers (cumulative)

Four **Barrier Core** tiers. Higher tiers have a larger radius, more fuel
capacity, tougher panels, and add an effect on top of the lower tiers'. Each
tier's sphere is colour-coded:

| Tier | Max radius | Panel health | Effect (cumulative) | Colour |
|---|---|---|---|---|
| 1 | 16 | 10,000 | **Wall** — blocks hostiles | Blue |
| 2 | 28 | 20,000 | + **Heal** — Regeneration to non-hostiles inside | Green |
| 3 | 42 | 40,000 | (larger and tougher) | Magenta |
| 4 | 60 | 60,000 | (larger and tougher) | Gold |

A tier's radius is the size the barrier starts at and the top of its range
before extra cores are counted; you can always dial it down.

While fueled, a barrier also **prevents hostile mobs from spawning** inside its
footprint — both vanilla monsters and Tensura hostiles, whether from night-time
wild spawns, mob spawners, trial spawners, pillager patrols, or reinforcements.
Mobs you place on purpose (spawn eggs, commands, dispensers) and your own tamed
or summoned creatures are not affected. It also stops enemy arrows, spells, and
breath attacks at its panels (these still pass through an open hole). Raid waves
never appear inside a fueled barrier either — raiders always show up outside it
and have to break through.

### Fuel

The barrier runs on magicule:

- **Right-click** with an empty hand to open its menu (move your own magicule
  in or out, set the layer count, show or hide the walls).
- **Right-click** with Tensura magic crystals to add fixed amounts (low /
  medium / high quality add increasing amounts).

The fuel pool is spent on keeping extra layers raised and on repairing broken
panels — **attacks themselves no longer drain it**. At zero fuel the whole
barrier drops (with a warning); refuel and it raises again with every panel
restored to full. Each panel's transparency shows its remaining health.

### Magicule Storage

**Magicule Storage** blocks (four tiers) extend a barrier's fuel capacity.
Place them adjacent to a Barrier Core (or chained to one through other
storage blocks); each adds capacity, and the network updates automatically
as you build or break it.

### Concentric layers (Demon Lords / Heroes)

### Upkeep

A barrier is never free. While it is up it burns magicule every second, and the
bill depends on how big it is and how many layers it has: **10 per second for
each layer, plus 1 per second for every block of that layer's radius.** Outer
layers are bigger, so each one you add costs a little more than the last.

Some examples of what that means for a full tank:

| Setup | Cost | A full tank lasts |
|---|---|---|
| Tier 1, default size 16 | 26/s | about an hour |
| Tier 4, default size 60 | 70/s | about an hour |
| Tier 4, shrunk to 8 | 18/s | about four hours |
| Tier 4, at the 128 ceiling | 138/s | about half an hour |
| Tier 4 at 60, three layers | 225/s | about twenty minutes |

Repairing broken panels costs fuel on top of that. If the pool runs dry the
barrier falls, so a big barrier or a stack of layers is a standing commitment —
keep it fed, or keep it small.

A Barrier Core can project up to **three concentric spheres**, expanding
outward. The first layer is available to anyone; raising it to two or three
requires you to be a true Demon Lord or true Hero. Each extra layer costs
ongoing magicule upkeep from the fuel pool.

Raising the **third layer** also grants a buff inside the barrier, depending
on which title the raiser holds:

- **True Demon Lord:** players inside regenerate their own magicule **10%
  faster**.
- **True Hero:** citizens inside receive **Regeneration II** and **Absorption**
  (extra hearts), on any core tier.

If the raiser loses their title, the extra layers collapse back to one and the
buff ends.

The Barrier Core's menu (right-click) shows fuel, lets you move magicule
between yourself and the core, sets the field size and the layer count, and
toggles whether the walls are visible. Any core in the colony opens the same
menu.
