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
that hits the ≥25 orc threshold gets a dumb-friendly *"Your village
is so big now!"* acknowledgement instead of base-only text.

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

**Stage L3-hotfix-6 — three coupled bugs + one polish.**

**Bug E — `JobBeastGuard.entry` null on legacy/reloaded citizens.**
The L3-hotfix-5 fix routed NEW assignments through
`JobEntry.produceJob` so `setRegistryEntry` got called. But
citizens that were assigned BEFORE the fix have JobBeastGuard
saved to NBT with the registry entry NEVER set, and reloading
them through MC's deserialise path doesn't call produceJob.
Same NPE returns. Fix: set `entry` in `JobBeastGuard`'s
constructor too — defensive, harmless when produceJob ALSO
calls it (overwrites with same value), but covers every other
construction path.

**Bug F — spider visual lost on player walk-away/return.** MC
discards citizen entity bodies on chunk unload and respawns a
FRESH `EntityCitizen` instance when the chunk reloads. The new
entity has none of the data attachments — including BeastTag —
even though the persisted NBT carried them on the OLD entity
that got discarded. `PlayerEvent.StartTracking` then fires for
the new entity, our handler sees no BeastTag (server-side gone
too), and the re-sync block doesn't fire. Result: spider visual
gone, plain humanoid citizen.

Fix: extend `onStartTracking` to RE-ATTACH the BeastTag if
missing. Look up the citizen's identity via citizenId →
`RaceIdentitySavedData.getByCitizenId`. If found and is a beast
identity, construct a fresh BeastTag from
{@code (identity.identityId, identity.beast)} and attach it,
pinning SCALE=1.0 to keep the humanoid hitbox. The existing
beast-tag re-sync block immediately below picks up the
freshly-attached tag and broadcasts to the player.

This also retroactively fixes any worker-race citizens that
were silently losing their visuals on respawn (same root cause,
same gap) — though the re-attach is currently beast-only since
race tags carry per-citizen variant data which the identity
doesn't store. Race re-attach is a separate stage.

**Bug G — "your goblin" hardcoded messages.** User saw "your
goblin's body isn't loaded" when summoning a knight spider
citizen. Replaced every user-facing "goblin" string in
`handleMenuAction` / `handleConfirmCollapse` / display-name
fallback with race-/beast-neutral terms ("citizen",
"subordinate"). Logs untouched (those are debug-internal).

**Polish — spider leap attack.** `BeastGuardCombatAI.doAttack`
overrides the parent's melee swing with a leap-then-melee
combo. On each combat tick, ~35% chance (cooldown 80 ticks /
~4s) to lunge the spider toward the target with `setDeltaMovement`
+ vertical lift, marking `hurtMarked = true` so MC syncs the
velocity. The leap fires only when the spider is out of melee
range (4+ blocks from target). Damage resolves via the standard
melee path on the next swing once the spider lands close. This
is the manual port of `KnightSpiderEntity.getFightTasks`'s
`LeapToTarget` SmartBrainLib behaviour — runtime conversion
since MC citizens don't run SmartBrainLib. Ranged spit and
slam-from-fall (`isSlammingFall`) deliberately omitted — they
need their own state-machine states; left for a later stage.

**Stage L3 polish — spider hit-react animation.** The shadow-render
mirror was copying position / rotation / walkAnimation / pose /
sprint / equipment from the citizen onto the shadow spider every
frame, but NOT the hit-react fields (`hurtTime`, `hurtDuration`,
`deathTime`). Result: the spider visual was stoic regardless of
damage taken — knockback already worked (the existing
`setDeltaMovement` + `setPos` mirror carried velocity-driven
movement through), but no red flash, no rotation wobble, no death
animation. Fix: mirror the three public hit-react fields onto the
shadow each frame. GeckoLib's `GeoEntityRenderer` (via inherited
vanilla `LivingEntityRenderer` behaviour) reads them automatically.
`hurtDir` is not a public field in 1.21.1 (driven via the
`knockback(...)` parameter directly) so the direction-of-hit wobble
is omitted; the red-flash + duration alone is the visually
prominent piece.

**Stage L3-hotfix-5 — `getJobRegistryEntry()` NPE on every beast
kill.** Crash log shows endless spam:
{@code NPE: Cannot invoke "JobEntry.getKey()" because the return
value of "IJob.getJobRegistryEntry()" is null} originating from
`KnightCombatAI.onTargetDied` → `CitizenSkillHandler.levelUp` →
`SoundUtils.playSoundAtCitizenWith`. The chain fires every time
the spider kills something (XP grant + level-up sound lookup).

