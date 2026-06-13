# Commands

Commands you can use to inspect your standing, plus operator/debug commands
for testing. **Operator commands** are marked — they need permission level 2
(a server operator or single-player cheats enabled).

## Player readouts

| Command | What it shows |
|---|---|
| `/reputation` | Your colony's [reputation](../features/colony-reputation.md) value and tier. |
| `/worldrep` | Your standing with every [faction](../features/world-reputation.md), tier-coloured, plus your overall notoriety. |
| `/diplomacy` | Your [relations](../features/diplomacy.md) and active deals for each faction. |

## Operator / debug commands

These force or inspect systems for testing.

| Command | Op | What it does |
|---|---|---|
| `/reputation set <0–100>` | ✅ | Set your colony's reputation. |
| `/worldrep set <faction> <0–100>` | ✅ | Set your standing with a faction. |
| `/worldrep mark <faction>` | ✅ | Mark a nearby boss for a faction (so killing it has faction consequences). |
| `/diplomacy open <faction>` | ✅ | Force-open relations with a faction. |
| `/diplomacy offers` | ✅ | Force-refresh a faction's deal offers. |
| `/diplomacy reply <faction>` | ✅ | Force the pending envoy reply. |
| `/festival run` | ✅ | Run the [Harvest Festival](../features/harvest-festival.md) on your colony. |
| `/tensuraraid` | ✅ | Start a [raid](../features/raids-barriers.md) on your colony now. |
| `/tensuraraid disaster` | ✅ | Start the Orc Disaster lore event. |
| `/tensuraraid end` | ✅ | End the active raid. |
| `/assassin [state\|arm\|strike\|defuse]` | ✅ | Inspect or drive the [assassin](../features/assassins.md) plot for your colony. |
| `/rivalcolony spawn <faction>` | ✅ | Generate a [settlement](../features/rival-colonies.md) (for Dwargon, stand in a dwarven village). |
| `/rivalcolony wild <faction>` | ✅ | Spawn a wild (unmarked) faction boss. |
| `/rivalcolony list` | ✅ | List generated settlements. |
| `/rivalcolony garrison <id>` | ✅ | Show a settlement's garrison, assault, and discovery state. |
| `/rivalcolony declare <id>` | ✅ | Declare war on a settlement (takes your loaded subordinates as the party). |
| `/rivalcolony assault <id>` | ✅ | Begin an assault snapshot on a settlement. |
| `/rivalcolony win <id>` | ✅ | Force-win the current assault. |
| `/rivalcolony reset <id>` | ✅ | Reset a settlement's garrison. |
| `/rivalcolony retreat [id]` | ✅ | Force a retreat from an assault. |

There are also envoy-system utility commands (`/spawnenvoy`, `/envoystate`,
`/envoyforce`, `/envoyresetcooldown`) and race utilities (`/summongoblin`,
`/raceflip`, `/setcolonyrace`) used mainly for testing.
