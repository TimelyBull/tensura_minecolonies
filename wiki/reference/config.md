# Config

The mod's options live in its common config file. The settings that affect
gameplay:

| Option | Default | What it does |
|---|---|---|
| `factionSystemEnabled` | `true` | Master switch for the whole faction layer — [world reputation](../features/world-reputation.md), faction standings, [diplomacy](../features/diplomacy.md), and [rival colonies](../features/rival-colonies.md). Off: no faction standing, no envoys, no deals, no settlements, no wars. Colony-level systems (citizens, [colony reputation](../features/colony-reputation.md), raids) are unaffected, and boss kills behave as if the faction layer didn't exist. |
| `enableAssassins` | `true` | Enables the [assassin](../features/assassins.md) system. Off: no new assassins form, existing plots that haven't struck defuse, and no strikes occur (an already-active assassin boss stays until killed). |
| `rivalSettlementMode` | `SOME` | How many faction [settlements](../features/rival-colonies.md) generate. **ALL** — every physical faction boss gets a settlement. **SOME** — a per-boss chance (see below). **NONE** — no settlements (faction bosses still appear as free-roaming wild bosses). |
| `rivalSettlementSomeChance` | `0.5` | Under `SOME` mode, the chance (0–1) that a physical faction boss generates as a settlement rather than a wild boss. |
| `rivalNaturalGeneration` | `true` | Whether rival settlements generate naturally over time. Off: settlements only appear via debug commands. |
| `dragoNovaHarmAllies` | `false` | Whether the [Drago Nova](../features/diplomacy.md) blast also harms your allies, citizens, and named subordinates. |
| `dragoNovaBreakBlocks` | `false` | Whether the Drago Nova blast damages terrain (a TNT-style crater). |
