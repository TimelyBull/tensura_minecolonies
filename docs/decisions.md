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

**Advisory messages — single styling chokepoint, NO Great Sage gate (abandoned 2026-06-06)**
All player-facing advisory / explanatory chat (overflow notices, evolution
hints, swap diagnostics, etc.) routes through `ExampleMod.sendAdvisoryNotice`
for consistent green-italic styling. Do NOT inline `Component` constructions
for advisory text at call sites — go through the helper.

An earlier design proposed gating these messages behind the Tensura "Great
Sage" skill ("only players with the analysis skill see advisories"). That
plan is abandoned — advisories are now always shown. The helper remains
useful as a styling chokepoint; the gate is gone.

**Renderer requirement (Stage F — landed)**
The colony citizen MUST render as a goblin, not a default colonist.
Reference implementation: the "Colonies Maid Citizen" mod, which overrides
`EntityCitizen` rendering to display another mod's model while the real
`EntityCitizen` handles all colony logic.

Built the mechanic first with the default ugly colonist appearance
(Stages A–E), proved summon/send/persistence/death all work, then added
the goblin renderer as an isolated final polish step (Stage F). The
goblin appearance was deliberately deferred so a fragile rendering
problem could not block the core mechanic. Stage F is now complete
through F5 (per-variant appearance + hobgoblin scale & overlays);
higher evolved tiers are Stage G.

## Stage F renderer — tagging + override mechanism

**Override mechanism: `RenderLivingEvent.Pre` (NeoForge bus), NOT mixin or
renderer replacement.**
MineColonies registers `RenderBipedCitizen` for `minecolonies:citizen`; we can
neither register a competing renderer for the same entity type nor reliably
mixin into MC internals. `RenderLivingEvent.Pre` fires per-entity before every
render, lets us cancel and draw whatever we want for tagged citizens, and
leaves all untagged citizens flowing through MC's renderer unchanged. The
goblin's own `GoblinRenderer` is hard-bound to `GoblinEntity` (generic upper
bound `TamableAnimal`) and cannot be instantiated for a citizen — Stage F2+
will write a dedicated `LivingEntityRenderer<AbstractEntityCitizen,
HumanoidModel<…>>` that reads goblin textures. The goblin does NOT use
GeckoLib; `GoblinRenderer extends PlayerLikeRenderer<GoblinEntity> extends
LivingEntityRenderer`. All goblin assets are vanilla biped-format PNGs in
`assets/tensura/textures/entity/goblin/`.

**Tag storage: NeoForge `AttachmentType<GoblinTag>`, NOT any MC String
EntityDataAccessor.**
Verification of MC source: `AbstractEntityAIBasic.updateRenderMetaData()`
unconditionally writes `""` or `"working"` (or a job-specific override from
~15 job AI subclasses — Farmer, Fisherman, Druid, Quarrier, etc.) into
`DATA_RENDER_METADATA` every AI tick. `DATA_STYLE` is written by
`CitizenColonyHandler` from the colony's structure pack and is read by
`EntityCitizen` against `TextureReloadListener.TEXTURE_PACKS` for normal
texture lookup. `DATA_TEXTURE_SUFFIX` is written by `CitizenData` from
`getTextureSuffix()`. Every existing String accessor has an active
server-side owner that would clobber our tag. A NeoForge data attachment
registered under our mod id is invisible to MC and survives the citizen's
lifetime untouched, with NBT persistence for free.

**Client sync: explicit S2C payload, NOT NeoForge's `.sync(StreamCodec)`.**
NeoForge 1.21.1 entity attachments DO support a `.sync(...)` option on the
builder, but we use an explicit `SyncGoblinTagPayload` for two reasons:
(1) we already have the `Networking` payload infrastructure; (2) we need
fine-grained control over the per-player resync on tracking start so the
goblin appearance never flickers as the default colonist for a frame —
the eager `PlayerEvent.StartTracking` unicast (no `enqueueWork`, no delay)
is the documented "biggest risk" from the verification report.

**Single tag chokepoint: `sendGoblinToColony` sets, `summonGoblin` clears.**
Tag lifecycle is bound 1:1 to the citizen body's lifecycle in the world.
No other code path may set or clear the attachment. The send-side broadcast
covers all current viewers; the StartTracking handler covers any future
viewer; the summon-side broadcast covers the prompt clear. Client `Map`
cleanup is belt-and-braces via `EntityLeaveLevelEvent`.

**Variant capture: ID-encode at send, replicate Tensura's texture formulas
at render.**
Tensura's variant enums (`GoblinVariant.Skin/Hair/Face/Head/Top/Bottom/Gender`)
all expose `getId()` and `byId(int)`, so the wire/NBT format only needs to
carry small integers — 7 enum IDs + 4 ARGB tint ints + 1 bandages byte +
1 evolutionState byte = a fixed 25-byte little-endian record
(`GoblinVariantData`). The renderer reconstructs ResourceLocations by
calling `byId(id)` and feeding the result into the SAME path formulas
Tensura's static initialisers use (e.g. `textures/entity/goblin/{gender}/skin/{prefix}{gender}.png`).
We avoid Tensura's `getTextureLocation(GoblinEntity)` because the citizen
isn't a goblin entity, but we use the enum-resolved values to drive the
same texture lookup paths so any future Tensura asset reshuffle that
changes a prefix would surface as a single string change here, not silent
mis-rendering.

Pre-F5 24-byte records (F4) decode as `evolutionState = 0` (base goblin)
on load — backward-compatible by construction. The decode tolerates short
payloads from F2's empty placeholder by returning `GoblinVariantData.DEFAULT`,
so no NBT migration was needed across the F2 → F4 → F5 transitions.

**Overlay layers: one generic class, base+hobgoblin layer set, deferred
higher tiers.**
Tensura's renderer adds 9 `GoblinLayer.*` subclasses, each a thin
`RenderLayer<GoblinEntity, …>` that bakes a per-overlay
`ModelLayerLocation` (e.g. `tensura:goblin_face main`) and resolves the
texture from the live goblin's variant. We can't reuse them (they're
typed to `GoblinEntity`) but we can reuse their `ModelLayerLocation`
constants from `GoblinLayer.Face.FACE` etc. — Tensura registers those
model layers at mod init and `mc.getEntityModels().bakeLayer(...)` returns
the right baked geometry at runtime. A single `GoblinOverlayLayer<…>`
parameterised by `(modelLayer, textureFn, colorFn, shouldRenderFn)` covers
all 8 non-armor overlays uniformly — no per-layer subclass — and the
predicate handles Tensura's per-layer gates (bandages flag, hobgoblin
gate, head id != -1).

## Stage B — race-aware population spawn

**Per-colony race storage in our own SavedData.**
MineColonies' built-in per-colony customisation is the `ICommonSettingsModule` /
`ISettingKey` / `BoolSetting` chain, with a Structurize-rendered Setting view
required to expose new settings — heavyweight for a single enum field. Our
`ColonyRaceConfigSavedData` (Map<colonyId, Race>, NBT-serialised, stored
on the overworld's data storage) mirrors the existing `RaceIdentitySavedData`
pattern, is the single source of truth the future town-hall race-picker menu
will write to, and decouples our race system from MC's settings internals.
A `ColonyDeletedModEvent` hook clears stale entries when colonies are deleted.

Default behaviour: absent key → vanilla MineColonies citizens. Legacy
colonies and any colony predating the race picker get standard citizens
with zero migration.

**Interception via CitizenAddedModEvent(INITIAL) — post-hoc spawn-then-undo.**
`CitizenManager.onColonyTick` posts `CitizenAddedModEvent` AFTER the citizen
is created+spawned, and `IModEvent` is non-cancellable in 1.1.1319 (verified
by re-grep of the API). We can't intercept pre-spawn; we can only react to
the event by:
1. `citizenEntity.discard()` — `Entity.remove(DISCARDED)`, fires
   `CitizenRemovedModEvent` (we do NOT subscribe; only MC's own EntityCitizen
   self-listens). Does NOT fire `CitizenDiedModEvent`, so the identity-death
   cleanup in `onCitizenDied` stays untouched.
2. `colony.getCitizenManager().removeCivilian(citizenData)` — drops from
   `citizens` map, count↓, unassigns from buildings (no-op for fresh INITIAL
   citizen with no job/home assignment), clears work orders, sends a
   `ColonyViewRemoveCitizenMessage` to nearby subscribers.
3. Spawn the chosen-race wild mob at the same position via
   `EntityType.create` + `finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null)`
   + `addFreshEntity`.

`MobSpawnType.SPAWN_EGG` (not `NATURAL`) is mandatory: it both triggers
Tensura's variant-randomisation pipeline in `finalizeSpawn` AND marks the
mob as non-despawnable so it persists until the player names it.

**Spawn-then-undo client-visibility — same-tick, never reaches clients.**
The full sequence runs synchronously inside `onColonyTick`. Vanilla MC's
entity tracker (`ChunkMap.TrackedEntity`) dispatches `ClientboundAddEntityPacket`
at end-of-tick when it iterates each tracked entity; our discard sets
`removalReason` BEFORE that dispatch, so the tracker skips sending the
add packet. ColonyView messages remove an unknown id on the client (no-op).
No flash is expected; if one ever appears the fallback path is a mixin
into `CitizenManager.onColonyTick` to short-circuit before
`createAndRegisterCivilianData` runs (cleaner, coremod cost).

**Accepted risk — ghost CitizenAddedModEvent(INITIAL) for third-party subscribers.**
The event has already fired by the time our handler discards the citizen.
Any third-party mod that subscribes to `CitizenAddedModEvent(INITIAL)`
sees a stale reference to a citizen we're about to undo. Within MC the
two internal subscribers (`CommandCitizenSpawnNew`, `RecruitmentInteraction`)
both no-op for INITIAL — verified safe. Cross-mod compatibility is
technically broken for any mod that subscribes specifically to INITIAL
adds and expects persistence. The clean fix is the mixin path above;
it's deferred future-proofing, not worth the coremod cost until empirically
needed.

**Growth path is eventless — reproduction needs a mixin (2026-06-29).**
`CitizenAddedModEvent` covers only INITIAL (town-hall top-up to
`initialCitizenAmount`, default 4), RESURRECTED, HIRED, and COMMANDS. The
actual ongoing population growth — `ReproductionManager.trySpawnChild()` →
`createAndRegisterCivilianData()` + `spawnOrCreateCitizen()` — fires NO event
(decompile-verified, 1.1.1319). So the event-based `onCitizenAdded`
interception silently missed every grown citizen: a race colony filled up with
plain human colonists once it passed `initialCitizenAmount`, and naming a wild
mob (a separate new CitizenData) couldn't displace them. Reported as a player
bug (docs/user-bug-reports.md). Fixed with `ReproductionManagerMixin` (the
coremod path the note above anticipated — now empirically needed, so built).

**Integrated child route over wild-mob substitution (2026-06-29).** Two shapes
were considered for what reproduction should produce in a race colony:
1. *Substitute a wild mob* — cancel the birth, spawn an unnamed wild race-mob at
   the town hall, player names it (mirrors the INITIAL hook). Smaller change.
2. *Breed a race child* — let MC create the child, then CONVERT it into a
   citizen of the colony's race (a baby tied to its real colony parents that
   grows up). Keeps MC's reproduction/family system intact, no per-newcomer
   naming step.

Picked (2) at the developer's direction — more integrated and more lore-true (a
goblin village grows goblins; naming is reserved for evolution). Mechanism:
`@WrapOperation` around `createAndRegisterCivilianData()` in `trySpawnChild`
(gives the fresh child reference; the original is still called so MC's flow is
untouched) → `ExampleMod.onReproductionChild` → `mintRaceChildCitizen`, which
mints an IN_COLONY `RaceIdentity` with a randomised variant + body snapshot from
a transient `finalizeSpawn`'d wild mob (never world-added), persists a `RaceTag`
snapshot (the body-join / reconcile pass stamps it), and applies the race skill
profile + named happiness. Race-gated by `pickRandomMember` (pending / legacy /
COLONIST draw → ordinary human child). All four races; mixed colonies breed in
proportion. Bred children are AUTO-NAMED for now (full race citizens
immediately); leaving them unnamed-for-evolution is recorded in
docs/future-ideas.md. Evolved (hobgoblin) appearance is not applied — Tensura's
`evolutionState` is NBT-only with no public setter, so children render the base
race form (correct for babies regardless).

## Stage G — race system foundation

**Sealed `RaceVariantData` interface, NOT one-mega-record-fits-all or
race-discriminated raw bytes at the consumer.**
The original goblin pipeline stored `GoblinVariantData` directly in
`RaceTag`. Generalising for orc had three plausible shapes:
1. raw `byte[]` in the tag, every consumer dispatches on race;
2. one concrete record with goblin+orc fields union'd;
3. sealed interface with per-race implementations.

Picked (3). The wire format is unchanged from option (1) — the race byte
already on the wire/NBT picks the decoder via `RaceTag.fromWire` — but
consumer-side type-narrowing happens in one place
(`RaceTagClientStore.getGoblinVariant`/`getOrcVariant` with `instanceof`)
and `RaceTag.encodeVariant()` is polymorphic. Adding a new race is one
new `permits` entry, one new record, one new typed accessor; no other
code changes. Pattern-match exhaustiveness on the sealed type is
compile-checked, so adding a new race surfaces every incomplete switch
as a compile error rather than a runtime CCE.

**Race tag includes a `Race race` discriminator — separate from
`identity.race`.**
`RaceIdentity` and `RaceTag` both carry race. They're not always the
same: the `/raceflip` debug command toggles the tag's race independently
of the identity. Keeping them separate avoids special-casing the debug
path through the SavedData store. For the production naming→send flow
they match; for `/raceflip`, `identity.race` stays goblin while the tag
flips.

**Orc rendering: shadow-OrcEntity fed to Tensura's own renderer, NOT a
custom renderer.**
`AbstractEntityCitizen` cannot implement `GeoAnimatable` (third-party
class). The shadow approach — `EntityType.create(level)` without
`addFreshEntity` — gives Tensura's `OrcRenderer` a real `OrcEntity` to
render. Pivotal find from Stage 3 investigation: Tensura's 7 OrcLayer
subclasses (`OrcLayer$Neck/Top/Necklace/Bottom/Belt/Boots/Bandage`) plus
`$1` (ItemArmorGeoLayer) and `$2` (BlockAndItemGeoLayer) all read state
directly off the entity parameter. Setting variant + equipment fields on
the shadow each frame makes Tensura's renderer draw every accessory
correctly. Zero new layer classes for orcs — substantially less code
than the goblin accessory path.

**Per-citizen shadow pool — NOT a single shared shadow.**
GeckoLib's `AnimatableInstanceCache` keys by `entity.getId()`. A single
shared shadow would have its animation state overwritten every frame
for whichever citizen was synced last; two simultaneously-visible
orc-citizens would blink between each other's animations. Per-citizen
shadows cost ~one `OrcEntity` allocation per visible orc-citizen but
eliminate the blink entirely. Cleanup via `EntityLeaveLevelEvent` keeps
the pool bounded.

**HARD RULE: shadow never `tick()` / `aiStep()` / `move()`.**
Off-world entities can't run AI, navigation, or collision safely —
brain context is null, navigation target is null, entity movement would
query off-world blocks. Worst case: collision detection at the synced
citizen position triggers `Entity.hurt`, which propagates a death event
with no real subscriber, the shadow's `health` drops, animation state
corrupts. Strict policy: only direct field writes and public setters
(`setPos`, `setDeltaMovement`, `setPose`, `setSprinting`, `setVariant`,
`setNeck...`, `setItemSlot`, `setCustomName`) and
`walkAnimation.update(target, 1.0f)` once per detected tick advance.