Root cause: `assignBeastToTower` was constructing
`new JobBeastGuard(citizenData)` directly. The job's internal
`entry` field is populated by `JobEntry.produceJob` via
`IJob.setRegistryEntry(this)` — skipping that path leaves `entry`
null forever. `AbstractJob.getJobRegistryEntry()` is a trivial
{@code return this.entry} so any downstream code expecting the
registry entry NPEs.

Functionally the spider still attacks (damage lands BEFORE the
XP-grant in `onTargetDied`), but XP gain is lost and the log
fills with the swallowed-exception spam.

**Fix**: construct via `ModJobsRegistry.BEAST_GUARD.get().produceJob(citizenData)`
instead of the direct constructor. `produceJob` runs the producer
then calls `setRegistryEntry(this)` on the result. The
`preLinkWorkBuilding` call still applies right after.

**Stage L3-hotfix-4 — assignment silently failing → spurious
no-tower advisory + paused beast.** User report: spider still not
patrolling AND the "no Guard Tower" advisory fires even when a
tower clearly exists in the colony. Both symptoms trace to
`module.assignCitizen` silently returning false. Three reject
conditions found in the disassembled bytecode:

1. **Wrong tower type**: `findFirstGuardTower` previously
   instance-checked `BuildingGuardTower` ONLY — missed
   `BuildingBarracksTower`, `BuildingArchery`, future mod
   subclasses, and any colony where the player has a Barracks
   Tower as their only guard-capable building.
2. **Tower full / level-0**: `assignCitizen` returns false if
   `list.size() >= sizeLimit`. A newly-placed (level-0) tower can
   have `sizeLimit == 0` → rejects all assignments.
3. **Citizen already assigned with wrong job-type**: on re-send
   the citizenData persists with `JobBeastGuard` from the previous
   send. `assignCitizen` calls `job.assignTo(module)` which checks
   `module.getJobEntry().equals(job.getJobRegistryEntry())` —
   beast_guard ≠ knight → returns false.

When any of these fires, `citizenData.getJob() instanceof JobBeastGuard`
is false → the "doesn't move unless hired" pause triggers + the
"no tower" advisory fires (confusingly even when a tower exists).

**Fix — broaden + bypass:**
- `findFirstGuardTower` now returns `AbstractBuildingGuards`
  (parent of all guard-capable buildings). Covers Guard Tower,
  Barracks Tower, Archery, and any future subclass.
- `assignBeastToTower` rewritten to bypass `module.assignCitizen`
  entirely:
  1. Add citizen to the module's `assignedCitizen` list via
     reflection (skips isFull + dedup checks).
  2. Clear `citizenData.job` directly via reflection (skips
     setJob's onRemoval cascade which would re-unassign on
     re-send when the persisted job is JobBeastGuard).
  3. Construct JobBeastGuard with `preLinkWorkBuilding(tower)`.
  4. `citizenData.setJob(beastJob)` — old job is now null, so
     onRemoval is skipped, AI ctor reads workBuilding from new
     job (pre-linked) → succeeds. No NPE, no spurious advisory,
     spider patrols.
- Old `reAddCitizenToModuleList` helper subsumed by
  `addCitizenToModuleListIfMissing` (same reflection, earlier in
  the flow).

The reflection-heavy path is a pragmatic L3 patch — a custom
`BuildingEntry` registering a beast-guard module on the tower at
registration time would be the architecturally clean fix (see
earlier hotfix notes), but adds substantial scope. The reflection
path is contained, well-commented, and gets the user functional.

**Stage L3-hotfix-3 — beasts don't need a sword.** Two more bugs in
the guard pipeline surfaced after L3-hotfix-2 — both due to the
guard AI's tight assumption that the worker is a human knight with
a chest of swords and armor.

**Bug C — `equipInventoryArmor` throws same IOOB as
`atBuildingActions`.** Same empty-`itemsNeeded.get(level - 1)`
trap, different method. `prepare()` calls `equipInventoryArmor`
**before** `atBuildingActions`, so L3-hotfix-2's
`atBuildingActions` override never got a chance to run — the
state machine threw earlier in the prepare flow, got swallowed by
`AbstractAISkeleton.onException` (still a no-op `{ return; }`),
stuck in PREPARING. Fix: also override `equipInventoryArmor()`
as a no-op on `EntityAIBeastGuard`. Both methods are now bypassed.

