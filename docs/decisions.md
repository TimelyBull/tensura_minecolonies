# Key Decisions

## Architecture

**Standalone integration mod, not KubeJS scripts**
User preference. A compiled mod gives full access to both mods' Java APIs and
is not constrained by what KubeJS exposes.

**Target: NeoForge 1.21.1, JDK 21**
Matches the BigBigSlime server environment.

## Dependency management

**Manual jars in `libs/` — not Maven/CurseForge Maven**
MineColonies has a "no 3rd party sharing" policy on CurseForge, which breaks
CurseMaven. Jars are sourced manually and placed in `libs/`.

**`libs/` is NOT committed to git**
Licensing: we do not redistribute other mods' binaries. Exact versions are
tracked in `docs/dependencies.md` instead so the build can be reproduced.

**ManasCore sub-modules extracted from the parent jar**
ManasCore uses NeoForge's JARs-in-a-JAR (JiJ) system — 10 sub-module jars are
embedded inside `manascore-neoforge-4.0.0.2.jar`. NeoForge extracts and loads
them at runtime, but `javac` cannot see inside nested JARs. Sub-modules are
extracted to `libs/` and added as `compileOnly` so the compiler can resolve
their classes (e.g. `Changeable`). They do NOT need `localRuntime` because the
parent jar already handles runtime loading via JiJ.

## Event system

**Use `TensuraEntityEvents.NAMING_EVENT.register(...)`, NOT `@SubscribeEvent`**
`NAMING_EVENT` is an Architectury `Event<>`, not a NeoForge bus event.
`@SubscribeEvent` will silently do nothing. Registration must be done via the
`.register(lambda)` method on the event field itself.

## Feature design

**Colony lookup order: owner → first colony → none**
When a goblin is named, the code tries `getIColonyByOwner(player)` first, then
falls back to `getColonies(level).get(0)` if the player owns no colony. The
fallback is intentionally naive — it picks the first colony in the list, which
is arbitrary when multiple colonies exist. This is fine for single-colony
testing but must be revisited before multi-colony support. Open question: should
the target colony be the one nearest the goblin, nearest the player, or chosen
via a UI prompt?

**Pending pool drains into the first colony created (single-colony assumption)**
Goblins named before any colony exists are queued in a pending pool in
`GoblinIdentitySavedData`. On `ColonyCreatedModEvent` the pool is drained:
every still-alive pending goblin is promoted via `createAndRegisterCivilianData()`
+ `setName()` + `startTravellingTo(...)` into the newly-created colony.
Subsequent colony creations find an empty pool. Multi-colony future will need
a per-pending-entry colony-assignment policy (by player ownership? by location?
by UI prompt at promotion time?). Stale pending entries (goblin died before
any colony existed) are dropped silently — the goblin-death hook also removes
matching pending entries proactively so the list doesn't grow.

**FUTURE FEATURE — Town hall citizen-type menu**
When a player signs a MineColonies town hall to create a colony, show a menu
asking what citizen TYPE the colony should use (goblin, human, etc.). This
ties into the broader race/citizen-type system from the original design
doc. The pending-pool drain would then also filter by chosen type — only
goblin pending entries promoted into a goblin-typed colony, etc. Not
implemented now; the colony-creation hook (`ColonyCreatedModEvent`) is the
right interception point for this.

**SUPERSEDED — dual-tracking / single-entity approaches abandoned**
Earlier designs (Option A: convert to citizen, Option B: single entity dual
tracking, Option B2: paired shadow citizen) are all superseded by the
"two bodies, one identity" design below. The `ITravellingManager` spawn-
suppression hack and the full AbstractEntityCitizen hierarchy weld (Option B3)
are also abandoned.

---

## Core design: "Two bodies, one identity, one materialized at a time"

A named goblin has a **persistent identity** — name, EP, Tensura `IExistence`
data — stored in our mod's saved data, independent of any in-world body.
That identity is represented by **either** a Tensura goblin entity (subordinate
mode, at the player's side) **or** a MineColonies `EntityCitizen` (colony mode),
but **only one body exists in the world at a time**. Swapping is done via magic
circles.

This design avoids the entity-hierarchy conflict entirely: each mod always
operates on a native entity type it fully understands.

**"Citizen" = roster membership only**
Naming a goblin immediately creates a `CitizenData` entry via
`createAndRegisterCivilianData()` — permanent count increase, no `EntityCitizen`
spawned. The goblin stays at the player's side as a Tensura subordinate. The
earlier "stray EntityCitizen auto-spawn" problem is resolved by design: no body
should exist at naming time, so there is nothing to suppress.

**Send-to-colony (subordinate → citizen)**
Triggered explicitly by the player. The goblin dissolves at the player's side
(magic circle animation) → a goblin-rendered `EntityCitizen` materializes in the
colony. The `CitizenData` that was already in the roster now has a live body.

**Summon (citizen → subordinate)**
A keybind opens a roster menu of named entities. Selecting one dissolves the
`EntityCitizen` in the colony → the Tensura goblin materializes at the player's
side. `CitizenData` persists; count stays up.

**At all times:** `CitizenData` persists and the population count is unchanged,
regardless of which body (if any) is currently materialized.

**Death rule**
If the currently-materialized body dies in either state (goblin-as-subordinate
OR citizen-in-colony), the named identity dies: `CitizenData` is removed and
the colony count decreases. There is no resurrection.

**Advisory messages gated by Great Sage (future)**
The mod will surface a range of advisory / explanatory chat text to the player
— things like "the goblin couldn't carry the excess items, they've been
returned to you," upcoming flavour text for "the citizen finished a job,"
"the goblin has reached an evolution threshold," etc. The design intent is
that these advisory messages should only appear when the player has the
Tensura **Great Sage** skill (or an equivalent analysis skill that ships in
Tensura). The Great Sage is a known in-universe analysis ability; reserving
helpful advisory text for players who have it preserves its identity and
gives a reason to acquire the skill.

Implementation note: all advisory messages should be routed through a single
helper (currently `ExampleMod.sendOverflowNotice`) so the gate can be added
in one place when the skill-check is implemented. Do NOT inline `Component`
constructions for player-facing advisory text at call sites — go through the
helper. This is a deliberately deferred feature; the gate is not in place
yet, and overflow notices currently fire unconditionally.

**Renderer requirement (deferred)**
The colony citizen MUST ultimately render as a goblin, not a default colonist.
Reference implementation: the "Colonies Maid Citizen" mod, which overrides
`EntityCitizen` rendering to display another mod's model while the real
`EntityCitizen` handles all colony logic.

Build the mechanic first with the default ugly colonist appearance (Stages A–E),
prove summon/send/persistence/death all work, then add the goblin renderer as
an isolated final polish step (Stage F). The goblin appearance is a firm
end-product requirement but is deliberately deferred so a fragile rendering
problem cannot block the core mechanic.
