# Investigation: Envoy system — technical foundations

**Status:** investigation only, no code written yet.

**Scope:** the two pivotal technical unknowns flagged in the brief —
identifying / marking an envoy and the dialogue/UI approach. Also covered
the smaller questions (naming suppression, right-click intercept, spawn).

Class references confirmed by decompiling jars in `libs/` with Vineflower
and the MineColonies bytecode with `javap`.

The wider envoy-system design (3 envoy types: colonist/goblin/orc, per-colony
unlock conditions, accept→`addMember`, kill-gate-resets-requirements) is not
re-stated here — it lives in `decisions.md` "Multi-race per colony" and the
brief itself.

---

## 1. ENVOY MARKER — how to identify an envoy entity

### Options evaluated

| Option | Persists | Identifiable | Suppress naming | Cost |
|---|---|---|---|---|
| (A) NeoForge data attachment | Yes (`.serialize`) | `entity.hasData(...)` | trivial check in `onRaceNamed` | tiny |
| (B) Custom persistent NBT via mixin into entity NBT | Yes | manual NBT read | same | bigger |
| (C) Subclass `OrcEntity` / `GoblinEntity` (one per race) | Yes | `instanceof` | trivial | huge — per-race EntityType registration, brain wiring, renderer plumbing |

### RECOMMENDED: NeoForge data attachment

`Attachments.java` already registers `RACE_TAG` with `.serialize(...)`.
Add a sibling `ENVOY_TAG`:

```java
public static final Supplier<AttachmentType<EnvoyTag>> ENVOY_TAG =
    ATTACHMENTS.register("envoy_tag",
        () -> AttachmentType.<EnvoyTag>builder(() -> null)
            .serialize(EnvoyTag.SERIALIZER)
            .build());
```

Payload (minimum): `colonyId` (which colony this envoy is FROM diplomatically
— for the accept→addMember target), `race` (which race to add on accept),
and `state` (alive/declined/accepted, in case follow-up is needed).

- **Persists** across save/load via the attachment's NBT serializer — same
  guarantee `RACE_TAG` already enjoys.
- **Identifiable** anywhere we have the entity: `entity.hasData(Attachments.ENVOY_TAG.get())`.
- **Checkable on right-click** server-side in our `EntityInteract` listener.
- **Survives reload, chunk unload, and dimension change** — the attachment
  rides on the entity's NBT.

Same pattern as existing infrastructure; no new mechanism.

### COLONIST envoy — what entity?

Colonists aren't a Tensura race; there's no Tensura mob to spawn for them.
Three options:

| Option | Look | Lifecycle risk | Effort |
|---|---|---|---|
| (i) Vanilla `Villager` + envoy attachment | human-shaped | none (no MC colony binding) | tiny |
| (ii) A stray `EntityCitizen` outside any colony | citizen model | high — `EntityCitizen` assumes a colony binding (jobs, citizen manager, etc.); a "colony-less" citizen would either break MC's bookkeeping or need deep integration | large |
| (iii) Custom entity type with a citizen-skinned renderer | custom | none | huge — own EntityType, renderer, model |

**RECOMMENDED: (i) vanilla `Villager` with the envoy attachment.** Suppress
its trading on right-click (we already cancel `EntityInteract`). It looks
roughly human, doesn't pollute MC's colony state, and ships with vanilla so
no registration. The aesthetic gap vs an actual citizen model can be closed
later with a per-citizen-look render override (parallel to our existing
goblin/orc renderer overrides), but that's polish, not a foundation
requirement.

The envoy attachment makes envoys ENTITY-TYPE-agnostic: same attachment on
a `Villager` (colonist envoy), `GoblinEntity` (goblin envoy), `OrcEntity`
(orc envoy). All three are recognised by the same `hasData(ENVOY_TAG)`
check at every call site.

---

## 2. NAMING SUPPRESSION

### Where Tensura's naming flow fires