**Bug D — `KnightCombatAI.canAttack` requires a sword in the
citizen's inventory.** Bytecode-traced: `canAttack` calls
`InventoryUtils.getFirstSlotOfItemHandlerContainingEquipment(...,
SWORD, ...)` on the citizen's `InventoryCitizen` and returns
`false` if no slot is found. Spider citizens have an empty
inventory → false → `doAttack` never fires → spider ignores
adjacent hostiles. And even if `canAttack` returned true,
`getAttackDamage()` starts at 0.0 and only increases from a held
weapon's attribute — empty hand → 0 damage anyway.

**Fix D — override both methods on `BeastGuardCombatAI`.**
- `canAttack()` → always return `true` (beast's body IS the
  weapon).
- `getAttackDamage()` → return `user.getAttributeValue(ATTACK_DAMAGE)`
  directly. This is the slot Stage L3a's `applyBeastLevelScaling`
  writes the spider-baseline × EP-multiplier value into. Baseline
  knight spider (22 ATK × ~2× scale) hits ~44 per swing with
  empty hands.

Combined L3-hotfix-2 + L3-hotfix-3: state machine advances cleanly
(no-op overrides on equipInventoryArmor / atBuildingActions),
`decide()` returns `patrol()`, patrol fires, combat AI engages
hostiles, doAttack lands attribute-driven damage.

**Stage L3 polish — "beast doesn't move unless hired".** When a beast
is sent to the colony but the send pipeline can't assign them to a
Guard Tower (no tower exists, or the tower's module rejected the
assignment), the citizen body is now `setPaused(true)` so they
stand still rather than wandering as a generic idle vanilla
citizen. A giant spider visually wandering the colony unmanaged is
jarring; standing in place at the landing spot reads as "waiting
for a post." The HIRED branch defensively un-pauses in case a
prior session had left the flag set. Player gets a yellow
advisory: *"<name> has no Guard Tower to patrol from — they will
stand still until you build one and re-send them."* The pause
clears naturally on the next successful send to a colony that has
a tower (the re-send produces a fresh JobBeastGuard → hired
branch → un-pause). Uses MC's standard `ICitizen.setPaused`/`isPaused`
API.

**Stage L3-hotfix-2 — beast-guard inert + relog-visual-lost.** Two bugs
reported after the previous L3 hotfix: spider sits in tower doing
nothing (no patrol, no combat, ignored by hostiles), AND on relog
the spider visual is gone (just a named citizen).

**Bug A — inert beast (no patrol, no combat).** ROOT CAUSE
bytecode-traced from MC 1.1.1319:

1. `EntityAIBeastGuard`'s state machine starts in
   `AIWorkerState.INIT` (per `AbstractAISkeleton` ctor). Advances
   INIT → PREPARING.
