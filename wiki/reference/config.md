# Config

The mod's options live in its common config file. The settings that affect
gameplay:

| Option | Default | What it does |
|---|---|---|
| `enableFactionSystem` | `false` | Master switch for the whole faction system — [world reputation](../features/world-reputation.md), faction standings, [diplomacy](../features/diplomacy.md), and [rival colonies](../features/rival-colonies.md). **Defaults to off**, so a fresh install is the plain "Tensura mobs as colony citizens" experience. Off: no faction standing, no diplomacy envoys, deals or trades, no settlements generating, no wars, and the **Diplomacy and Wars buttons are hidden** from the roster screen. Colony-level systems stay on regardless — your citizens, the race emissaries that add new races to a colony, [colony reputation](../features/colony-reputation.md), and raids — and boss kills behave as if the faction layer didn't exist. Set it to `true` to turn the faction system on. |
| `enableAssassins` | `true` | Enables the [assassin](../features/assassins.md) system. Off: no new assassins form, existing plots that haven't struck defuse, and no strikes occur (an already-active assassin boss stays until killed). |
| `rivalSettlementMode` | `SOME` | How many faction [settlements](../features/rival-colonies.md) generate. **ALL** — every physical faction boss gets a settlement. **SOME** — a per-boss chance (see below). **NONE** — no settlements (faction bosses still appear as free-roaming wild bosses). |
| `rivalSettlementSomeChance` | `0.5` | Under `SOME` mode, the chance (0–1) that a physical faction boss generates as a settlement rather than a wild boss. |
| `rivalNaturalGeneration` | `true` | Whether rival settlements generate naturally over time. Off: settlements only appear via debug commands. |
| `dragoNovaHarmAllies` | `false` | Whether the [Drago Nova](../features/diplomacy.md) blast also harms your allies, citizens, and named subordinates. |
| `dragoNovaBreakBlocks` | `false` | Whether the Drago Nova blast damages terrain (a TNT-style crater). |
