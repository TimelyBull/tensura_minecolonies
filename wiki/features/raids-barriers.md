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
Disaster**: if you provoke **Clayman** (by killing his marked bosses) and
your standing with him is low, he can march a horde led by **Geld, the Orc
Disaster** on a colony. Killing the lead boss breaks the horde. (More lore
events are planned — see the [Roadmap](../roadmap.md).)

## The barrier

The barrier is a block you craft and place that projects a protective field
around itself while it has magicule fuel **and** a raid is active. The field
is a square footprint centred on the block.

### Tiers (cumulative)

There are four **Barrier Core** tiers. Each higher tier has a larger radius,
more fuel capacity, and adds an effect on top of the lower tiers'. The wall
is colour-coded by tier:

| Tier | Radius | Effect (cumulative) | Wall colour |
|---|---|---|---|
| 1 | 16 | **Wall** — blocks hostiles from entering | Blue |
| 2 | 28 | + **Heal** — Regeneration to non-hostiles inside | Green |
| 3 | 42 | + **Eject** — teleports hostiles back out | Magenta |
| 4 | 60 | (all of the above) | Gold |

While fueled, a barrier also **prevents hostile mobs from spawning** inside
its footprint.

### Fuel

The barrier runs on magicule:

- **Right-click** it with empty hand to channel your own magicule in (or
  open its menu for finer control).
- **Right-click** with magic crystals to add fixed amounts (low / medium /
  high quality crystals add increasing amounts).

Hostiles pressing the wall drain its fuel — harder-hitting, higher-EP mobs
drain it faster. At zero fuel the field drops (with a warning); refuel to
raise it again. The wall's transparency shows its fuel level at a glance.

### Magicule Storage

**Magicule Storage** blocks (four tiers) extend a barrier's fuel capacity.
Place them adjacent to a Barrier Core (or chained to one through other
storage blocks); each adds capacity, and the network updates automatically
as you build or break it.

### Concentric layers (Demon Lords / Heroes)

A Barrier Core can project up to **three concentric square shells**,
expanding outward. The first layer is available to anyone; raising it to two
or three requires you to be a true Demon Lord or true Hero. Extra layers
cost ongoing magicule upkeep from the core's fuel pool, on top of raider
drain. When the pool can't pay, the outermost layer drops first, then the
next, down to the always-available first layer.

The Barrier Core's menu (right-click) shows fuel, lets you move magicule
between yourself and the core, and sets the layer count.
