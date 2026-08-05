# Config

The mod's options are split across its **common** config (applies at game
launch) and its per-world **server** config (some need a world reload to take
effect, as noted below). The settings that affect gameplay:

| Option | Default | What it does |
|---|---|---|
| `enableFactionSystem` | `false` | Master switch for the whole faction system — [world reputation](../features/world-reputation.md), faction standings, [diplomacy](../features/diplomacy.md), and [warfare](../features/warfare.md). Off (the default): no faction standing, diplomacy, settlements, or wars, and the **Diplomacy and Wars buttons are hidden** from the roster. Core colony systems (citizens, race emissaries, [colony reputation](../features/colony-reputation.md), raids) stay on regardless. Per-world setting — reload the world after changing it. |
| `enableAssassins` | `true` | Enables the [assassin](../features/assassins.md) system. Off: no new assassins form, existing plots that haven't struck defuse, and no strikes occur (an already-active assassin boss stays until killed). |
| `enableRaids` | `true` | Whether a colony with low [reputation](../features/colony-reputation.md) gets [raided](../features/raids-barriers.md) by monsters at night. Off: those raids never trigger (a raid already underway still finishes, and the debug command still works). Does **not** affect faction story raids like the Orc Disaster. Per-world setting — reload the world after changing it. |
| `enableDefenseSwap` | `true` | Whether strong Tensura-race citizens transform into their monster body to fight during a raid (see [Raids & Barriers](../features/raids-barriers.md)). Off: no citizen ever transforms — guards still guard, everyone else flees the MineColonies way. Per-world setting — reload the world after changing it. |
| `protectColoniesFromMobGriefing` | `true` | Stops Tensura **giant monsters** (Orc Lord, Charybdis, and the like) from smashing blocks inside a colony just by walking into them. Off: giant mobs break colony blocks freely (as vanilla `mobGriefing` allows). Applies immediately, no reload. |
| `protectColoniesFromSkillGriefing` | `true` | Stops Tensura **terrain-shaping skills** (Earth Manipulation and similar) from carving up blocks inside a colony. Off: those skills reshape colony terrain freely. Applies immediately, no reload. |
| `citizenAggression` | `OFF` | How aggressively naturally-hostile Tensura mobs hunt your colony's citizens — extra targeting this mod adds on top of vanilla Tensura (which by itself ignores citizens). **`OFF`** (default): mobs never single out citizens as prey. **`MEDIUM`**: roughly half of encounters treat a citizen as prey. **`HIGH`**: citizens are prey on sight. |
| `rivalNaturalGeneration` | `true` | Whether faction [settlements](../features/warfare.md) appear as you explore — both the faction towns and the dwarf villages that become Dwargon settlements. Off: no settlement forms on its own (debug commands still work). |
| `dragoNovaHarmAllies` | `false` | Whether the [Drago Nova](../features/diplomacy.md) blast also harms your allies, citizens, and named subordinates. |
| `dragoNovaBreakBlocks` | `false` | Whether the Drago Nova blast damages terrain (a TNT-style crater). |