**Orc lord and orc disaster: blocked from the citizen pipeline, not
supported as separate shadow types yet.**
Tensura models orc evolution as a chain of distinct EntityTypes
(`tensura:orc` → `tensura:orc_lord` → `tensura:orc_disaster`), each with
its own renderer and GeoModel. Supporting them in the citizen pipeline
needs per-tier shadow pools, parallel to the orc work already done.
Deferred (roadmap Stage G6). Blocking enforced via
`Races.isBlocked(EntityType)` at three sites: naming filter
(`Races.of` returns null for unregistered types), sneak-send filter
(same), and `handleMenuAction` chokepoint (catches the case where a base
orc evolved to orc_lord at the player's side after being named).

**Sentinel guard pattern — raw read for public accessors, try/catch for
private.**
Tensura's variant enums use `BY_ID[id % BY_ID.length]` for `byId`, which
throws AIOOBE for `id = -1` (Java's modulo preserves sign). Tensura uses
`-1` as the "no accessory" sentinel for goblin HEAD/TOP/BOTTOM and the
crash propagates through the public `getHead()` etc. getters. Mitigation
is per-accessor: HEAD is `public static final EntityDataAccessor`, so we
read the raw int directly and guard `>= 0`; TOP and BOTTOM are private,
so we wrap `g.getTop().getId()` in try/catch returning -1. The renderer's
texture helpers already null-check on negative ids. Same pattern repeated
for orc — three crash-prone getters (`getVariant/getNeck/getTop`) wrapped
in `safeOrcEnumId(IntSupplier)` with fallback 0.

**Fail-before-commit in the send path.**
After the sentinel crash was caught (goblin with HEAD=-1 during F4/F5
test), an orphaned citizen was left in the world with no race tag —
visible across reload as a default-skinned citizen with no race tag,
counting in the colony population. Restructured the send to capture the
variant BEFORE `spawnOrCreateCivilian`, then wrap the post-spawn block
in try/catch with rollback. Rollback path mirrors the existing
"chunk not loaded" handling: `citizenBody.discard()` +
`startTravellingTo` to re-suppress respawn + advisory. Identity stays
valid (still SUBORDINATE); items stay in `InventoryCitizen` and round
back via the summon path on retry. Defence-in-depth: any future
throwable in the post-spawn block can no longer orphan a citizen.

**Goblin accessory rendering — PlayerModel overlays, not HumanoidModel.**
F5 added the three hobgoblin-only overlay layers, but Top (shirt) and
Bottom (shorts/pants) didn't draw on citizens. Cause: `GoblinOverlayLayer`
wrapped Tensura's PlayerModel-baked LayerDefinitions in a plain
`HumanoidModel`, which only resolves 7 basic parts (head/hat/body/arms/legs).
The 5 PlayerModel overlay parts (`jacket`, `left_sleeve`, `right_sleeve`,
`left_pants`, `right_pants`) — where the inflated shirt/pants cubes
live — were silently dropped from the draw. Fix: change the overlay
model class to `PlayerModel<…>` (slim=false, matching Tensura's
`GoblinLayer.<clinit>` template). PlayerModel extends HumanoidModel so
the layers Tensura builds as HumanoidModel-based (Face, Head) still
render through the same basic parts unchanged.

**Goblin armor + held items + baby state — three small bridge fixes.**
After base goblin rendering worked, three follow-ups landed together:
(1) `HumanoidArmorLayer` and `ItemInHandLayer` were missing from
`GoblinCitizenRenderer` — vanilla equipment slots aren't drawn without
them. (2) Items lived in `InventoryCitizen` but not on the entity's
vanilla equipment slots — the renderer layers read from the entity, so a
bridge `applyEquipmentFromInventory(body, inv)` runs once in the
post-spawn block. (3) `goblin.isBaby()` was never propagated to the
citizen body — fix: `citizen.setIsChild(true)` if the source was a baby.
Hitbox, render scale, and the model's `young` flag all follow from
`setIsChild` automatically.

## Stage B — race picker menu

**Tri-state config: `pendingChoice` set + `raceByColony` map.**
A naive two-state config (race set or not) couldn't distinguish "new
colony, player hasn't picked yet" from "legacy colony, treat as vanilla".
Adding a separate pending set resolves it: pending colonies suppress ALL
growth (no citizens, no mobs) until choice; race-configured colonies
spawn the race mob; "no entry in either" is both DEFAULT-picked AND
legacy — indistinguishable by intent. Existing colonies in pre-menu
worlds need no migration: they read as `no entry → vanilla citizen`.
`Race.DEFAULT` was rejected as an enum value (would have to be ignored
by every Race consumer; overloads the type) in favour of a
payload-only `RaceChoicePayload.CHOICE_DEFAULT` byte that the
server-side handler maps to "no entry".

**Screen-collision: parent-pointer stacking + 1-tick deferred open.**
MineColonies' `CreateColonyMessage.onExecute` sends both
`ColonyCreatedModEvent` (our hook) and `OpenBuildingUIMessage` (MC's
town hall UI) in the same synchronous call, both queued onto the
client's network thread within milliseconds. There's no guarantee on
arrival order. Two approaches considered:
1. Parent-pointer stacking alone — capture whatever screen is current
   when we open the picker; close-to-parent on dismiss. Works in most
   cases but loses the parent if our payload arrives first.
2. Fixed N-tick delay — open picker after N ticks. Robust but blunt.

Picked both. Parent-pointer is the primary mechanism; the 1-tick defer
on the client side guarantees MC's town hall UI message has had time to
process by the time our `setScreen` runs.

**MC's "colony_founded" message suppressed via Mixin — NOT via custom
chat dispatch.**
MineColonies sends the `com.minecolonies.coremod.progress.colony_founded`
translation key from `CreateColonyMessage.onExecute` BEFORE
`ColonyCreatedModEvent` fires, with no event-driven cancellation in MC's
API. To stop the duplication ("MC's message + our race-specific
message"), we Mixin-suppress MC's automatic send using MixinExtras
`@WrapOperation` at the two success-path `MessageBuilder.sendTo` calls
(`colony_reactivated` at ordinal 2, `colony_founded` at ordinal 3 of the
sendTo invocations inside `onExecute`). Error-path sendTo calls
(notileentity at ordinal 0, secondary failure at ordinal 1) are
deliberately NOT wrapped — failed-creation errors still reach the
player.

For the DEFAULT-citizens pick, the server-side `handleRaceChoice`
re-issues the same translation key via `player.sendSystemMessage`. Same
flavour text, just routed through our handler instead of MC's automatic
path.

**Mixin ordinal targeting is brittle vs future MC updates** — a new
`sendTo` call inserted before either of our ordinals would shift the
count and either leave a success message visible or accidentally
suppress an error. Detection is easy (smoke-test colony creation,
observe whether double messages appear); failure mode is recoverable.
Accepted as the cost of not setting up a slice-targeted mixin (more
robust but more verbose). Worth flagging if a MC version-compat matrix
becomes a thing.

**Re-engagement on town-hall right-click and player login.**
An ESC-dismissed picker leaves the colony permanently pending (no
growth), which is bad UX — the player thinks "I'll set it later" then
forgets. Two recovery paths: log-out-and-back-in (re-sends on
`PlayerLoggedInEvent` for any pending colony the player owns) and
town-hall right-click (`PlayerInteractEvent.RightClickBlock` on the
town hall block of a pending owned colony). Right-click detection:
closest-colony lookup at the clicked position, verify the colony's town
hall position equals the click, verify pending status, verify the
clicker is the owner — then re-send the picker payload.

**Suppression of the green-italic "harder than normal" warning after
race pick.**
Originally each race pick fired both a white flavour message AND a
green-italic `sendAdvisoryNotice` warning ("Mob races are harder to
grow..."). Removed during menu polish — redundant with the picker's own
difficulty disclosure ("Recommended for veteran players only.") in the
panel before picking. Single message per pick now: white flavour text
only.

## Roster menu expansion — Stage 1 (landed) and Stage 2 (planned)

**Stage 1 — search bar + EP-desc sort (landed 2026-06-06)**
The roster Screen now has a vanilla `EditBox` search field below the title
and sorts rows by Tensura EP (aura + magicule) descending, "most powerful
at the top." Search filters case-insensitively against the row's name
(substring match). Empty search shows everything. The filtered list stays
EP-sorted.

EP is added to the `RosterEntry` payload — read server-side from the live
body's `IExistence` via the same resolve+read pair (`resolveTargetBody` +
`readExistence`) the cost gate uses, so the EP shown in the roster matches
what the cost gate will charge against. When the live body cannot be
resolved (chunk unloaded, dim mismatch), EP is 0.0 — keeps sort
deterministic and the row still renders. Sort and filter are client-side
from the now-available EP; the server payload format is unchanged besides
the appended double field.

The existing toggle/refresh round-trip is untouched: row click still sends
`ActOnIdentityPayload`; the server's post-action `sendRosterTo` push
arrives as the same `RosterResponsePayload`, which `RosterScreen.setEntries`
applies through the same filter+sort pipeline — search text and sort
survive the refresh.

**Stage 2 — drag-multi-select bulk summon (planned, not yet built)**
Drag across rows in the roster to select multiple identities (up to 9 at a
time, the magic-circle's per-summon cap). Submit summons them as a group.

Cost rule on the BULK total vs the player's current magicule:
- `total > 1.25 × current_magicule` → outright FAIL (no summon, advisory).
- `total ≤ current_magicule`        → summon normally.
- in-between (player CAN afford the group but ends below 0) → summon +
  trigger the existing Sleep-Mode collapse via the established overspend
  path, extended to apply to the whole group atomically.

Visuals: a bigger magic circle than the single-summon variant; subordinates
materialize in a 3×3 slightly-spaced pattern around the player (centred on
the player tile, one body per pattern slot, fewer slots used for groups
< 9).

Scope: **bulk SUMMON only** for the initial Stage 2 plan. Bulk send-to-colony
was originally deferred but added in the same Stage 2a landing — the cost
band, decision helper, and per-identity queueing turned out to be fully
symmetric so the marginal cost was small.

**Bulk send — symmetric path (landed alongside Stage 2a).**
Mirrors bulk summon: drag-multi-select across SUBORDINATE rows, "Send
Selected (N) to Colony" button, same three-band cost decision on the total
(refuse / safe / overspend-Sleep-Mode). The selection drag is **mode-locked
to the first row touched** — once the batch starts as SUBORDINATE the
client silently skips IN_COLONY rows during the drag (and vice versa), so
one drag = one operation = one payload. The server-side gate also blocks
evolved-tier orcs (`Races.isBlocked`) from the batch, mirroring the
single-send filter. Each identity in the batch goes to its OWN colony's
town hall, so a single batch can fan across multiple colonies if the
player has them. Basic placement uses the existing per-identity town hall
position (no fan for Stage 2a — citizens disperse naturally via MC AI).

## Multi-race per colony (foundation for the envoy system)

**`Set<ColonyMember>` per colony, NOT `Set<Race>` and NOT a `Race.COLONIST`
enum value.**
`ColonyRaceConfigSavedData` now maps `colonyId → EnumSet<ColonyMember>`
where `ColonyMember` is a new enum disjoint from `Race`:
`COLONIST(0), GOBLIN(1), ORC(2)`. The set lets a colony spawn a mix
(`{GOBLIN, ORC}` for a multi-race tribe, `{COLONIST, GOBLIN}` for an
envoy-style mixed colony). One member is drawn at random per
population-grow tick (uniform — weighting deferred).

`COLONIST` is its own type and **NOT** added to `Race`. `Race` stays
Tensura-mob-only (GOBLIN, ORC) — every existing `Race` switch / sealed
pattern in the renderer, naming hook, send/summon logic, and identity
storage stays exhaustive without forcing a "COLONIST has no mob /
texture / naming flow" case in every site. The mapping
`ColonyMember.toRace()` returns `Optional<Race>` (empty for COLONIST);
the spawn hook reads this — empty → leave vanilla citizen alive;
present → discard + spawn the race mob.

**Why a separate type space:**
1. `Race` semantics ("this entity has a Tensura race-mob") and
   `ColonyMember` semantics ("this slot of the colony's composition")
   are orthogonal — collapsing them would force every Race consumer to
   handle a value that never appears in a `RaceTag` or `RaceIdentity`.
2. The envoy system will need to express diplomacy edges between a
   colony and external "factions"; modeling factions as
   `ColonyMember`-shaped entries (with COLONIST as the default human
   faction and Tensura races as additional ones) lets the envoy code
   reuse the composition primitive directly.

**DEFAULT picker choice writes `{COLONIST}` (explicit), NOT empty.**
The old picker mapped DEFAULT to "no entry," indistinguishable from
legacy / pre-menu colonies. The new picker writes an EXPLICIT
`{COLONIST}` single-member set. Functionally identical at spawn time
(the spawn hook draws COLONIST → leaves the vanilla citizen alive) but
records the player's deliberate choice so the envoy system can later
`addMember(colonyId, GOBLIN)` to make it a `{COLONIST, GOBLIN}` mixed
colony. Legacy "no entry" still works and reads as vanilla; treated as
a transparent default at every site.

**Migration is transparent.**
NBT format went from `entry { colonyId, race: byte }` to
`entry { colonyId, members: byte[] }`. The load path tries `members`
first; if absent (legacy save) it reads the old `race` byte and wraps
it in a one-element `EnumSet.of(ColonyMember.fromRace(race))`. Save
files written by the previous mod version load with a one-element set
and behave identically. Save files written by this version using only
single-member sets are forward-compatible insofar as the spawn-time
behaviour is unchanged for single-member colonies.

**Testing command extension.**
`/setcolonyrace` retains its bare-arg form (`/setcolonyrace goblin`
replaces with `{GOBLIN}`) and adds `add <member>` / `remove <member>` /
`list` subcommands. The bare form's accepted values gained `colonist`
in addition to `goblin|orc|clear`. Sufficient to exercise multi-member
colonies before the envoy UI lands; no client-side changes.

## Envoy system (landed across Stages 1, 2, 3a, 3b)

**Design and full as-built record live in `docs/envoy-system.md`** — this
section captures only the decisions that touch other parts of the
codebase.

**Colonist envoy = real `VisitorCitizen`, registered via `VisitorManager`.**
Investigation considered (vanilla Villager + tag, stray EntityCitizen,
custom entity); the chosen path is the only one that gives the colonist
look without ghost-citizen state pollution. The bookkeeping cost
(envoy briefly appears in the colony's visitor list until accept /
decline / kin-kill calls `removeCivilian`) is acceptable. The specific
discard-on-zero-citizen-id failure in `VisitorColonyHandler` made the
no-VisitorData approach unworkable.

**Dialogue UI: custom `EnvoyDialogueScreen`, NOT MC's
`IInteractionResponseHandler`.** The MC interaction system is hard-bound
to `ICitizenData` at every callback. Reuse paths require either a ghost
citizen (state pollution) or substantial glue. Custom Screen uses the
networking + screen infrastructure we already operate confidently and
keeps envoys cleanly separated from citizen interactions.

**Race-mob marker = NeoForge attachment (`ENVOY_TAG`), parallel to
`RACE_TAG`.** Persists across save / load via the attachment's NBT
serializer. Same primitive the rest of the project already uses for
per-entity persistent state.

**Naming suppression at `NAMING_EVENT.interruptFalse()`, NOT a Tensura
mixin.** Tradeoff: Tensura's naming menu still opens on envoys; the
player has to submit before bouncing. Open-then-bounce is the v1 UX. A
Tensura-side mixin on `RequestNamingKeyPacket.canName` would prevent
the menu from opening; deferred until playtesting shows the bounce
matters.

**Scheduler cadence = 20 ticks (1 s), NOT once per in-game day.** The
original 24000-tick cadence used `server.getTickCount()`, which does NOT
advance on `/time add`. After `/time` jumps the scheduler never aligned
during testing. The new cadence re-checks every second; the day-based
gates inside `tryScheduleEnvoy` use `level.getGameTime()`, so the actual
spawn cadence is unchanged in normal play — only the latency between
"conditions met" and "envoy appears" tightens.

**Kill-gate ORC condition uses snapshot-then-grow-past, NOT a boolean
flag.** A boolean "needs retrigger" would never clear if the citizen
count didn't drop and rise again. The snapshot approach makes
"re-trigger" mean "colony grew further" — robust to stable populations
that stay above the 25 threshold. Snapshot clears when an ORC envoy
resolves so subsequent eligibility defaults back to the plain ≥25 check.

**`tensuraMaxNonColonistEnvoys` is per-player, not per-colony.** Players
running multiple colonies see envoys based on their personal history —
the first 2 non-colonist races they encounter become "theirs"; a third
(once more races exist) is locked out. COLONIST is exempt from the cap.

## Lizardman, Dwarf, citizen-skill profiles, subordinate trade tab

**Full as-built record lives in `docs/lizardman-dwarf-and-skills.md`** —
this section captures only the decisions that touch other parts of the
codebase.

**Skill profile is a STARTING BIAS on MC's randomised baseline, NOT an
absolute override.** Each race profile maps Skill → (mean, spread). The
apply pass reads each skill's current level (already randomised by
`CitizenSkillHandler.init(levelCap)` during `createAndRegisterCivilianData`),
adds the bias, clamps to [1, 99], writes back via `SkillData.setLevel`.
The "eroding head-start" falls out naturally: a biased orc still earns
XP at the normal rate and converges toward the cap over a career, no
progression interference. An earlier draft set absolute starting values
(e.g. ORC Strength = 12 fixed) — rejected because it ignored MC's
happiness-driven `levelCap` scaling for advanced colonies. Bias-on-baseline
respects MC's scaling and lets orcs in a happiness-10 colony start
proportionally higher than orcs in a happiness-5 colony.

**Profile applied at NAMING ONLY, not on swap.** Two sites — `onRaceNamed`
and the pending-pool drain — both fire `RaceSkillProfiles.applyForRace`
immediately after `createAndRegisterCivilianData()` returns. Send /
summon paths reuse the same `CitizenData` and don't reapply. Verified
by tracing MC's `CitizenSkillHandler.init` call sites: legacy save
migration (`levelMap` present + `newSkills` absent) is the only other
init path and is gated on pre-1.x save formats, so modern saves never
hit it.

**Existing pre-skill-system citizens are NOT retroactively biased.**
Considered a one-time migration on world load; rejected because over the
lifetime of a working citizen their skill levels have moved well past
MC's init baseline, and adding the race bias to that current level would
over-bias them (an orc at Strength 8 from work would jump to Strength
16+). Accepted that only newly-named citizens get profiles. Dev-world
inconsistency is small and recoverable per-citizen via `/mc citizens`
commands if it matters.

**Dwarf SCALE attribute approach abandoned in favor of renderer-only
scale.** Original plan was `Attributes.SCALE = 0.5` on the citizen
body. In practice it broke rendering:
`LivingEntityRenderer.render` applies a hardcoded `-1.5` Y translate
AFTER `scale()` fires, in scaled space, so SCALE=0.5 placed the model
~-0.75 below the entity origin (sunken half into the ground, often
appeared invisible). Plus MineColonies' custom citizen pathfinding may
not consult SCALE the way vanilla does. Final approach: hardcode the
scale in `DwarfCitizenRenderer.scale`, exactly mirroring goblin's
pattern. The hardcoded value is **`0.9375F`** — what Tensura's own
`PlayerLikeRenderer.scale()` uses (decompile-verified). An interim
pass used `0.5F` on the mistaken assumption that the `0.5F` in
Tensura's `DwarfRenderer` constructor was the model scale (it's the
shadow radius); that produced visibly-shorter citizen dwarves than
the wild dwarfs they were named from, fixed by switching to the
correct `0.9375F`. Hitbox stays at the standard citizen size since
SCALE is left at 1.0. `applyRaceScaleAttribute` retained as a
defensive 1.0-clearing helper so a Tensura tier-scale doesn't leak
onto the destination citizen body.

**Dwarf texture lookup uses a lazy "texture proxy" entity.** Tensura's
`DwarfVariant.<X>.getTextureLocation` static helpers all require a
`DwarfEntity` instance to read its package-private `texture` field. We
render an `AbstractEntityCitizen`, not a `DwarfEntity`. Solution: a
single lazy proxy `DwarfEntity` in `DwarfTextures` — built once,
never added to world, never ticked. Each lookup mutates its variant
fields from our `DwarfVariantData`, then calls Tensura's static
getter. For lookups Tensura exposes publicly (`Skin.getTextures()`,
`Hair.getTextures()`) we skip the proxy entirely.

**Naming gate for dwarf via datapack tag merge, NOT mixin.**
`tensura:dwarf` was missing from Tensura's `can_be_named` entity-type
tag and dwarf doesn't implement `INameEvolution`, so the naming gate
silently rejected dwarves. Datapack tag at
`data/tensura/tags/entity_type/can_be_named.json` with `replace: false`
+ `tensura:dwarf` value adds it via NeoForge's additive tag merge. Same
mechanism as the existing `animal_prey` tag merge — proven, no mixin
needed. Once in the tag, `isNotNameable` falls through to the
`INameEvolution` check which short-circuits to false (instanceof fails),
so dwarf becomes nameable.

**Dwarf envoy unlock = PLACEHOLDER (≥30 citizens AND a Miner / Miner's
Hut built).** The real conditions (dwarven village found / 20 in-game
days / true demon lord existence) are deferred-content. The placeholder
gates dwarf as the late-game envoy with a thematic stoneworking signal:
the colony has to be sizeable AND interested in underground craft. The
`/envoystate` diagnostic surfaces the per-race reason and tags the line
`[PLACEHOLDER condition]` so the interim nature is visible in-game.
Replace when the real conditions ship.

**Subordinate trade tab — `ScreenEvent.Init.Post` + reflection on
`HumanoidMainScreen`, NOT `HumanoidInventoryScreen`, NOT mixin.**
Tensura's subordinate inventory is split across two distinct Screen
classes: `HumanoidMainScreen` is the armor + weapons page (the
default page you land on when right-clicking a subordinate with an
empty hand — 4 armor slots, 2 weapon slots), and
`HumanoidInventoryScreen` is the chest-overflow paged view reached
by the nav arrows. The trade tab anchors to **`HumanoidMainScreen`**
so it's always visible on the page the player opens to and never
clutters the chest-scroll views. Cleanest non-mixin path: hook
NeoForge's screen-init event, check `instanceof HumanoidMainScreen`,
reflect the private `humanoid` field once (cached `Field` after first
success), and add a vanilla `Button` widget via `event.addListener`.
No page-index guard is needed — `HumanoidMainScreen` has no pages.
Click sends a C2S payload with the entity id; server resolves and
calls `merchant.openTradingScreen(...)` which is the vanilla
`Merchant` interface default method. No Tensura mixin, no menu
rewriting, no subclassing. If Tensura ever renames the `humanoid`
field, the reflection logs once and disables itself — the button
vanishes but the inventory screen continues to work.

**Envoy dialogue Stage J2 — condition-dependent text via modular
snippet composition.** Dialogue is no longer a flat per-race string;
it's a per-race BASE plus zero-or-more per-condition snippets joined
by a space. New `EnvoyCondition` enum (7 values, one byte bitmask)
captures the SET of unlock alternatives satisfied at the moment the
envoy spawned. The capture happens in `captureMetConditions` — same
per-race branching as `isEnvoyEligible` but each alternative is
recorded as its own enum value, since multiple may be true at once
(e.g. dwarf envoy for a ≥30-citizens colony whose owner is also a
true demon lord captures `{COUNT, TRUE_DEMON_LORD}`). The mask is
persisted on `EnvoyTag.conditionMask`, threaded through
`OpenEnvoyDialoguePayload` to the client, and `EnvoyDialogueScreen`
asks `EnvoyDialogue.body(member, conditions)` for the composed body.

**No combinatorial explosion.** Composition is strictly
`base + " " + snippet(memberA, condX) + " " + snippet(memberA, condY)`.
Each snippet is a complete self-contained sentence in the race's
voice — readable in any combination, no inter-snippet references.
Unsupported `(member, condition)` pairs (e.g. COLONIST + IFRIT)
return `null` and are skipped silently. Iteration order is the
`EnvoyCondition` enum-declaration order (COUNT, TIMER, IFRIT,
ORC_DISASTER, DWARVEN_VILLAGE, TRUE_DEMON_LORD, TRUE_HERO) which is
stable across runs and groups related observations.

**Count/timer snippets included, not skipped.** The early decision
to write race-voiced snippets for the COUNT and TIMER alternatives
(rather than treating them as "plain" / no-snippet) keeps the
single-condition early-game dialogue from feeling flat — a colony
that hits the ≥25 orc threshold gets a race-voiced acknowledgement
(post canon-voice rewrite, the orc's dutiful *"Your settlement has
grown great and strong — a place … where many hands like ours could
do honest work."*) instead of base-only text.

**Canon-voice rewrite of envoy dialogue (latest).** The goblin / orc /
lizardman / dwarf envoy copy (greetings, condition snippets, accept /
decline lines) was reworded to match each race's "That Time I Got
Reincarnated as a Slime" voice, with envoys as generic race
representatives revering the player as a powerful protector-ruler:
goblin humble-eager-grateful, orc dutiful-solemn (with a note of
atonement — orcs were starving and leaderless before being given
purpose), lizardman proud-formal-but-sincere, dwarf
gruff-hearty-craftsman. Constraint honoured: no invented canon — voices
came only from supplied race profiles, and functional references that
convey unlock conditions (Orc Disaster, Ifrit, true demon lord / true
hero, dwarven village, colony size / age, "twenty days") were kept
verbatim so the snippets still communicate the same conditions. Invented
org / character names (Elder, Marsh-Tribe, Dwarven Holds, council,
chroniclers) were removed; neutral reverent address ("great one") used
in place of gendered forms. COLONIST left unchanged (not a Tensura race;
no profile supplied). Function of every line preserved exactly —
wording / voice only.

**Dialog panel grows with body.** Multi-condition dwarf envoys can
push the wrapped-line count past the original 220px floor.
`EnvoyDialogueScreen` now computes `dialogHeight =
max(220, CHROME_TOP + lines × 12 + CHROME_BOTTOM)` clamped to
`this.height − 20`, so 5-condition dwarf dialogues still fit.

**Backward compat.** `EnvoyTag.conditionMask` reads as `0` for
legacy envoys saved before Stage J2. The composer treats `0` as
"no captured conditions, fall back to base-only" — old envoys still
talk, they just don't reference their unlock condition.

**Accept/Decline mechanics untouched.** Only the body text path
changed. The accept/decline payloads, server-side validation, and
the accept-locks-the-race semantics in
`ColonyRaceConfigSavedData.acceptedEnvoys` all stay as they were.

**Accept / decline TEXT is condition-aware too — DWARF + TRUE_HERO /
TRUE_DEMON_LORD only.** A dwarven envoy that came specifically
because the player bears the hero or demon-lord title parts with a
title-acknowledging line on both accept and decline — the title
materially changes the social register of the parting moment, and
the standard race line undersells it. Other races stay flat: orc
disaster / ifrit are already woven into the greeting, and the
count/timer alternatives don't warrant a parting-line variant.
HERO takes precedence over DEMON_LORD when both are captured (the
hero frame is narratively the more "honoured" reading; demon-lord
acknowledgements lean weighted/reverent rather than honoured). The
existing no-condition overloads are retained for debug / fallback
paths so any caller that doesn't have a condition set still works.

**Deferred-content envoy unlock conditions — Stage 1 (eligibility +
kill-gate, no dialogue yet).** Stages 2 (condition-dependent dialogue
copy) and 3 (per-condition flavour text) are explicitly out of scope.
The seven detection hooks (all confirmed in the prior investigation
turn) wire in as follows:

- **20 in-game days no death** (per-colony, dwarf alternative):
  `LivingDeathEvent` filter on owning `ServerPlayer` →
  `setLastOwnerDeathTick(colonyId, now)`. Eligibility:
  `(now − tick) / 24000 ≥ 20`, using
  `getColonyCreationTick` as fallback when no death is recorded.
  Kill-gate penalty (dwarf kill): anchor moves forward by 10 days,
  capped at `now` (partial reset — 10 days of progress lost, not a
  full re-anchor).
  - **AMENDMENT (2026-06-18) — online-only timer.** The anchor compares
    against `level.getGameTime()`, which keeps advancing on a running
    server even while the owner is offline (their colony chunk may stay
    loaded), so the streak was wrongly counting offline time. Fix:
    `ExampleMod.resetDwarfPeaceTimer(level, owner, reason)` re-bases the
    anchor to `now` on BOTH owner **logout** AND **login**
    (`onPlayerLoggedOut` / `onPlayerLoggedIn`). Logout resets the streak
    the moment the player leaves; login wipes any time that elapsed while
    they were away. Net effect: the 20-day streak only accrues during
    continuous online presence. Reuses the same anchor field as the
    death hook, so it composes cleanly (whichever reset fires last wins).
- **Dwarven village found** (per-player, dwarf alternative): per-tick
  poll inside `runPerPlayerEnvoyPasses` —
  `level.structureManager().getStructureAt(playerPos,
  dwarfVillageStructure).isValid()` flips the `dwarvenVillageEntered`
  flag. Cleared by the dwarf kill-gate.
- **True demon lord / true hero** (per-player, dwarf alternatives):
  live `IExistence.isTrueDemonLord/.isTrueHero()` read via
  `TensuraStorages.getExistenceFrom`. Each path is gated by a
  per-player disable flag (`demonLordPathDisabled`/`heroPathDisabled`)
  that the dwarf kill-gate sets ONLY when the killer currently has
  that status. Cleared by **(a)** `LivingEntityUseItemEvent.Finish`
  on `ResetScrollItem` with `RESET_ALL` (the character-reset path)
  AND **(b)** the scheduler's fallback pass — if the disable flag is
  set but the live status reads false, clear (catches admin commands,
  Tensura reincarnation, etc.).
- **Orc Disaster defeated** (per-player, ORC alternative,
  ONE-TIME-IMMUNE): `LivingDeathEvent` filter on
  `OrcDisasterEntity`; killer attribution via
  `event.getSource().getEntity()` then fallback
  `victim.getKillCredit()`. Sets `orcDisasterDefeated` permanently.
  No removal path anywhere — orc kills don't reset it; scrolls
  don't reset it; admin reset doesn't reset it.
- **Ifrit defeated** (per-player, LIZARDMAN alternative, repeatable):
  same `LivingDeathEvent` shape on `IfritEntity`. Cleared by the
  lizardman kill-gate (Ifrit is repeatable — both `shizu_spawn_egg`
  and `ifrit_spawn_egg` exist, multiple encounters possible).

Three-tier kill-gate routing (dwarf-kill only; orc/lizardman keep
their existing snapshot reset plus the new boss-flag clear for
lizardman):

| Condition shape | Behaviour on kin-kill |
|---|---|
| COUNT/TIMER (citizens, 20-day) | reset/penalize (20-day → −10 days, capped at now) |
| ONE-TIME (Orc Disaster) | IMMUNE — flag is permanent |
| CURRENT-STATE (demon lord, hero) | DISABLE-until-character-reset per-player |

Storage: extended `ColonyRaceConfigSavedData` with one new per-colony
map (`lastOwnerDeathTick`) and five `Set<UUID>` per-player flag sets
(`dwarvenVillageEntered`, `orcDisasterDefeated`, `ifritDefeated`,
`demonLordPathDisabled`, `heroPathDisabled`). NBT-serialised through
a shared `encodeUuidSet/decodeUuidSet` pair. Backward-compat:
missing keys load as empty sets / unset death tick — legacy saves
behave identically to pre-Stage-1.

**Dwarf facial-hair overlay needs `HumanoidModel`, not `PlayerModel`.**
Every other dwarf overlay (Face, Hair, HairBody, Top, Bottom, Feet) is
baked from `PlayerModel.createMesh(deformation, false)` — verified by
disassembling Tensura's `DwarfLayer.<clinit>`. But `FACIAL_HAIR_LAYER`
is the lone outlier: built from `HumanoidModel.createMesh(deformation,
0F)`. Wrapping it in our generic `DwarfOverlayLayer` (which always
constructs a `PlayerModel<>(bakedRoot, false)`) throws during bake
because PlayerModel's constructor reaches for slim-arm / cloak / ear
children that the HumanoidModel-shaped mesh doesn't provide. The
overlay then falls back to `null`-model + early-return, so the
beard silently never renders — visible to the user as "the
subordinate's facial hair vanishes when sent to the colony."
Fix: dedicated `DwarfFacialHairLayer` that wraps the baked part in a
vanilla `HumanoidModel`, matching Tensura's own
`DwarfLayer.FacialHair.<init>`. Male-only gate retained (mirrors
Tensura's `FACIAL_HAIR == -1` early-return for unset females).

**Subordinate send-to-colony — roster only, no sneak-right-click.**
Earlier, sneak-right-clicking a named subordinate with an empty hand
routed through `handleMenuAction` to send them home. Removed by
request — the roster menu (G keybind → click entry, or bulk-send)
is now the only path. Same `handleMenuAction` chokepoint; just one
entry point instead of two. The envoy right-click branch in the
same handler stays — it short-circuits BEFORE the (removed) sneak
gate, so envoy dialogues still open.

**Subordinate trades — view 24h, restock at dawn.** Tensura's
`wantsToTrade()` returns false while a merchant is sleeping or
not-working — at night, that gate blocked the trade screen from
opening. Removed from our `handleOpenSubordinateTrade` so the
screen is reachable at any hour; live / alive / not-baby checks
stay. To match the "no overnight stock refresh" semantics, a
once-per-server-tick `tickDawnRestock` watches each dimension's
`level.getDayTime() / 24000`; when the day-number rolls over, it
walks every `SUBORDINATE`-mode race identity and calls
`merchant.restock()` on the live entity if it's a
`TensuraMerchantEntity`. First sighting of a dimension after server
start anchors `lastDay` without firing so login doesn't double-
restock. Tensura's own `restock()` already handles the per-entity
counter / cooldown internals — we just call it.

**Dwarf citizen scale — per-citizen captured from the wild dwarf,
not hardcoded.** Tensura's `DwarfEntity.finalizeSpawn` randomises
`Attributes.SCALE` per-spawn: royal-guard = 1.0, others =
`0.7 + rand³ × 0.3` (range ~0.7–1.0, biased low). A hardcoded
renderer scale would erase that variation, so each named dwarf
would render at the same size regardless of the wild dwarf it was
named from. Fix: capture the source dwarf's `Attributes.SCALE` at
`captureDwarfVariant` time, extend `DwarfVariantData` to 29 bytes
with a trailing `float scale` field (25-byte legacy payloads decode
with `scale = 0.9375f`, the `PlayerLikeRenderer` median), and have
`DwarfCitizenRenderer.scale()` multiply the captured value by
Tensura's `PlayerLikeRenderer` base of `0.9375f`. Final per-citizen
render scale ≈ `(0.7..1.0) × 0.9375 ≈ (0.656..0.9375)`. Supersedes
the earlier Wrinkle-2 SCALE-attribute approach (which was abandoned
when the `-1.5` Y translate after `scale()` proved to break
positioning for non-1.0 values); the scale now lives entirely
client-side on the renderer and the citizen entity's own SCALE
attribute is unaffected, so the hitbox stays full citizen size.

**Why naming doesn't lose merchant state.** `TensuraMerchantEntity`
already round-trips Profession / MerchantLevel / Offers / Xp / Gossips
through `addAdditionalSaveData` / `readAdditionalSaveData`. Naming sets
the tame flag but doesn't clear any of those. The only thing naming
changes is Tensura's `handleCommanding` interaction-routing: owned +
tame entities are sent to the inventory screen instead of the
trading screen. The trade-tab button sidesteps that branch by calling
the merchant flow directly. Restocking, level-ups, and gossip updates
continue to fire from `customServerAiStep` which runs regardless of
tame state.

**Trade button moved from subordinate to citizen, with transient
merchant.** Stage I4's subordinate-side trade-tab `ScreenEvent.Init.Post`
overlay on `HumanoidMainScreen` is no longer registered;
`SubordinateTradeButtonHandler` stays in the source tree as dormant
reference. Trade now opens from a button on MineColonies'
`MainWindowCitizen` (the citizen info window opened by right-clicking
a colonist). Three things forced a specific implementation shape:

1. **BlockUI's `BOScreen` doesn't extend the vanilla `Screen` render
   path.** Its `render` method does NOT call `super.render`, so
   vanilla widgets added via `event.addListener(button)` get
   registered but never drawn. `mouseClicked` also forwards directly
   to the `BOWindow` without consulting the screen's children list,
   so the same widgets receive no input either. Confirmed by
   disassembling BOScreen — there is no `super.` call and no
   children iteration in either method.
2. **BlockUI's own `ButtonImage` (which DOES participate in the
   BOWindow render tree) gets clipped if placed outside the parent
   window's interior bounds.** `View.childIsVisible` (disassembled)
   skips any child whose `x >= parent.interiorWidth` or
   `y >= parent.interiorHeight`. So we can't use a BlockUI widget
   to render off-window either.
3. **`boScreen.width` is unreliable on the BlockUI render path.**
   BOScreen installs a framebuffer-pixel projection matrix during
   its draw (saved and restored at the end), so vanilla Screen
   width/height read during render gave odd results — using
   `mc.getWindow().getGuiScaledWidth()` directly is the authoritative
   value the vanilla render pipeline actually uses.

Result (original): trade button was a vanilla `Button` drawn as an
overlay via `ScreenEvent.Render.Post` (fires after BOScreen finishes) at
a position computed from `mc.getWindow().getGuiScaledWidth()`. Clicks
were intercepted via `ScreenEvent.MouseButtonPressed.Pre` with a
manual bounds check; on a hit, the click is dispatched and the
event is canceled so BOScreen never sees it. Per-screen state held
in a `WeakHashMap<BOScreen, ScreenState>` so closed screens evict
automatically.

**SUPERSEDED — trade button is now a native BlockUI tab.** The three
blockers above all concern OFF-window placement (`childIsVisible`
clipping) or VANILLA widgets (BOScreen not rendering/routing them).
Neither applies to a BlockUI `ButtonImage` added INSIDE the window via
`View.addChild` — it renders and receives clicks through BlockUI's own
pipeline, exactly like MC's native tabs. So the overlay (Render.Post +
MouseButtonPressed.Pre + the WeakHashMap/ScreenState/`getGuiScaledWidth`
machinery) was removed and replaced with a real tab in the citizen
window's left tab strip:

- The single `ScreenEvent.Init.Post` hook now targets
  `AbstractWindowCitizen` (not just `MainWindowCitizen`), so the tab is
  present on every citizen sub-page (main/requests/inventory/happiness/
  family/job). The base class has no public `getCitizen()` — only
  `MainWindowCitizen` does — so the shared protected `citizen` field is
  read reflectively (cached `Field`), same reflection tolerance as the
  old subordinate trade button.
- The tab is a `ButtonImage` reusing MC's own
  `minecolonies:textures/gui/modules/tab_left_side3.png` (32×26 at x=0)
  with a 20×20 icon `ButtonImage` layered at `(5, tabY+3)` — the exact
  pair-and-offset MC's `citizen/nav.xml` uses. The icon is a shipped
  asset `tensura_minecolonies:textures/gui/modules/trade.png` (a simple
  placeholder exchange glyph; swap for final art later).
- Slot: the first free Y at/after `familyTab` (144) — `jobTab` (170) and
  `debugTab` (196) set their visibility in the `AbstractWindowCitizen`
  constructor (before `Init.Post`), so the handler reads `isVisible()`
  and picks 170 → 196 → 222 to avoid overlap.
- Routing is identical to MC's own tabs: `setHandler(window)` +
  `window.registerButton("tm_tradeTab"/"tm_tradeIcon", runnable)`, where
  the runnable fires the UNCHANGED `OpenCitizenTradePayload`. A
  `findPaneByID(TAB_ID)` re-init guard prevents double-adds.
- Eligibility unchanged: only GOBLIN / LIZARDMAN / DWARF race citizens
  (RaceTag present, not ORC) get the tab.

Net: pixel-matches MC's tabs, drops all the projection-matrix /
manual-hit-test fragility, and the server-side trade flow is untouched.

## Race picker — rebuilt as a native BlockUI window (first standalone window)

**`RacePickerScreen` (vanilla `Screen`) replaced by `WindowRacePicker
extends AbstractWindowSkeleton`.** This is the first of our *own* screens
converted to a genuine MineColonies-style BlockUI window (the trade tab
only injected children into MC's existing citizen window; this builds a
fresh window from our own XML). It validates the standalone-window
pattern before the heavier roster rebuild. Layout in
`assets/tensura_minecolonies/gui/windowracepicker.xml`:
`builder_paper_wide2.png` (400×244) content panel + two
`builder_button_large.png` image buttons + black-on-paper `<text>` panes
— same copy as the old screen. The colony-name subtitle is the only
dynamic text, set in `onOpened` via `findPaneOfTypeByID("subtitle",
Text.class)`.

**Parent stacking uses `AbstractWindowSkeleton`'s `parent` field + plain
`.open()`, NOT `openAsLayer()`.** Decompiling confirmed
`AbstractWindowSkeleton.close()` does `super.close()` (BlockUI's
`popGuiLayer`) **then `parent.open()`** — so passing the town-hall
`BOWindow` as parent makes both a race pick and ESC return to the town
hall, reproducing the old screen's manual `setScreen(parent)` behaviour.
ESC routes there because BlockUI's `onUnhandledKeyTyped` calls the
virtual `close()` on key 256. The town-hall UI is itself a `BOScreen`, so
the parent is grabbed as `((BOScreen) mc.screen).getWindow()`; when
there's no BlockUI screen current (rare — the 1-tick defer normally lets
MC's town-hall UI install first) the parent is null and the window simply
closes to the game, matching the old no-parent path. `openAsLayer()` +
`close()` (push/pop layer) is the *other* coherent BlockUI idiom but
would double up against the skeleton's `parent.open()`, so we use the
skeleton's built-in mechanism, which is what every MC sub-window does.

**Open trigger and payload unchanged.** `OpenRacePickerPayload` →
`RacePickerClientHandler` still defers one client tick, then calls
`WindowRacePicker.tryOpen(...)`; the two buttons still fire the unchanged
`Networking.RaceChoicePayload` (same `colonyId` / choice bytes). Only the
presentation changed.

**Fail-closed, vanilla screen retained.** `RacePickerClientHandler` opens
the BlockUI window inside a `try/catch (Throwable)`; on any failure
(missing BlockUI/MC class — caught as the lazily-resolved
`WindowRacePicker` reference links —, XML parse error, etc.) it falls
back to `mc.setScreen(new RacePickerScreen(parent, …))`. The vanilla
`RacePickerScreen` stays in the source tree purely as that safety net.
MC texture `ResourceLocation`s are centralized as constants on
`WindowRacePicker` (fragility mitigation — the XML references the same MC
asset paths, so a future MC rename surfaces in one place).

## Roster restyled to the flat cream mockup (2026-06-09)

**`WindowRoster` rebuilt to the flat paper mockup** (vanilla `RosterScreen` kept
as fail-closed fallback). Window **400×260**; compressed **24px row-card pitch**
(card 21 + 3 gap → ~6 rows visible, scroll for more — the `ScrollingList`/
`ScrollingView` `Scrollbar` renders on overflow, wheel + drag, natively).

Layout (`windowroster.xml`): title "Citizen Roster" + "[colony name]" subtitle +
divider; a magicule **badge** (`<box>` border + purple-diamond placeholder +
count) with a **slime** peeking over the top edge; a full-width search field with
a toggled "Search citizens…" hint; a `ScrollingList` of bordered row-cards
(`<box>` + name + "EP …" + status **pill** + Summon/Send button); footer
"N citizens" / "N at your side" counts.

**Key BlockUI gotcha — XML `color` is unreliable.** The XML `color` attr parses
only named colours cleanly; hex needs `#`+UPPERCASE and 6-digit → alpha 0
(invisible), 8-digit → `parseInt` overflow. So **every custom colour is set in
Java** (`Box.setColor(r,g,b)`, `Text.setColors(argb)`), and the flat green/blue
pills + green/tan buttons are **placeholder PNG textures** (`textures/gui/roster/`)
swapped per row via `Image.setImage`/`ButtonImage.setImage` — no gradient colour
attrs. `<box>` (border outline, recoloured at runtime) draws the card/badge/
search/divider borders.

**Decisions (confirmed with the user):**
- Per-row stat labelled **EP** (not the mockup's "CP") — same underlying value.
- **Bulk kept visible**: click a row to toggle, **press-and-drag to paint**
  select/deselect (deselect mode when the anchor row is already selected) —
  `click()`/`onMouseDrag()`/`onMouseReleased()` paint model; the row's `sel`
  image is a non-clickable indicator. Mode-locked; reuses `BulkSummon`/`BulkSend`.
  A Group Summon/Send bar appears when ≥2 are selected (only the button matching
  the selection's mode shows) and shares the footer band with the counts (counts
  hide while selecting).
- Action semantics kept: In colony → Summon (back); At side → Send.
- Colony name added as one `String` on `RosterResponsePayload` (primary owned
  colony via `getIColonyByOwner`). Magicule + EP + counts already available.

Placeholder textures (to be replaced by final art): `roster/pill_green.png`,
`pill_blue.png`, `btn_green.png`, `btn_tan.png`, `diamond.png`, `slime.png`.
UNTESTED in-game — flag for playtest (G opens it; vanilla fallback intact).

## Citizen roster — rebuilt as a native BlockUI window (validates the list path)

**`RosterScreen` (vanilla `Screen`) replaced by `WindowRoster extends
AbstractWindowSkeleton`.** Second screen converted (after the picker), and
the one that exercises BlockUI's *interactive list* support. Layout in
`assets/tensura_minecolonies/gui/windowroster.xml`: `builder_paper_wide2.png`
panel, a native `<input>` search field, a BlockUI `<list>` (`ScrollingList`),
and MC image buttons. The list/search/sort logic ported cleanly — exactly the
investigation's prediction.

**Roster list → `ScrollingList` with a `DataProvider`.** `getElementCount()`
returns the filtered+sorted `displayed` size; `updateElement(i, rowPane)`
binds each row's panes (name / EP / colored status text, the selection-toggle
image, the action-button label). Same primitive MC uses for every list
(modelled on `WindowHireWorker`). Search is the native `<input>` with a
`setHandler(InputHandler)` change listener; the substring filter + EP-desc
sort are the unchanged helpers from the old screen.

**Per-row buttons share one id across rows; the row is resolved by
`ScrollingList.getListElementIndexByPane(button)`.** The row template's
`act`/`sel` buttons carry fixed ids; a click with no explicit handler bubbles
up the parent chain to the window (`AbstractWindowSkeleton implements
ButtonHandler`), which dispatches by id. The handler asks the list which row
the clicked pane belongs to. This is MC's own idiom (verified by decompiling
`WindowHireWorker` + `Button.handleClick`) and avoids re-wiring per-row
handlers on every refresh.

**Bulk selection: native checkbox toggles AND click-and-drag.** Each row has a
toggle that swaps `builder_button_mini.png` ↔ `builder_button_mini_check.png`
(MC's own checkbox texture pair). The single-identity action
(`ActOnIdentityPayload`) is an explicit per-row Summon/Send button instead of a
bare row click. The selection set, the per-batch mode lock (first selected
row's mode), the 9-cap, and the `BulkSummonPayload` / `BulkSendPayload` they
fire are all unchanged.

The continuous **click-and-drag-through-rows** gesture from the old screen is
also reproduced (it turned out to port cleanly after all): `WindowRoster`
overrides `onMouseDrag`, which BlockUI forwards from `BOScreen` with
window-relative coords. It maps the point to a row via the list's `getScrollY()`
offset and the 22px row pitch (the row-template height; `childspacing` is 0),
then adds every row the drag passes over. Two facts make this clean: (1)
`super.onMouseDrag` is called first and the `Scrollbar` is the only child that
*consumes* a drag (returns true), so scrollbar drags still scroll while
list-body drags fall through to selection — no need to compute the scrollbar's
width; (2) `Button`/`Text`/`View` don't override `onMouseDrag` (Pane default
returns false), so the *entire* row rectangle — name, EP, status, and the two
buttons — is a drag-select surface. `tryAddToSelection` returns whether it
actually changed the selection, so the list + footer refresh only on a real
add, not every drag pixel.

**Live refresh routed through a static instance, not `mc.screen instanceof`.**
The server pushes a fresh roster after every action. `WindowRoster.route`
holds a static reference to the open window and refreshes it in place,
covering three cases that the old `instanceof RosterScreen` chain handled: (a)
the roster is the active screen; (b) a `ConfirmCollapseScreen` (still vanilla)
is layered over it on the magicule-overspend path — its `getParent()` is the
roster's `BOScreen`, so we update the window's data *without* reopening, and
it shows on dialog dismissal; (c) nothing open → open fresh. The instance is
deliberately **not** cleared in `onClosed()` (the confirm-dialog layering
fires `onClosed`, and we still need the window to update behind it); a stale
reference is harmless because `route` only refreshes while `mc.screen`
actually matches its `BOScreen`. Search text and selection survive refresh.

**No parent → closes to game on ESC, like MC's own citizen window.** Unlike
the picker (which stacks on the town hall), the roster opens from the `G`
keybind in-game with no current screen, so it's a no-parent `.open()` window —
the *same shape as MineColonies' right-click citizen window*, which closes to
game on ESC. So `AbstractWindowSkeleton.close()` (with null parent) closing to
game is the proven behaviour, not a risk.

**Fail-closed, vanilla screen retained.** `route` opens the BlockUI window in
a `try/catch` and falls back to `mc.setScreen(new RosterScreen(entries))`;
`ClientRosterHandler` is now a one-line delegate to `route`. The vanilla
`RosterScreen` stays in the tree as the safety net (its own
`ConfirmCollapseScreen`-parent refresh branch is kept in `route` too, so a
fallback session still refreshes correctly). MC texture `ResourceLocation`s
(panel + the two mini toggle textures) are centralized as constants on
`WindowRoster`.

The trade itself runs against a **transient merchant** so the
player no longer has to summon the subordinate back. Server
`handleOpenCitizenTrade`: reconstruct merchant via
`EntityType.create(identity.entitySnapshot, level)`, position it on
the citizen for bookkeeping only (never `addFreshEntity`, never
visible to anyone), `setTradingPlayer(player)`, `openTradingScreen`.
The reconstructed entity satisfies the `Merchant` interface the
MerchantMenu calls into; the menu doesn't reach into the world for
the merchant. Session held in `TRANSIENT_MERCHANTS` keyed by
player UUID. New `@SubscribeEvent onPlayerContainerClose` handler
saves `merchant.save(freshTag)` back to `identity.entitySnapshot`
via `saved.updateEntitySnapshot` and drops the session — so any
uses-count / demand / xp changes during the trade persist. Re-entry
is blocked while a trade is open. Server restart mid-trade loses
the in-flight session but never corrupts the snapshot.

**Dawn restock extended to citizen-form snapshots.** The Stage I4
`tickDawnRestock` loop only walked SUBORDINATE-mode identities and
called `merchant.restock()` on the live entity. After the trade
button moved to citizen-side, every merchant a player actually
interacts with is IN_COLONY mode with `mobEntityUUID == null` —
all of them were silently skipped, so offers drained on use and
never refilled. The fix: a second pass for IN_COLONY identities
that reconstructs a transient merchant via
`EntityType.create(snapshot)`, calls `restock()` (which iterates
offers and calls `resetUses()` on each, per the bytecode), then
`merchant.save(fresh)` → `saved.updateEntitySnapshot`. Skip any
identity whose `identityId` is in `TRANSIENT_MERCHANTS.values()`
to avoid racing the close-event persist hook clobbering a
freshly-restocked snapshot — those identities catch up on the
next dawn. Log line now reports both counts: `... {X} live
subordinate(s), {Y} citizen snapshot(s)`.

**Summon-time skin sync via `applyVariantToMob`.** The wild form's
appearance is restored via Tensura's `readAdditionalSaveData` from
the snapshot NBT (`EntityType.create(snapshot, level)`). In
principle that round-trips cleanly because the snapshot was
captured with `goblin.save(snapshot)` at send time, which calls
the corresponding `addAdditionalSaveData`. In practice the
snapshot can be older than the RaceTag — the snapshot is written
at naming time, refreshed periodically while the subordinate is
loaded, and re-saved on each send (2026-07-13; it used to be
written only at first send), while the
RaceTag is refreshed on every send via `captureRaceVariant`, AND
is the source of truth for what the player has been watching the
citizen wear. Any drift (e.g. a re-send that captures a new
variant but goes through a code path that doesn't immediately
re-save the snapshot) manifested as "summoned mob's skin doesn't
match the citizen." Fix: new `applyVariantToMob(LivingEntity mob,
RaceTag tag)` polymorphic dispatcher in `summonGoblin`, called
immediately after `EntityType.create` succeeds and before
`addFreshEntity`. Per-race apply methods stamp every appearance
field via the entity's public setters (`setGender`, `setSkin`,
`setVariant(byId)`, etc.). The dwarf apply also restores
`Attributes.SCALE` because the dwarf renderer reads that for
size.

**`tensuraMaxNonColonistEnvoys` default 2 → 4.** Allows a player
to encounter all four non-colonist races (goblin / orc / dwarf /
lizardman) at the default setting without raising the cap
manually. Existing worlds keep their stored gamerule value; new
worlds start at 4.

## Harvest Festival — persistent prestige colony buff (rebuilt)

**Three investigation findings (pivotal ones starred):**
- ★ **Skill-past-cap drives productivity (FEASIBLE).** `SkillData.setLevel` does
  not clamp; `CitizenSkillHandler.getLevel` returns the raw level (no re-cap, no
  research add); the productivity formula `0.85^(getPrimarySkillLevel/2)` in
  `AbstractEntityAIInteract` reads that same raw value. So a level **>99 really
  increases productivity**. `addXpToSkill`'s level-up is gated `if (level < 99)`
  and increments `level+1` — it never resets a >99 value, so a baked >99 bonus
  **persists**. We bake the bonus into the level and record the per-citizen,
  per-skill offset in `FestivalSavedData` for a clean prestige reset (subtract
  the offset; correct even if the base grew underneath).
- ★ **Unloaded-chunk changes — split by stat type.** MC **skills** live on
  `ICitizenData` (server-side, exists unloaded; `markDirty` persists) → the
  indirect skill buffs run unloaded. **Tensura EP / the in-place swap** need the
  live entity → not doable unloaded; queued via `FestivalSavedData.queueSwap`
  to run on next colony load.
- **Blue "+X" UI (FEASIBLE).** The citizen window (`MainWindowCitizen`, BlockUI)
  renders each skill number in a `<text>` pane whose id is the lowercase skill
  name; a `ScreenEvent.Init.Post` hook (the trade-tab pattern) can `addChild` a
  blue `+X` sibling. Needs the per-citizen offsets synced to the client.

**Decisions (confirmed with user):** tiers rank ALL citizens by Tensura EP
descending, strongest first (top → +4); vanilla citizens (EP 0) sink to the
untiered "minimal" baseline; unloaded colonies queue the Tensura swap for next
load.

**Tier math:** `tier1Count = round(EP/100k)` below 1M EP, else
`10 + round((EP−1M)/500k)`. T2/T3/T4 counts = 2×/3×/4× T1. Cumulative slots
(by EP rank): T1 +4, T2 +3, T3 +2, T4 +1 (all to the top-3 skills); beyond
10×T1, a minimal +1 to the single top skill. Once per colony.

**Two triggers, two cadences (fixed 2026-06-09).** A demon-lord awakening fires
TWO Tensura events for one festival: `ENTER_HARVEST_FESTIVAL_EVENT` at the start
and `AWAKENING_EVENT` at the completion (~34s later, after the soul countdown).
We hook both because not every awakening path routes through
`enterHarvestFestival`. But the two halves of the buff have different cadences:
- **Skill prestige** (the tiered MC-skill bonus): **once per colony** (`isDone`
  guard). Idempotent, so it can run on whichever event fires first.
- **Tensura EP/stat gift** (the demon-lord aura/magicule ×`epMultiplierDemonLord`
  multiply): **once per SUBORDINATE, ever** — matching base Tensura, where a
  subordinate upgrades only once. Gated on a per-identity `isGifted` flag in
  `FestivalSavedData` (keyed by the stable identity UUID), NOT the per-colony
  `isDone` flag and NOT "every festival". Runs on the completion
  (`AWAKENING_EVENT`) and `/festival run`; the START event applies skill prestige
  only (`HarvestFestival.applyPrestigeOnly`).

Why per-identity and not per-colony: the reported bug was a goblin that joined
the colony AFTER its first festival, so a per-colony `isDone` gate skipped it
forever. Per-identity means a late-joining subordinate still gets its single
upgrade the next festival, and an already-gifted one is never re-gifted no matter
how many festivals fire. A prestige reset clears the per-identity flags (so the
festival is re-earnable); the gift stats themselves are NOT auto-reverted (a
Tensura-side buff, like a permanent evolution), so re-running after a reset
re-applies/compounds them — to verify "once", run `/festival run` twice WITHOUT
a reset between: the second run logs `EP-gifted 0`.

**EP gift applied directly, not via the Tensura helper.**
`RaceHelper.applyHarvestFestivalGift`'s EP-multiply branch is gated behind
`HARVEST_FESTIVAL_REWARD_EVENT.reward().isFalse()`, which vetoes an off-festival
call (observed: EP 7620→7620). `ExistenceStorage` is trivial — `getEP() == aura +
magicule`, `setAura/setMagicule` only cap at ~2.1e9 (no max clamp) — so
`applyFestivalEPGift` multiplies the `MAX_AURA`/`MAX_MAGICULE` attribute bases and
the existence aura/magicule directly by `epMultiplierDemonLord` (default 3.0),
persists the snapshot, and pushes onto the live citizen body.

**Prestige reset = Tensura character reset scroll.** The festival reset (subtract
the tracked skill offsets + clear the once-per-colony flag, so it can be earned
again) is wired into the existing `LivingEntityUseItemEvent.Finish` handler that
already detects a `ResetScrollItem` `RESET_ALL` (the envoy demon-lord/hero-path
clear) — so using a character reset scroll resets the festival on every colony
the player owns, and re-syncs the cleared "+X" to the client. `/festival
run|reset` remain as debug/testing commands. Note: the **Tensura EP gift** is a
one-time Tensura-side buff and is NOT reverted by the reset (only the tracked
MC-skill offsets are).

**What counts as a "prestige" depends on the installed Tensura add-ons.** Base
Tensura's character reset is the `ResetScrollItem` (`RESET_ALL`) we hook here,
but the notion of a "prestige" / character-reset is not fixed — third-party
Tensura add-ons can introduce their own reset/prestige mechanics (different
items, events, or commands). If such an add-on is installed and a different
trigger should reset the festival, add that trigger alongside the
`ResetScrollItem` hook (the reset itself is centralised in
`HarvestFestival.resetColony` + `ExampleMod.sendFestivalBonus`, so wiring a new
trigger is just calling those from the add-on's reset path).

**Swap-track proximity (chosen: B).** Base Tensura's festival gathers
subordinates only within a radius of the awakening player, so swapping a colony's
citizens to subordinates *in place at the colony* only gets them buffed if that
colony is near the player. **Option B (chosen for now):** rely on that proximity
— only colonies near the player when they awaken receive the Tensura EP buff;
all colonies still get the indirect skill buff (saved-data). **Option A (future,
noted for reference):** call Tensura's gift directly on each swapped colony
subordinate (`RaceHelper.applyHarvestFestivalGift` — the EP-multiply found in the
prior investigation, race-independent), which would buff every owned colony
regardless of distance and avoids the multi-tick proximity dependency. Revisit A
if "every colony benefits" becomes desired.

**Status:** indirect-buff core built (`FestivalSavedData`, `HarvestFestival`,
event wiring, commands). The Tensura swap → base-festival → stat-sync → return
track (option B) and the blue "+X" client UI are the remaining pieces.

## Subordinate command — "Patrol Colony Outskirts"

This is the concrete realisation of the "new direction" that replaced
the scrapped beast-guard / guard-tower approach: the mob stays a
Tensura **subordinate** (its own entity, AI, hitbox, pathfinding,
native combat) and receives a standing order through Tensura's
**existing right-click command cycle** — it never becomes a
MineColonies citizen or guard. Full roadmap entry in `roadmap.md`.

**Add PATROL INTO the native cycle via a mixin on `cycleCommands`, so it
activates EXACTLY like the vanilla commands.** The native command system
is `ISubordinate.cycleCommands(Mob, Player)` — a default interface method
cycling FOLLOW → WANDER → STAY via boolean flags
(`isWandering`/`isOrderedToSit`); combat stance is a separate axis
(`cycleBehaviour`: neutral/passive/aggressive/protect).

The first two iterations hooked `PlayerInteractEvent.EntityInteract` to
avoid a mixin (initially a full self-run 4-cycle; then intercepting only
the two PATROL edges and letting the others pass through). Both required
re-deriving Tensura's interaction gating in our handler. The user then
asked for the activation to be *merged with vanilla* — "shift + right
click while looking at the entity, **without needing an empty hand**,"
exactly like Tensura's own command. Reproducing that gating from the
interact event is not feasible: Tensura's `mobInteract` consumes a held
**hipokute / arcane potion** (heal) or **edible item** (`isHealingFood`/
`isFood`, both entity-specific and state-dependent) BEFORE it ever
reaches the command cycle, and the humanoid inventory-vs-command split
keys off `isSecondaryUseActive`. The only way to inherit all of that
exactly is to hook the command path itself.

So PATROL is now inserted by `ISubordinateCommandMixin` — an `@Inject`
at the HEAD of `cycleCommands` (cancellable). `cycleCommands` is reached
ONLY after Tensura's full gating has passed, so PATROL is offered in
precisely the same situations as FOLLOW/WANDER/STAY — same gesture, no
empty-hand requirement, food/potions still take priority, inventory
screen still opens on plain right-click for humanoids. The injector
delegates to `SubordinatePatrol.handlePatrolCycle`, which handles ONLY
the two edges that touch PATROL (STAY → PATROL, PATROL → FOLLOW) and
cancels native for those; for every other edge it returns false and
Tensura's own `cycleCommands` runs FOLLOW → WANDER / WANDER → STAY
unchanged (native behaviour AND native message). Gated to NAMED
subordinates (`hasCustomName`) so unnamed ones keep the vanilla 3-cycle.

This is the one place a mixin is warranted (the "prefer extension"
guidance yields once "match vanilla exactly" is the requirement and the
interaction-event path provably can't). Interface-default-method mixin:
`@Mixin(ISubordinate.class)` with a `private` (Java-21 private interface
method) injector handler — the standard pattern for attaching an
injector to an interface target's default method.

**Message style matched to native: AQUA.** Tensura's `cycleCommands`
emits its command feedback with `Style.withColor(ChatFormatting.AQUA)`
above the hotbar (decompile-confirmed). The two edges we own (PATROL
enter, FOLLOW on patrol-exit) send their messages with the same AQUA
style, so all four commands look identical as the player cycles. The
FOLLOW/WANDER/STAY edges we don't intercept keep native styling for
free.

**Command state is DERIVED, never a separate counter.** The edge taken
is decided from the entity's real `isWandering`/`isOrderedToSit` flags
plus the `PATROL_ORDER` attachment, so there is no state to desync from
Tensura's own cycle. The patrol driver additionally auto-cancels the
order if it ever sees the mob in a state `beginPatrol` didn't leave it
in (not-wandering or ordered-to-sit).

**Patrol movement is brain-native via the `WALK_TARGET` memory, NOT a
vanilla Goal.** All Tensura subordinates use SmartBrainLib brain AI
(`SmartBrainOwner`), so adding a `goalSelector` Goal would not
integrate. Instead the per-entity `EntityTickEvent.Post` driver keeps
the brain's vanilla `WALK_TARGET` memory pointed at the current
outskirts point; the `MoveToWalkTarget` core task every subordinate
has paths the mob there with its native pathfinding. Tensura's idle
wander (`SetRandomWalkTarget`) is an SBL path behaviour that only
starts when `WALK_TARGET` is **absent**, so a continuously-populated
memory suppresses native wandering without touching Tensura — the
driver refills the memory the same tick the brain clears it on arrival,
closing the window in which wander could grab it. This is also fully
entity-agnostic (humanoid, beast, mount all share the memory + core
task), satisfying "available on ANY named subordinate."

**Combat coexists by yielding, not by competing.** Entering PATROL sets
the aggressive stance (`SubordinateHelper.setAggressive`) so the brain's
target sensors acquire hostiles. While `mob.getTarget() != null` the
driver does nothing — the native fight behaviours own movement and
`WALK_TARGET`. When the target clears, the driver resumes patrolling.
We force aggressive on enter (and leave the stance as-is on exit, since
the prior stance isn't recorded) — accepted as the simplest way to
guarantee "fights ANY hostile while patrolling."

**Targeting leash must follow the patroller, not the owner (fixed).**
`ISubordinate.shouldTarget` (decompiled) gates ALL targeting while the
mob is wandering: a candidate is rejected if it's farther than
`EntityConfig.tamedWanderRadius` (default **20**) blocks from
`getWanderPos()`. `SubordinateHelper.setWander` leaves `getWanderPos()`
at the OWNER's position, so a subordinate patrolling far from its owner
could neither proactively acquire hostiles (the `getBehaviour()==2`
aggressive branch is still distance-gated) NOR even retaliate when hit —
it visibly *ignored* nearby always-hostile mobs. Fix: the patrol driver
re-anchors `WANDER_POS` to the mob's own `blockPosition()` (throttled —
it's synced entity data — re-anchoring only after ~8 blocks of drift),
so the 20-block targeting leash follows the patroller and it engages
anything near where it actually is.

**Run-vs-walk animation: clear lingering anger on calm patrol (fixed).**
Tensura's per-entity GeckoLib movement controllers pick the **run**
clip when moving and `isAngry() || isSprinting()`, else **walk**
(verified on `LeechLizardEntity.loopController`). `isSprinting()` is only
set while a mob is *ridden* (`TensuraRideableEntity.tickRidden`), so for
an autonomous patroller the run clip is driven purely by `isAngry()` —
the `NeutralMob` persistent-anger timer, which lingers for seconds after
a fight ends (and was being kept alive indefinitely by the leash bug
above: the mob got hit, became angry, couldn't target back, stayed
angry). That left a calm patroller playing the **run** animation while
the driver moved it at the patrol/walk `WALK_TARGET` speed (1.0) — the
reported mismatch. Fix: in the no-target branch the driver calls
`NeutralMob.stopBeingAngry()`, so peaceful patrol shows **walk** (matching
its speed) while an actual chase — driven by the native fight behaviour,
which re-arms anger instantly on a fresh target — shows **run** at chase
speed. The patrol `WALK_TARGET` speed (1.0) already equals the entity's
native idle-wander modifier, so peaceful-patrol pace matches the walk
clip. Casting is unaffected: cast animations fire from the fight
behaviours, which only run when the mob has a target — exactly when the
driver has already yielded, so it never overrides a cast's movement.

**Targeting veto extended: spare citizens + friendly races, allow only
hostile orcs.** The aggressive stance would otherwise make a patroller
attack other Tensura race mobs and colonists. The existing
`onSubordinateChangeTarget` listener on ManasCore's `LIVING_CHANGE_TARGET`
(the exact gate Tensura routes assist-targeting through — see
`docs/subordinate-citizen-targeting.md`) was widened so a subordinate's
target change is vetoed when the proposed target is:
- any `AbstractEntityCitizen` (was previously scoped to the owner's
  colony; now all citizens — "citizens are not targeted");
- any `GoblinEntity` or `LizardmanEntity` (friendly races, no exception);
- an `OrcEntity` that is **friendly** — `isTame()` (someone's
  subordinate) or `entity.isAlliedTo(orc)`.

A **wild / hostile orc** (untamed, not allied) falls through and may be
targeted, so the patrol still fights hostile orcs while sparing the
player's own orc subordinates — per the request "orcs targeted if
hostile to the player, not when they are not hostile." `OrcLordEntity`
and `OrcDisasterEntity` extend `OrcEntity`, so the same rule covers
them. The veto is global (all subordinate target changes, not just
patrol) since it expresses a general "don't attack allied races /
colonists" rule; it remains an acquisition-time veto (doesn't force-drop
an already-held target).

**Patrol fights only hostiles — `tensura:hostile_monster` tag, not the
`Enemy` interface (fixed: was attacking pigs).** Forcing the aggressive
stance made `ISubordinate.shouldTarget` return true for ANY attackable,
non-allied mob within the leash — including peaceful animals (pigs,
cows). Vanilla's `Enemy` interface can't classify this because Tensura's
own mobs extend `TamableAnimal` (so they're `Animal`, like pigs) and
never implement `Enemy`. The right classifier is Tensura's own
`tensura:hostile_monster` entity-type tag, which lists the genuinely
always-hostile mobs (vanilla zombies/skeletons/creepers/… AND Tensura
beasts like `direwolf`/`knight_spider`/`leech_lizard`/`orc_lord`) but
**not** base goblin/orc/lizardman or peaceful animals. New patrol-only
veto branch (`SubordinatePatrol.isPatrolTargetAllowed`, gated on the mob
carrying `PATROL_ORDER`): a target is allowed only if it's in
`hostile_monster` **or** is currently attacking the patroller / one of
its allies / a colony citizen (so a normally-neutral wild orc that
turned on the colony is still fought). The tag is built from its
`ResourceLocation` rather than Tensura's constant, so we're decoupled
from field renames and pick up datapack additions. The driver also drops
an already-held invalid target (a pig that slipped through, or a target
set off-event) rather than only vetoing acquisition.

**Colony tether — patrol the outskirts, don't chase mobs off into the
distance (fixed).** Two complementary limits keep a patroller defending
its colony instead of chain-aggroing outward:
- *Acquisition:* `isPatrolTargetAllowed` also requires the target to be
  within the colony claim + a small buffer (`TARGET_AREA_BUFFER` = 8
  blocks), so it never locks onto mobs well outside the colony.
- *Recall:* each tick the driver checks the mob's own position; if a
  chase has carried it beyond the claim + a larger buffer
  (`STRAY_RECALL_BUFFER` = 24 blocks), it `removeTarget`s and sets
  `WALK_TARGET` back to the nearest outskirts point
  (`outskirtsReturnTarget` — the outer-band point on the bearing from the
  colony centre toward the mob, so it returns to the edge it left rather
  than the centre). The combat yield also drops a target that has fled
  past the recall buffer.

Membership uses `colony.isCoordInColony` (colonies claim whole chunks)
plus a toward-centre shift for the buffer, so no hardcoded radius. This
sits on top of the existing leash-follows-the-mob anchor: the leash lets
it *detect* nearby hostiles, the tether stops it *committing* to or
chasing distant ones. The outskirts targeting itself (outer 70–95% band)
already keeps it off the colony centre.

**Outskirts = outer ring of the claimed chunks, found by marching, NOT
a hardcoded radius.** Colonies claim whole chunks (membership =
`colony.isCoordInColony(level, pos)`), so from `colony.getCenter()` the
driver marches outward along a random bearing in one-chunk (16-block)
steps while membership holds — finding the real claimed boundary in
that direction — then places the patrol point in the outer 70–95% band
of that distance. This self-adapts to irregular claims and any colony
size, with a 16-chunk search cap (256 blocks) bounding the march.
Reading MineColonies' `maxColonySize` config was rejected: its
`getConfig()` returns a BlockUI `Configurations` type that's
runtime-only (not on the compile classpath), and the march makes the
exact radius unnecessary anyway. Candidate points are snapped to the
surface (`MOTION_BLOCKING_NO_LEAVES` heightmap) and rejected if the
surface or the block under it is water, so the patrol never wades in;
several bearings are tried so a water edge in one direction doesn't
strand the mob.

**Order pinned to the colony nearest the PLAYER at issue time, stored on
the entity.** `beginPatrol` resolves
`IColonyManager.getInstance().getClosestColony(playerLevel, playerPos)`
and stores `colony.getID()` + dimension in the `PatrolOrder`
attachment, so the subordinate keeps patrolling that specific colony
even after the player walks away or to another colony. The attachment
is NBT-serialised (same mechanism as `RaceTag` / `EnvoyTag`), so the
standing order survives unload/reload and relog — combined with Tensura
persisting its own `isWandering`/`behaviour` flags, `EntityTickEvent`
simply resumes the patrol when the mob reloads. No new save data of our
own; no client/networking changes (server-authoritative movement
replicates normally).

## Citizen merchant professions (Feature C)

**Citizen-form merchants gain/lose a villager profession from a nearby
job-site block, reproducing the subordinate behaviour server-side.**
Tensura merchants normally gain a profession only as a LIVE subordinate:
the brain behaviour `AssignProfession` claims a nearby job-site POI and
calls `setProfession`, and `getOffers()` then lazily generates that
profession's trades. A colony citizen is an `EntityCitizen` with no
merchant brain, so its merchant snapshot stays jobless and tradeless.
A new throttled server pass (`tickCitizenProfessions`, every 60 ticks
from `onServerTickPost`) reproduces the vanilla job-site mechanic for
IN_COLONY merchant identities (GOBLIN / LIZARDMAN / DWARF, not orc):

- **Gain:** jobless (`Profession == minecraft:none`) and never traded
  (`Xp == 0`) → if a job-site POI is within 16 blocks of the live citizen,
  take the matching `VillagerProfession` and generate its trades.
- **Lose (before first trade):** has a profession but `Xp == 0` → if no
  matching job-site POI remains near, revert to NONE and drop the trades.
- **Keep (after first trade):** `Xp > 0` → locked; never revert (the
  vanilla "a villager with XP keeps its job" rule). First-trade XP is
  already persisted by the trade close hook.

**Cheap steady state, reconstruct only on transitions.** The pass reads
`Profession`/`Xp` straight off the snapshot NBT and does one POI scan
(`PoiManager.findClosest*`); it only reconstructs the transient merchant
(`EntityType.create`) when it actually changes the profession. POI→
profession mapping uses each `VillagerProfession.heldJobSite()` predicate
(the same one `AssignProfession` uses), so butcher↔smoker etc. come for
free from vanilla. Generating trades for a freshly-set profession needs
the protected `updateTrades()` (getOffers only auto-generates when offers
is null), invoked reflectively after `setProfession` + `setMerchantLevel(1)`
+ `setOffers(new MerchantOffers())`.

**No exclusivity / no pathing.** Per the request, "near" is enough — we
don't claim the POI or path to it, so several merchant citizens can share
one workstation. Only runs when the citizen is loaded (so an unloaded
citizen can't falsely revert from an empty scan), and skips identities
that are mid-trade (same guard as the dawn restock pass). No new persisted
state: profession + XP already live in the merchant snapshot, and "is the
block still there" is answered by re-scanning rather than storing the
claimed position. (The matching profession-clothes RENDER on the citizen
is Feature B.)

## Citizen merchant profession render parity (Feature B)

**A dwarf citizen with a villager profession renders the matching
profession clothes, like its subordinate form.** Tensura's subordinate
dwarf shows profession-specific clothes via `ProfessionClothesLayer`
(a `RenderLayer<TensuraMerchantEntity, HumanoidModel>` keyed off
`getProfession()`, textures at `tensura:textures/entity/dwarf/profession/
{name}.png`). Our `DwarfCitizenRenderer` previously omitted it. Feature B
adds the parity:

- **Profession threads through the `RaceTag`** as a stable registry-name
  string (e.g. `"minecraft:butcher"`; `""` = jobless) — chosen over a byte
  id in the variant record so it survives mod/registry-id churn. `RaceTag`
  gained a 4th field with a 3-arg back-compat constructor (every existing
  call site keeps working) and a `withProfession` copy helper; the NBT
  serializer and `SyncRaceTagPayload` carry it (absent → `""`, so legacy
  tags/saves are fine).
- **Server sets it** at send time (`merchantProfessionId(goblin)` reads the
  subordinate's `VillagerProfession`) and whenever Feature C changes a
  citizen's profession (`applyCitizenProfession` rewrites the live citizen's
  tag and re-broadcasts), so the render updates immediately and persists.
- **Render** is a new `DwarfProfessionLayer` mirroring `DwarfFacialHairLayer`
  (Tensura's clothes model is a `HumanoidModel`, not `PlayerModel`, so it's
  baked from `ProfessionClothesLayer.CLOTHES` and wrapped in a vanilla
  `HumanoidModel`). It reads the profession from `RaceTagClientStore`,
  resolves the dwarf texture by registry-path name (gated to the set Tensura
  actually ships, so unmapped professions / jobless citizens render nothing
  — matching Tensura's EMPTY fallback), and draws with
  `RenderType.entityTranslucent`. Reuses Tensura's own textures (no new
  assets). Dwarf-only — Tensura only renders profession clothes on dwarves.

## Citizen merchant: leveling + block-anchored stability (Feature C refinements)

Two corrections after testing the first Feature C cut.

**Trade level-ups now apply citizen-side.** A merchant only levels up (and
thereby unlocks the next trade tier) inside `customServerAiStep`
(`while shouldIncreaseLevel(): increaseMerchantCareer()`), which the citizen
form never runs — so citizen merchants accrued trade XP but never gained
higher-tier trades. Fix: the trade close hook (`onPlayerContainerClose`) now
calls `applyPendingMerchantLevelUps` on the just-traded merchant before
persisting — a reflective `while shouldIncreaseLevel() (capped at vanilla
level 5): increaseMerchantCareer()`. `increaseMerchantCareer` raises the
level and APPENDS the new tier's trades (existing offers preserved), so the
next time the player opens the trade the new trades are there. (Leveling
applies at close, i.e. visible on reopen, rather than live mid-session —
the transient citizen merchant is never ticked, and ticking an off-world
entity is the documented hard-no.)

**Profession/trades are anchored to the claimed block, not the citizen's
position (fixes trades regenerating).** The first cut re-scanned for a job
site around the citizen every pass and reverted when none was near — but
MineColonies citizens wander, so a merchant would repeatedly drift out of
range, revert, drift back, and **re-roll new random trades**. Now the
claimed job-site `BlockPos` is stored on the `RaceIdentity` (`jobSitePos`,
NBT-persisted; the class already had mutable fields, so no constructor
churn) and the rule is purely block-tied, per the request:

- **Gain** (jobless, unanchored): a nearby job-site block → take its
  profession, generate trades ONCE, and anchor to that block.
- **Keep** (anchored): while the anchored block is still a job site, do
  nothing — trades never regenerate, regardless of the citizen wandering or
  trading. (Supersedes the earlier "lock after first trade" rule.)
- **Lose** (anchored): the anchored block is no longer a job site (broken) →
  drop the profession + trades, clear the anchor. Re-checked only when the
  block's chunk is loaded, so an unloaded anchor never false-reverts.
- **Regenerate**: after a loss the merchant is jobless again, so placing a
  job block re-runs Gain — fresh level-1 trades, like normal. (Levels earned
  before the break are not preserved — block break is a full reset, matching
  "lose the trades … regenerate like normal".)
- A profession captured from the subordinate form (no anchor yet) keeps its
  trades and is opportunistically anchored to a matching nearby block.

Dawn restock is unaffected — it only resets uses on existing offers and
never touches the profession or the anchor.

## CORRECTION — citizen merchant professions are COSMETIC ONLY (trades are intrinsic)

The two Feature-C sections above assumed a merchant's trades come from its
villager profession (vanilla-villager model). They do NOT. Decompiling the
three merchant races shows **`GoblinEntity`, `LizardmanEntity`, and
`DwarfEntity` each override `getPossibleTrades()` with their own intrinsic
trade tables, independent of any villager profession** — `DwarfEntity` never
even reads `getProfession`. Tensura's own profession assignment (the brain's
`AssignProfession`) only calls `setProfession`; it never `updateTrades`.

Consequence: Feature C's `setProfession + setMerchantLevel(1) + setOffers(new)
+ updateTrades()` was **re-rolling** these merchants' intrinsic trades (and
could downgrade the level), so a shop showed different trades across the
wild → named → colony stages. Fixed:

- **Feature C is now cosmetic-only and DWARF-only.** It sets the villager
  profession purely to drive the dwarf profession-clothes render (Feature B)
  and NEVER touches trades — no `setOffers`/`updateTrades`/`setMerchantLevel`.
  The reconstruct→save round-trips the existing offers unchanged.
  Goblin/lizardman citizens are skipped entirely (intrinsic trades, no
  profession clothes), so their shops are byte-identical across stages.
- The earlier gain/lose/anchor logic is kept but now governs only the
  cosmetic profession (and thus the clothes): gain a profession from a nearby
  job block, keep it while the anchored block stays a job site, drop it (and
  the clothes) when the block is broken. Trades are never affected.
- The merchant **level-up** path (close hook, `applyPendingMerchantLevelUps`)
  is unchanged and correct — `increaseMerchantCareer` APPENDS the next tier's
  intrinsic trades on reaching the XP threshold; it does not re-roll existing
  trades. This is the only place trades change, and only by growing on a
  level-up (as intended).

The earlier "lose the trades if the block breaks before first trade" framing
is therefore moot for these races — there are no profession-gated trades to
lose; only the cosmetic clothes follow the block.

## Reputation system v1 — foundational spine (LOCKED API)

Full investigation + as-built record: `docs/reputation-system.md`. This
entry records the decisions that bind future features.

**Reputation is a NEW system — MineColonies' two standing metrics were
investigated and rejected as bases.** Happiness is per-citizen internal
morale (0–10, recomputed authoritatively every MC day, already
load-bearing for skill level caps) — wrong semantics, and injected values
get clobbered. The quest system's `IQuestManager.getReputation()` is an
unbounded, tier-less, effect-less quest-gating currency — overloading it
would two-way-entangle our standing with datapack quests. Ours lives in
its own store; happiness remains the sanctioned post-v1 effect CHANNEL
(`ICitizenHappinessHandler.addModifier`, the `"quest"` precedent).

**`ReputationManager` is the SOLE door to storage — LOCKED.** Every
feature (crime, raids, assassins, reclaim, dialogue, trades, …) reads
via `getReputation`/`getTier`/`isAtLeast`/`isBelow` and writes via
`modifyReputation(colony, amount, ReputationReason)` ONLY.
`ReputationSavedData` is package-private and policy-free (no clamping,
no defaults); all policy — clamp, default, logging, and any future HUD
sync / throttling / per-reason multipliers — lives in the manager.
Touching the SavedData from a feature is a design violation, not a
shortcut.

**Scale: clamped double 0–100, default 50, with DERIVED tiers.** Bands
(0–9 HOSTILE / 10–19 PASSIVEAGGRESSIVE / 20–39 WARY / 40–59 NEUTRAL /
60–79 LOYAL / 80–100 DEVOTED) live exclusively in the ordered
`ReputationTier` enum so gates are `isBelow(colony, WARY)` one-liners
and rebanding is a one-file change. Absent storage key = 50 = NEUTRAL —
legacy worlds and fresh colonies need zero migration.

**Per-colony is the v1 scope; per-player (ruler) standing is plumbed but
undriven.** Storage + API twins (`getPlayerReputation` /
`modifyPlayerReputation`) exist now so future ruler-level features
(assassins, reclaim) don't force a storage migration; no v1 mover writes
them.

**Citizen-kill mover hooks `LivingDeathEvent`, NOT `CitizenDiedModEvent`.**
The MC event doesn't carry the killer, so it can't distinguish a player
murder from a raider/fall/starvation death — and reputation tracks how
the colony regards the PLAYER. `LivingDeathEvent` carries the damage
source; attribution mirrors the envoy boss-flag pattern (source entity,
then `getKillCredit()`).

**Citizen-attack mover dedupes combos.** −5 per `LivingDamageEvent.Post`
with a 100-tick in-memory per-(attacker, citizen) window, so a sword
combo is one offence, not three. Envoys are exempt from both citizen
movers (diplomatic visitors; the kill-gate owns those semantics).

**Mover magnitudes are STARTING values** (+10 boss kill, +2 building
built/upgraded — REPAIR/REMOVE excluded, −5 citizen attack, −15 citizen
kill), kept as constants in `ExampleMod` beside the movers (mover
policy), not in the manager (storage policy). Expect tuning.

**NEUTRAL tier appends NO envoy-dialogue tone line.** Default-reputation
dialogue stays byte-identical to the pre-reputation copy — zero text
churn for fresh/legacy colonies; only earned standing (either way)
changes the envoy's register.

## Assassin system — binding decisions (full record: docs/assassin-system.md)

- **The assassin IS the Tensura body.** Activation discards the citizen
  (travelling-marked) and rebuilds from the identity snapshot, or flips a
  live subordinate in place; ownership stripped (permanent/temporary
  owner + tamable) so Tensura's owner-protection can't veto the target.
  Both-bodies-die-on-death falls out of the existing case-A death hook.
- **Assassin state lives in its own SavedData**, NOT on RaceIdentity —
  no identity-NBT surgery; entries prune when identities vanish.
- **EP theft is reversible by construction:** negative stable-id
  modifiers on the player's MAX_MAGICULE/MAX_AURA (EP = their sum);
  reclaim = remove them (offline → pending set, applied on login).
  Skills are COPIED (clones keep mastery), never removed from the player.
- **Skill USE is whitelist-curated** (CASTABLE_PRESS/TOGGLE sets):
  resistances/passives work free; actives fire via
  instance.onPressed(mob, 1, 0) on a 5 s driver; anything not
  whitelisted is held, not cast. Curation = in-game smoke testing.
- **One assassin per colony, EVER** (persistent assassinChosen marker,
  set at candidate pick) — defuse also consumes the colony's one story.
  /assassin arm bypasses for testing only.
- **enableAssassins config:** disabled = no buildup, plots defuse, no
  strikes; an ACTIVE boss intentionally survives (it may hold stolen
  EP — despawning would orphan the player's power).

## Raid system v1 — extend MC's raid EVENT framework, own scheduler + Tensura mobs

Full investigation + as-built record: `docs/raid-system.md`. Decisions
that bind other parts of the codebase:

**Custom `IColonyRaidEvent`, NOT MC's raider entities/AI/scheduler.**
MC's `RaidManager` hardcodes its raid event types by biome (verified in
bytecode — direct `new BarbarianRaidEvent` etc., no registry lookup), and
its raider AI (`RaiderWalkAI` / `RaiderMeleeAI`) is welded to
`AbstractEntityMinecoloniesRaider`. But `IEventManager.addEvent` is public
and the `minecolonies:colonyeventtypes` registry is a real NeoForge
registry. `TensuraRaidEvent implements IColonyRaidEvent` (registered with
`isRaidEvent=true`) buys MC's citizen flee/hide behavior
(`RaidManager.isRaided()` scans for active `IColonyRaidEvent`s), event NBT
persistence, and rehydration — while the mobs stay plain Tensura
MONSTER-category entities that guard towers auto-list
(`CompatibilityManager.discoverMobs` filters on `MobCategory.MONSTER`;
Tensura registers 54 such types).

**Steering = the SubordinatePatrol `WALK_TARGET` technique, plus a
SmartBrainLib target assist.** Raiders march on the barrier (if fueled)
else the colony center via per-second `WALK_TARGET` writes; when a raider
has no live attack target, `BrainUtils.setTargetOfEntity` aims it at the
nearest citizen of the raided colony. Native Tensura combat owns the mob
while it has a target — steering never fights the brain.

**Raid mobs are marked with a `RAID_TAG` attachment** `(colonyId, eventId)`
— the universal raider check for steering, the barrier, and death
bookkeeping; NBT persistence is what re-links loaded mobs to their
rehydrated event after save/reload.

**The barrier is a field effect, not physical blocks — and a CYLINDER,
not a sphere.** Per-tick horizontal position clamp + velocity zero on
RAID-tagged mobs inside radius 16 of the block (the proven
direct-entity-driving technique). DIVERGENCE from the investigation
sketch: a literal 3D-sphere clamp can bury ground mobs on sloped terrain;
the vertical cylinder reads identically in play. Pushback is
horizontal-only so gravity still applies (no fall-damage accumulation).

**EP-scaled barrier drain.** Each raider pressing the shell drains
`EP × BARRIER_DRAIN_COEFFICIENT_PER_SECOND / 20` per tick —
**coefficient 0.02** (2% of the raider's own EP per second; a 3,000-EP
mob drains 60/s, so an 8-mob wave of those empties the 100k tank in
~3.5 min of constant press). Unreadable existence → 1,000-EP fallback.
All barrier knobs are named constants on `BarrierBlockEntity`.

**Trigger reads ReputationManager — exactly the seam reputation v1
reserved.** Nightfall (per-dimension `getDayTime()%24000` crossing
13000) → tier below NEUTRAL → chance per night (WARY 15% /
PASSIVEAGGRESSIVE 30% / HOSTILE 50%), 3-day cooldown persisted in
`RaidSavedData`, one-active-raid gate by scanning the event manager.
Victory pays `+8` through `modifyReputation(..., RAID_REPELLED)` (new
`ReputationReason` value — the enum extension pattern working as
designed).

**Roster divergence: Tensura has no Ogre.** Tiers by MC's own
`getColonyRaidLevel()`: <10 Giant Ant / Black Spider; 10–19 Hound Dog /
Evil Centipede / Direwolf; ≥20 Knight Spider / Blade Tiger / Evil
Centipede. Wave size = `calculateRaiderAmount(raidLevel)` × (1 +
reputation deficit), clamped [3, 12].

**Active-barrier discovery via a refresh registry, not block scanning.**
`BarrierBlockEntity` re-reports its position every second while fueled;
readers treat entries stale after 60 ticks as gone — covers chunk
unload and block break without explicit deregistration edge cases.

## Faction model v1 (expanded world-reputation spine) - 2026-06-11

**Live base + earned delta.** Effective standing = clamp(dispositionBase
+ storedDelta). The base is computed LIVE from the player's CURRENT race
side every read (never stored), so a mid-game race change (human ->
majin demon-lord path, reset scroll) shifts every faction's posture
automatically with zero bookkeeping. The STORED number is reinterpreted
from absolute (default 50) to earned delta (default 0) - dev-stage
saves, no migration shipped. Writes clamp the delta against the CURRENT
base so effective stays in [0,100] without dead accumulation;
/worldrep set stores (value - base).

**The 5-step race-side classifier** (verified against the jars): no race
-> human; Alignment MAJIN/CHAOS -> majin, HOLY -> human; Tensura's
HUMAN_LIKE tag -> human (BASE races only - the verified gap); our own
shipped tensura_minecolonies:human_side race tag -> human (the
evolutions Tensura's tag misses; datapack-extensible); else -> majin.
Goblin/ogre/lizardman correctly land majin (they report DEFAULT
alignment and carry no human tag).

**Marked-only two-sided movers.** The flat any-kill movers (-3 attack /
-20 kill) are RETIRED. Faction consequences now fire ONLY for entities
carrying FactionMarkTag (attachment + faction-colored title - placed by
faction events / lore events / "/worldrep mark"). Kill fan-out through
the sole-door manager: victim faction -KILL_BASE(30) x importance
(KEYSTONE 1.0 / MAJOR 0.6 / NOTABLE 0.3 / MINOR 0.1), allies -50% of
that, enemies +40%, each leg x the TARGET faction's swing multiplier
(Milim/Carrion 1.5, Leon/Otherworlders 0.5). Attacks (-3 x w, deduped)
do not ripple - only kills are statements. Wild/self-summoned boss
kills: ZERO faction effect; colony +10 and envoy unlocks fire either way
(behavior change, user-confirmed). Clowns folded into CLAYMAN for v1.

**Offense ledger + derived provocation.** Marked acts also write a
no-decay offense score (+10 x w kill / +1 x w attack) in
WorldReputationSavedData; isProvoked = offense >= the faction's profile
threshold (Clayman 3, Holy bloc 5, swingables 8, diplomats 10, aloof
15) - derived, never stored. Faction events ARM on provocation and let
standing only SCALE chance/intensity (soft influence - supersedes the
Orc Disaster's hard isBelow-WARY gate in lore-events.md).

**Config gates.** `enableFactionSystem` (renamed from `factionSystemEnabled`;
**default FALSE as of Unreleased** — the whole faction + diplomacy system now
ships OFF). It is the single source of truth — NOT a gamerule/command (there
never was a faction gamerule/command). When false it makes the whole
faction layer dormant at its entry points: manager reads return flat
NEUTRAL, every write no-ops, the two ExampleMod mover hooks skip,
RivalColonies.tick / DiplomacyManager.tick / LoreEvents early-return,
/worldrep + /diplomacy report disabled, and the roster's Diplomacy + Wars
buttons are hidden (server flag on RosterResponsePayload; the server-side
diplomacy/war/faction-envoy packet handlers also refuse). Colony-level
systems (colony rep, generic raids, barrier, assassins, RACE envoys via
runEnvoyScheduler, festivals) are untouched - boss
kills behave pre-faction-system (colony +10 + envoy unlocks, no faction
consequences). enableAssassins (default TRUE, pre-existing) already
gates the assassin system at its three entry points (daily buildup +
defuse, ARMED strike check, debugArm).

**Known divergence from the doc's worked example:** Carrion lands +10.8
(not +7.2) on a marked Orc Disaster kill - the example forgot Carrion's
own 1.5x swing multiplier; the confirmed TABLE wins over the example.

**Notoriety:** formula structurally unchanged; its hostility component
now reads EFFECTIVE standings, so a majin player carries some base
notoriety from the Holy bloc's disposition (lore-correct; no consumer
yet).

## Identity-swap robustness — three additive recovery layers (2026-06-23)

Three bugs in the "two bodies, one identity" core were fixed as PURELY
ADDITIVE recovery layers — no re-architecture of the fragile swap state
machine. Investigation: the identity link is split across the RaceTag
attachment on the live `EntityCitizen` (drives the renderer), the durable
`RaceIdentity` record (mode / mobEntityUUID / snapshots), and MineColonies'
own `CitizenData` (which knows nothing of race). They desync whenever a
transition our code doesn't own happens (MC body respawn; external removal).

**FIX 1 — `summonGoblin` is now transactional (the "strand").**
The colonist body's respawn loop is suppressed up front
(`startTravellingTo(MAX_VALUE)`) but `mode` only flips to SUBORDINATE on full
success. A throw partway (stat copy / `copyHealthAbsolute` / `applyVariantToMob`
/ EnergyHelper) used to leave the citizen with respawn suppressed AND mode
still IN_COLONY → no body ever spawns → permanent strand, un-resummonable.
`summonGoblin` now returns `boolean` and wraps its body in try/catch mirroring
`sendGoblinToColony`'s existing rollback. **Invariant: on EVERY failure /
early-return path the travelling suppression is CLEARED
(`finishTravellingFor`)**, the half-built mob is discarded, the identity is
left IN_COLONY with a real body. `executeAction` propagates the flag;
`executePendingSwap` refunds magicule when it's false. Item-safe because
`transferCitizenItemsToGoblin` copies (the citizen inventory isn't drained
until after the commit point).

**FIX 2 — re-stamp RaceTag (the "revert").**
The RaceTag attachment is LOST whenever MC rebuilds a body from `CitizenData`
(its respawn loop; chunk-NBT relog) — the citizen then renders as a plain
colonist and nothing put the tag back. New durable field
`RaceIdentity.raceTagSnapshot` (the serialized RaceTag, written via every
citizen-side tag set through the new `applyRaceTagToCitizen` chokepoint). The
re-stamp rebuilds the tag from that snapshot (or the race's DEFAULT appearance
if absent — pre-FIX-2 records have no snapshot, so they re-stamp to default
until the next summon→send recaptures the real variant). Precise
`getByColonyAndCitizen` lookup (citizen IDs are only unique per-colony).

> **CORRECTION (2026-06-23, after in-game test) — the re-stamp is driven by a
> per-second RECONCILE PASS, not `EntityJoinLevelEvent`.** The first cut hooked
> `EntityJoinLevelEvent` and read `entity.getCitizenData()`. That FAILED on
> relog: a chunk-loaded citizen joins the level BEFORE MineColonies links its
> `CitizenData` (the link happens later, in `EntityCitizen.initialize()` during
> the AI tick — confirmed by decompile: `registerWithColony` → `setCivilianData`
> is called there, not at join). So `getCitizenData()` was null at join and the
> handler always bailed; the tag was never re-stamped. **Fix = Option B:**
> `tickReconcileRaceTags` runs in the existing `onServerTickPost` 1 s block and
> resolves the body the REGISTRATION-GATED way —
> `colony.getCitizenManager().getCivilian(id).getEntity()` (only non-empty once
> the colony has linked the entity), the same idiom `tickCitizenProfessions`
> uses. If the body lacks `RACE_TAG` it re-stamps + broadcasts; self-heals
> within ~1 s of load. **Idempotent** (skips bodies that already have the tag;
> skips `mode != IN_COLONY` so it never races the send/summon helpers or
> `/raceflip`). The `onEntityJoinLevel` handler is KEPT as a zero-latency fast
> path for the colony-driven respawn case where `getCitizenData()` IS populated
> at join, and `onStartTracking` re-stamps on first track (Option D) for
> zero-flicker on observed citizens — both no-op safely when the link isn't
> ready yet and defer to the reconcile pass.

**FIX 3 — `/recoverorphans` (Bug 2 + FIX-1 victims), dry-run by default.**
When a subordinate is removed OUTSIDE our hooks (third-party mob-capture item;
or a pre-FIX-1 strand), the record is stuck SUBORDINATE pointing at a vanished
mob UUID and can never re-join. The command walks the RUNNING player's own
SUBORDINATE identities whose `mobEntityUUID` resolves to no live entity and
buckets them: recoverable (has `entitySnapshot`) vs identity-only (no snapshot).
**Scope = runner's own identities** (owner is online and present, so a still-
valid subordinate would be in a loaded chunk — an unresolvable mob is a strong
"genuinely gone" signal, not merely unloaded). **DRY RUN by default** — reports
counts + names, mutates nothing; only `/recoverorphans confirm` acts. Restore =
`updateMobUUID(null)` + flip to IN_COLONY + `finishTravellingFor` so MC rebuilds
the body from the already-counted CitizenData (no double-count; FIX 2 re-stamps
appearance). **Fail-safe: records are NEVER deleted**; missing colony/citizen or
any exception → skip and keep the record. Snapshot-less records are reported
separately and left untouched (no fabricated stats/appearance; their real
variant returns on the next summon→send cycle).

**DEFERRED (not built this round):** Bug-2 auto-detection via
`EntityLeaveLevelEvent` capture-catching. That event also fires on ordinary
chunk unloads, so a too-eager handler could mistake an unload for a capture and
destroy valid identities — needs separate careful work. `/recoverorphans` is the
manual, fail-safe stand-in.

**FIX 3 follow-up (2026-07-13) — snapshot earlier + `/recoverorphans purge`.**
Two additions closing the gaps in the original FIX 3:

- **Snapshot is now captured at naming time, not only at first send.**
  `captureSnapshotFromLiveMob(saved, identity, mob)` runs right after
  `addIdentity` in BOTH identity-creation sites (`onRaceNamed` and the
  pending-pool drain), and a per-`AMBIENT_PERIOD_TICKS` (5 s) pass
  `tickRefreshSubordinateSnapshots` re-saves every LOADED subordinate. Rationale:
  the send-only capture meant a subordinate that vanished before it was ever
  sent had no snapshot and was permanently unrecoverable. Capturing at naming +
  refreshing while loaded means such a body is recoverable, and the snapshot
  tracks EP/leveling instead of freezing at naming. The tag is identical in form
  to the send-time one (`Entity.save`). No behaviour reads
  `entitySnapshot == null` for a SUBORDINATE in a way this breaks — restock
  Pass A reads the live mob, Pass B is IN_COLONY-only, and every summon/trade
  path is strictly more capable with a snapshot present. Net effect: the
  identity-only bucket now only holds legacy pre-update records (or a body gone
  the same tick it was named).

- **`/recoverorphans purge`** — the destructive counterpart to `confirm`.
  `confirm` restores the recoverable ones; `purge` DELETES the identity-only
  ones (`removeCivilian` to free the housing/count slot the CitizenData still
  occupies, then `removeIdentity`), mirroring the death-hook cleanup. Purge only
  ever touches the identity-only bucket, owner-matched; recoverable and live
  subordinates are never purged. The dry run now lists identity-only records as
  "purge?" and points at the follow-up. Handler refactored from a
  `boolean confirm` to an `OrphanAction {DRY_RUN, CONFIRM, PURGE}` enum sharing
  the one scan. This is the "free housing held by a truly-gone body" path that
  FIX 3 originally lacked (it deliberately never deleted).

## Enemy/mob skill casting — Sentient skill replaces our hand-built autocasters (2026-06-23)

**Decision:** removed ALL four of our `NightmareUtilsApi.registerReflectiveManascoreAutocaster`
registrations (bone-golem, Tempest slime-boss, assassin, colony-defender) and
drive mob skill use with Nightmare's Tensura Utils' **"Sentient" skill**
(`nightmareutils:sentient`) instead, granted per-mob.

**Why:** the Sentient skill IS the same reflective-autocaster machinery, but the
nightmareutils mod registers ONE global autocaster keyed on
`SentientSkillService.hasSentient(mob)` (verified in the jar:
`ModLifecycle.init` registers it + the tick listeners). So a single per-mob
grant replaces every bespoke predicate/cooldown/skill-filter we maintained —
less code, one mechanism, and it auto-covers any learned active skill. Sentient
drives only LEARNED ACTIVE skills (it toggles them); it never touches a mob's
native-AI attacks, so granting it to a passive-only / true-native-caster mob is
a safe no-op (no double-cast).

**As built:** `ExampleMod.grantSentient(LivingEntity)` / `removeSentient(...)`
look the skill up via `SkillAPI.getSkillRegistry().get(SENTIENT_ID)` and
learn/forget it. Granted at: every garrison defender + anchor boss
(`spawnDefender` / `spawnGarrison`, skip `isSkillUntouched`), the assassin at
manifestation, and the colony-defender body in `defenseSwapToSubordinate`.
**Colony defenders get `removeSentient` on swap-back** (in `defenseSwapToColony`,
BEFORE the snapshot is captured) so the autonomous-casting driver never persists
into a normally-summoned subordinate — mirrors the old tag-scoped behaviour. No
registration on our side (the mod's own `SentientSkillService` ticks it).

## Jura-Tempest anchor Slime → "Rimuru" demon-lord boss (2026-06-24)

**Decision:** the Tempest anchor Slime is named **Rimuru** and buffed to
demon-lord tier with ABSOLUTE energy pools, in a new `buffRimuruBoss`, replacing
the old flat `SLIME_BOSS_BUFF ×8`.

**Stats:** HP ×100 (5→500), ATTACK ×40 (0.5→20), spiritual HP ×10; magicule cap
SET to 100,000 and aura cap SET to 10,000 (absolute, via a new
`setAttributeAbsolute` helper — the `multiplyAttribute` idiom but to a target,
not a factor); CURRENT magicule/aura filled to those caps. Caps raised BEFORE
filling current (else the set clamps to the slime's ~980/10 base). Kit unchanged
(Predator learn-only; Water Blade + Corrosion driven by Sentient — now affordable
because the 100k pool is filled, which is also what fixed the "won't cast" bug).

**Why absolute, not a multiplier:** ×8 of a 5-HP slime is a glass boss (weaker
than its own buffed subordinates). The slime's HP/ATTACK are NOT EP-derived, so
EP can't fix them — only absolute stat values reach boss tier. EP (magicule+aura)
is used as the lever for garrison strength.

**Where applied — `spawnAnchorBoss`, NOT `spawnGarrison`'s boss block:** on
purpose. `spawnGarrison` reads the boss EP at its TOP (`readBossEP`, before the
boss-buff block), so the buff must land earlier to scale the garrison on the
FIRST encounter. `spawnAnchorBoss` is the single funnel for every boss creation
(initial colony, wild, AND `resetGarrison`'s revive), so applying it there also
keeps a revived Rimuru strong. It runs after `markBoss`, overriding markBoss's
"Slime" nameplate with "Rimuru".

**Accepted side effect — the garrison scales up.** Filling the pools sets
EP = magicule + aura ≈ 110,000; `readBossEP` feeds the garrison scaler →
scale √(110k/5k)=4.69 → **count 20 (the cap), stat× ≈2.85**. This is intended
(strong subordinates). The boss does NOT receive `statFactor` (only rank-and-file
do), so there is no triple-stack on Rimuru; the ×100/×40 are the only multipliers
on him. ⚠ all values BALANCE GUESSES — tune after a siege test.

## Barrier + garrison decisions (2026-06-21)

**Bone golems can't be enemy defenders — they're player-possessed.** Tensura
golems (`BoneGolemEntity → TensuraHumanoidEntity → TensuraTamableEntity →
TamableAnimal`) are owned constructs; a tamed mob won't attack its owner even
when angered, so they ignored the player as garrison defenders. REMOVED from all
rosters and replaced (Leon → Lesser/Greater Daemon; Eastern Empire → Falmuth
Knight rank-and-file; Luminous → pure Falmuth Knights, Kyoya kept in Falmuth).
Boss anchors unchanged (Luminous = Hinata).

**Garrison targeting needs NeutralMob anger, not just `setTarget`.** Nearly
every Tensura defender is a SmartBrainLib `NeutralMob`; its brain drops a hostile
target it isn't ANGRY at, so `setTarget`/`BrainUtils.setTargetOfEntity` alone
didn't stick. `steerGarrisonToInvaders` now also sets the persistent-anger target
+ timer on the invader. This is load-bearing for ALL neutral defenders (knights,
heroes, daemons, dwarves, goblins) — not just the golem; the golem is the one
case it can't fix (owned). So the anger fix is KEPT.

**Unique Otherworlders spawn at most once.** Round-robin spawn duplicated named
characters when count > roster length. `pickGarrisonType` caps named
Otherworlders/lieutenants at one per garrison and substitutes a repeatable
troop; `resetGarrison` seeds the tracker with still-alive uniques.

**Barrier damage DECOUPLED from the fuel pool.** The original two-counter model
drained both the pressed section's health AND the shared pool per hit; section
health scales ×6 across tiers (10k→60k) but pool capacity only ×2.5
(100k→250k), so a high-tier pool emptied before any section broke. Now attacks
(contact / projectiles / blocked skills) reduce ONLY section health; the pool is
spent solely on layer upkeep + repairs (a repair costs exactly the health it
restores, 1:1). Whole-barrier fall still gated on pool 0; refuel resets sections.

## Faction rewards review — Phase 0 decisions (2026-06-27)

Decisions locked before the per-faction reward build (full plan:
docs/faction-rewards-roadmap.md). Three ambiguities resolved:

**Shizu — purge reward data, keep the enum.** Shizu is soft-retired: no
settlement generates and it can't be raided, yet it still carried a
`ConquestPayoff.PROFILES` entry, a full `DealSpec.FACTION_DEALS` table, and an
`sh_pupils → Heat Resistance` `SKILL_REWARDS` mapping. Decision: REMOVE those
three (profile, deal table, skill mapping) so Shizu is truly dormant and stops
polluting future reward reviews. KEEP the `BossFaction.SHIZU` enum value (old
saves reference the id) and the auto-built `MENDING_DEALS` entry (built by a
loop over all factions; harmless). Why: dead reward data on a retired faction
is pure confusion with no gameplay reach.

**Tempest — Self-Regeneration is the single capstone/conquest skill.** Tempest
had TWO deals mapped to skills: `tp_joyful → Self-Regeneration` and
`ja_sages → Thought Communication` (a leftover from the Jura-Alliance→Tempest
merge). `covenantSkillFor` returns the first match in deal order, which is
`tp_joyful` (Self-Regeneration). Decision: KEEP Self-Regeneration as the one
skill and DROP the `ja_sages` entry from `SKILL_REWARDS` (the deal itself
stays; it just no longer grants a skill). Why: a strong passive combat reward
beats a utility/comms skill as a player payout, and Tempest's mental/sage angle
was already de-emphasized post-merge (the Jura Sage training deal was dropped).
Removes the first-match ambiguity.

**Clayman — gets THEMED PACT perks (spy/manipulation), not parity or nothing.**
Clayman (Moderate Harlequin Alliance) was the only abstract faction missing
`FACTION_GOODS` + `ALLIANCE_BUFFS` (Milim and Eurazania already have them).
Decision: ADD both, flavored to its intel/manipulation identity (shady
valuables as caravan goods + a stealth/insight-flavored alliance buff) rather
than a generic copy or leaving it empty. Why: parity with the other abstract
factions, while keeping Clayman distinct — it already trades in intel + summon
at the Covenant tier, so its PACT perks should read as "information/edge," not
"more bread." Exact item list + MobEffect chosen in Phase 1.

## Faction rewards review — Phase 1 (close structural gaps) (2026-06-27)

Implemented the Phase 0 decisions + the Leon/Eastern Empire parity gaps. All
effect/item picks are FIRST-PASS balance guesses (Phase 3 tunes them). Faction
system is OFF by default, so none of this reaches a default player yet.

**Leon + Eastern Empire — raidable towns, were "aloof" with no Covenant.**
Both had a catalog + a capstone skill (`le_flamebearers` → Flame-Attack
Resistance, `ow_specialists` → Eye of Truth) but no `cov_*` milestone, no
caravan, no alliance buff. Added:
- `COVENANT_DEALS`: `cov_leon` ("Tribute to the Platinum Saber" — SupplyBundle
  16 Gold Block + 16 Blaze Rod + 1 Netherite Ingot) and `cov_eastern_empire`
  ("The Imperial Compact" — SupplyBundle 4 Diamond Block + 32 Amethyst Shard +
  16 Redstone Block). Both ALLIED-tier milestones, 48-emerald reward, mirroring
  the other towns' covenant shape. The generic offer logic
  (`DiplomacyManager` `COVENANT_DEALS.get(id)`) needed no per-faction wiring.
- `ALLIANCE_BUFFS`: Leon → Fire Resistance (fire-knight theme); Eastern Empire
  → Absorption (disciplined/armored imperial legion — a new effect not used by
  another faction).
- `FACTION_GOODS`: Leon → 8 Gold Ingot + 4 Blaze Rod; Eastern Empire → 12 Iron
  Ingot + 6 Amethyst Shard.
The catalog `cov_*` deals do NOT grant skills (consistent with every other
faction — the skill comes from the catalog deal in `SKILL_REWARDS`, which these
two already have).

**Clayman — themed spy perks.** `ALLIANCE_BUFFS` → Night Vision (the spy's
insight); `FACTION_GOODS` → 6 Emerald + 4 Ender Pearl (ill-gotten wealth +
infiltrator mobility). Distinct from the generic "food/ingots" caravans.

**Shizu — purged (Phase 0).** Removed `ConquestPayoff.PROFILES["shizu"]`, the
`FACTION_DEALS["shizu"]` catalog table, and the `sh_pupils` `SKILL_REWARDS`
mapping. KEPT: the `BossFaction.SHIZU` enum (old saves), the baseline-standing
entry, and the auto-built `MENDING_DEALS` entry (loop over all factions).
`tableFor("shizu")` now returns empty; `isActive("shizu")` already false.

**Tempest — single skill (Phase 0).** Dropped `ja_sages → Thought
Communication` from `SKILL_REWARDS`; `tp_joyful → Self-Regeneration` is the
sole capstone/conquest skill. The `ja_sages` deal stays in the catalog (still a
lend deal with item rewards) — it just no longer grants a skill.

## Faction rewards review — Phase 3 (diplomacy balance pass) (2026-06-28)

**Done out of order, BEFORE Phase 2 (conquest balance), on purpose.** The
peaceful (diplomacy) route is the REFERENCE: raid/conquest rewards (Phase 2)
will be tuned to MATCH each faction's peaceful value, so the peaceful value has
to be locked first.

**Philosophy: TIERED by difficulty** (user decision), not flat parity. A
faction harder to conquer (raid) / more powerful in lore gives MORE on BOTH
routes. For raidable factions "difficulty" = boss EP (the garrison already
scales to it); for abstract factions it = lore power / diplomatic demand.

**Tier assignment** (2026-07-10 — expanded to a FOUR-tier ladder; the COMBAT
side reads these via `difficultyTierFor`, reward magnitudes are still docs-driven
/ user-managed). The old Covenant-emerald guides [64/48/32] were tied to the
3-tier scheme — re-space them across the four tiers in the reward pass.
**KEY PRINCIPLE (user, 2026-07-10):** tiers are keyed to the CANON power of the
KINGDOM, NOT the current boss mob's strength. The current bosses (Hinata,
Ifrit, Mai) are PLACEHOLDERS for the true leaders and will be swapped as the
mod updates — so a placeholder being weaker/stronger than its tier is expected
and temporary.
- **Tier IV — Apex:** Luminous, Leon, **Dwargon** (Gazel's Armed Nation is a
  canon great power; moved III→IV 2026-07-10), Milim.
- **Tier III — High:** Eastern Empire, Eurazania.
- **Tier II — Major:** Falmuth, Tempest.
- **Tier I — Minor:** Moderate Harlequin Alliance (Clayman). (Shizu retired.)
- Reconciliation the manual pass owes the new tiers: Dwargon covenant 64→48;
  Milim + Leon covenant 48→64. (Emerald amounts are independent numbers in
  code — the tier is just the target.)

**Per-tier reward guideline** (the magnitude each faction's catalog + covenant
should hit):
- Tier III: top deals give diamond blocks / netherite / enchanted golden apples
  / premium tomes; ~10 deals; Covenant 64 emeralds.
- Tier II: top deals give diamonds (4–8) + magisteel/metals + a tome; ~10
  deals; Covenant 48 emeralds.
- Tier I: deals give crystals / gold / redstone + one tome; Covenant 32
  emeralds.

**Done this pass:**
- Leon catalog expanded 4 → 10 deals at Tier II (fire/martial theme).
- Eastern Empire catalog expanded 4 → 10 deals at Tier II (magitech/imperial).
- `cov_clayman` reward fixed (was empty) → 32 emeralds (Tier I).

**Remaining Phase 3 — catalog deals reworked MANUALLY (user-led, 2026-06-28).**
The user is sorting through the catalog deals by hand against the updated tiers
(full inventory: `docs/faction-rewards-roadmap.md` §7). Reconcile as part of
that pass: Milim + Leon up to Tier III value (incl. covenant 48→64); Dwargon
down to Tier II value (covenant 64→48); Leon's Phase-1 catalog was authored at
Tier II and now wants a lift to III. Then Phase 2 mirrors each faction's locked
tier on the raid side.

## Faction combat — tier-keyed garrison difficulty (2026-07-10)

Follows the stats/EP audit in `docs/faction-combat-audit.md`. The old garrison
scaler drove difficulty purely off boss EP vs a 5,000 baseline; real boss EP is
110k–1M, so every faction pinned to the count cap (20) at stat× ~3 — no
differentiation. **Decision: difficulty is now PRESCRIBED by the faction's
reward tier, and boss EP only NUDGES it within a clamped band.** Nothing read
the reward-tier labels before; the combat side now does.

- New `RivalColonies.DifficultyTier {IV(16, 2.8), III(14, 2.4), II(11, 2.0),
  I(7, 1.5)}` = (baseCount, baseStat) — a FOUR-tier ladder (revised the same day
  from the first 3-tier cut, per user; Tier IV inherits the old top stats, Tier
  III is the new middle). `difficultyTierFor(factionId)`: Luminous/Leon/**Dwargon**
  → IV (Dwargon moved III→IV 2026-07-10 — canon-power principle, see above);
  Eastern Empire → III; Falmuth/Tempest → II; Clayman → I. (Milim IV /
  Eurazania III / Clayman I are ABSTRACT — reward mapping only, no garrison.)
  `epF = clamp((bossEP/150 000)^0.5, 0.80, 1.30)`;
  `count = clamp(round(baseCount×epF), 4, 20)`,
  `stat× = clamp(baseStat×epF, 1, 4)`.
- **Why a band, not pure EP:** keeps "difficulty ≈ reward" as the primary rule
  (user's Phase-3 philosophy) while letting canon show through — a legend
  (Gazel, 1M EP) tops its tier's band but can't cross into another tier. **Two
  same-tier factions are deliberately NOT identical** (user): they vary by boss
  EP (the nudge) + roster + boss kit.
- **Tier ladder (final):** IV Luminous/Leon/**Dwargon** (+ Milim) · III Eastern
  Empire (+ Eurazania) · II Falmuth/Tempest · I Moderate Harlequin Alliance.
  Reward-catalog reconciliation for the moved factions is owed to the user-led
  reward pass — combat only here. (Per-faction garrisons: IV Luminous/Leon 20 @
  ×3.64 / Dwargon 20 @ ×3.64 · III EE 15 @ ×2.55 · II Falmuth 11 @ ×1.93 /
  Tempest 9 @ ×1.71.)
- **Bosses are placeholders (user):** the current boss MOBS (Hinata/Ifrit/Mai)
  don't represent their kingdoms' true leaders — they're placeholders to be
  swapped later, so a boss weaker/stronger than its tier is expected. Mai
  (Eastern Empire) buffed 2026-07-10: HP ×5 (~1500) but ATTACK only ×1.8 (~54,
  kept BELOW Gazel's 80) — the old flat ×3.5 `EMPIRE_BOSS_BUFF` made her out-hit
  Gazel. Now `EMPIRE_HP_MULT`/`EMPIRE_DMG_MULT`.
- **Leon boss upgraded** (user): `buffIfritBoss` (mirrors `buffRimuruBoss`)
  raises Ifrit to ~2800 HP / EP ~380k so apex-tier Leon has a real boss. Applied
  in `spawnAnchorBoss` before the garrison reads EP (the buffRimuruBoss precedent).
- **Eastern Empire's 1.6× `factionPowerMultiplier` REMOVED** (the audit's
  "triple-dip"). EE is a hard Tier III on its lieutenants' own high stats/EP;
  Mai keeps her `EMPIRE_BOSS_BUFF`.
- **Dwargon rank strengthened** (user: buff dwarves a lot + give skills/magic +
  more of them, add ≤1 War Gnome lieutenant): roster = `DWARF + WAR_GNOME`,
  War Gnome added to `isUniqueGarrisonMob` (exactly one, Gazel's earth-magic
  lieutenant). `strengthenDwarfDefender`: HP ×2.5 / ATK ×6.0 on top of the tier
  stat× (→ ~187 HP / ~28 ATK at Tier III) + Body Armor. Dwarves remain the base
  troop; population = the Tier III count.
- **Every garrison now gets an active elemental ATTACK** (2026-07-10, user):
  `assignFactionDefenderSkills` grants each non-untouched defender one Aspectual
  attack **Magic** + the matching low-tier element **Manipulation**. KEY FINDING:
  Tensura `Magic` is a `ManasSkill` (`Magic → TensuraSkill → ManasSkill`) with an
  attacking `onPressed`, so the Sentient autocaster FIRES it — whereas a bare
  Manipulation's onPressed is element control that does nothing offensive alone
  (the user's point). So the Manipulation is only granted as the support skill
  ALONGSIDE the magic, never as the attack. Element themes: Leon/Luminous
  `FIRE_BALL`, Falmuth `WIND_CUTTER`, Eastern Empire/Dwargon `STONE_SHOT`,
  Tempest `WATER_CUTTER` (+ each element's Manipulation). Earth has NO attack
  *skill* (only Magic), which is exactly why the old dwarf/bone-golem
  `EARTH_MANIPULATION` "attack" did nothing; `boneGolemElementSkill` earth case
  fixed to `STONE_SHOT` too. ⚠ Playtest-unverified: whether the autocaster
  completes magic *cast-times* on mobs (mechanism is sound — Magic is a ManasSkill).
- **Garrison rank splits into CASTER / WARRIOR roles** (2026-07-10, user).
  Generic rank only (NOT bosses/lieutenants — they keep the full elite kit +
  native behaviour). ~40% casters / 60% warriors, assigned at spawn, role
  INFERRED later from the held weapon (`isCasterDefender`: staff = caster) so no
  new persisted storage. CASTER = faction attack magic (+25% chance of a 2nd
  same-element magic) + Magic Resistance (not Physical) + tiered magic staff +
  0.65× speed + a best-effort per-second retreat (`applyCasterRetreat`); melees
  within 2 blocks. WARRIOR = elemental resistances + Physical Resistance + Shadow
  Motion (flash-step) + tiered long sword (Diamond I → High Magisteel IV) + NO
  magic (native rush/melee). ⚠ KEY LIMITATION: these are SmartBrainLib brain
  mobs and the steering runs PER-SECOND, so true ranged kiting isn't achievable
  via the WALK_TARGET idiom — the caster "stay back" is a speed-reduction + a
  per-second retreat nudge, best-effort only. A real kite would need a per-tick
  `EntityTickEvent` driver or a brain-behaviour mixin (deferred). Also unproven:
  Shadow Motion autocasting as a "flash step".
- **All values are BALANCE GUESSES** — no combat playtest yet (playtesting.md).
  Deferred: Tempest rank buff (still weakest II), same-tier boss-stat
  normalisation, betrayal-stack re-check post-rework, real caster kiting AI,
  and the Form Hide / concealment check (future-ideas.md 2026-07-10).

**Barrier push is purely horizontal; render uses a depth-write-off type.** The
old radial push pointed partly upward and flung mobs up the dome → now always
horizontal (`pushFromShell`, Y preserved). Coincident translucent panels
z-fought under default `entityTranslucent` (far panels looked fainter) → custom
`BarrierRenderType` with depth-write OFF + sort-on-upload blends them evenly.

**Enemy skills stopped by damage interception, not entity blocking.** Beams /
breaths extend `TensuraProjectile` but are anchored at the caster (not moving
point projectiles), so the projectile-crossing blocker missed them. A
`LivingIncomingDamageEvent` handler cancels hostile damage to a victim inside
the barrier when the attacker is outside an intact section in its direction,
chipping that section.

## Citizens immune to the Fear effect (2026-07-04)

**Problem.** Tensura's FEAR mob effect deals fear DAMAGE every tick
(`FearEffect.applyEffectTick`). A player casting a fear-inducing skill (Mortal
Fear haki, etc.) anywhere near their own colony would tick their citizens down
and kill them — an accidental self-inflicted wipe with no intent to harm.

**Decision.** Block the FEAR effect from ever landing on colony citizens.

**Mechanism — a NeoForge event, not a mixin.** `MobEffectEvent.Applicable`
fires from `LivingEntity.addEffect` before the effect is added. The handler
(`ExampleMod.onFearApplicableToCitizen`) returns `Result.DO_NOT_APPLY` when the
target is an `AbstractEntityCitizen` and the effect is
`TensuraMobEffects.FEAR`. No tick, no damage — the effect simply never lands.
Chosen over a mixin because the event is the intended vanilla/NeoForge seam and
needs no bytecode weaving. Nothing else about the citizen's effects changes;
only FEAR is intercepted.

**Scope — citizen BODY only, on purpose.** The exemption keys on
`AbstractEntityCitizen`, the in-colony body. A named follower swapped OUT to its
Tensura mob form (GoblinEntity/OrcEntity/…) is a different entity type, not a
citizen, so it is fully feareable. The assassin's "Betrayer" body and any
subordinate fighting in the field are Tensura mobs and get NO exemption — the
mutually-exclusive entity types give this for free, no extra guard needed.

**Not the `tensura:no_fear` tag.** That entity-type tag only controls whether a
mob gains the `AvoidFearedEntityGoal` (fleeing feared entities) — it does NOT
gate receiving the effect. Adding citizens to it would not have stopped the
damage.

## Faction settlements are Overworld-only (2026-07-04)

**Problem.** Auto-generated rival towns were being placed in the Nether: buried
in the bedrock roof, with defenders/citizens spawned above the roof (the
2026-06-30 bug report). Root cause: every placement helper in `RivalColonies`
resolves Y from an open-sky Overworld lookup (`groundSurfaceY` scans down from
`Heightmap.WORLD_SURFACE`; the boss/garrison use `getHeightmapPos(WORLD_SURFACE)`),
and `RivalColonies.tick` runs generation over `server.getAllLevels()` with **no
dimension gate**. In a roofed dimension `WORLD_SURFACE` resolves to the bedrock
ceiling, so buildings anchored on the roof and entities spawned on top of it.

**Decision.** Gate all settlement generation to the vanilla Overworld rather
than teach the placement code to find a floor under a roof. The worldgen
faction-anchor structures already only target Overworld biomes, so this aligns
the runtime with the data; and MineColonies town schematics aren't suited to
Nether/End terrain anyway (chosen over the "make Nether placement work" option
after asking the developer).

**Mechanism.** `RivalColonies.isOverworld(level)` =
`level.dimension().equals(Level.OVERWORLD)`, checked at three chokepoints:
`generateColony` (ALL mode + retries + debug colony spawn) → null + warn log;
the `tick` generation section skips `tickDwarvenVillages` +
`tickWorldgenSettlements`; `debugSpawn` → clear player message. `tickGarrison` /
`tickDiscovery` / `tickAssaults` need no gate — they already filter by
`s.dimension`, and every settlement is now Overworld.

**Defensive companion.** `isGroundSurface` now also excludes `Blocks.BEDROCK`,
so a building can never anchor on the world-floor / ceiling bedrock layer even
if a surface scan falls through to it — hardens the Overworld "below bedrock"
half of the report independent of the dimension gate.

## `enableFactionSystem` is a per-world SERVER config, not COMMON (2026-07-04)

**Context.** A player reported that toggling the faction system in the in-game
Mods → Config menu did nothing; only hand-editing the config file worked (see
docs/user-bug-reports.md, 2026-07-04). Root cause (verified against the
neoforge-21.1.233 bytecode): the toggle was a COMMON config, and
`ModConfigSpec.ConfigValue.get()` returns a cached value cleared only on a
config reload (`afterReload → resetCaches(NONE)`). The config screen's save
writes the file but never triggers that reload, and a COMMON config is loaded
only once per game launch — so a running session kept the stale value until a
full reload/restart.

**Decision.** Move `enableFactionSystem` into a separate per-world SERVER spec
(`Config.SERVER_SPEC`, registered `ModConfig.Type.SERVER`) and mark it
`.worldRestart()`. SERVER configs are stored per-world in `serverconfig/` and
reloaded on every world load, so the in-game edit reliably applies on world
re-entry; `worldRestart()` makes the screen prompt the player to reload rather
than silently no-op. Chosen over keeping it COMMON + `worldRestart()` (murky
cache-clear semantics — COMMON isn't world-reloaded) after presenting both to
the developer.

**Trade-offs (accepted).** The setting is now per-world instead of global, so
existing setups see it back at the default (off) and re-enable per world; and on
a dedicated server the client menu is read-only for it (NeoForge gates SERVER
configs to single-player / not-open-to-LAN — edit the world's serverconfig
there). `enableDefenseSwap` — the other player-facing world toggle — moved to
`SERVER_SPEC` with the same `worldRestart()` treatment for the same reason; the
remaining toggles (assassins, aggression, rival/Drago, MDK placeholders) stay in
the COMMON spec. All reads still funnel through
`WorldReputationManager.isFactionSystemEnabled()` / `Config.enableDefenseSwap()`,
whose existing not-loaded catches return the defaults at the main menu (SERVER
configs aren't loaded until a world is).

## Dependency reference set (`deps/`) (2026-07-10)

**Durable, source-grounded API reference for the upstream mods now lives in a
dedicated top-level `deps/` directory** — one file each for MineColonies, Tensura
(+ ManasCore folded in), Nightmare's Utils, and Structurize, plus a `deps/README.md`
orientation. Written after a full decompile-and-inventory pass so future sessions
don't re-investigate these mods from zero and don't rebuild systems that already
exist upstream.

**Why a new dir, not `docs/`:** `docs/` is feature-organized (per-system as-built
records); dependency knowledge is orthogonal and belongs in its own navigable home
that any feature doc can point into. `docs/dependencies.md` stays the pure
version/ID ledger; `deps/` holds the deep API detail. Pointers added from
`CLAUDE.md`, `STATE.md`, and `dependencies.md`.

**How the source was read:** none of the three mods ship sources — only compiled
`.class` with official Mojang mappings (readable names). Decompiled with
**Vineflower 1.10.1** (Gradle-cached); the exact regeneration command is in
`deps/README.md`. Claims are marked `[READ]` vs `[INFERRED]`.

**Key facts the pass surfaced (captured in `deps/`):**
- **Three event/registry substrates, don't mix them:** NeoForge (our mod), the
  MineColonies custom bus (`IMinecoloniesAPI.getInstance().getEventBus().subscribe`,
  exact-class dispatch, non-cancellable), and the Architectury bus (all of
  Tensura/ManasCore). `@SubscribeEvent` silently never fires for MC or Tensura
  events. This is the #1 latent-bug risk.
- **Confirmed bug:** MC's `EventManager.readFromNBT` hardcodes the `minecolonies`
  namespace when rehydrating colony events, so our `tensura_minecolonies:tensura_raid`
  is silently dropped on save/reload — an in-progress raid does NOT survive reload
  (contra the old javadoc, now corrected in `TensuraRaidEvent`/`RaidSavedData`).
  Code fix tracked separately.
- **nightmareutils `registerAutocaster` vs `sentient` resolved:** same machinery
  at two levels — granting `sentient` enrols the mob in nightmareutils' own
  built-in autocaster; we correctly grant the skill and never call
  `registerAutocaster`. Stale CLAUDE.md wording corrected.
- **Available-but-unused upstream surface** the pass surfaced is tracked in its
  own index below — see "Available-but-unused upstream surface (adoption index)".

## Available-but-unused upstream surface (adoption index) (2026-07-10)

Tracked index of upstream hooks we could adopt instead of a local workaround,
surfaced by the dependency-investigation pass. **This is the durable status
record; the mechanics live in `deps/*` — entries point there, they don't restate
it.** No duplication.

**Status:** `ADOPTED` (already wired) · `TO-ADOPT` (a workaround exists, hook is
better) · `INVESTIGATE` (adopt only after scoping) · `NOTED` (intentional
alternative, not planned).

**Task-linkage convention** (new — no prior convention existed in the repo): a
scheduled entry carries an inline `→ task_<id>`; that task's prompt references
this section + the relevant `deps/` file. None are scheduled as tasks yet (index
first); add the id here when one is spawned. (Precedent: the raid-reload bug is
tracked as its own task, noted in `deps/minecolonies.md` §7.1.)

Each entry marks intended-usage assumptions `[verify in-game]`.

### 1. Subordinate target veto — `LIVING_CHANGE_TARGET` — ADOPTED
- **Hook:** ManasCore `EntityEvents.LIVING_CHANGE_TARGET`.
- **Status:** already wired — `ExampleMod.onSubordinateChangeTarget`
  (`ExampleMod.java:362`) vetoes a subordinate's target change when the proposed
  target is a citizen / friendly race (see this file, "Targeting veto extended").
  Nothing to adopt; listed so the hook isn't re-flagged as unused.
- **Not to be confused with** the *separate, still-open* case of making hostile
  mobs *add* citizens as targets during raids — that canNOT use this hook (it
  gates transitions, can't widen a candidate filter) and is tracked in
  `docs/hostile-mob-targets-citizens.md` (approach: a `tensura:animal_prey`
  datapack tag). Ref: `deps/tensura.md` §8.

### 2. Majin side-watch — `RaceEvents.SET_RACE` — TO-ADOPT (low value)
- **Hook:** `io.github.manasmods.manascore.race.api.RaceEvents.SET_RACE`.
- **Replaces:** `DiplomacyManager.tickSideWatch` (`DiplomacyManager.java:1666`),
  which polls `WorldReputationManager.isMajinSide(player)` every 100 ticks to
  detect a majin flip and downgrade Holy-bloc PACT→OPEN.
- **Rationale:** cleanliness only (event-driven vs a cheap 5 s poll) — **not**
  correctness or measurable perf. Low priority. `[verify in-game]` that
  `SET_RACE` fires on every path that changes a player's effective side.
- Ref: `deps/tensura.md` §8.

### 3. Raid scheduler convergence — `IRaiderManager` — INVESTIGATE
- **Hook:** MC `IRaiderManager` (per-colony `RaidManager`).
- **Current state:** NOT a clean duplication — we already use its utilities
  (`calculateSpawnLocation`, `willRaidTonight`) and deliberately run our own
  scheduler on the 1 s tick because ours is **reputation-tier-triggered** with
  tiered Tensura rosters (see `docs/raid-system.md`). 
- **Open question:** whether any of our scheduling could hand back to MC's
  nightfall/difficulty machinery without losing the rep-tier trigger. Scope
  before changing anything. Refs: `deps/minecolonies.md` §8, `docs/raid-system.md`.

### 4. Non-MC schematic placement — `StructurePlacementUtils` — NOTED
- **Hook:** `com.ldtteam.structurize.placement.StructurePlacementUtils.loadAndPlaceStructureWithRotation(Level, Blueprint, BlockPos, RotationMirror, boolean, Player)`
  — a trap-safe one-call helper taking a resolved `Blueprint`.
- **Why unused (intentional):** for MC blueprints we use MC's
  `CreativeBuildingStructureHandler` for its per-building block-substitution /
  domum parity (`RivalColonies.placeBuilding`). The Structurize helper is the
  right shape only for **non-MC** packs — a documented fallback, not a planned
  change. Ref: `deps/structurize.md` §8.

### 5. Flying-defender pathing — `tickReflectiveTensuraMobilityAssist` — TO-ADOPT
- **Hook:** `NightmareUtilsApi.tickReflectiveTensuraMobilityAssist(LivingEntity mob, LivingEntity target)`
  — drives a Tensura mob's own mobility skills (flight, instant transmission)
  toward a target.
- **Replaces / complements:** our raw `WALK_TARGET` steering for defenders /
  garrison / raiders (`ColonyThreatResponse`, `TensuraRaids`, `RivalColonies`),
  which paths flying Tensura mobs poorly.
- **Rationale:** behavior/quality (grounded mobs are unaffected; flyers path
  better) — an enhancement, not a bug fix. `[verify in-game]` that it improves
  flyer pursuit without fighting our steer. Ref: `deps/nightmares-utils.md`
  "Beyond the autocaster".

## Barrier in-field spawn suppression — environmental spawn types (2026-07-10)

**Decision:** a fueled barrier suppresses hostile spawns inside its footprint
across the whole *environmental* spawn set, not just `NATURAL` +
`CHUNK_GENERATION`. The scope was a genuine design choice (user-suggestion
2026-07-10 #3); the user picked "block involuntary/world spawns, leave
deliberate placement alone."

- **In (blocked):** NATURAL, CHUNK_GENERATION, SPAWNER, TRIAL_SPAWNER, PATROL,
  REINFORCEMENT, JOCKEY, STRUCTURE, EVENT, TRIGGERED — the set
  `ExampleMod.BARRIER_BLOCKED_SPAWN_TYPES`.
- **Out (allowed):** SPAWN_EGG, COMMAND, DISPENSER, MOB_SUMMONED, BREEDING,
  CONVERSION, BUCKET — so a player can still deliberately place a mob inside the
  field, and the mod's own SPAWN_EGG raid/envoy/garrison/defense spawns are never
  self-blocked.
- **Two hooks, one predicate.** Only NATURAL/CHUNK_GENERATION/SPAWNER reach
  `MobSpawnEvent.PositionCheck` (verified in NeoForge 21.1.233 sources:
  `NaturalSpawner` + `BaseSpawner`). The rest reach `finalizeSpawn` only, so a
  second hook on `FinalizeSpawnEvent` (`setSpawnCancelled(true)`) is required —
  neither hook alone covers the set. Both call the shared
  `shouldBarrierBlockSpawn` (type in set AND in the `barrier_blocked` tag AND
  inside a fueled footprint).
- **Why the `barrier_blocked` tag, not `MobCategory.MONSTER`:** Tensura
  registers goblins/orcs as MONSTER despite being passive-aggressive, so the
  category over-blocks. The tag = Tensura's curated `#tensura:hostile_monster` +
  the vanilla hostiles it omits — the same tag the field pushback uses, so "a
  mob the barrier repels" == "a mob it won't let spawn." This is also why TENSURA
  hostiles are covered (they're in that tag) rather than only vanilla monsters.
- **Raids stay orthogonal.** Raiders spawn via direct `finalizeSpawn(SPAWN_EGG)`
  (exempt) + `addFreshEntity` (posts neither event), and the raid placement fix
  already keeps them outside the field. Two independent belts.
- **Rationale:** correctness/coverage — closes the "dungeon spawner / patrol pops
  a hostile inside my dome" gap without breaking intentional spawns. `[verify
  in-game]` — playtesting.md §1b. Ref: raid-system.md "IN-FIELD SPAWN
  SUPPRESSION — BROADENED".

## Barrier centers on the town hall; cores network per colony; layer-3 buff splits DL/Hero (2026-07-13)

**Decisions** (user-directed, options confirmed via Q&A):

1. **Field center = town hall** when the core sits inside a colony's CLAIMED
   area (claimed-chunk lookup, not closest-colony; colony center used until a
   town hall exists). Core outside any claim keeps the old self-centered
   behavior. Rationale: the barrier protects the colony, so it should wrap the
   colony's heart regardless of where the core physically fits.
2. **One barrier per colony, cores pool.** Multiple cores claimed by the same
   colony merge: highest tier (tie-break lowest BlockPos) is the elected
   PRIMARY driving field/sections/layers/render/menu at ITS radius; the rest
   are tank-only secondaries. Capacity/pool = all member tanks + the DEDUPED
   union of their storage networks. Chosen over per-core concentric spheres
   (visual mess, unclear section semantics) and over summing radii (unbounded).
3. **Layer-3 buff moved off tier, split by the raiser's status.** The old
   tier-3+ "+10% player magicule regen for anyone" is gone; raising the THIRD
   layer (already DL/Hero-gated) now grants: Demon Lord → the +10% magicule
   regen (unchanged mechanics), Hero → citizens inside get Regeneration II +
   Absorption (user picked "citizen blessing" over an aura-regen mirror, guard
   buffs, or citizen damage reduction). DL wins if a player somehow holds both
   titles. Buff type persists and is re-derived on the per-second gate check.
4. **Magicule Storage kept, fate deferred.** Core pooling overlaps storage's
   capacity role; options (repurpose as trickle-refill/repair-battery/bank,
   keep, remove+refund, or lower core base capacities) recorded in
   future-ideas.md — decide in a balance pass, not by drift.
5. **Evil barrier variant** (tiered ability-suppression field, EP-limited per
   enemy) recorded in future-ideas.md as an idea needing a design pass.

**Known consequence:** a core placed near the claim edge can sit OUTSIDE its
own sphere (radius still per tier) — exposed to attackers. Accepted; the fix
is player-side (higher tier or better placement).

## Enchanted / engraved reward stacks — registry-aware rewards (2026-07-06)

Diplomacy deal rewards can now include ENCHANTED / Tensura-ENGRAVED gear.
Engravings are just enchantments tagged `#tensura:engraving`, so one path
covers both.

**Why a mechanic was needed at all:** enchantments live in the DYNAMIC
per-world registry (`Registries.ENCHANTMENT`), reachable only via a
`RegistryAccess`/`HolderLookup.Provider` at runtime — never at `DealSpec`
static class-load (where the reward `ItemStack`s are built). So an enchanted
stack cannot be baked in as a data literal.

**Chose approach B over A (user decision).** A = store enchant INTENT, apply
only in `giveItems`. B = the reward IS the finished stack for every consumer.
Picked B because (1) conquest loot (`factionRewardPool`) and the UI summary
also read rewards, and (2) the planned Dwargon "Masterwork Trade" is a whole
catalog of pre-engraved gear — B's plumbing is needed there anyway.

**Implementation (low-churn B):** added an 11th `DealSpec` component
`List<EnchantedReward> enchantedRewards` with a DELEGATING 10-arg constructor,
so all ~120 plain-reward deal literals are unchanged. `EnchantedReward(item,
count, List<EnchantSpec>)` builds its stack via `HolderLookup.Provider`;
`EnchantSpec(ResourceKey<Enchantment>, level)` stores a registry KEY (safe at
class-load). `DealSpec.resolvedRewards(provider)` = plain rewards + built
enchanted ones, and is now the SOLE reward accessor (giveItems ×3,
factionRewardPool). Helper `engraving(path)` → a `tensura:` enchantment key.
First use: Falmuth "I Need More Steel!" (Diamond Sword: Sharpness III + Looting
+ Unbreaking). ⚠ Compiles; not yet runtime-verified in-game.

## Drago Nova charge-up animation (2026-07-15)

Drago Nova used to detonate instantly. Now `use()` (and the Sage-warning confirm
path) call `beginCharge` → a ~2.5s (`CHARGE_TICKS` 50) wind-up before the blast.

**Chose a floating `ItemEntity` orb over a custom entity or ItemDisplay.** An
`ItemEntity` (no-gravity, `setNeverPickUp`, `setUnlimitedLifetime`, invulnerable)
renders the floating item for free with zero registration and no renderer. It's
pinned each tick (`setPos` + zero delta) so it can't drift/merge.

**Driven from the existing `ServerTickEvent.Post` handler**, EVERY tick (not the
per-second block) because the rising orb + converging particles need per-tick
smoothness. `DragoNovaItem.tickCharges` early-returns when idle (a static list),
so there's no idle cost. Blast fires at the orb's risen position (not the
player's) → walking away moves the blast; logout mid-charge still blasts, only
the self-backlash is skipped.

**Particles version-safe:** deliberately AVOIDED `DustParticleOptions` (its ctor
is `(Vector3f,float)` in 1.21.1 but `(int,float)` in later mappings — confirmed
`(int,float)` in the compile jar). Used built-in blue particles instead:
`SOUL_FIRE_FLAME` streamed inward via `sendParticles(count=0, velocity, speed)`
(count 0 makes dx/dy/dz the velocity), a growing `GLOW` shell (radius tracks
progress) for the bubble, `SOUL` core glow.

## Absolute Annihilator — custom item, EP-gated effect ladder, charged sprite (2026-07-15)

The Milim capstone weapon. Design landed over several passes; final state:

**Weapon EP is a datapack thing, not an item interface.** Tensura weapon EP comes
from a `tensura:gear_existence` registry entry keyed by item id (merges across
namespaces, so we ship it under our own). `GearHandler` stamps the EP components
on equip/pickup and grows them on kills. No Java EP code needed. Our entry:
`minEP 10k`, hard cap `maxEP 1M`, `epGain 0.01`, `uniqueEvolutions` adding
attack damage + speed + knockback resist + max health at 150k/400k/700k/1M.
⚠ CORRECTED 2026-07-22 — this originally said "only the highest-reached tier's
set applies". It does NOT; the tiers compound. See "gear_existence
uniqueEvolutions COMPOUND" below.

**Charged sprite via an item-model override, not a second item.** A `_charged`
texture (derived from the base, dark pixels lit to electric cyan) + model, swapped
by a client `ItemProperties` property `tensura_minecolonies:charged` that reads
`TensuraDataComponents.EP >= CHARGE_EP`. Mirrors Tensura's own active/inactive
weapon pattern (e.g. hihiirokane_scythe). Threshold = `AbsoluteAnnihilatorItem.
CHARGE_EP` (500k), a single constant shared with the ability so they can't drift.

**Non-attribute "effects" live in a custom `AbsoluteAnnihilatorItem` (SwordItem).**
`use()` fires the Drago Nova blast (shared `DragoNovaItem.triggerAnnihilatorNova`,
no item consumed) at ≥500k EP on an EP-scaled cooldown (60/45/30s). `hurtEnemy()`
adds on-hit Weakness (≥150k), lifesteal (≥700k), and a hostiles-only sonic-boom
AoE shockwave (≥1M, spares players/citizens/ally+race-tagged). Stats stay in the
gear_existence evolutions; effects stay in code — clean split.

**500k charge / 1M cap (user).** maxEP was briefly 2M (headroom) then set to a
hard 1M cap with the charge at 500k midpoint. EP grows as `min(current+gain,
maxEP)` so it reaches the cap exactly; 500k is reachable well before it.

**No hardcoded enchants; earns a material-line engraving instead (user).** Dropped
the deal's pre-applied crushing + Sharpness V + Unbreaking III `EnchantedReward`;
the deal now grants the hammer PLAIN. It instead carries `tensura:holy_coat` 3 via
its gear_existence `engravings` (the mithril/adamantite material line; force-
stamped past holy_coat's anvil `max_level` 1 exactly as Tensura's mithril data
does at level 2, bumped to 3 for our 1M EP). Chose holy_coat over the hihiirokane
line's `tsukumogami` because tsukumogami is penalty-only without an activation
mechanic we don't drive. Durability lowered to 2031 (== a netherite axe, user).
⚠ All of the above compiles; NOT yet runtime-verified — esp. the evolution stat
numbers (compound-vs-replace is Tensura-internal) and the particle look.

## Custom weapons must join Tensura's item tags (0.2.1, 2026-07-22)

**Every custom weapon ships with the item tags its Tensura counterpart has.**
Tensura's engravings all declare `"supported_items": "#tensura:handheld_enchantable"`,
which resolves down to the VANILLA tags (`#minecraft:swords` / `#minecraft:axes`
/ `#minecraft:enchantable/*`), and Tensura populates those from its own datapack.
A weapon that is in no tag fails `Enchantment.canEnchant` for *every* engraving
and every vanilla enchantment — and, less obviously, silently never earns the
random engravings Tensura grants as gear EP crosses its milestones
(`EngravingHelper.getRandomEngraving` filters the candidate list with that same
check and just returns null when nothing survives). That's not an error anywhere;
the weapon simply never gets one. Extending a Tensura item class is NOT enough,
because tag membership is data, not type.

Rule of thumb: when adding a weapon, find its closest Tensura counterpart and
copy its tag membership verbatim (we mirror hihiirokane). Deliberately accepted
side effect: the counterpart's tags can offer enchantments our item can't use
(Riptide on the spear, Silk Touch on the sickle, since ours are all `SwordItem`s).
Parity with the counterpart was judged more valuable — and less surprising — than
hand-curating a tag set per weapon.

## Weapon abilities go through Tensura's on-hit path, never raw `hurt()` (0.2.1, 2026-07-22)

Shared helper: `WeaponAbilities`. Any custom-weapon ability that damages
something goes through `WeaponAbilities.hit(...)` rather than calling
`target.hurt(...)` itself. Three reasons, all of which bit us in 0.2.0:

1. **Invulnerability frames.** Vanilla charges a second hit inside a 10-tick
   window only `amount - lastHurt`, and drops it entirely when it isn't bigger.
   Since a player naturally swings and then right-clicks, an ability that scales
   off the same attack-damage attribute as the swing always lands a tiny
   remainder — the reported "always deals like 2 damage, doesn't scale with
   anything". Abilities zero `invulnerableTime` first. Tensura's own Battlewill
   arts do the same thing (they also force it to 40 afterwards to stop
   multi-hits; we don't need that, our abilities hit each target once).
2. **The on-hit pipeline.** Engravings run from
   `TensuraEnchantmentHelper.doAdditionalAfterAttack/AfterDamage`, installed by
   Tensura's mixin on `Player.attack`. Damage dealt outside that path triggers no
   engraving at all. `hit()` mirrors the exact recipe Tensura's arts use:
   `hurtEnemy` → `EnchantmentHelper.doPostAttackEffectsWithItemSource` →
   `doAdditionalAfterDamage` → `doAdditionalAfterAttack`. The `runItemOnHit`
   flag exists for splash damage spawned FROM a weapon's on-hit effect, which
   must not re-enter it (the Annihilator shockwave).
3. **Named attacker.** An ownerless `damageSources().magic()` is environmental
   damage to Tensura: no kill credit, no EP gain, no subordinate/ally veto in
   `DamagingHandler`, and no path for the wielder's magicule to bypass a
   target's magic interference. Ability sources always name the wielder —
   `tensura:magic` (`TensuraDamageTypes.MAGIC_GENERIC`) for magic, `player_attack`
   for physical.

Ability damage is floored at the weapon's own attack damage read off the stack
(`WeaponAbilities.weaponAttackDamage`, mirroring Tensura's
`TensuraDamageHelper.getWeaponBaseDamage`), so it keeps scaling with the gear's
EP evolutions even when the wielder's attack-damage attribute doesn't include the
weapon (off-hand use).

## gear_existence `uniqueEvolutions` COMPOUND (0.2.1, 2026-07-22)

**The four numbers in an EP ladder are INCREMENTS, not "the stats at that tier".**
`GearHandler.applyUniqueGearEvolution` picks the LOWEST tier the weapon has
reached and not yet applied, adds its amounts to the stack's CURRENT
`ATTRIBUTE_MODIFIERS`, then deletes that tier from the stack's remaining list —
so over a weapon's life all four amounts sum. 0.2.0 was authored on the opposite
belief (an earlier note in this file, now corrected, claimed only the
highest-reached tier applied), which is how a Masterwork katana ended up reading
184 attack damage against an intended cap of 128.

Two further facts, both verified in the bytecode:

- `getEvolvedAttributeModifiers` only bumps attributes the BASE item ALREADY
  declares — it iterates the current modifiers and looks each one up in the
  tier's map, so a tier entry for an attribute the item doesn't have is silently
  dropped. The Absolute Annihilator's knockback-resistance and max-health steps
  had never done anything for exactly this reason. **Fix: declare the attribute
  on the base item at 0.** That costs nothing visually — vanilla's
  `ItemStack.addModifierTooltip` only renders a line for `amount > 0` or `< 0`,
  so a zero modifier is invisible until a tier raises it. Any future weapon whose
  ladder grants an attribute MUST declare that attribute (at 0 if it shouldn't
  start with it).
- Stats are **baked into the stack**, so re-tuning the datapack does nothing for
  weapons that already exist, and a player can't re-forge without spending
  another Masterwork Core. `GearEvolution.recalibrate` closes that: once a second
  it rebuilds a stack's stats from the item's current base attributes plus
  exactly the tiers its EP has reached (folded through Tensura's own helper, so
  the maths can't drift from `GearHandler`'s), and restores the not-yet-reached
  tiers to the stack's remaining list. A correct stack compares equal and is left
  untouched. Any future ladder re-tune now self-heals; do NOT hand-migrate.

## Masterwork weapons are positioned RELATIVE to their counterpart (0.2.1, 2026-07-22, user)

`MasterworkItem.START_OFFSET` (-30) and `MAX_OFFSET` (+2) are the whole balance
statement: a freshly forged Masterwork sits 30 damage BELOW the hihiirokane
weapon it consumed, and at max EP ends 2 ABOVE it. `EVOLUTION_STEPS`
(+5/+7/+9/+11, cumulative) closes exactly that 32-point gap, and
`ExampleMod.masterwork()` takes the COUNTERPART's damage param so nothing has to
be recomputed by hand per weapon.

The earlier reading — base = counterpart + 2, growing far beyond — was a
misinterpretation of "make it +2 over hihiirokane" (user, 2026-07-22: "I meant
the MAXIMUM damage, not the base"). Hihiirokane weapons are the top of Tensura's
evolution chain and have NO evolutions of their own, so their number is fixed and
"+2 at the cap" is unambiguous. The Masterwork's identity is the growth, the
abilities, the durability/enchantability and the self-repair — not raw damage.

## Naming an EXISTING citizen is a rename, never a second registration (0.2.1, 2026-07-22)

`onRaceNamed` must look the mob up in `RaceIdentitySavedData` before it does
anything else. A mob standing next to the player is not necessarily a stranger:
a colony-born child, or any citizen the player summoned out of the colony, still
owns a `RaceIdentity` and a `CitizenData` — and has no Tensura name yet, so the
naming menu opens on it normally.

The reason this MUST be guarded rather than merely tidied: `addIdentity` keys the
reverse index by mob UUID, one entry per mob —

```java
mobUUIDToIdentityId.put(identity.mobEntityUUID, identity.identityId);
```

— so a second registration for the same body silently DISPLACES the first. Every
later lookup (`getByMobUUID`: send trigger, death hook, roster) resolves to the
new record and the old one becomes unreachable, while its `CitizenData` keeps its
housing slot and stays travelling-suppressed forever. That is a citizen slot with
nothing behind it, and nothing in the codebase would ever have noticed.

The same shape applies to the pending pool (`renamePending`, not a second
`addPending`) — two pending entries for one mob promote to two citizens.

**Rule:** any future path that registers a citizen from a live mob checks
`getByMobUUID` first. Treat "one mob ⇒ at most one identity" as an invariant of
`RaceIdentitySavedData`, because the reverse index enforces it destructively
rather than by rejection.

## A citizen's age must be settled BEFORE its body spawns (0.2.1, 2026-07-22)

MineColonies stamps a new body from the CitizenData in the same tick as the
spawn (`addFreshEntity` → `registerWithColony` → `registerCivilian` →
`setEntity` → `setCivilianData` → `initEntityValues` →
`citizen.setIsChild(this.isChild())`). So any code that spawns a citizen body and
then corrects its age has already lost: the body is briefly wrong, and the client
is wrong for much longer than that.

The client part is worth remembering on its own: `EntityCitizen.isBaby()` reads a
private cached field, NOT the synced `DATA_IS_CHILD`, and that field is assigned
on the client in exactly one place — `CitizenColonyHandler.updateColonyClient()`,
which runs from the `ACTIVE_CLIENT` state that a fresh body only reaches after
leaving `EntityState.INIT`, on a **40-tick** timer. `LivingEntityRenderer` sets
`model.young = entity.isBaby()` every frame, so a child renders full-size for up
to two seconds. `mixin/EntityCitizenBabyMixin` closes that by falling back to the
synced value on the client. Treat `isBaby()` on a freshly spawned citizen as
UNRELIABLE without that mixin.

## The CITIZEN owns the age, the mob copies it (0.2.1, 2026-07-22)

Two independent child flags exist and both have to be written:
`EntityCitizen.setIsChild` (the body you see) and `CitizenData.setIsChild` (the
durable one). MineColonies does not sync them, and only the CitizenData survives
a body rebuild — writing just the entity's silently reverts.

Direction of truth: on SUMMON the mob's baby state is set from
`citizenData.isChild()`; on SEND both citizen flags are set from
`goblin.isBaby()`. Do NOT trust `entitySnapshot` for age. A colony-born child's
snapshot is captured in `mintRaceChildCitizen` from a transient ADULT mob spawned
only to roll an appearance, and it is never refreshed by a send — which is why
summoned babies used to arrive fully grown.

## Colony-born citizens are auto-named subordinates (0.2.1, 2026-07-22, user)

A child born in a race colony is the owner's NAMED SUBORDINATE from birth,
carrying the name MineColonies gave it — no summon-and-hand-name step. This is
also what removes the last reason to point the naming menu at your own citizen,
the action that produced the phantom (see the entry above).

`ExampleMod.applyAutoNaming` is the sole door. It copies the "this is yours and
it is called X" half of Tensura's naming commit — `IExistence.setName`,
`setCustomName`, `IExistence.setPermanentOwner`, `TamableAnimal.setTame` +
`setOwnerUUID` — and deliberately omits two things Tensura's ceremony also does:

- **the name-evolution** (`INameEvolution.onPreNamed`, what turns a named goblin
  into a hobgoblin). Evolving every newborn would hand the colony a population of
  hobgoblins for free and make hand-naming meaningless. A child is a baby of its
  own race and evolves the normal way.
- **the energy transfer**. Naming spends the namer's magicule and pours it into
  the named. Nobody is present paying for a birth, so nothing is charged and
  nothing is granted.

Ownership is only ever CLAIMED, never reassigned — an existing owner and the
subordinate's chosen command behaviour are left alone — but the NAME is re-synced
from `CitizenData` on every summon, because the citizen's name is the single
source of truth (renaming the citizen renames the mob, and our naming-menu guard
turns "name this citizen" into "rename this citizen"). That re-sync also repairs
citizens minted before auto-naming existed.

Free side effect: Tensura's own `canName` refuses a mob that is already named or
already owned by the asker, so the naming menu simply won't open on a colony-born
citizen — a second, upstream lock on the duplicate-citizen path.

## Only the four RACES take citizen slots (verified 2026-07-22)

Confirmed on request: a named non-humanoid subordinate (direwolf, spider, slime,
tempest serpent, …) never consumes a colony citizen slot. `onRaceNamed` filters
on `Races.of(entity.getType())` — which only maps `tensura:goblin`, `orc`,
`lizardman` and `dwarf` — and returns before any `createAndRegisterCivilianData`.
Every other citizen-creating call site is accounted for and none of them can be
reached from naming a mob: the pending-pool drain (entries only ever created
after that same race filter), `mintRaceChildCitizen` (colony reproduction, race
drawn from the colony's own member set), the conquest levy and the diplomacy
lend-return (both plain colonists), and the envoy path (which uses the VISITOR
manager — visitors are not citizens).

## Barrier size is a per-colony CHOICE inside an earned band (0.2.1, 2026-07-22, user)

Players were outgrowing the barrier: the radius was locked to the primary core's
tier (16/28/42/60) with no way to cover a bigger colony. The radius is now
chosen in the core menu, between `MIN_RADIUS` (8) and a maximum the colony earns
— the primary's tier radius plus, for every OTHER core in the network, that
core's own tier × `RADIUS_PER_EXTRA_CORE_TIER` (2), i.e. **+2/4/6/8 blocks for an
extra tier-1/2/3/4 core** (user-specified). Capped at `RADIUS_HARD_CAP` (128).
Tiering the bonus means a spare tier-4 core is worth four spare tier-1s, so the
cheap-core-spam route is deliberately weak.

Design points worth keeping:

- **The choice lives on the network PRIMARY**, next to the layer count and the
  shared pool, and every menu path already routes through `resolveMenuTarget`.
  That is what makes the whole thing per-COLONY rather than per-block or
  per-player for free: click any core, edit the same field. Requirement met by
  the existing network design, not by new code.
- **Clamp on READ, not on write.** `getRadius()` clamps the stored number into
  the current band each time it is asked. Losing a core therefore shrinks the
  field immediately, but the player's chosen size survives, so rebuilding the
  core restores it instead of silently leaving the barrier small. Writing a
  clamped value would have thrown their setting away.
- **`networkCoreCount` is synced** because the client renderer calls
  `getRadius()`, and the clamp must land on the same number on both sides.
- Nothing downstream needed touching: layers, collision, raid steering, hostile
  spawn suppression and the renderer are all derived from `getRadius()` already.

**Size costs fuel (user, 2026-07-22).** The barrier used to be free to hold up
at one layer, which made the tank matter only for repairs. Now every active
shell costs `UPKEEP_BASE_PER_LAYER` (10/s) plus `UPKEEP_PER_RADIUS_BLOCK` (1/s)
per block of ITS OWN radius, so a bigger field, an extra layer, and the fact that
outer shells sit further out all push the bill up. Charged per shell rather than
as one figure × layers because that falls out of the geometry and needs no extra
constant.

The numbers are set so each tier's full tank lasts about an hour at that tier's
DEFAULT size (t1@16 = 26/s on 100k; t4@60 = 70/s on 250k) — capacity and size
cost scale together on purpose, so upgrading a core is not secretly a downgrade.
Dialling down to the minimum stretches a tier-4 tank to ~4 hours; running three
layers cuts it to ~20 minutes.

Still not done: panel health is per-TIER, not per-area, so a big barrier is
thinner per unit of wall. Flagged in raid-system.md as an unplayed seam rather
than guessed at here.

## Bred children inherit race from their PARENTS (0.2.1, 2026-07-23, user)

Reproduction used to pick a child's race by a uniform draw from the colony's race
set (`pickRandomMember`), so two goblins could bear a lizardman. Now the parents
decide (user rule): both parents → 50/50 of the two parents' races; one parent →
that parent's race; NO parents → the old colony draw (fallback only). A parent's
"race" is its `RaceIdentity` race, or COLONIST when it has none. A COLONIST result
leaves the vanilla human MC already created.

**Why the reproduction mixin had to MOVE.** The old hook was `@WrapOperation` on
`createAndRegisterCivilianData()` — but `trySpawnChild` assigns firstParent /
secondParent AFTER that call, so the parents don't exist there. The hook is now
`@Inject` before the `spawnOrCreateCitizen` invoke, capturing `@Local`
newCitizen(slot 5)/firstParent(6)/secondParent(7) (LVT-verified for MC 1.1.1319,
and runtime-verified that the injection applies — see playtesting.md 000).
Injecting there also lands after `generateName` (child name is final when we
stamp it) and after MC's parent-skill init, so our race skill bias now layers ON
TOP of inherited parent skills instead of being clobbered before them — a latent
bug fixed as a side effect. It still runs before the body spawns, which
`mintRaceCitizen` requires.

Either parent may be null; guard for it. `mintRaceCitizen` gained an `asBaby`
param (true for births, false for the grown seed/immigrant intake below).

## Race intake beyond birth — envoy seed + free immigration (0.2.1, 2026-07-23, user)

MineColonies grows a colony from its ~4 INITIAL citizens to the 250 cap almost
entirely by REPRODUCTION; the only other routes are the paid Tavern hire (HIRED)
and edge cases. So a diplomacy-unlocked race would never appear on its own — you
had to go name a wild one. Two additions fix that (user vision, 2026-07-23):

- **Envoy seed.** Accepting an envoy spawns ONE grown citizen of that race at the
  town hall immediately (`spawnColonyMember`), so the alliance is concrete rather
  than only a permission.
- **Free immigration.** A per-colony pass on the envoy scheduler's per-second
  loop (cooldown `IMMIGRATION_COOLDOWN_TICKS` 2400): for any member of the
  colony's race set below `IMMIGRATION_RACE_FLOOR` (3), spawn one grown citizen.
  Pick = 2/3 the least-represented eligible race, 1/3 a random OTHER eligible
  (user split), which keeps races roughly equal and lets a race appear even with
  no breeding pair. COLONIST is a race here too, so it can't quietly dominate.

Design choices worth keeping:
- **The floor is per-RACE, not per-colony**, and immigration NEVER pushes a race
  above 3 — it can only prevent under-representation, never cause dominance.
  Growth past 3 is births (parent-driven) + Tavern. This is what makes it
  "balanced with other spawning mechanisms" without coordinating with them.
- **Counting:** race count = `RaceIdentity` records for (colony, race); COLONIST
  count = total citizens − all race identities. Slightly conservative if orphan
  identities linger (under-serves that race rather than over-spawning) — safe.
- Immigrants + INITIAL both run early, so a new colony fills a little faster than
  vanilla. Accepted. Immigrants spawn at the town hall regardless of housing
  (unhoused = happiness hit); gating on a free bed is a possible later refinement.
- `IMMIGRATION_COOLDOWN_TICKS` (2400) and the floor (3) are the tunable knobs;
  the floor and 2/3-1/3 split were user-specified.

## Guards don't attack faction ALLIES — NeoForge target veto, not ManasCore (0.2.2, 2026-07-26)

The PACT/COVENANT ally fighters we spawn to help defend a raid are Tensura
`goblin`/`lizardman` mobs, which are `MobCategory.MONSTER` (bytecode-confirmed) —
so MineColonies guard towers auto-list and attack them. Fixed by
`ExampleMod.onLivingChangeTarget`, a NeoForge `LivingChangeTargetEvent` handler
that cancels a colony citizen targeting an `ALLY_TAG` mob of its own colony.

**Why NeoForge's event, not ManasCore's `LIVING_CHANGE_TARGET`** (the one
`subordinate-citizen-targeting.md` recommends for the mirror "subordinates attack
my citizens" case): the two problems have DIFFERENT attackers.
- Subordinate→citizen: the attacker is a TENSURA mob; it commits assist-targets
  through Tensura's `RetaliateOrTarget`, which fires ManasCore's Architectury
  `EntityEvents.LIVING_CHANGE_TARGET`. That's the right hook there.
- Guard→ally: the attacker is an MC `AbstractEntityCitizen`, NOT a Tensura mob.
  It picks from a `ThreatTable` and commits via `TargetAI.onTargetChange →
  Mob.setTarget` (verified in the MC jar). `Mob.setTarget` fires NeoForge's
  `LivingChangeTargetEvent` for ANY mob, so that's what catches a guard; the
  ManasCore event would never fire for a non-Tensura entity.

So the project now has TWO sibling target vetoes for two entity families. The
subordinate→citizen veto (ManasCore) is still unbuilt (separate investigation);
this one (NeoForge) is built for the ally case.

**Scope + caveats.** Gated attacker-first (`instanceof AbstractEntityCitizen`)
then the `ALLY_TAG` attachment check, so whole-game target traffic early-returns
cheaply. Vetoes for the ally's OWN colony (or an unresolvable colony —
conservative); a confirmed different colony's guards may still treat the mob as
wild. It's an ACQUISITION-time veto (blocks committing the vanilla target), not a
threat-table exclusion — a residual target-thrash risk (a guard fixating on a
low-threat ally) is flagged for playtest in potential-bugs.md, with the escalation
(mixin `TargetAI.isEntityValidTarget`) noted if it appears.