Two paths through Tensura's code, both reach the same `NAMING_EVENT`:
1. **N-key**: `RequestNamingKeyPacket.handle` raytrace-targets a mob,
   runs `canName(player, sub)`, opens `NamingMenu` if passes. On submit,
   `NAMING_EVENT` fires.
2. The menu submit fires `NAMING_EVENT` regardless of how the menu was
   opened.

### `canName` gates (decompiled in `RequestNamingKeyPacket`):

```java
isNotNameable(sub, player)        // -> entity-type tag check + INameEvolution + summoned check
SubordinateHelper.isSubordinate   // already-yours
existence.getName() != null        // already-named
existence.getPermanentOwner()      // someone else's
isNotSubmitting(sub, player)       // mob hasn't submitted (low HP / fear / EP gate)
hasEffect(INSANITY|RAMPAGE)        // mob in raging state
EnergyHelper.getMaxEP(sub) >= getBaseMaxEP(player)  // too strong to name
```

None of these can be customised per-entity without a mixin — the entity-tag
check is `EntityType`-level, not per-entity. We cannot mark ONE specific
orc as non-nameable at Tensura's pre-menu gate without modifying Tensura.

### RECOMMENDED: suppress at our `NAMING_EVENT` listener

In `onRaceNamed`, early-return at the top:

```java
if (entity.hasData(Attachments.ENVOY_TAG.get())) {
    player.sendSystemMessage(Component.literal(
        "This envoy is here on diplomatic business and cannot be named."));
    return EventResult.interruptFalse();   // aborts Tensura's commit
}
```

`EventResult.interruptFalse()` halts the Architectury event chain.
`NAMING_EVENT` is created with `EventFactory.createEventResult` (same shape
as `LIVING_CHANGE_TARGET` we already use) — interrupt-false aborts.
Verified by reading `RequestNamingKeyPacket.canName` and Tensura's existing
event-handling pattern.

### Trade-off: the menu still OPENS

The N-key check (`canName`) passes for envoys at the pre-menu stage —
nothing in `canName` can distinguish an envoy. So the player can open the
naming menu on an envoy, type a name, hit submit — and only then see the
"cannot be named" message. The menu open-then-bounce is mildly ugly but
fully functional, and avoids a Tensura mixin.

**If the open-then-bounce UX is intolerable later**, the fix is a small
Tensura-side mixin on `RequestNamingKeyPacket.canName` (or `isNotNameable`)
that returns `false`/`true` respectively when the envoy attachment is
present. Pattern already exists in our `TensuraBehaviourHelperMixin`. Not
recommended for v1 — over-engineered for the diplomatic-business edge case.

---

## 3. DIALOGUE UI — reuse MineColonies' interaction system?

### Pivotal finding: MC's interaction system is hard-bound to citizens

