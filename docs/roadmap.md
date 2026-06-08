# Project Roadmap

## Milestone 0 — Toolchain ✅
JDK 21, IntelliJ, Git, GitHub repo set up.

## Milestone 1 — Blank mod builds and runs ✅
MDK-based NeoForge mod compiles, loads in-game, and appears in the mod list.

## Milestone 2 — Dependency mods loading in dev ✅
MineColonies, Tensura, ManasCore (including sub-modules), Architectury, GeckoLib,
TerraBlender, SmartBrainLib, Structurize, and their runtime deps all load cleanly
via `libs/` jars in the dev environment.

## Milestone 3 — Vertical slice: goblin joins colony 🔄 IN PROGRESS

**Finalized design: "Two bodies, one identity, one materialized at a time."**
See `docs/decisions.md` for the full design rationale. Earlier dual-tracking and
spawn-suppression approaches are abandoned.

### Stage 1b — Pending pool for pre-colony naming ✅ COMPLETE
Goblins named before any colony exists are queued in `GoblinIdentitySavedData.pending`
and persist across save/reload. On `ColonyCreatedModEvent`, every still-alive
pending goblin is promoted to a real `ICitizenData` (count goes up) via the
same path normal naming uses. Stale entries (dead goblin) are dropped both
proactively (via the goblin-death hook) and at promotion time. Promoted
identities are full `GoblinIdentity` records — fully compatible with the
existing send/summon/death paths.

- [x] `PendingGoblin` record (identityId, name, goblinEntityUUID) saved/loaded
      in `GoblinIdentitySavedData`
- [x] Naming-time capture in the no-colony branch
- [x] `ColonyCreatedModEvent` subscription drains the pool
- [x] Stale check via `server.getAllLevels()` UUID lookup
- [x] Goblin-death hook also removes pending entries

### Stage A — Name → CitizenData + count, no body ✅ COMPLETE
Naming a goblin (server-side, when a colony exists) immediately creates a
`CitizenData` entry and increases the colony's citizen count. No `EntityCitizen`
is spawned. The goblin stays alive at the player's side as a Tensura subordinate.

- [x] Find Tensura naming event (`TensuraEntityEvents.NAMING_EVENT`)
- [x] Add Architectury + ManasCore sub-modules to compile classpath
- [x] Register naming listener; detect colony at goblin's position
- [x] Call `createAndRegisterCivilianData()`, set citizen name, log count
- [x] Store persistent identity (saved data linking goblin UUID → citizen ID)
- [x] Prevent `EntityCitizen` auto-spawn — `startTravellingTo(..., MAX_VALUE)`
      immediately after naming suppresses `updateEntityIfNecessary`'s respawn loop

### Stage B — Send-to-colony swap (ugly colonist) ✅ COMPLETE
Player triggers send. Goblin dissolves → default `EntityCitizen` materializes in
colony at a valid spawn point. No goblin renderer yet — default colonist appearance
is intentional at this stage.

- [x] Trigger: sneak-right-click named goblin with empty hand
- [x] Snapshot `IExistence` into `SavedData` before discarding goblin
- [x] `finishTravellingFor` + `spawnOrCreateCivilian(data, level, [townHallPos], force=true)`
- [x] `goblin.discard()` — swap removal, NOT death (no `LivingDeathEvent`)
- [x] Mode flag updated to `IN_COLONY` in `GoblinIdentitySavedData`

### Stage C — Summon swap + roster keybind menu 🔄 IN PROGRESS

**C1 — Core summon mechanic** ✅ COMPLETE
Placeholder trigger: `/summongoblin <name>` command. Looks up an `IN_COLONY`
identity by citizen name, discards the `EntityCitizen` (count unchanged),
re-marks travelling, and reconstructs a complete Tensura goblin from a
full-entity NBT snapshot.