2. PREPARING calls `AbstractEntityAIFight.prepare()`. Beast has
   empty `toolsNeeded` (KnightAI populates it; we don't), so the
   tool loop is skipped. Beast is within 50 of the tower, falls
   through to `atBuildingActions()`.
3. `AbstractEntityAIFight.atBuildingActions()` reads
   `itemsNeeded.get(buildingLevel - 1)`. Beast `itemsNeeded` is
   ALSO empty (KnightAI populates it; we don't) → throws
   **IndexOutOfBoundsException**.
4. `AbstractAISkeleton.onException` is a literal `{ return; }` — the
   exception is **silently swallowed**. State machine retains its
   current state. Next tick: same code, same throw, same swallow.
   Infinite silent-failure loop.
5. **Combat AI lives on the SAME state machine.** Stuck state
   machine → combat transitions never fire → no target acquisition,
   no attacks. Explains "ignores adjacent zombies."

The previous L3 crash-fix (pre-link workBuilding) made AI
*construction* succeed. The new failure happens on the FIRST tick
AFTER construction — different code path, same class of bug
(empty list inherited from KnightAI expectations).

**Fix A — override `atBuildingActions()` as a no-op.** Beasts have
no inventory, no chest to dump into, no gear to equip from the
tower — the parent method has no legitimate work for a beast.
No-op is correct (not a stub). State machine advances cleanly to
DECIDE; `decide()` returns `patrol()`; patrol fires; combat
transitions fire on target detection.

**Bug B — relog visual lost.** ROOT CAUSE confirmed direct read:
`ExampleMod.onStartTracking` only handles `RACE_TAG`, never
`BEAST_TAG`. On logout the client-side `BeastTagClientStore`
wipes. On re-login the citizen entity is re-tracked → race-tag
re-sync fires for race-tagged citizens but beasts are never
re-synced → client renderer sees no tag → plain humanoid visual.
The BeastTag PERSISTED server-side (attachment NBT-serialises
fine) — the gap is the client-side re-sync only.

**Fix B — extend `onStartTracking` to also re-sync BeastTag.**
Mirror the race-tag block: read `BEAST_TAG` from the citizen,
send `SyncBeastTagPayload.of(uuid, tag)` to the newly-tracking
player. Spider visual restores on first frame.

**Stage L3-hotfix — beast-guard send crash + untagged citizen + placement.**
Three bugs reported after Stage L2 testing; one root cause + one
follow-on artifact + one separate placement issue.

**Root cause of the crash (#2)** — bytecode-traced from MC 1.1.1319:
`citizenData.setJob(new JobBeastGuard(citizenData))` in our
`assignBeastToTower` triggers MC's `CitizenData.setJob` → calls
`JobKnight.onRemoval()` on the previously-assigned knight job →
`onRemoval` unassigns the citizen from the tower's
`WorkerBuildingModule` (clearing the *knight's* `workBuilding` via
`module.removeCitizen` → `workBuilding.removeAssignedCitizen` chain)
→ `setJob` then sets `citizenData.job = newJobBeastGuard` and fires
`onJobChanged` → AI ctor for JobBeastGuard. The AI ctor reads
`worker.colonyHandler.getWorkBuilding()` which delegates to
`citizenData.getWorkBuilding()` which delegates to
`citizenData.job.getWorkBuilding()` — i.e. the NEW JobBeastGuard's
`workBuilding` field, which is null because the fresh job was never
`assignTo`'d. NPE at `AbstractEntityAIGuard.<init>:187` on
`buildingGuards.getPosition()`.

**Issue #3 is the same bug's artifact.** Stage L2 placed the
`assignSpawnedBeastToTower` call OUTSIDE the post-spawn try/catch
block in `sendGoblinToColony`. The NPE escaped that unprotected
window, **no rollback ran**, the citizen body remained spawned
(it had succeeded before the throw point), and the BeastTag was
never attached (it happens INSIDE the later try block). On relog
the citizen loaded with `JobBeastGuard` in CitizenData NBT but no
BeastTag → render handler missed → plain humanoid visual. Same
crash, different visible symptom.

**Fix #2 — pre-link `workBuilding` on JobBeastGuard before setJob.**
New `JobBeastGuard.preLinkWorkBuilding(IBuilding)` method writes
directly to the protected `AbstractJob.workBuilding` field. The
swap sequence becomes:
1. `module.assignCitizen(citizenData)` — JobKnight created,
   workBuilding linked, knight AI builds successfully.
2. `JobBeastGuard beastJob = new JobBeastGuard(citizenData)`.
3. **`beastJob.preLinkWorkBuilding(tower)`** — set workBuilding
   BEFORE setJob fires AI build.
4. `citizenData.setJob(beastJob)` — JobKnight.onRemoval clears
   the knight's workBuilding and unassigns from the module, then
   JobBeastGuard's AI ctor reads `citizenData.job.getWorkBuilding()`
   = `beastJob.workBuilding` = `tower`. NPE avoided.
5. New `reAddCitizenToModuleList` reflects on
   `AbstractAssignedCitizenModule.assignedCitizen` and re-adds the
   citizen, so the tower's hire loop doesn't see a free slot and
   spawn a replacement knight. The module's `assignedCitizen` now
   contains a citizen whose actual job is BeastGuard — type
   mismatch is acceptable for L3 (the tower is only a structural
   anchor for the AI's colony lookup; we don't use module's
   job-type semantics).

**Fix #3 — move `assignSpawnedBeastToTower` INSIDE the existing
try/catch rollback boundary, AND attach BeastTag BEFORE the
assignment.** New L3 sequence in `sendGoblinToColony`'s post-spawn
block (still beast-only branch):
1. `attachAndSyncBeastTag` (always succeeds — cheap setData +
   broadcast).
2. `assignSpawnedBeastToTower` (may fail; rollback discards body
   if it does).

Even if a future regression breaks the assignment, no orphan
un-tagged citizen can survive — the rollback discards the body
and its tag with it.

**Fix #1 — `beastMaterialisePos` finds safe spot AROUND tower, not
inside.** Stage L2's `beastMaterialisePos` returned
`tower.getPosition()` directly — that's the tower's anchor block,
typically inside the structure. Stage L3 adds `findSafeSpotNear`:
scans concentric perimeter rings from radius 3 outward, returns
the first position with surface-ground + 2-block humanoid
clearance + within ±2 of the anchor's Y. Falls back to the tower
anchor if no spot found (better to spawn-clip than abort).

**Stage L3a — beast-guard combat reach + level-scaled stats.** Two
coupled fixes: the spider's functional attack reach was humanoid
(citizen hitbox is 0.6 wide) while visually it looks ~5 wide, AND
the citizen-guard's combat stats didn't scale with the underlying
spider's Tensura power level.

**Combat reach lives in the AI, not the attribute.**
`Attributes.ENTITY_INTERACTION_RANGE` is player-only in vanilla 1.21
— mobs don't get it. Mob attack reach is
`KnightCombatAI.getAttackDistance()` — hardcoded `2.0`, protected,
subclassable. New `BeastGuardCombatAI extends KnightCombatAI`
overrides it to `4.0` (half the spider's 5-wide footprint plus
margin). The combat AI also drives target acquisition via
`isWithinPersecutionDistance`, which composes with the attack
distance, so engagement range scales the same way.

**Stage 1 bug uncovered while implementing this**:
`EntityAIBeastGuard`'s Stage-1 constructor was just `super(job)` —
it never instantiated a combat AI. The parent `AbstractEntityAIGuard`
doesn't construct one either; the concrete subclass does
(`EntityAIKnight` does `new KnightCombatAI(worker, getStateAI(),
this)` in its constructor). Without a combat AI, the beast had no
`inCombat` action and effectively didn't fight. Stage L3a fixes
this bug AND adds the reach boost in one constructor line:
`new BeastGuardCombatAI(worker, getStateAI(), this)` — the combat
AI registers itself with the state machine via its superclass ctor;
the returned instance is intentionally discarded (same pattern as
EntityAIKnight).

**Level scaling reads `IExistence.getEP()`.** EP is Tensura's
continuous power metric (a `double`). Read at send time from the
live spider, with a fallback to `aura + magicule + spiritualHealth`
sum when EP is zero (some entities aren't EP-charged until first
combat). The shape is a clamped log multiplier:
`scale = 1.0 + log10(max(EP, 100) / 100) × 0.5`, clamped to
`[1.0, 4.0]`. So:

| EP | Multiplier |
|---|---|
| 100 | 1.0× |
| 1 000 | 1.5× |
| 10 000 | 2.0× |
| 11 000 (spider baseline) | 2.04× |
| 100 000 | 2.5× |
| 1 000 000 | 3.0× |

Log-shape chosen so 10× EP gain is +0.5×, not 10× — keeps scaling
modest as the wild-spider EP range varies.

**Direct vs indirect transfer (per the spec's framing).**

DIRECT (combat attributes set on the citizen body, multiplied by
the EP scale):
- `MAX_HEALTH` → `spider.maxHp × scale`. Spider baseline 90 × 2 = 180.
- `ATTACK_DAMAGE` → `spider.attack × scale`. Spider baseline 22 × 2 = 44.
- `ARMOR` → `spider.armor × scale`, capped at 30 (vanilla armor cap).

INDIRECT modest (single safe translation per "don't overreach"
rule):
- `KNOCKBACK_RESISTANCE` → `min(0.95, max(spider.naturalKR,
  (scale - 1.0) × 0.3))`. Tanky beasts shrug knockback; capped at
  0.95 so they're not unmovable. Spider's natural 0.9 dominates
  for low EP; high EP nudges toward the cap.

Other "common-sense" beast traits intentionally NOT translated —
follows the established "don't overreach the Tensura↔MC bridge"
rule from earlier stages.

**Strictly more rigorous than races.** Race send only matches max
attributes via the existing `bumpBodyMaxAttributes` (multiplier
effectively 1.0). Beasts get THAT (so MAX_HEALTH has headroom for
the boost to stack on) PLUS the EP multiplier on ATTACK_DAMAGE /
ARMOR / KNOCKBACK_RESISTANCE / further MAX_HEALTH boost. Spec
requirement "beasts scale HARDER by level than races do" satisfied.

**Mechanism doesn't break existing swap/stat-sync.** New
`BEAST_LEVEL_SCALE_ID` `ResourceLocation` constant — distinct from
`SWAP_ENERGY_BOOST_ID` so the two modifiers don't fight. Permanent
operation so MC's per-tick attribute recompute doesn't strip it.
Removed-and-re-applied on every send so repeat sends don't
compound. `copyStats` (IExistence energy pools) is a separate code
path — untouched.

**Stage L2 — beasts join the identity/swap lifecycle (guard-oriented
send-back).** Stage 1's "name spider → citizen instantly" was a
one-way pipeline; Stage 2 brings beasts into the same
two-bodies-one-identity model the worker races already use, with the
single divergence that send targets the GUARD TOWER instead of the
town hall.

**Identity record extended.** `RaceIdentity` gets a nullable `Beast
beast` field. Exactly one of `race` / `beast` is non-null per
identity. New `isBeast()` convenience. NBT save/load writes whichever
is set; the legacy "missing race tag = GOBLIN default" path stays for
older saves. New convenience constructor accepts beast directly; the
existing race-only ctor delegates with `beast=null` so every prior
call site is untouched.

**Naming flow rewritten.** `handleBeastNaming` no longer creates the
citizen body or assigns to a tower. It now mirrors race naming
exactly: `createAndRegisterCivilianData` (count +1), suppress
auto-spawn via `travellingManager.startTravellingTo`, store a
SUBORDINATE-mode identity with `beast=KNIGHT_SPIDER` / `race=null`.
The wild spider stays alive at the player's side; sending it to the
colony is what produces the citizen body. Stage 1's instant-citizen
behaviour is gone — beasts are now subordinate-first like races.

**Send pipeline diverges at four points.** Within
`sendGoblinToColony` (kept the worker-race-flavoured method name; it
handles both now via `identity.isBeast()` branches):

1. **Variant capture skipped** for beasts — KnightSpiderEntity has
   no per-instance appearance fields, `variant` stays null.
2. **Materialise position** picks the first Guard Tower in the
   colony (`beastMaterialisePos`); falls back to town hall with a
   yellow advisory if no tower exists. Worker races still go to TH.
3. **Item transfer skipped** for beasts — spider has no inventory;
   no overflow possible; the snapshot's HandItems/ArmorItems aren't
   stripped (they round-trip via summon).
4. **Tag attach uses `attachAndSyncBeastTag`** (BeastTag instead of
   RaceTag) which also pins `Attributes.SCALE = 1.0` — humanoid
   hitbox preserved for pathfinding, spider visual is decoration.

After the citizen body spawns, `assignSpawnedBeastToTower` runs
(beasts only) — assigns the citizen to the tower's
`WorkerBuildingModule`, then overrides the auto-assigned job with
`JobBeastGuard`. This block moved from naming time (Stage 1) to send
time (Stage 2) so the identity-swap pipeline can produce it on every
re-send, not just the first naming.

**Summon pipeline diverges at two points** within `summonGoblin`:
1. **Item transfer skipped** for beasts (citizen→spider has nothing
   to transfer).
2. **Tag clear is dual-aware** — checks BeastTag first, then
   RaceTag (the two are disjoint per identity). Each clears via its
   matching sync payload.

Everything else round-trips through the snapshot mechanism:
`EntityType.create(snapshot, level)` returns the right entity type
(KnightSpiderEntity for beasts, the named race-mob for races)
because the snapshot's `id` tag carries the entity-type registry
key. ManasCoreStorage / IExistence / attributes / EvoState all
restore from the embedded NBT — `copyStats(citizenBody, goblin)`
works on any LivingEntity so the post-snapshot stat overlay is
race-agnostic.

**Roster integration**: beasts appear naturally in the roster (it
iterates `RaceIdentitySavedData.all()`) and the send/summon toggle
works via the standard `handleMenuAction` chokepoint, which routes
through `executeAction` → `sendGoblinToColony` /
`summonGoblin` (now beast-aware).

**Trade-snapshot reconstruction (item 6) DEFERRED to Stage 3.**
Stage 2's snapshot work makes Stage 3 cheap (the identity snapshot
already carries the merchant NBT), but the trade-screen lifecycle
(open against transient entity, write back on close, handle
disconnects) is a self-contained chunk that doesn't belong in the
swap rework. The Stage 1 citizen-trade-button fallback ("summon
first to trade") stays in place. Spiders aren't merchants so the
question doesn't apply to beasts.

**Stage L — beast-guard (knight spider) + trade button flip.**

Two coupled changes implementing the investigation's "spider can be a
patrol-locked guard-citizen" pathway, plus reversing the trade button
surface.

**L1 — beast-guard scaffolding.** Knight spider becomes a new category
distinct from the four worker races:

- `Beast` enum + `Beasts` registry — parallel to `Race` / `Races`.
  Disjoint registries: a type is in at most one. Naming probes
  `Beasts.of()` BEFORE `Races.of()` so the beast pipeline takes
  precedence.
- `BeastTag` data-attachment + `BeastTagClientStore` mirror — same
  shape as the race-tag pair. Sync via new
  `Networking.SyncBeastTagPayload`. Stage 1 carries no per-citizen
  variant data (the spider has no per-instance appearance fields).
- `JobBeastGuard extends AbstractJobGuard<JobBeastGuard>` — minimal
  parallel to MC's `JobKnight`. Registered via
  `ModJobsRegistry` (DeferredRegister against
  `CommonMinecoloniesAPIImpl.JOBS`). Translation key:
  `com.minecolonies.job.beast_guard`.
- `EntityAIBeastGuard extends AbstractEntityAIGuard<JobBeastGuard,
  AbstractBuildingGuards>` — overrides `decide()` to always dispatch
  to `patrol()` (skipping the GuardTaskSetting branches), and
  `guardMovement()` as a no-op (skipping the drift-back-to-tower
  idle behaviour). Combat / sleep / regen / flee / randomPatrolPoint
  inherited unchanged.
- `KnightSpiderCitizenRenderHandler` — shadow-entity render. Mirrors
  `LizardmanCitizenRenderHandler` (both are GeckoLib paths). Per-citizen
  `KnightSpiderEntity` shadow pool keyed by citizen UUID, never
  `tick()`'d. Citizen body `Attributes.SCALE = 1.0` enforced at the
  spawn site so the humanoid hitbox is preserved — MC pathfinding
  remains tractable. Visual / hitbox mismatch accepted per the
  investigation report (5w × 3.75h spider visually clips through
  1-block doorways; hitbox is humanoid).

**Naming pipeline branch.** `handleBeastNaming` in `ExampleMod`:
1. Create `CitizenData` (count +1).
2. Find first `BuildingGuardTower` in the colony. If none, surface
   a yellow advisory ("build a Guard Tower and assign them") — the
   citizen exists but has no patrol post.
3. Assign the citizen to the tower's `WorkerBuildingModule` (so the
   tower's `assignedCitizen` list includes the beast — this
   satisfies `AbstractEntityAIGuard`'s `buildingGuards`-field
   requirement, since the AI is generated from the citizen's job
   which queries `citizenData.getWorkBuilding()`). Then
   `citizenData.setJob(new JobBeastGuard(...))` to override the
   tower's default `JobKnight` with our PATROL-locked job. The
   tower remains structurally a guard tower (knight-typed) but the
   beast runs the BeastGuard AI on top.
4. Spawn the citizen body via
   `colony.getCitizenManager().spawnOrCreateCivilian(...)` at the
   town hall position.
5. Attach `BeastTag` to the spawned body + sync to all players via
   the new payload.
6. Discard the wild spider mob (the beast IS the citizen — Stage 1
   does NOT keep a subordinate form for beasts; the swap-circle /
   subordinate↔citizen toggle from worker races is deferred).

**No identity store for beasts in Stage 1.** `RaceIdentitySavedData`
isn't extended; beast identities live only on the citizen entity's
`BeastTag`. Side effect: send-back-and-resummon doesn't work for
beasts yet. Stage 2 will revisit if we want subordinate↔citizen swap
for beasts (the "send-to-duty-not-TH" variant the user explicitly
deferred).

**L2 — trade button flip.** Citizen-side ADD + subordinate-side
REMOVE:

- `CitizenTradeButtonHandler` (new) — hooks `ScreenEvent.Init.Post`,
  filters `event.getScreen() instanceof BOScreen` (BlockUI's screen
  wrapper, which extends `Screen`), reads `BOScreen.getWindow()` and
  instance-checks `MainWindowCitizen`. Pulls
  `MainWindowCitizen.getCitizen()` → `ICitizenDataView`, resolves
  the entity via `mc.level.getEntity(citizen.getEntityId())`, reads
  `RaceTagClientStore.get(uuid)`. If the race is merchant-capable
  (GOBLIN / LIZARDMAN / DWARF — NOT orc), adds a vanilla yellow
  "Trade" button overlaid on the BOScreen panel. Click → C2S
  `Networking.OpenCitizenTradePayload(citizenEntityId)`.
- `SubordinateTradeButtonHandler` no longer registered (file kept
  for now; tagged in a comment as "no longer wired" in
  ClientEvents).
- Server handler `handleOpenCitizenTrade`: validates citizen is
  merchant-capable, identity is owned by player, then opens trade
  IF the subordinate body (`identity.mobEntityUUID`) is currently
  in the world. Stage 1 punt: if the subordinate is despawned
  (mode = IN_COLONY), the player must `/summon`-equivalent first.
  Snapshot-reconstruction merchant flow is Stage 2.
- BlockUI promoted from `localRuntime` to `implementation` in
  `build.gradle` (`MainWindowCitizen` extends BlockUI types so they
  need to be on the compile classpath).

**Earned-race skill partition + Orc removed from picker (Stage K).**
Codifies the STARTER / EARNED split:

- **STARTERS** (Colonist, Goblin) — available at colony creation via
  `RacePickerScreen`. Flat baselines; goblin keeps only
  `Adaptability mild` + `Stamina mild`. No change.
- **EARNED** (Orc, Dwarf, Lizardman) — envoy-only, moderately
  stronger skill biases. The three partition the skill space so each
  owns a distinct lane and is peer-equal.

Final earned-race profile partition (`HIGH = +8 ± 2`, `LOW = -3 ± 2`):

| Skill | Orc | Dwarf | Lizardman |
|---|---|---|---|
| Strength | HIGH | LOW | LOW |
| Athletics | HIGH | LOW | — |
| Stamina | HIGH | — | LOW |
| Knowledge | LOW | HIGH | — |
| Intelligence | LOW | HIGH | — |
| Creativity | LOW | HIGH | — |
| Dexterity | — | — | HIGH |
| Agility | — | LOW | HIGH |
| Focus | — | — | HIGH |
| Mana | — | — | HIGH |
| Adaptability | — | — | — |

Counts: Orc 3↑/3↓, Dwarf 3↑/3↓, Lizardman 4↑/2↓.

**Key shifts vs prior profiles:**
- DWARF — swapped `Dexterity HIGH` for `Intelligence HIGH` so dwarves
  own the pure MENTAL domain (Knowledge + Intelligence + Creativity)
  unambiguously, and Dexterity moves to lizardman's precision lane.
  Added `Strength LOW` to the existing Athletics + Agility LOWs so
  dwarves can't double as heavy labour — they stay head-not-hands.
- LIZARDMAN — added `Mana HIGH` (the niche expansion). No other race
  covers Mana, which gates Healer / Alchemist / Enchanter; the
  addition gives those jobs race-bias parity with the physical /
  crafting lanes orc and dwarf cover.

**Asymmetry decision — lizardman 4↑/2↓ kept.** The extra HIGH (Mana)
is a narrow gate (3 jobs total) and doesn't bleed into the broader
physical or crafting lanes. Peer-equal in practice; the spec listed
LOWs as `Strength, Stamina` only and adding a third LOW for pure
count symmetry was rejected.

**Adaptability stays starter-only.** None of the earned races touch
it — Adaptability is the generalist skill, and Goblin's mild
positive is the only earned/starter signal on it. Keeps the
starter-vs-earned identity clean.

**Picker UI** (`RacePickerScreen`): Orc button removed. Two buttons
remain — Default citizens / Goblin — re-centered at `buttonW=120,
gap=16`. Description text reframed: the starter races are described,
then a closing line notes `"Other races (Orc, Lizardman, Dwarf)
arrive later through diplomacy."` so the player knows where Orc
went.

**`CHOICE_ORC = 2` retained.** Both the constant on
`Networking.RaceChoicePayload` AND the server-side
`handleRaceChoice` case stay intact — defensive against legacy
in-flight payloads (mid-update old clients), and a cheap re-enable
path if the picker is ever expanded again. `/setcolonyrace` admin
command drives `ColonyMember.ORC` through a different API and is
unaffected. Orc fully exists as a race; just not a STARTER choice.

**Dialogue length + orc voice pass.** Iteration after Stage J2 to
shorten everything and fix the orc voice:

- **Length**: every base body cut to ~2-3 sentences; every condition
  snippet cut to a single sentence; accept / decline tightened. Key
  identifiers kept throughout — "Elder" (goblin), "Marsh-Tribe"
  (lizardman), "Dwarven Holds" (dwarf), "Orc Disaster" (mentioned by
  name in the orc snippet), "Ifrit," "true hero / demon lord mantle,"
  "our kin in their own hold," "twenty days." Worst-case dwarf
  (5 conditions captured) is roughly half its previous length while
  still surfacing every identifier.
- **Orc voice fix**: limited reasoning, not limited vocabulary. The
  earlier voice leaned on baby-talk intensifiers ("pinky swear,"
  "big walls," "really nice place," "very strong"); the rewrite uses
  normal words ("noticed," "stand with," "grateful," "the one no
  orc could fight") but keeps sentences short, declarative, and
  single-thought. They sound like adults with simple thinking rather
  than children with simple vocabulary.
- The `EXTRA_DIALOGUE_BODY` legacy string-keyed entries (currently
  unconsulted) were updated in parallel so the two tables don't
  drift if a future stage starts reading them.

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
