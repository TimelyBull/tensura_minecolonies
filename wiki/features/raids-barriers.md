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

A raid spawns a wave of Tensura monsters that head for your colony and
attack citizens. Your guards engage them. **Win** by killing the whole wave
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
**sphere** around itself while it holds magicule fuel. The sphere is centred on
the block and sinks partway into the ground.

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

| Tier | Radius | Panel health | Effect (cumulative) | Colour |
|---|---|---|---|---|
| 1 | 16 | 10,000 | **Wall** — blocks hostiles | Blue |
| 2 | 28 | 20,000 | + **Heal** — Regeneration to non-hostiles inside | Green |
| 3 | 42 | 40,000 | + **Magicule regen** — your own magicule regenerates 10% faster while inside | Magenta |
| 4 | 60 | 60,000 | (all of the above) | Gold |

While fueled, a barrier also **prevents hostile mobs from spawning** inside its
footprint, and stops enemy arrows, spells, and breath attacks at its panels
(these still pass through an open hole).

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

A Barrier Core can project up to **three concentric spheres**, expanding
outward. The first layer is available to anyone; raising it to two or three
requires you to be a true Demon Lord or true Hero. Each extra layer costs
ongoing magicule upkeep from the fuel pool. When the pool can't pay, the
outermost layer drops first, then the next, down to the always-available first
layer.

The Barrier Core's menu (right-click) shows fuel, lets you move magicule
between yourself and the core, sets the layer count, and toggles whether the
walls are visible.
