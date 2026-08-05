# Colony Protection

Tensura has two ways of destroying terrain that normally ignore MineColonies'
build protection. This feature stops both from damaging blocks inside a colony,
so a raid or a wandering giant can't demolish your buildings. It's on by
default and works whether or not the faction system is enabled.

## What It Protects Against

- **Giant monsters** — creatures like the Orc Lord, Charybdis, and Megalodon
  smash blocks just by walking into them. Inside a colony's claimed area, they
  no longer can.
- **Terrain-shaping skills** — abilities such as Earth Manipulation that carve
  up the ground are blocked from reshaping blocks inside a colony.

This matters because raids, faction garrisons, and lore bosses are made of
exactly these mobs — without the guard, an attack could wreck your walls and
huts mid-fight.

## What It Does *Not* Change

- It only covers blocks **inside a colony's claimed area**. Outside your
  colony, Tensura's world-editing works exactly as it always has.
- It doesn't stop monsters from **attacking your citizens** — only from
  breaking your blocks. Defending against the mobs themselves is what
  [guards, the defense form-swap, and barriers](raids-barriers.md) are for.

## Turning It Off

Two [config](../reference/config.md) options control the two paths
independently, both on by default and applied immediately (no reload):

- `protectColoniesFromMobGriefing` — the giant-monster guard.
- `protectColoniesFromSkillGriefing` — the terrain-skill guard.

Turn either off to restore the old free-for-all for that source.