- [x] Despawn `EntityCitizen` in colony (`discard()`, not `die()`)
- [x] Re-suppress respawn loop via `startTravellingTo`
- [x] Full-entity NBT roundtrip — send-side: `goblin.save(tag)` captures
      type, position, attributes, inventory, appearance, EvoState, and all
      Tensura storages (via ManasCore mixin's `"ManasCoreStorage"` subtag).
      Summon-side: `EntityType.create(tag, level)` reconstructs the entity,
      then `setUUID(random)` + `moveTo(playerPos)` before `addFreshEntity`.
      Replaces the earlier `IExistence`-only snapshot which lost evolution,
      stats, inventory, and appearance.
- [x] Update reverse map with the new goblin entity UUID
- [x] Item transfer (Option B — citizen as source-of-truth):
      send copies goblin armor/hands into `InventoryCitizen`
      (`forceArmorStackToSlot` + free-slot scan + `setHeldItem`), strips
      `HandItems`/`ArmorItems` from snapshot; summon reads citizen inventory
      back onto the goblin via `setItemSlot`. Overflow (citizen's 27 main
      slots vs goblin's 6 equipment slots) drops at the triggering player's
      position with a chat notice. Citizen inventory cleared after summon
      to avoid phantom carryover between swaps. `ItemStack.copy()` preserves
      full component data (enchants, custom names, durability, Tensura item data).

**C2 — Roster keybind menu** 🔄 IN PROGRESS

*C2a — Networking round-trip (proves the plumbing before the GUI)* ✅ COMPLETE
- [x] Add `ownerPlayerUUID` to `GoblinIdentity` and `PendingGoblin`
      (set from `player.getUUID()` at naming time; matches
      `IExistence.permanentOwner` set by Tensura's `submitNaming`)
- [x] Client keybind: default `G` (unbound in vanilla 1.21.1),
      registered via `RegisterKeyMappingsEvent` in a `@OnlyIn(CLIENT)` class
      initialised conditionally from the mod constructor
- [x] C2S `RequestRosterPayload` (empty), S2C `RosterResponsePayload`
      with `List<RosterEntry(identityId, name, modeByte)>`
- [x] Registered via `RegisterPayloadHandlersEvent` + `PayloadRegistrar`
- [x] Server handler filters identities by exact `ownerPlayerUUID` match,
      resolves citizen names via `colony.getCitizenManager().getCivilian(...).getName()`,
      replies via `PacketDistributor.sendToPlayer`
- [x] Client handler logs each entry (name + mode) — no Screen yet

*C2b — Roster Screen (two-way toggle)* ✅ COMPLETE
- [x] `RosterScreen` (plain `Screen` subclass) with `ObjectSelectionList<RosterRow>`
- [x] Row layout: name on left, status on right
      (green "At your side" for SUBORDINATE / gold "In colony" for IN_COLONY)
- [x] Empty-state: centred italic gray "No named goblins yet."
- [x] Click → C2S `ActOnIdentityPayload(identityId)` → server reads
      authoritative mode → routes to existing `sendGoblinToColony` or `summonGoblin`
- [x] Server re-sends roster after every action; client refreshes the
      open Screen in place (no reopen)
- [x] Keybind switched to `KeyConflictContext.IN_GAME` so it doesn't toggle
      while the Screen is open
- [x] Failure paths surface via green-italic chat advisory:
      no-colony, citizen-missing, goblin-not-loaded, not-your-goblin,
      and the chunk-not-loaded send failure
- [x] `sendOverflowNotice` now routes through a generic `sendAdvisoryNotice`
      helper — single chokepoint for the future Great Sage skill gate
- [x] `/summongoblin` remains as fallback

### Stage D — Identity persistence + death hooks 🔄 IN PROGRESS
The saved identity (name, citizen ID, goblin UUID, current mode) survives world
save/reload and repeated send/summon cycles. Death cleanup removes the identity
permanently — no revival, matches the "death reflects in the colony" rule.

- [x] Implement saved data (`GoblinIdentitySavedData` — built in Stage B)
- [x] Death hook A — goblin dies as subordinate: `LivingDeathEvent` (NeoForge
      bus) → `colony.getCitizenManager().removeCivilian(citizenData)` drops
      count, then `saved.removeIdentity` cleans the SavedData record
- [x] Death hook B — citizen dies in colony: `CitizenDiedModEvent`
      (MineColonies bus via `IMinecoloniesAPI.getInstance().getEventBus()`).
      Count is already handled by MineColonies' own `EntityCitizen.die()`;
      handler only cleans the SavedData record
- [x] Swap safety verified by construction: `discard()` never fires either
      death path; `EntityCitizen.discard()` posts `CitizenRemovedModEvent`
      (which we do not subscribe to), not `CitizenDiedModEvent`
- [ ] Verify identity survives server restart
- [ ] Verify citizen count stays correct across swaps

### Stage D2 — Magicule cost for swaps ✅ COMPLETE
Each send/summon costs the player magicule = target's current EP × 0.25,
read from the live body's IExistence. One shared chokepoint
(`chargeOrPrompt`) used by the menu, `/summongoblin`, and sneak-right-click —
no path bypasses the cost.

- [x] `readExistence(LivingEntity)` helper via ManasCore StorageHolder mixin
- [x] Cost = `targetExistence.getEP() * 0.25`
- [x] Sufficient → deduct from `playerExistence.getMagicule()`, run action
- [x] Insufficient → S2C `OpenCollapseConfirmPayload` opens the warning Screen
- [x] `ConfirmCollapseScreen` layered on top of `RosterScreen` (or no parent
      if triggered from command/sneak-click). Explicit wording about
      defenceless + could die. Cancel = no packet, no change. Proceed sends
      C2S `ConfirmCollapsePayload`
- [x] On Accept: `setMagicule(0)` exactly, never negative; Tensura's natural
      `handleSleepMode` tick detects depletion and enters Sleep Mode through
      its full pipeline (event fires, attribute applied, magicule reset to 1.0,
      unride, flying disabled). We do NOT suppress `ENTER_SLEEP_MODE_EVENT`
- [x] Sneak-click and `/summongoblin` rerouted through `handleMenuAction` so
      the chokepoint is enforced once for all entry points

### Stage D3 — Stat sync across the swap ✅ COMPLETE
Field-by-field copy of the IExistence stat subset (aura, magicule,
spiritualHealth, gainedEP, soulPoints, humanKill, alignment +
originalAlignment, the five destiny flags, targetNeutralList) plus
absolute HP copy. To avoid `MagiculePoisonEffect` killing the citizen
(which has zero default max for the energy attributes), the destination
body's max attributes are first boosted to the source's via a tracked
`AttributeModifier`. See `docs/decisions.md` → "Energy pool scale
mismatch between goblin and citizen bodies".

- [x] `copyStats(IExistence src, IExistence dst)` — absolute aura/magicule
      copy (NOT setEP — preserves imbalance), `clearNeutralTargets` +
      re-add for the list, `markDirty` at the end
- [x] `bumpBodyMaxAttributes(dst, src)` — adds a tracked permanent
      `AttributeModifier(SWAP_ENERGY_BOOST_ID, delta, ADD_VALUE)` lifting
      `MAX_AURA` / `MAX_MAGICULE` / `MAX_SPIRITUAL_HEALTH` / vanilla
      `MAX_HEALTH` on the destination up to the source's values so
      absolute copy is safe and round-trips cleanly
- [x] `copyHealthAbsolute(src, dst)` — `dst.setHealth(min(src.health,
      dst.maxHealth))`, defensive skip if source already dead
- [x] Send: stat copy goblin → citizen after item transfer, before
      `goblin.discard()`. Both bodies alive at the copy.
- [x] Summon: citizen discard deferred until AFTER goblin `addFreshEntity`.
      Order: reconstruct → setUUID → moveTo → applyInventory →
      `bumpBodyMaxAttributes` + `copyStats` + `copyHealthAbsolute` →
      `addFreshEntity` → discard citizen body
- [x] Magicule-cost round-trip is symmetric (EP carries across, citizens
      can actively gain/spend during colony service).

### Stage E — Magic circle visuals + delayed swap ✅ COMPLETE
The swap is fully dramatic: cost charged up front, big spinning Tensura
`MagicCircle` entities (SPACE variant, 3× size) at both ends, dissolve
body sinks through its circle into the ground, ~2s pause, materialize
body emerges from its circle, all positions locked at queue time.

**Timeline (constants):**
- `SWAP_DELAY_TICKS = 40` (2.0s — dissolve sink phase)
- `RISE_DURATION_TICKS = 20` (1.0s — materialize emergence)
- `CIRCLE_DURATION_TICKS = 80` (4.0s — covers sink + delay + rise + afterglow)
- `CIRCLE_SIZE = 3.0f` (3× default MagicCircle visual scale)
- `SINK_DEPTH = 3.0` blocks (dissolve body's drop)
- `RISE_START_OFFSET = 2.0` blocks (materialize body's underground spawn)

**Visuals:**
- [x] `new MagicCircle(level, player)` + `setVariant(SPACE)` + `setSpinning(true)`
      + `setSize(3.0f)` + `refreshDimensions()` + `setPos` + `addFreshEntity`.
      Standard entity replication; no custom packets.
- [x] Both ends spawn a circle: dissolve at the live body's current
      position; materialize at the locked destination (town hall pos for
      send, `summonMaterializePos(player)` for summon — see below).
- [x] Discard timer: `pendingCircles` list drained by `ServerTickEvent.Post`.
- [x] Diagnostic log on each circle spawn: position, caster, `addFreshEntity` result.

**Delay + position lock:**
- [x] Swap delay 40 ticks. Action queued in `pendingSwaps` with
      `(playerUUID, identityId, expectedMode, expectedGoblinUUID,
       magiculePaid, executeAtTick, materializePos)`.
- [x] `materializePos` captured at queue time so the materialize body
      appears where the circle is, regardless of player movement during
      the delay.
- [x] Summon materialize position: `summonMaterializePos(player)` —
      `player.position() + 3 × horizontal(lookAngle)` at the player's
      feet Y. Always 3 blocks ahead horizontally regardless of pitch, so
      the circle never spawns inside the ground when the player looks
      downward. Falls back to yaw direction for nearly-vertical looks.

**Sink and rise animations:**
- [x] `VerticalMovement` record: `(entityUUID, dim, lockX, lockZ, startY,
       targetY, startTick, endTick, clearInvulnerableOnEnd)`.
- [x] Tick handler interpolates Y linearly, locks X/Z via setPos each
      tick (prevents AI walking the body out of its circle), zeroes
      `deltaMovement` (no residual velocity), zeroes `fallDistance` (no
      accumulated fall damage on invulnerability clear).
- [x] Dissolve body: `setInvulnerable(true)` + sink from current Y to
      `currentY − SINK_DEPTH` over the delay. No need to clear invuln
      (body discarded at execute).
- [x] Materialize body: `markMaterializedBody(level, body, surfaceY)` —
      lowers body to `surfaceY − RISE_START_OFFSET`, marks invulnerable,
      queues rise over `RISE_DURATION_TICKS`. `clearInvulnerableOnEnd = true`
      so the body is normal again post-rise.
- [x] Order in tick handler: circles → vertical movements → swaps. Dissolve
      body's final sink position settles in the same tick the swap
      discards it.

**Cost handling:**
- [x] Cost charged **up front** in `chargeOrPrompt` (sufficient path) or
      `handleConfirmCollapse` (forced-collapse path). Never deferred.
- [x] Re-validate at execute time: identity exists, mode unchanged,
      goblin UUID unchanged (for SUBORDINATE), target body alive and
      resolvable. Any fail → advisory + `refundMagicule` capped at
      `EnergyHelper.getMaxMagicule(player)`.
- [x] Player-disconnect during delay: log, skip refund (can't safely
      modify offline data).

### Stage F — Goblin renderer ✅ COMPLETE (base + hobgoblin)
Higher evolved forms (Enlightened Hobgoblin, Hobgoblin Saint) are
intentionally deferred to Stage G — they share Tensura's hobgoblin
geometry but may want per-tier cosmetic differences (auras, distinctive
accessories) handled there.
Override `EntityCitizen` rendering for goblin-citizens so they appear as goblins
in the colony, not default colonists. Reference: "Colonies Maid Citizen" mod.
**This is a firm end-product requirement, deliberately deferred until Stages A–E
are proven working.**

*F1 — Tag pipeline (data plumbing only, no model swap)* ✅ COMPLETE
Provides the client-side signal the renderer (F2) will read to decide
goblin-vs-default per-citizen. See `docs/decisions.md` → "Stage F renderer
tagging — verification + storage choice" for why MC's String EntityDataAccessors
are unusable.

- [x] `GoblinTag` record (`identityId` + placeholder `variant` byte[]) with
      a `CompoundTag` `IAttachmentSerializer` for NBT persistence
- [x] `Attachments` — `DeferredRegister<AttachmentType<?>>` on the mod bus
      registering `goblin_tag` against `NeoForgeRegistries.ATTACHMENT_TYPES`
- [x] S2C `SyncGoblinTagPayload(entityUuid, present, identityId, variant)`
      registered alongside the existing payloads; `byteArray(256)` codec
      for the variant placeholder
- [x] Server set: `sendGoblinToColony` attaches `GoblinTag.of(identityId)`
      to the freshly-spawned citizen body and broadcasts via
      `PacketDistributor.sendToPlayersTrackingEntity`
- [x] Server clear: `summonGoblin` calls `removeData` + broadcasts a
      `clear()` payload immediately before `citizenBody.discard()` so any
      other players viewing the citizen drop their mirror entry promptly
- [x] Eager per-player resync: `@SubscribeEvent onStartTracking(PlayerEvent.StartTracking)`
      filters for `AbstractEntityCitizen` carrying the attachment and
      unicasts the tag — no `enqueueWork`, no scheduler — so the tag
      arrives before the first render frame for the new viewer (relog,
      chunk re-enter, dimension change)
- [x] Client mirror: `GoblinTagClientStore` (`ConcurrentHashMap<UUID, GoblinTag>`)
      populated by the payload handler installed in `ClientEvents.init`
- [x] Client cleanup: `EntityLeaveLevelEvent` drops single entries;
      `ClientPlayerNetworkEvent.LoggingOut` wipes the map so a relog or
      world-switch starts clean
- [x] Verification probe `GoblinTagRenderProbe` — `RenderLivingEvent.Pre`
      subscriber that logs (throttled, 100-tick window) when a tagged
      citizen is rendered. **No model swap yet** — F2 owns that

**F1 test plan (manual):**
- send a goblin to colony → server log "send: goblin tag attached" and
  client log "client tag SET" → probe log within 5s when looking at the
  citizen
- summon it → server log "summon: goblin tag cleared" and client log
  "client tag CLEARED" → no further probe logs for that entity
- relog while a goblin-citizen exists → client log "tracking: re-synced
  goblin tag" on server, "client tag SET" on client, probe resumes

*F2/F3 — Model swap + base goblin texture* ✅ COMPLETE
Tagged citizens render through a custom `GoblinCitizenRenderer` extending
`LivingEntityRenderer<AbstractEntityCitizen, HumanoidModel<…>>`. Untagged
citizens fall through to MineColonies' `RenderBipedCitizen` unchanged.

- [x] `GoblinCitizenRenderer` baked from `ModelLayers.PLAYER` so the
      player-skin UV layout matches Tensura's goblin texture format
- [x] Texture hardcoded to `tensura:textures/entity/goblin/male/skin/dark_goblin_male.png`
      (base dark-green goblin) — variant selection is F4
- [x] 0.7× scale to match Tensura's own `GoblinRenderer.scale()` for
      non-hobgoblin goblins
- [x] Nameplate inherits from `LivingEntityRenderer.renderNameTag` — no
      extra code needed
- [x] `GoblinCitizenRenderHandler` subscribes to `RenderLivingEvent.Pre`,
      filters for `AbstractEntityCitizen` carrying a tag, cancels the
      event, and calls our renderer with the same PoseStack /
      MultiBufferSource / packedLight / partialTick; recomputes
      `entityYaw` via `Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)`
      since the event doesn't carry yaw
- [x] Lazy renderer construction — `EntityRendererProvider.Context` built
      on first use from `Minecraft.getInstance()` fields
      (`ItemInHandRenderer` reached via `EntityRenderDispatcher` in 1.21.1)
- [x] Renderer invalidated on `ClientPlayerNetworkEvent.LoggingOut` so
      the next session rebuilds against fresh baked models / resources
- [x] Defensive try/catch around the render call — log + invalidate
      rather than spam stacktraces every frame

*F4 — Per-citizen variant appearance* ✅ COMPLETE
Each goblin-citizen renders with ITS goblin's specific gender, skin,
face, hair, hair-color, and conditional bandages. Hobgoblin-only fields
(head / top / bottom + their colors) are captured for forward-compat
with Stage G but not rendered for base goblins (Tensura's own
`GoblinLayer$Top/Bottom/Head` are `isHobgoblin()`-gated).

- [x] `GoblinVariantData` — fixed 24-byte little-endian record
      (7 enum IDs + 4 ARGB ints + 1 bandages bool), single chokepoint
      for encode/decode, includes a DEFAULT fallback for legacy / short
      payloads so existing F2 attachments don't break on load
- [x] `GoblinTag` — variant field promoted from placeholder `byte[]`
      to structured `GoblinVariantData`; NBT serializer round-trips
      through the byte[] encoding
- [x] Server capture (`captureGoblinVariant` in `ExampleMod`) — reads
      `GoblinEntity.getGender/getSkin/getFace/getHair/getHead/getTop/getBottom`
      and their `.getId()`, plus the four color getters and
      `hasBandages()`. Called once in `sendGoblinToColony` before tag
      attach; logged with the variant fingerprint
- [x] Wire format unchanged — payload still carries `byte[]`, now
      populated with real variant bytes instead of an empty placeholder
- [x] Client decode — `GoblinTagClientStore.onPayload` decodes the wire
      bytes once on receipt into `GoblinVariantData`; renderer reads via
      `getVariant(uuid)`
- [x] `GoblinTextures` — replicates Tensura's per-variant texture-path
      logic (skin: `{gender}/skin/{prefix}{gender}.png`,
      hair: `{gender}/hair/{prefix}{gender}.png`, face: instance
      `getTextureLocation()`, clothing: `{gender}/clothing/loin_{gender}.png`,
      bandages: literal `unisex/bandages.png`) using
      `GoblinVariant.<X>.byId(id)` for enum resolution — same
      ResourceLocations Tensura's own layers consult, just sourced from
      stored IDs instead of a live `GoblinEntity`
- [x] `GoblinOverlayLayer` — generic `RenderLayer<AbstractEntityCitizen,
      PlayerModel<…>>` parameterised by ModelLayerLocation,
      texture-fn, color-fn, and a should-render predicate; one
      instance per overlay kind, no per-layer subclasses
- [x] `GoblinCitizenRenderer` — base body switched from
      `HumanoidModel<…>` to `PlayerModel<…>` (matching Tensura's
      `PlayerLikeModel`); base texture now variant-driven via
      `GoblinTextures.skin(v)`; adds five overlay layers (Face, Hair,
      HairBody, Clothing, Bandages) reusing Tensura's own
      ModelLayerLocation constants from `GoblinLayer.*`

**F4 test plan (manual):**
- Spawn two goblins with different gender/skin/hair via Tensura's
  naming menu, send both to colony → each citizen renders with its
  own appearance, not a shared one
- Send a goblin with bandages → bandages overlay visible
- Send a goblin with non-zero hair color → hair tinted with that color
- Server log shows the variant fingerprint
  (`variant=g0/s2/f1/h2/hc#ffaabb/btrue`) on each send

*F5 — Evolution stage (hobgoblin)* ✅ COMPLETE
Naming a goblin promotes it to a Hobgoblin via Tensura's
`INameEvolution`. The citizen body must render at the hobgoblin's
geometry and clothing, not the smaller base-goblin form.
`GoblinEntity.isHobgoblin()` is just `getCurrentEvolutionState() >= 1`,
and Tensura's own `GoblinRenderer.scale()` swaps `0.7f → 0.9375f` plus
enables `GoblinLayer$Head/Top/Bottom` (all `isHobgoblin()`-gated).
We mirror both.

- [x] `GoblinVariantData` — `evolutionState` byte appended at offset 24
      (record now 25 bytes). Backward-compatible: pre-F5 24-byte
      payloads decode with `evolutionState = 0` (base goblin), the
      correct historical default
- [x] `isHobgoblin()` helper on `GoblinVariantData`
- [x] Server capture — `captureGoblinVariant` now reads
      `g.getCurrentEvolutionState()`; the variant fingerprint log line
      gains `/evo<n>` so the wire value is verifiable live
- [x] Scale switch — `GoblinCitizenRenderer.scale()` reads the tag at
      render time and picks `HOBGOBLIN_SCALE` (0.9375f) or
      `BASE_GOBLIN_SCALE` (0.7f). Falls back to base if the tag is
      momentarily missing (no NPE during the one-frame race window)
- [x] Three hobgoblin overlay layers added to the renderer (Head, Top,
      Bottom) with `v.isHobgoblin() && texture != null` predicates;
      base goblins skip them, mirroring Tensura's gate
- [x] `GoblinTextures.head/top/bottom` — formulas
      `unisex/{kind}/{prefix}_{loc}.png` using each variant enum's
      `getLocation()`; guarded against negative / out-of-range IDs
      (catches Tensura's `head == -1` "no accessory" sentinel)

**F5 test plan (manual):**
- Name a goblin → it evolves to a hobgoblin at the player's side
- Send to colony → citizen renders TALLER (0.9375 scale) than a
  base-goblin citizen would, with vest + pants + (if set) bandana
- Server log: `variant=…/evo1` confirms evolution state was captured
- Existing F4 citizen records (without evolutionState in NBT) decode
  as `evo0` on world load → render unchanged as base goblins. No
  migration needed.

*F6 — Goblin accessories on PlayerModel overlay parts* ✅ COMPLETE
After F5 landed, Top (shirt) and Bottom (shorts/pants) layers didn't
render on the citizen — diagnosed as `GoblinOverlayLayer` wrapping
Tensura's PlayerModel-baked overlay meshes in a plain `HumanoidModel`,
which silently drops the 5 PlayerModel overlay parts (jacket,
sleeves, pant-legs) where the clothing cubes live.

- [x] Swap `GoblinOverlayLayer`'s overlay model from
      `HumanoidModel<…>` to `PlayerModel<…>` (slim=false, matching
      Tensura's `GoblinLayer.<clinit>` template)
- [x] Head also rendered correctly post-swap (hypothesis 1 from the
      diagnosis — bandana geometry extends onto overlay parts)

*F7 — Goblin armor + held items + baby state* ✅ COMPLETE
Goblins arrived at the colony naked-handed even when carrying gear,
and baby goblins transferred as adult citizens. Two root causes
addressed in one batch.

- [x] `HumanoidArmorLayer` + `ItemInHandLayer` added to
      `GoblinCitizenRenderer` constructor — without these, vanilla
      equipment slots aren't drawn even when populated
- [x] `applyEquipmentFromInventory(body, inv)` helper bridges from
      `InventoryCitizen` (where item transfer puts them) onto the
      entity's vanilla equipment slots (where the renderer layers
      read from)
- [x] Baby propagation — `if (goblin.isBaby()) citizen.setIsChild(true)`
      after the equipment sync. Renderer's `young` flag, hitbox via
      `getAgeScale=0.62`, and NBT round-trip on summon all flow
      automatically from `setIsChild`

### Stage G — Race system foundation 🔄 IN PROGRESS

Generalisation of the goblin-only pipeline into a race-aware system
supporting goblins, orcs, and (future) further races. Required
because orcs use a completely different rendering pipeline
(GeckoLib) and a different variant field set.

*G1 — Race registry + race-aware naming + sealed variant data* ✅ COMPLETE
Rebuilt the data plumbing without changing user-visible behaviour
for goblins.

- [x] `Race` enum (GOBLIN, ORC) + `Races` central registry mapping
      to `ResourceLocation` ↔ `EntityType`. Reverse lookup
      `Races.of(EntityType)` returns null for unrecognised types so
      naming/send filters fall through cleanly
- [x] `Races.isBlocked(type)` — orc lord and orc disaster excluded
      from the citizen pipeline (separate Tensura EntityTypes with
      their own renderers; per-tier shadow pools deferred)
- [x] Renamed:
      `onGoblinNamed → onRaceNamed`,
      `GoblinIdentitySavedData → RaceIdentitySavedData`,
      `GoblinIdentity → RaceIdentity`,
      `PendingGoblin → PendingRaceMob`,
      `GoblinTag → RaceTag`,
      `GoblinTagClientStore → RaceTagClientStore`,
      `SyncGoblinTagPayload → SyncRaceTagPayload`,
      `Attachments.GOBLIN_TAG → Attachments.RACE_TAG`,
      `goblinTagClientHandler → raceTagClientHandler`,
      `getByGoblinUUID → getByMobUUID`,
      `updateGoblinUUID → updateMobUUID`,
      `goblinEntityUUID → mobEntityUUID`
- [x] `RaceIdentity` + `PendingRaceMob` carry a `Race race` field;
      pending-pool drain propagates race so promoted orcs land tagged
      ORC, not GOBLIN
- [x] Sealed `RaceVariantData` interface; `GoblinVariantData` and
      `OrcVariantData` both implement; `RaceTag` switched from
      concrete `GoblinVariantData` to sealed `RaceVariantData`
- [x] `RaceTagClientStore.getGoblinVariant` / `getOrcVariant` —
      typed accessors with `instanceof` narrowing inside; return null
      on race mismatch (fail-closed, no CCE)
- [x] Four goblin-only sites in `ExampleMod` generalised through the
      `Races` registry: naming filter, sneak-send filter, tag
      construction (3-arg `RaceTag.of`), variant capture dispatcher
- [x] Wire format & save format unchanged — `byte[] variant` is
      opaque, the race byte already on the wire/NBT picks the decoder
- [x] Two latent goblin-side bugs fixed during the refactor:
      (1) sentinel crash — Tensura's `Head.byId(-1)` AIOOBE; guarded
      with raw-read (HEAD public accessor) + try/catch (TOP/BOTTOM
      private accessors) returning -1; (2) partial-state-on-throw —
      `sendNamedMobToColony` was leaving orphaned citizens in the
      world if anything threw between spawn and tag-set; restructured
      to capture variant BEFORE spawn, then wrap the post-spawn block
      in try/catch with rollback (discard + re-suppress travelling)

*G2 — Orc shadow-entity render path (Stage 1)* ✅ COMPLETE
Orc citizens render through Tensura's own `OrcRenderer`
(GeckoLib-based, generically typed to `OrcEntity`). Since
`AbstractEntityCitizen` cannot be a `GeoAnimatable`, we feed a
client-side shadow `OrcEntity` instance — constructed via
`EntityType.create(level)` but never `addFreshEntity`'d.

- [x] PoC proved off-world `OrcEntity` renders correctly (no NPE in
      GeckoLib's `AnimatableInstanceCache`)
- [x] `OrcCitizenRenderHandler` cancels `RenderLivingEvent.Pre` for
      ORC-tagged citizens, syncs shadow position/yaw/scale/nameplate
      from citizen, calls `OrcRenderer.render(shadow, …)` with the
      event's PoseStack/MultiBufferSource/packedLight/partialTick
- [x] Race tag carries `Race race` discriminator so goblin and orc
      handlers are mutually exclusive on the same `Pre` event
- [x] `/raceflip <name>` debug command — toggles a citizen's race tag
      goblin↔orc for visual testing without a real orc spawn
- [x] Nameplate via copying `citizen.getCustomName()` onto the shadow
      before render — basic name shows, MineColonies' status-pip
      overlay deferred

*G3 — Orc animation driver + per-citizen shadow pool (Stage 2)* ✅ COMPLETE
Stage G2's shadow was frozen (head tracked but walk/idle anims
didn't play). Stage 2 of Tensura investigation traced the cause to
`AnimationState.isMoving` being computed from `entity.walkAnimation.speed`
× `getDeltaMovement.{x,z}` — neither of which the shadow had set.

- [x] `OrcCitizenRenderHandler.syncShadowFromCitizen` writes
      `shadow.setDeltaMovement(citizen.getDeltaMovement())`,
      `setPose(citizen.getPose())`, `setSprinting(citizen.isSprinting())`
- [x] Once-per-tick walkAnimation advance: when
      `citizen.tickCount != shadow.lastSyncedTick`, call
      `shadow.walkAnimation.update(citizen.walkAnimation.speed(), 1.0f)`
      (matches what vanilla `LivingEntity.aiStep` does each tick)
- [x] Per-citizen shadow pool — `Map<UUID, Shadow>` instead of a
      single shared shadow. GeckoLib's `AnimatableInstanceCache` keys
      by `entity.getId()`, so two simultaneously-visible orc-citizens
      need their own shadow instances to avoid animation-state
      blinking between them
- [x] Cleanup hooks: `EntityLeaveLevelEvent` drops single entries;
      `ClientPlayerNetworkEvent.LoggingOut` wipes pool
- [x] HARD RULE: shadow never `tick()` / `aiStep()` — would run
      brain/nav/collision against off-world state with NPE +
      off-world-death-event risks. Direct field writes / public
      setters only

Deferred: swim animation (needs reflection on `Entity.wasInWater`),
attack/shield/crossbow one-shots (needs citizen-side attack detection
+ `triggerAnim` plumbing). Orc lord and orc disaster as separate
shadow types are Stage G6.

*G4 — Per-citizen orc variants (Stage 3)* ✅ COMPLETE
Each orc citizen renders with ITS specific OrcVariant fields, not a
shared default. Substantially simpler than goblin variants — pivotal
finding was that Tensura's `OrcLayer$Neck/Top/Bottom/Belt/Boots/Bandage/Necklace`
read state directly off the entity, so setting fields on the shadow
before render = Tensura's own layers draw the right accessories.

- [x] `OrcVariantData` record — 12 fields, fixed 26-byte little-endian
      layout, sealed `RaceVariantData` implementation
- [x] `captureOrcVariant` server-side — reads `OrcEntity.getVariant/
      getNeck/getTop/getNeckColor/getTopColor/getBottomColor/
      getBeltColor/getBootsColor/hasBandage/hasNecklace/
      getCurrentEvolutionState/getEvolving`. The three enum getters
      that go through `byId(raw % length)` are wrapped in `safeOrcEnumId`
      try/catch with fallback `0` (same crash-shape as goblin's HEAD)
- [x] Orc render handler sets the 10 variant setters on the shadow
      each frame + forces `setCurrentEvolutionState(0)/setEvolving(0)`
      (orc lord / disaster blocked upstream)
- [x] Equipment sync — `EquipmentSlot.values()` loop copies citizen
      slots to shadow; Tensura's `OrcRenderer$1` (ItemArmorGeoLayer)
      and `OrcRenderer$2` (BlockAndItemGeoLayer) read from those
      slots for armor and held items
- [x] Race-flip via `/raceflip` resets variants to DEFAULT via
      `RaceTag.withRace` (re-encoding through the other race's
      `decode(empty bytes)` returns the safe sentinel)

### Stage B — Race-aware population spawn ✅ COMPLETE

When a colony grows its population, spawn UNNAMED wild Tensura
mobs of the colony's chosen race instead of normal MineColonies
citizens. Player then names them via the existing pipeline to
convert to citizens.

*B1 — Storage + intercept + race picker menu* ✅ COMPLETE

- [x] `ColonyRaceConfigSavedData` — per-server overworld-scoped
      `Map<colonyId, Race> raceByColony` + `Set<colonyId> pendingChoice`.
      NBT-serialised; pending list missing in legacy data tolerated
      as empty (backward compat)
- [x] `CitizenAddedModEvent(INITIAL)` subscription — tri-state predicate:
      * pending → discard + removeCivilian, NO mob spawn (suppress
        ALL growth until choice)
      * race configured → discard + spawn wild race-mob (existing
        race-spawn path)
      * neither → vanilla MC citizen (DEFAULT pick AND legacy
        colonies; indistinguishable by intent)
- [x] Wild mob spawn — `EntityType.create` + `finalizeSpawn(level,
      difficulty, MobSpawnType.SPAWN_EGG, null)` (Tensura's variant
      randomisation runs in finalizeSpawn; SPAWN_EGG prevents
      despawn-when-far-away) + `addFreshEntity`
- [x] Race picker modal — `RacePickerScreen` (vanilla `Screen`). Originally
      three buttons (Default / Goblin / Orc); Stage K removed the Orc
      button when Orc moved to the EARNED tier (envoy-only). Now two
      buttons: Default citizens / Goblin. Mirrors the
      `ConfirmCollapseScreen` pattern (vanilla blur + bounded opaque
      panel + drop shadow + 1px white border, no see-through)
- [x] S2C `OpenRacePickerPayload(colonyId, colonyName)` + C2S
      `RaceChoicePayload(colonyId, choice)` with 3 choice constants
      (DEFAULT=0, GOBLIN=1, ORC=2). CHOICE_ORC is retained
      post-Stage-K as a defensive wire path (legacy in-flight payloads,
      cheap re-enable) but never sent by the current picker UI
- [x] Screen-collision resolution: parent-pointer stacking +
      1-tick deferred screen-open on client. Captures MC's town hall
      UI as parent regardless of network-arrival order
- [x] `onColonyCreated` (alongside existing pending-pool drain — drain
      first, picker second) marks pending + sends payload to owner.
      Offline owner case: re-sent on `PlayerLoggedInEvent`
- [x] Re-engagement: town-hall right-click hook (`PlayerInteractEvent.RightClickBlock`)
      re-opens the picker for the owner if the colony is still pending
- [x] Player choice handler `handleRaceChoice` on server: ownership
      check (only colony owner can answer) + dispatch:
      * DEFAULT → `config.clearRace(id)` + re-issue MC's
        `colony_founded` translation key
      * GOBLIN/ORC → `config.setRace(id, race)` + race-specific
        flavour message via translation key
- [x] MineColonies' two auto-sent chat messages (`colony_founded`
      and `colony_reactivated`) suppressed via Mixin
      (`CreateColonyMessageMixin` with `@WrapOperation` at ordinals
      2 and 3 of the `MessageBuilder.sendTo` call list in
      `CreateColonyMessage.onExecute`). Mixin infrastructure: config
      file `tensura_minecolonies.mixins.json` registered in
      `neoforge.mods.toml`; mixin class targets the success-path
      messages only — error messages (ordinals 0 and 1) still reach
      the player
- [x] `ColonyDeletedModEvent` cleanup — clears both `raceByColony`
      and `pendingChoice` entries for the deleted id
- [x] `/setcolonyrace <goblin|orc|clear>` debug command — kept as
      fallback / debug tool until the picker is battle-tested
- [x] Translation keys in `en_us.json`:
      `tensura_minecolonies.colony.created.goblin`,
      `tensura_minecolonies.colony.created.orc`

**B1 test plan (manual):**
- Create a colony. Picker opens 1 tick after MC's town hall UI.
  Pick **Default** → chat shows ONLY MC's standard "colony_founded"
  text. Future ticks spawn vanilla MC citizens.
- Create another colony, pick **Goblin** → chat shows ONLY our
  white "News of your new goblin town..." text. No MC line above.
  Future ticks spawn wild goblins; name them to convert.
- Same for **Orc**: only our "Whispers of your orc settlement..."
  shows.
- ESC the picker → no chat message. Population stays at 0 — no
  citizens, no mobs — until the player re-engages. Right-click the
  town hall → picker re-opens with MC's TH UI as parent.
- Log out while picker is pending → log back in: picker re-opens.
- `/mc colony delete N` → server log "cleared race config entry"
  drops both race and pending entries.
- Existing colonies in a pre-menu world → no entry in either map →
  next spawn tick produces a vanilla MC citizen. No migration.

### Stage G6 — Orc lord and orc disaster as separate shadow types ⬜ DEFERRED
Tensura has two evolved orc EntityTypes (`tensura:orc_lord`,
`tensura:orc_disaster`) with their own renderers. Currently blocked
from the citizen pipeline via `Races.isBlocked` — naming filter
rejects, send filter rejects, advisory "Orc lords and orc disasters
cannot serve as colony citizens." appears on send attempt.

To support: per-tier shadow pool (one shadow per EntityType, parallel
to the existing `OrcCitizenRenderHandler` shadow pool). Estimated
~5 hours, mirrors the work already done for base orc.

### Stage H — Envoy system ✅ COMPLETE (3 buildable races)
Envoys are diplomatic emissaries that periodically arrive at the colony
offering a new race for the colony's spawn set. Shipped in four stages:

**Stage H1 — Envoy entity, spawn, naming suppression ✅**
- `EnvoyTag` NeoForge attachment (parallel to `RACE_TAG`) marks an
  envoy entity with `(colonyId, ColonyMember, State)`. NBT-persisted.
- Goblin / Orc envoys are Tensura `GoblinEntity` / `OrcEntity`. Colonist
  envoy is a real `VisitorCitizen` registered with the colony's
  `VisitorManager` (required so `VisitorColonyHandler.registerWithColony`
  doesn't immediately discard it).
- Spawn via `EntityUtils.getSpawnPoint(level, townHall)` with fallback.
  `SPAWN_EGG` + `setPersistenceRequired` for double non-despawn lock.
- Naming suppression in `onRaceNamed` — early-return with
  `EventResult.interruptFalse()` when entity has the envoy tag.

**Stage H2 — Dialogue + accept/decline + nameplate + roam radius ✅**
- `mob.restrictTo(townHallPos, 15)` — verified to bound all three entity
  types (vanilla Villager goals and SmartBrainLib's `SetRandomWalkTarget`
  both honor `isWithinRestriction` via the underlying `LandRandomPos`).
- Coloured nameplates: GOBLIN→GREEN, ORC→DARK_RED, COLONIST→AQUA. For
  colonist envoys, additionally `data.setName("Colonist Envoy")` so the
  citizen renderer doesn't stack a random colonist name underneath.
- Custom `EnvoyDialogueScreen` (mirrors `ConfirmCollapseScreen` pattern)
  — title in race colour, body wrapped via `font.split`, Accept / Decline.
  Rejected MC's `IInteractionResponseHandler` reuse — hard-bound to
  `ICitizenData` at every callback.
- Accept → `addMember(colonyId, member)` + `markEnvoyAccepted` (permanent
  diplomacy lock) + despawn with poof. Decline → just despawn with poof.

**Stage H3a — Unlock conditions + scheduler ✅**
- COLONIST: colony age ≥ 3 in-game days.
- GOBLIN: ≥ 3 named goblin identities for this colony.
- ORC: colony citizen count ≥ 25.
- `tensuraMaxNonColonistEnvoys` gamerule (default 4) — per-player cap on
  distinct non-colonist races whose envoys can ever spawn. COLONIST is
  exempt.
- Scheduler ticks every 1 s (was once per in-game day; changed because
  `/time add` doesn't advance `server.getTickCount()`). Gates: active
  envoy, 3-day post-resolve cooldown, eligibility.
- Debug commands: `/spawnenvoy`, `/envoystate`, `/envoyforce`,
  `/envoyresetcooldown`.

**Stage H3b — Kill-gate ✅**
- Killing a Tensura race resets that race's unlock condition for every
  colony the killer owns — EXCEPT races already accepted (permanently
  immune).
- Per-shape resets: COLONIST → re-anchor timer to now; GOBLIN → snapshot
  current count, require 3 more above baseline; ORC → snapshot current
  citizens, require colony to grow past that snapshot (handles "still
  at 25" cleanly).
- Orc Lord / Orc Disaster excluded from the gate (`Races.isBlocked` +
  `Races.of` returns null).
- Active envoy of the killed race despawns (driven off by kin-kill).

**Pending (deferred):** Dwarf and Lizardman envoys. Both have dialogue
copy pre-written in `EnvoyDialogue.EXTRA_*`. Adding them needs
`ColonyMember` entries (which forces `Race` enum decisions — Dwarf has a
Tensura `DwarfEntity`; Lizardman would need a Tensura mob too) and
`Races` registry plumbing. Estimated ~3 hours per race, mostly mirror
work of the existing GOBLIN / ORC paths.

Full as-built record: `docs/envoy-system.md`.

### Stage I — Lizardman, Dwarf, citizen-skill profiles, trade tab ✅ COMPLETE
Two new races (Lizardman, Dwarf) added alongside the existing
goblin / orc, with a per-race citizen-skill-profile system and a
post-naming subordinate-trade tab. Shipped in four interlocking sub-stages:

**Stage I1 — Race skill profile infrastructure ✅**
- `RaceSkillProfile` record maps each MC `Skill` to a `(meanBias, spread)`
  tuple. {@link RaceSkillProfile#apply} layers the bias on top of MC's
  random init: `newLevel = clamp(currentLevel + meanBias + randInt[-spread, +spread], 1, 99)`.
- Applied ONCE at naming-to-citizen time (immediately after
  `createAndRegisterCivilianData`); persists on `CitizenData` NBT
  forever; never re-applied on send/summon swap.
- Locked design: bias on baseline, NEVER absolute override. Normal MC
  progression (XP from work, level-ups) continues untouched — no flag,
  no offset, no cap logic. The eroding head-start falls out naturally.
- Per-race profiles (presets `HIGH = +8±2`, `LOW = -3±2`, `MILD = +3±1`),
  post-Stage-K re-partition into STARTERS (Colonist + Goblin, flat) and
  EARNED (Orc + Dwarf + Lizardman, three-way lane split):
  ORC `+Str/+Ath/+Sta HIGH, -Int/-Knw/-Cre LOW` (unchanged). GOBLIN
  `+Adp/+Sta MILD` (unchanged starter). LIZARDMAN
  `+Agi/+Dex/+Foc/+Mana HIGH, -Str/-Sta LOW` (Mana HIGH added — the
  niche expansion; 4↑/2↓ asymmetry kept intentionally). DWARF
  `+Knw/+Int/+Cre HIGH, -Ath/-Str/-Agi LOW` (Intelligence in place of
  Dexterity to own the MENTAL domain; Strength added to LOWs).
  COLONIST no bias (unchanged).
- Existing pre-system citizens NOT retroactively biased — over a working
  career their levels have moved past MC's init baseline, so adding the
  race bias to current values would over-bias them. Accepted "new
  citizens only" inconsistency in a dev world.

**Stage I2 — Lizardman race ✅**
- `tensura:lizardman` (`LizardmanEntity` extends `TensuraMerchantEntity`).
- GeckoLib renderer: REUSES the orc shadow-entity pattern
  (`OrcCitizenRenderHandler` → `LizardmanCitizenRenderHandler`).
  Per-citizen shadow `LizardmanEntity`, never `tick()`'d, fed to
  Tensura's `LizardmanRenderer`.
- `LizardmanVariantData` — 18-byte record (9 variant fields).
- Envoy unlock: ≥15 citizens (below the orc bar of 25).
- Dialogue tone: formal-but-respectful (softened from the initial
  passive-aggressive draft on player feedback).
- Skill profile: speed/precision/carrier archetype.

**Stage I3 — Dwarf race ✅ (3 wrinkles solved)**
- `tensura:dwarf` (`DwarfEntity` extends `TensuraMerchantEntity`).
- Vanilla biped renderer: REUSES the goblin PlayerModel + overlay-layer
  pattern (`GoblinOverlayLayer` → `DwarfOverlayLayer`). 7 dwarf overlays
  reusing Tensura's `DwarfLayer.*` `ModelLayerLocation` constants.
- `DwarfVariantData` — 25-byte record (9 enum-id bytes + 4 colour ints).
- `DwarfTextures` lazy "texture proxy" `DwarfEntity` — built once,
  never added to world, never ticked. Solves the
  "Tensura's static `getTextureLocation` needs a `DwarfEntity` but we
  render a citizen" problem without a mixin.
- **Wrinkle 1**: dwarf was NOT in `can_be_named` tag and doesn't
  implement `INameEvolution`. Datapack tag merge at
  `data/tensura/tags/entity_type/can_be_named.json` adds `tensura:dwarf`
  (additive merge, `replace: false`). Naming gate now passes.
- **Wrinkle 2**: original SCALE-attribute approach (Attributes.SCALE = 0.5
  for hitbox + visual together) broke rendering — vanilla's hardcoded
  `-1.5` Y translate fires AFTER `scale()` in scaled space, placing the
  model partially below the entity origin. Switched to renderer-only
  hardcoded `0.5` scale in `DwarfCitizenRenderer.scale` (same pattern
  as goblin). Hitbox stays full citizen size; visual model is half.
  Accepted cosmetic trade-off.
- **Wrinkle 3 (defensive)**: overlay `bakeLayer` wrapped in try/catch
  per layer (one bad overlay no longer kills the renderer); handler
  tolerates 5 consecutive render failures before invalidating;
  `getTextureLocation` falls back to vanilla `steve.png` rather than
  returning null.
- Envoy unlock: PLACEHOLDER — ≥30 citizens AND a Miner / Miner's Hut
  built. Real conditions (dwarven village found / 20 days / true demon
  lord) are deferred-content. `/envoystate` flags the line
  `[PLACEHOLDER condition]`.
- Skill profile: craftsmanship archetype.

**Stage I4 — Subordinate trade tab ✅**
- Yellow "Trade" button injected onto Tensura's `HumanoidInventoryScreen`
  for named subordinates that extend `TensuraMerchantEntity` (goblin,
  lizardman, dwarf — NOT orc).
- Lives in one new file (`SubordinateTradeButtonHandler`) plus one
  C2S networking payload (`OpenSubordinateTradePayload`). No mixin.
- `ScreenEvent.Init.Post` hook reflects the screen's private
  `humanoid` field (cached after first success), checks
  `instanceof TensuraMerchantEntity`, adds a Button widget via
  `event.addListener`.
- Click → server resolves entity → identity-store ownership check →
  vanilla `Merchant.openTradingScreen` opens the standard merchant
  menu. Profession / merchant level / persisted offers / gossips all
  round-trip via Tensura's own NBT (untouched by naming) so the player
  sees the same trades they would have pre-naming, and merchant XP /
  level-ups continue to fire from `customServerAiStep`.

Full as-built record: `docs/lizardman-dwarf-and-skills.md`.

---

## Future Features

### Tier 1 (after vertical slice works)
- Race-restricted jobs
- Tensura-specific jobs: ore processing, weaponsmith, shop restocking
- Citizen skill leveling tied to Tensura skill system
- Boss Goblin → colony conversion
- Quest / skill-unlock rewrites

### Tier 2 (later)
- Reputation system
- Money / bank / vault
- Territory / barrier raids
- Retainer skill-bestowal

### Tier 3 (much later)
- Crime / police / disguise system
- Assassin system
- Dual reclaim mechanics
- Independent settlement leveling
- Angel raid

## Scrapped — beast-guard (knight-spider) approach

The Stage L track that bound a knight spider to a MineColonies Guard
Tower as a `JobBeastGuard` citizen has been removed. The MineColonies
pathfinder is width-blind and humanoid-tuned, the visible spider
geometry never matched the hitbox, the "shadow entity" GeckoLib
render needed extensive per-frame state mirroring, and the
guard-job lifecycle had several layered silent-exception failure
modes that each needed bespoke patches. The accumulated complexity
outweighed the feature.

**New direction — "Guard This Area" Tensura-menu commands.** For
non-humanoid mobs (knight spider and future beasts), control will be
driven by per-player commands issued through the Tensura command
menu rather than the MineColonies citizen/guard-tower system. The
mob stays a Tensura entity, owned and named by the player, and
receives stay-here / patrol-here / follow-me-style orders. No
citizen body, no shadow rendering, no guard tower binding, no
threat-table / state-machine integration. This sidesteps the
width-blind pathfinder problem (the mob keeps its native pathing),
the visual/hitbox mismatch (it IS the native entity), and the
silent-exception trap of MineColonies AI.

The race-citizen pipeline (goblin / orc / dwarf / lizardman) is
unaffected — those are humanoid, fit the pathfinder, and the
identity / send / summon flow remains the right home for them.
