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

**Roster menu (Stage C2b) — two-way toggle**
The keybind-opened roster menu will display each of the player's named
goblin-citizen identities with its current mode. Clicking a row routes to
existing server logic based on mode:

- Row showing `SUBORDINATE` → click triggers the send-to-colony flow that
  the sneak-right-click trigger currently invokes.
- Row showing `IN_COLONY` → click triggers the summon flow that
  `/summongoblin` currently invokes.

The mode indicator on each row tells the player which action a click will
perform. Both server-side flows already exist; C2b only adds the C2S click
packet and the Screen. Stage C2a (this commit) establishes the round-trip
plumbing for the roster list itself.

**Energy pool scale mismatch between goblin and citizen bodies**
Goblin entities have Tensura race-tier `MAX_AURA` / `MAX_MAGICULE` /
`MAX_SPIRITUAL_HEALTH` attributes; default MineColonies citizens have
~0 for those, no Tensura race modifiers applied. Direct absolute copy
of goblin-tier values into a default citizen would dump magicule far
above the citizen's max → `handleMagiculeRegen` applies
`MagiculePoisonEffect` with massive amplifier → near-instant death.

First fix attempted: **percentage-scale** the three pools to
`(srcCur/srcMax) × dstMax`. Failed because citizen's
`MAX_MAGICULE`/`MAX_AURA`/`MAX_SPIRITUAL_HEALTH` are 0, so the percentage
calc divided by zero and produced 0 → all energy values dropped to zero
on send, then summon read zero back into the goblin, draining everything.

Final fix: **`bumpBodyMaxAttributes(dst, src)` then absolute copy.**
On send, we add a permanent `AttributeModifier(SWAP_ENERGY_BOOST_ID,
delta, ADD_VALUE)` to the citizen body's `MAX_AURA` / `MAX_MAGICULE` /
`MAX_SPIRITUAL_HEALTH` AND vanilla `MAX_HEALTH` to lift them up to the
goblin's values. The citizen now has the headroom to safely hold the
goblin's absolute values across all four pools. On summon, the goblin
already has its race-tier maxes from the NBT roundtrip — no boost
needed, just absolute copy citizen → goblin. The modifier lives on the
citizen body's `AttributeInstance` and is discarded with the body at
the end of the summon flow. Re-swap removes the prior modifier first
(tracked via `SWAP_ENERGY_BOOST_ID`) so we don't compound.

HP follows the same pattern as the three energy pools: bump
`MAX_HEALTH` then absolute `setHealth`. An earlier attempt at
percentage HP (`ratio × dstMax`) was inconsistent — the citizen's
visible HP was always lower than the goblin's because citizens have
smaller default max-HP; users perceived this as "HP didn't transfer."
With the boost, both bodies show the same numeric HP.

Side-benefits: round-trip cost stays symmetric (absolute EP carries
across), and citizens with the boost can actively gain/spend magicule
during colony service. The goblin-citizen has higher max-HP than a
normal MineColonies citizen for the duration of its colony service
— consistent with the "fundamentally tougher entity" interpretation.

**Goblin/citizen stat systems differ — equalisation deferred**
Tensura and MineColonies maintain separate stat models: Tensura tracks
EP (aura + magicule), spiritual health, alignment, evolution state, and
race-applied attribute modifiers on the entity; MineColonies tracks
citizen skill levels (strength/dexterity/etc.), happiness, saturation,
job level, and a separate health pool. "Equal" gameplay between subordinate
mode and colony mode requires an explicit mapping — e.g. "EP threshold X
maps to citizen skill level Y" or "Tensura attribute modifiers translate
to citizen primary stats". This is a separate design problem and out of
scope for the current vertical slice. Flagged for a later stage. Until
mapped, a goblin appears strong as a subordinate and weak as a citizen
(or vice versa) — accepted prototype trade-off.

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
