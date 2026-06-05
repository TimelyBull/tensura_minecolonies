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