`IInteractionResponseHandler` (from MC's `api.colony.interactionhandling`):

```java
public interface IInteractionResponseHandler extends INBTSerializable<CompoundTag> {
    Component getInquiry();
    List<Component> getPossibleResponses();
    @Nullable Component getResponseResult(Component);
    boolean isValid(ICitizenData);                              // <- ICitizenData
    void onServerResponseTriggered(int, Player, ICitizenData);  // <- ICitizenData
    @OnlyIn(CLIENT)
    boolean onClientResponseTriggered(int, Player, ICitizenDataView, BOWindow);  // <- ICitizenDataView + BlockUI window
    ...
}
```

Every response-callback method takes `ICitizenData` or `ICitizenDataView`
as a mandatory parameter. The dialogue is rendered in a BlockUI `BOWindow`
that takes the `ICitizenDataView` as input. There is **no abstraction
layer for "non-citizen interaction target"**.

Reusing this for an envoy mob would require one of:

1. **Spawn a ghost `EntityCitizen`** alongside the envoy mob and use its
   `ICitizenData` for the handler. Bad: bloats colony state with a fake
   citizen, fights MC's bookkeeping (count, AI, jobs, work orders, death),
   high risk of subtle desync.
2. **Implement `IInteractionResponseHandler` with no-op citizen methods**
   and inject into an existing citizen somehow. Same ghost-citizen problem
   plus a layering hack.
3. **Fork just the BlockUI panel widgets** and render them ourselves. A
   lot of glue to reproduce the native look without the citizen plumbing.

### RECOMMENDED: custom Screen, no MC interaction reuse

A vanilla Minecraft Screen with the round-trip pattern this project already
uses for the race picker and the collapse-confirm dialog:

- S2C `OpenEnvoyDialoguePayload(envoyEntityId, envoyRace, colonyId, dialogueKey)`
- Client opens an `EnvoyDialogueScreen` — title, race-flavoured body text,
  Accept/Decline buttons.
- C2S `EnvoyResponsePayload(envoyEntityId, accepted: boolean)` on click.
- Server validates the entity still has the envoy attachment, resolves
  the colony, calls `ColonyRaceConfigSavedData.addMember(colonyId, race)`
  on accept, despawns the envoy.

**Why this beats MC reuse:**

- Avoids ghost-citizen state pollution (Option 1's quiet killer — would
  surface as "phantom citizen count went up" or "envoy appeared in roster").
- No coupling to MC's BlockUI which changes between versions.
- Uses infrastructure (`Networking` payload pattern, screen widgets) we
  already operate confidently.
- Aesthetic gap (no chat-bubble icon over the envoy's head) is small —
  a name-tag indicator + ✦ glyph above the head can be added cheaply with
  `RenderLivingEvent.Post`, the same hook we already use for race rendering.

**What's lost:**

- No native MC look. The dialogue panel will look like our existing race
  picker / collapse-confirm screens (which is fine — consistent with our
  mod's other panels) rather than blending into MC's existing UI.
- No chat-bubble icon over the envoy's head out of the box. (Cheap to add
  if desired — see above.)

---

## 4. RIGHT-CLICK HANDLING

**`PlayerInteractEvent.EntityInteract`** (NeoForge bus, server-side).
Fires when a player right-clicks any entity. Handler skeleton:

```java
@SubscribeEvent
public void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
    if (e.getLevel().isClientSide()) return;
    Entity target = e.getTarget();
    if (!target.hasData(Attachments.ENVOY_TAG.get())) return;
    EnvoyTag tag = target.getData(Attachments.ENVOY_TAG.get());
    if (tag == null || tag.state != EnvoyState.ALIVE) return;
    PacketDistributor.sendToPlayer((ServerPlayer) e.getEntity(),
        new OpenEnvoyDialoguePayload(target.getId(), tag.race, tag.colonyId, dialogueKey(tag)));
    e.setCanceled(true);
    e.setCancellationResult(InteractionResult.SUCCESS);
}
```

`e.setCanceled(true) + setCancellationResult(SUCCESS)` cancels the default
right-click behaviour — critical for the Villager-based colonist envoy, so
the trading screen doesn't open under our dialogue.

Server-authoritative by construction: the dialogue opens via S2C payload
only after the server has validated the envoy state. The C2S response
re-validates server-side before mutating `ColonyRaceConfigSavedData`.

---

## 5. SPAWN

The brief mentioned reusing "the safe-position finder from the send-to-colony
work" — that finder was **reverted** in the last user request, so it's no
longer in the codebase.

### RECOMMENDED: `EntityUtils.getSpawnPoint(level, townHall)` from MC

The MC helper we already discovered during the now-reverted safe-spawn
investigation:

- 5-block radius around the seed position
- 2-block clearance
- battle-tested by MC's own citizen spawn

Pros: no new code, MC's own helper, returns `BlockPos` or null.

Cons (already documented in the prior investigation): 2-block clearance
is tight for an orc envoy (~2.5 height). For a low-volume action (one
envoy per colony unlock, not bulk) this is acceptable risk — orc envoys
rarely spawn, and a brief clipping animation is tolerable.

Fallback when `getSpawnPoint` returns null: spawn at the raw town hall
position, log a warning. Same fallback shape as the existing
send-to-colony path.

### `finalizeSpawn` for appearance variety: YES

For goblin/orc envoys, call
`mob.finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null)`
BEFORE `addFreshEntity`, identical to the pattern in `spawnWildRaceMob`
(used for race-aware population spawn). This triggers Tensura's variant
randomisation in the entity's `finalizeSpawn` override so the envoy
doesn't look like a default-skin clone every time.

For the colonist (Villager) envoy: vanilla Villager's `finalizeSpawn`
already randomises profession; we can override the profession to e.g.
`nitwit` (no trades) or pick a thematic profession (`librarian` =
"scholar"). Reading the brief, a profession choice is cosmetic-only.

`SPAWN_EGG` (not `NATURAL`) is the right MobSpawnType per the same
reasoning as wild race-mob spawn: it triggers variant randomisation AND
marks the mob as non-despawnable, so the envoy waits for the player to
interact.

---

## Pivotal answers

**#1 — Envoy marker:** NeoForge data attachment (`ENVOY_TAG`), same pattern
as existing `RACE_TAG`. Persists, identifiable, low cost. Universal across
all three envoy entity types.

**Colonist envoy entity:** vanilla `Villager` with the envoy attachment.
Suppress trading by cancelling `EntityInteract`. Avoids the
ghost-`EntityCitizen` pitfalls.

**#3 — Dialogue UI:** **Custom Screen, NOT MC interaction reuse.** MC's
`IInteractionResponseHandler` is hard-bound to `ICitizenData` /
`ICitizenDataView` in every callback. Reuse paths all require either
a ghost citizen (state pollution risk) or substantial glue (defeats
"less work"). Custom Screen uses infrastructure (Networking payload
round-trip, screen widgets) we already operate confidently.

## Single biggest risk

The naming-suppression `EventResult.interruptFalse()` aborts the
NAMING_EVENT chain — but the Tensura naming menu STILL opens and the
player types a name before the suppression message appears. UX is
open-then-bounce. Likely fine for envoys (rare interaction); if
intolerable, fix is a small Tensura-side mixin on
`RequestNamingKeyPacket.canName` checking the envoy attachment, same
pattern as our existing `TensuraBehaviourHelperMixin`. Deferred until
playtesting shows the bounce is annoying.

---

# Implementation — landed

The envoy system shipped across four stages. Investigation findings above
guided the implementation. Below is the as-built record per stage; design
decisions originated in the investigation section and are NOT re-stated.

## Stage 1 — entity, spawn, naming suppression

**`EnvoyTag` data attachment.** Carries `(colonyId, ColonyMember member, State state)`
where `State` is `{ALIVE, ACCEPTED, DECLINED}`. NBT-serialised via
`IAttachmentSerializer`, sibling to `RACE_TAG` in `Attachments`. The
universal "is this an envoy" check is
`entity.hasData(Attachments.ENVOY_TAG.get())`.

**Entity per member:**
- GOBLIN → `tensura:goblin` (Tensura `GoblinEntity`)
- ORC → `tensura:orc` (Tensura `OrcEntity`)
- COLONIST → `minecolonies:visitor` (`ModEntities.VISITOR` → `VisitorCitizen`)

**Colonist envoy = real `VisitorCitizen`, NOT vanilla Villager.** The
investigation flagged the risk of a "stray EntityCitizen"; in practice the
fix was specific — `VisitorColonyHandler.registerWithColony` discards the
entity if `citizenId == 0`. We must create an `IVisitorData` so the
citizen id is non-zero. After `addFreshEntity`:

    data = (IVisitorData) colony.getVisitorManager().createAndRegisterCivilianData();
    data.setName("Colonist Envoy");
    mob.setUUID(data.getUUID());
    mob.setCitizenId(data.getId());
    handler.setColonyId(colony.getID());
    handler.registerWithColony(colony.getID(), data.getId());

The visitor IS counted in `IVisitorManager` (briefly visible in the tavern
visitor list if one exists). On accept / decline / kin-kill we route
through `colony.getVisitorManager().removeCivilian(citizenData)` to unwind
the VisitorData — NOT just `entity.discard()`.

**Spawn position.** `EntityUtils.getSpawnPoint(level, townHall)` — MC's
5-block-radius 2-block-clearance helper. Falls back to raw town hall
position on null with a warning log. Tight clearance is a known v1
trade-off for orc envoys; envoy actions are low-volume, so brief clipping
is tolerable.

**Persistence.** `MobSpawnType.SPAWN_EGG` in `finalizeSpawn` + explicit
`setPersistenceRequired()` — doubled non-despawn lock. The `ENVOY_TAG`
attachment serialises with the entity, so reload preserves the marker.

**Naming suppression.** Early-return in `onRaceNamed`:

    if (entity.hasData(Attachments.ENVOY_TAG.get())) {
        player.sendSystemMessage(literal("Envoys cannot be named.").withStyle(RED));
        return EventResult.interruptFalse();
    }

The Tensura naming menu still opens (per the investigation, `canName`
cannot be customised per-entity without a Tensura mixin). Open-then-bounce
UX is the v1 accepted trade-off.

## Stage 2 — dialogue, accept/decline, nameplate, roam radius

**Roam radius.** `mob.restrictTo(townHallPos, 15)`. Verified bounding for
all three entity types: vanilla Villager wander goals use `LandRandomPos`,
which honors `isWithinRestriction`. SmartBrainLib's `SetRandomWalkTarget`
(used by `GoblinEntity` and `OrcEntity` brains) also delegates to vanilla
`LandRandomPos.getPos`. Same restriction call works for all three.

**Nameplate colors (final):**

| Member | Label | ChatFormatting |
|---|---|---|
| GOBLIN | "Goblin Envoy" | `GREEN` |
| ORC | "Orc Envoy" | `DARK_RED` |
| COLONIST | "Colonist Envoy" | `AQUA` |
| Dwarf (future) | "Dwarf Envoy" | `GOLD` |
| Lizardman (future) | "Lizardman Envoy" | `YELLOW` |

`mob.setCustomName(coloredComponent)` + `setCustomNameVisible(true)` for
GOBLIN / ORC. COLONIST additionally requires `data.setName("Colonist Envoy")`
because the citizen renderer reads the citizen-data name path alongside
the vanilla custom name path; aligning both shows just "Colonist Envoy"
instead of stacking a random colonist name underneath.

**Right-click intercept.** `PlayerInteractEvent.EntityInteract`, server-side.
If the target has `ENVOY_TAG` and state is ALIVE:

    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.SUCCESS);
    PacketDistributor.sendToPlayer(player, OpenEnvoyDialoguePayload(...));

The cancellation kills the vanilla villager trade screen for colonist envoys.

**Dialogue UI — custom Screen, NOT MC interaction-handler reuse.** Per the
investigation's pivotal answer, `IInteractionResponseHandler` is hard-bound
to `ICitizenData` at every callback, requiring either a ghost citizen
(state pollution) or substantial glue. Custom `EnvoyDialogueScreen` mirrors
`ConfirmCollapseScreen`'s pattern: title in race colour, body wrapped via
`font.split`, Accept (right) and Decline (left) buttons. ESC = close
without committing (envoy stays, can be re-clicked).

**Race-flavoured dialogue text.** Stored in `EnvoyDialogue` constants —
nameplate label, nameplate colour, dialogue title, dialogue body, accept
message, decline message — all keyed by `ColonyMember`. Dwarf and
Lizardman copy pre-written in `EXTRA_*` maps for later stages. Tone
deliberately distinct per race, rewritten to match Tensura canon (envoys
are generic race representatives who revere the player as a powerful
protector-ruler): goblin humble-eager-grateful, orc dutiful-solemn with a
note of atonement, lizardman proud-formal-but-sincere, dwarf
gruff-hearty-craftsman. Colonist left polite-neutral (not a Tensura race;
no canon profile). Functional references that convey unlock conditions
(Orc Disaster, Ifrit, true demon lord / hero, dwarven village, colony
size / age) are kept verbatim; invented org/character names (Elder,
Marsh-Tribe, Dwarven Holds, etc.) were removed.

**Server resolves response.** C2S `EnvoyResponsePayload(entityId, accepted)`.
`handleEnvoyResponse` re-validates entity + ownership + ALIVE state, then:

- **Accept:** `config.addMember(colonyId, member)` (drives the spawn set),
  `config.markEnvoyAccepted(colonyId, member)` (permanent diplomacy lock),
  tag → ACCEPTED, despawn with poof, green confirmation chat.
- **Decline:** tag → DECLINED, despawn with poof, gray dismissal chat.
- **Both:** `setLastEnvoyResolveTick(now)` + `setActiveEnvoyUuid(null)`
  unblock the scheduler.

**Despawn visual.** `despawnEnvoyWithEffect` — 24 `POOF` particles around
body-centre + `PLAYER_TELEPORT` sound. Fires on accept, decline, AND
kin-kill (Stage 3b). Visual consistency reads as "the envoy left."

## Stage 3a — unlock conditions + scheduler

**Per-race unlock conditions:**

| Race | Condition | Source |
|---|---|---|
| COLONIST | Colony age ≥ 3 in-game days | `colonyCreationTick` recorded on `ColonyCreatedModEvent` |
| GOBLIN | ≥ 3 named goblin identities for this colony | derived via `RaceIdentitySavedData.all()` filter |
| ORC | Colony citizen count ≥ 25 | `colony.getCitizenManager().getCurrentCitizenCount()` |

Plus universal exclusions: race not already in colony's members, not
already in `acceptedEnvoys`, and (for non-COLONIST) within the player's
non-colonist envoy cap.

**`tensuraMaxNonColonistEnvoys` gamerule.** Default `4`. Per-player count
of distinct non-COLONIST races whose envoys have ever spawned for that
player, across all their colonies, persisted. Once the player's set
reaches the cap, only races they have already seen remain eligible (so a
declined goblin can return; a never-seen third race never appears).
COLONIST is not counted toward the cap.

**Scheduler cadence.** Initially once per in-game day
(`tickCount % 24000 == 0`), later changed to **every 20 ticks (1 s)** so
`/time add` advancement is picked up promptly — the previous cadence used
`server.getTickCount()`, which does NOT advance on `/time` jumps. The
day-based gates inside `tryScheduleEnvoy` use `level.getGameTime()`, so
the actual spawn cadence is still gated by the 3-day cooldown and the
3-day-age rules, just re-evaluated every second.

**Scheduler gates (in order):**

1. **Active-envoy gate:** `activeEnvoyUuid` set AND the entity still exists
   AND still tagged → skip. Stale uuid → silent resolve, log cleanup.
2. **3-day post-resolve cooldown** (`ENVOY_RESOLVE_GAP_TICKS = 72000`).
   Bypassed only when no envoy has ever resolved.
3. **Eligibility gate:** `computeEligibleEnvoys` returns at least one
   member.

Then `spawnEnvoy(level, colony, pick)` (uniform random pick from the
eligible set), record `activeEnvoyUuid`, record player history. Wrapped in
try/catch so a single colony's failure does not break the per-tick loop.

**Storage extensions to `ColonyRaceConfigSavedData`:**
- `Map<Integer, Long> colonyCreationTick`
- `Map<Integer, Long> lastEnvoyResolveTick`
- `Map<Integer, UUID> activeEnvoyUuid`
- `Map<UUID, EnumSet<ColonyMember>> playerNonColonistEnvoysSeen`

All NBT-persisted; legacy saves missing the keys load empty.

**Debug commands:**
- `/spawnenvoy <member>` — force-spawn an envoy (also writes
  `activeEnvoyUuid` + player history so the scheduler stays consistent).
- `/envoystate` — per-race eligibility diagnostic with reasons.
- `/envoyforce` — single immediate scheduler tick with chat diagnostics.
- `/envoyresetcooldown` — clear the 3-day cooldown for testing.

## Stage 3b — kill-gate

Killing a Tensura race resets that race's envoy unlock condition for every
colony the killer owns — EXCEPT races already in the colony's
`acceptedEnvoys` set, which are permanently immune.

**Excluded entities.** Orc Lord (`tensura:orc_lord`) and Orc Disaster
(`tensura:orc_disaster`). Two layers — `Races.isBlocked` early-return AND
`Races.of` returns null for them.

**Killer resolution.** `event.getSource().getEntity()`. Vanilla wraps
projectile owners, so arrow / spell kills count. Non-player killers
(environmental, mob-on-mob) skip the gate.

**Per-condition-shape reset:**

| Shape | Race | Reset semantics |
|---|---|---|
| TIMER | COLONIST | Store `colonistKillResetTick[colonyId] = now`. Eligibility anchor = `max(colonyCreationTick, killResetTick)` — wait the full 3 days from the kill. |
| CUMULATIVE | GOBLIN | Store `goblinNamedBaseline[colonyId] = currentNamedCount`. Eligibility: `(currentNamedCount - baseline) >= 3` — 3 NEW named goblins required. |
| CURRENT-VALUE | ORC | Store `orcCitizenSnapshot[colonyId] = currentCitizenCount`. Eligibility: `currentCount >= 25 AND currentCount > snapshot` — colony must GROW past the kill-time level. Snapshot clears on ORC envoy resolve. |

**Why snapshot-then-grow-past for ORC** (not a boolean flag): the colony
may still be at 25+ citizens immediately after the kill. A "needs
retrigger" flag without a numeric anchor would never clear if citizens did
not die. The snapshot makes "re-trigger" mean "the colony grew further" —
robust to stable populations and handles both "still at 25" and "way above
25" cases consistently.

**Active-envoy kin-kill despawn.** If `activeEnvoyUuid` is set and the
live envoy's `member == killedMember` and state is ALIVE:

- Poof effect (same as accept / decline).
- For colonist envoys, `VisitorManager.removeCivilian(data)`.
- `entity.discard()`.
- `setLastEnvoyResolveTick(now)` + `setActiveEnvoyUuid(null)`.

The 3-day cooldown still applies — kin-kill does NOT skip it; it just
guarantees the condition must re-fire too.

**`/envoystate` extended** with kill-gate diagnostics per race:
`"3d since anchor (anchored at last kill-reset)"` for COLONIST,
`"1/3 goblins named since baseline (baseline 4 from kill-reset)"` for
GOBLIN, `"citizen count 25 must grow past snapshot 25"` for ORC.

## Summary — total surface area

- **New files:** `EnvoyTag`, `EnvoyDialogue`, `EnvoyDialogueScreen`,
  `ColonyMember` (the multi-race foundation that envoys build on).
- **Networking payloads:** `OpenEnvoyDialoguePayload` (S2C),
  `EnvoyResponsePayload` (C2S).
- **Attachments:** `ENVOY_TAG` sibling to `RACE_TAG`.
- **Gamerules:** `tensuraMaxNonColonistEnvoys` (default 4),
  `tensuraHostileToCitizens` (default true, predates envoys but used
  alongside).
- **`ColonyRaceConfigSavedData` extensions:** members set,
  accepted-envoy lock, colony creation tick, last resolve tick, active
  envoy uuid, player non-colonist envoy history, colonist kill-reset
  tick, goblin named baseline, orc citizen snapshot — all NBT-persisted.
- **Commands:** `/spawnenvoy`, `/envoystate`, `/envoyforce`,
  `/envoyresetcooldown`.

The envoy system is feature-complete for the three buildable races
(COLONIST, GOBLIN, ORC). Dwarf and Lizardman race-mob entities exist in
Tensura but are not yet plumbed into `Race` / `ColonyMember`; their
dialogue copy is pre-written in `EnvoyDialogue.EXTRA_*` for when those
races are added.
