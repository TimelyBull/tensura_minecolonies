# Investigation: Assassin system — technical foundations

**Status:** v1 + v2 BUILT (2026-06-10). Spell-casting REWORKED
(2026-06-17) — see below.

**Casting now runs on Nightmare's Tensura Utils (2026-06-17):**
The hand-built cast driver (curated `CASTABLE_PRESS`/`CASTABLE_TOGGLE`
whitelist, `tryCastSkill`, per-mob cooldown/rotation maps) has been
DELETED and replaced by a single registration against the public
`NightmareUtilsApi.registerReflectiveManascoreAutocaster(...)`
(`Assassins.registerAutocaster()`, called once at common setup). It
targets any `ASSASSIN_TAG`-marked mob, allows ANY learned skill (the lib
filters passives/ambients itself), and reads the boss's `mob.getTarget()`
— which the boss driver's `lockTarget` keeps pointed at the victim — so
casting still fires ONLY in combat (no target → no cast). Integration is
PUBLIC-API-ONLY: no mixins into the lib's classes. Cooldown lives in
`ASSASSIN_CAST_COOLDOWN_TICKS` (100 / 5 s). The old whitelist curation
task is retired. The sections below describing the in-house cast driver
are HISTORICAL.

**Post-build amendments (2026-06-10):**
- Boss speed buff lowered ×1.4 → **×1.15**.
- **Town-hall tether:** `restrictTo(townHall, 32)` bounds idle wander;
  the per-second driver recalls the boss beyond 32 blocks (WALK_TARGET
  home) and drops its chase beyond 48 (the patrol-recall pattern).
- Theft message wording: skills are **COPIED** (the player keeps theirs);
  only the EP is gone until reclaimed.
- Resistance copy limit 15 → **10** (1 unique / 5 extra / 10 common /
  ≤10 resistance).
- **Config kill-switch:** `enableAssassins` (common config, default
  true). Disabled → no buildup, existing LURKING/ARMED plots defuse on
  the next daily pass, no strikes; an already-ACTIVE boss remains until
  slain (reclaim still works).
- **One assassin per colony, EVER:** a persistent `assassinChosen`
  marker is set the moment a candidate is first picked; the colony
  never breeds another regardless of the plot's outcome (defused,
  slain, lost). `/assassin arm` bypasses for testing but still records
  the choice; `/assassin` shows the lock.
- Debug command: `/assassin [state|arm|strike|defuse]` (op).

## v2 as-built (theft + reclaim)

Triggered when an ASSASSIN-tagged boss KILLS its target player
(`onLivingDeath` killer attribution, the boss-flag pattern). Steals
once per assassin (tag records the amounts).

- **EP theft (user-confirmed full-reclaim design):** steals
  `EP_STEAL_FRACTION = 0.5` of base max EP — since baseMaxEP =
  baseMaxMagicule + baseMaxAura, half of EACH is removed via negative
  stable-id `AttributeModifier`s on the player's MAX_MAGICULE /
  MAX_AURA (`assassin_stolen_magicule/aura`; persist in player NBT,
  remove-first so never compounding). Currents clamped to the new
  maxes (magicule kept ≥ 1 → Sleep Mode never fires). The assassin
  gains the same amounts as positive modifiers and its pools refill —
  its casting runs on YOUR stolen magicule.
- **Reclaim:** killing the boss removes the player's theft modifiers —
  100% restoration ("your stolen power floods back"). Victim offline at
  boss death → `pendingReclaim` persists in AssassinSavedData and
  applies on next login.
- **Skill copy:** `getLearnedSkills()` categorized by Tensura
  `Skill.getType()`; takes the HIGHEST-MASTERY first (it steals your
  best): 1 UNIQUE / 5 EXTRA / 10 COMMON / ≤10 RESISTANCE;
  `instance.copy()` (keeps mastery) + `learnSkill` onto the assassin's
  storage (persists on its NBT). Resistances/passives work with no AI.
  The victim gets the itemized theft message.
- **Cast driver:** while the boss has its live target within 24 blocks,
  every 5 s (`CAST_COOLDOWN_TICKS = 100`) it faces the target (look
  control + lookAt) and fires ONE whitelisted PRESS skill —
  round-robin, `canInteractSkill`-gated, activation
  `instance.onPressed(mob, 1, 0)`; a failed/blocked attempt re-checks
  in 2 s. Whitelisted TOGGLE buffs switch on once at theft time
  (`onToggleOn`). A cast that throws logs "consider removing it from
  the whitelist".
- **INITIAL WHITELIST (the curated seed — extend via in-game testing):**
  PRESS: Water Blade, Voice Cannon, Paralysis, Poison (common);
  Black Flame, Black Lightning, Heat Wave (extra).
  TOGGLE: Strength, Self-Regeneration (common); Magic Aura (extra).
  Both sets are plain `Set<ResourceLocation>` constants in
  `Assassins` (`CASTABLE_PRESS` / `CASTABLE_TOGGLE`) — one-line adds.
  Copied skills NOT whitelisted are held (flavor) and never cast.

## v1 as-built (unchanged by v2, except boss death now also reclaims)

- **State machine** (`AssassinSavedData`, overworld; separate from the
  identity NBT, stale entries pruned): determination per identityId +
  state NONE/LURKING/ARMED/ACTIVE + per-colony cold-shoulder set.
- **Tuning (user-confirmed):** +1 determination/day while reputation
  tier below WARY AND avg happiness < 4.0; −1/day decay pre-LURKING;
  LURKING at 2; ARMED at 4; defuse = instant clear (flag off) when the
  colony stops qualifying while LURKING/ARMED. One candidate per colony,
  picked randomly from its named race-identities. Rides the daily
  reputation-drift pass.
- **Great Sage tell:** `SyncAssassinFlagPayload` (+ StartTracking
  resync) feeds a client flag store; `AssassinClientHandler` draws a
  red "Assassin" line above the flagged body via
  `RenderLivingEvent.Post` ONLY when the local player has
  `UniqueSkills.GREAT_SAGE` (client self-check, 1 s cache).
- **Strike (ANY one window, checked 1/s while ARMED):** owner low HP
  (≤35%), sleeping, all armor slots empty, festival start, or
  just-prestiged (60 s windows marked by our own festival/prestige
  hooks). Activation: IN_COLONY → citizen discarded + travelling-marked,
  Tensura body rebuilt from the snapshot BEHIND the player (no
  circles/cost), identity flipped to SUBORDINATE; SUBORDINATE → flipped
  in place (waits for the body to be loaded). Ownership stripped
  (`setPermanentOwner/TemporaryOwner(null)` + TamableAnimal owner) and
  the target locked with the dual-write (setTarget + brain
  ATTACK_TARGET), re-asserted per second.
- **Boss (v1 power = stats only):** ×3 max health (healed), ×2.5
  spiritual health, ×1.4 speed, ×2.5 attack damage (stable-id ADD
  modifiers, the stat-sync pattern); dark-red "<name>, the Betrayer"
  custom name; purple `ServerBossEvent` HP bar shown within 64 blocks;
  `ASSASSIN_TAG` attachment (identityId, colonyId, target) for
  reload re-linking.
- **Cold shoulder (our surfaces):** citizen trade refuses ("the citizen
  turns away…"), envoy scheduling pauses for the colony. Cleared when
  the assassin dies. MC's internal worker behaviors untouched (per the
  investigation's honest limit).
- **Death cleanup:** confirmed free — the existing case-A race-mob death
  hook removes the citizen-data + identity; the assassin hook only
  clears the bar + cold shoulder first. No reward in v1.

---

## Original investigation (v2 findings remain authoritative)

**Status when written:** investigation only (2026-06-10).

**Concept:** a mistreated colony (low reputation + low happiness) breeds a
secret assassin among the player's own citizens; it strikes when the
player is vulnerable; on a successful kill it steals half the player's EP,
copies their skills, and becomes a hostile boss. Great Sage detects the
plot; raising happiness defuses it.

All class references verified by `javap` against the jars in `libs/`.

---

## #1 PIVOTAL — Architecture: the assassin IS the Tensura subordinate body. FEASIBLE.

The two-bodies-one-identity model already gives us everything: each named
race-citizen has a full Tensura entity (live, or reconstructable from the
identity's `entitySnapshot` — the summon/trade/restock pattern). EP,
skills (ManasCore storages), GeckoLib/biped renderers, and combat AI all
live on the Tensura body; a MineColonies citizen can hold none of them.

**On activation:**
- Identity `IN_COLONY` → discard the citizen body and reconstruct the
  Tensura body from the snapshot (the summon machinery, minus the cost
  gate and circles — an assassin doesn't announce itself), positioned
  near the player.
- Identity `SUBORDINATE` (already at the player's side — the scariest
  case) → flip the live body in place.
- Mark with an `ASSASSIN_TAG` attachment (the EnvoyTag/RaidTag idiom):
  identityId + phase (LURKING / ACTIVE / BOSS). NBT-persisted.

**Turning it hostile (the one wrinkle):** a named subordinate's targeting
runs through `ISubordinate.shouldTarget`, whose owner branch protects the
owner. The assassin must shed subordinate status: clear
`IExistence.permanentOwner` (the doc-verified field Tensura sets at
naming) + force `setTarget(player)` and the SmartBrain `ATTACK_TARGET`
(`BrainUtils.setTargetOfEntity` — the raid target-assist already proves
this drives Tensura combat). The raid steering also proved Tensura mobs
fight whatever is in that memory. Risk: low — worst case a ManasCore
`LIVING_CHANGE_TARGET` approval listener (the patrol veto in reverse)
keeps the target locked.

**Both bodies die when the assassin dies:** automatic. After activation
only ONE body exists (the Tensura body). Our existing death hook
(`onLivingDeath` case A) already removes the `CitizenData` (colony count
drops) and deletes the identity when a race-mob body dies. The spec's
"colonist + subordinate both die" falls out of the existing model with
zero new code.

## #2 PIVOTAL — EP theft: FEASIBLE, with a known-safe mechanism.

**EP is exactly `baseMaxMagicule + baseMaxAura`** —
`EnergyHelper.getBaseMaxEP` bytecode is literally
`getBaseMaxMagicule(e) + getBaseMaxAura(e)`. So "steal half the player's
EP" = halve those two attribute values.

**We already operate this machinery**: the swap stat-sync's
`bumpAttributeTo` applies a tracked permanent `AttributeModifier`
(stable id, `ADD_VALUE`) to `TensuraAttributes.MAX_MAGICULE` /
`MAX_AURA` / `MAX_SPIRITUAL_HEALTH` / vanilla `MAX_HEALTH` — on citizens
and goblins today. Applying the same with a NEGATIVE delta on the PLAYER:

- `player.getAttribute(TensuraAttributes.MAX_MAGICULE)
  .addPermanentModifier(new AttributeModifier(ASSASSIN_THEFT_ID,
  -half, ADD_VALUE))` (likewise MAX_AURA). Player attribute modifiers
  persist in player NBT across save/reload.
- Then clamp the player's CURRENT magicule/aura to the new max
  (`IExistence.setMagicule/setAura` + `markDirty` — the cost-gate
  idiom) so no over-max weirdness; magicule stays > 0 so Sleep Mode
  does NOT trigger (the forced-collapse pipeline only fires at 0).
- The assassin gains the same amount via positive modifiers on ITS
  attributes (the bump helper as-is).
- **Reversible by design**: removing the stable-id modifier restores
  the player — supports a future "slay the assassin boss to reclaim
  your power" beat, and is the safety valve if anything misbehaves.

**Safety notes:** use ONE stable modifier id (never compounds — the bump
helper already removes-before-add); Tensura's own EP growth raises
attribute BASE values, which coexist with our modifier; the player's
gear/skill temp buffs sit on top unaffected. Classified FEASIBLE — more
invasive than reading, but it's the same attribute surface we've run
since Stage D3.

## #3 PIVOTAL — Skill copy + use: COPY is FEASIBLE; USE is PARTIAL (better than feared).

**(a) Read + categorize: FEASIBLE, trivial.**
`SkillAPI.getSkillsFrom(LivingEntity)` returns the `SkillStorage`;
`getLearnedSkills()` → `Collection<ManasSkillInstance>`. Every Tensura
skill extends `Skill` with `getType()` →
`SkillType { RESISTANCE, INTRINSIC, COMMON, EXTRA, UNIQUE, ULTIMATE }`.
Selecting "1 unique, 5 extra, 10 common, ≤15 resistances" is a filter +
limit per type.

**(b) Grant to the assassin: FEASIBLE, confirmed entity-agnostic.**
`SkillStorage` attaches to ANY `LivingEntity` (re-confirmed — the
storage API is LivingEntity-typed throughout, as the spider arc found).
`ManasSkillInstance.copy()` clones an instance (mastery + NBT included);
`storage.learnSkill(copy, message)` grants it. The storage persists on
the entity's NBT (rides the snapshot too).

**(c) USE in combat: PARTIAL — three honest tiers.**
The activation API is fully programmatic and LivingEntity-typed:
`skill.onPressed(instance, entity, keyNumber, mode)`, `onHeld`,
`onToggleOn/Off`, `storage.startHoldSkill(...)`, plus per-tick
`onTick`. Tensura's own bosses interact with their OWN storages
(IfritEntity / OrcDisasterEntity bytecode shows `getSkillsFrom` +
`getSkill` + `SkillUtils.getSkillOrNull` + `SkillHelper` calls), so
non-player skill execution is an exercised path, not theory.

1. **Free tier — passives + resistances work with NO AI.** Resistances
   and passive skills apply through ManasCore's event hooks on the
   storage owner (damage events etc.). Granting them = they function.
   The "≤15 resistances" part of the spec is essentially free.
2. **Curated tier — many actives are mob-castable.** Offensive
   projectile/AoE/self-buff skills read the caster's look vector and
   position; a cast driver (face target via look control — the barrier
   pounding already does this — then `onPressed`/`startHoldSkill` on a
   cooldown) will genuinely fire them. CAVEATS: some skills assume
   `instanceof Player` for chat/UI/cooldown display (usually safe
   no-ops, but each needs a smoke test); magicule costs draw from the
   assassin's own (freshly stolen) pool — thematically perfect.
   Approach: a CURATED WHITELIST of known-good castable skills, built
   by testing the common/extra/unique catalogs; non-whitelisted copies
   are still HELD (visible in the kill feed / lore) but not cast.
3. **Excluded tier — UI/menu/vehicle-style skills** (Great Sage's
   analysis prompts, spatial storage, etc.) are meaningless on a mob —
   copied for flavor at most.

**Fallback (if the curated tier proves thinner than hoped):** the
assassin's power is expressed as stat-equivalents — the EP theft (#2)
already transfers real power; add damage/speed/health modifiers scaled
to the count+types of copied skills. The copy itself (visible list:
"X stole Spatial Motion, Black Flame, …") still lands the narrative
even where casting doesn't.

**Verdict: copy-with-curated-use.** Plan for tier 1+2 with the tier-3
exclusion list; the fallback only supplements, it doesn't replace.

## #4 Triggers — ALL FEASIBLE, hooks exist in our code today.

- **Determination buildup:** we already run a per-day pass per colony
  (the reputation drift tick). Add: when
  `ReputationManager.isBelow(colony, WARY)` (tunable) AND
  `getOverallHappiness() < threshold`, accumulate `determination` on a
  randomly-chosen eligible identity (persisted — new fields beside the
  identity or a small SavedData). At a threshold → LURKING (eligible to
  strike); the strike itself is then GUARANTEED at the next
  vulnerability window.
- **Vulnerability states — all cleanly detectable:**
  - low HP: `player.getHealth() / getMaxHealth()` (server tick check);
  - sleeping: `player.isSleeping()`;
  - no armor: armor `EquipmentSlot`s all empty;
  - festival start: WE call `HarvestFestival.run` — add a callback;
  - just-prestiged: WE handle the `ResetScrollItem RESET_ALL` Finish
    event — set a short "recently prestiged" window there.
- **Great Sage detection:** `UniqueSkills.GREAT_SAGE` is a registry
  constant; `SkillAPI.getSkillsFrom(player).getSkill(id).isPresent()`
  is the check. ManasCore syncs the player's OWN storage to their
  client, so the CLIENT can self-check Great Sage.
- **Red "assassin" nameplate:** sync a LURKING flag to clients via the
  RaceTag payload idiom; client-side `RenderNameTagEvent` (or our
  existing render handlers for race citizens, which already own the
  nameplate path) draws a red "Assassin" line above the name ONLY when
  the viewing client has Great Sage. Server-authoritative variant
  (only sync the flag to Great-Sage players) is the stricter option.
- **Defuse:** the same daily pass decays determination when happiness
  recovers above the threshold; LURKING clears below the activation
  bar. (Detect → fix the colony → defused, exactly per spec.)

## #5 Consequences — ALL FEASIBLE with known patterns.

- **Boss bar:** `ServerBossEvent` exactly as `TensuraRaidEvent` does
  (progress = boss HP fraction; players within range).
- **Boss buffs:** `bumpAttributeTo` on MAX_HEALTH / MAX_SPIRITUAL_HEALTH
  / MOVEMENT_SPEED / ATTACK_DAMAGE — existing machinery.
- **Colony passive-aggressive:** a per-colony flag (SavedData) gating
  OUR systems: citizen trade tab refuses ("they turn away"), roster
  send/summon advisories, envoys pause. HONEST LIMIT: MineColonies'
  own behaviors (builders building, couriers delivering) are not
  cleanly gateable without deep MC surgery — scope "won't trade/assist"
  to OUR interaction surfaces + flavor messaging.
- **Death cleanup:** existing case-A death hook already removes citizen
  + identity when the Tensura body dies (see #1). The boss-bar clear +
  attribute restoration (if we want EP returned on boss death) hang on
  the same hook.

---

## Recommended STAGED scope

**v1 — the social/manifestation layer (all-FEASIBLE parts):**
1. Determination buildup + LURKING state (daily pass; rep below WARY +
   happiness below threshold; persisted per identity).
2. Great Sage detection + red nameplate (flag sync, client check) +
   defuse-by-happiness.
3. Vulnerability-triggered strike: the guaranteed activation picks the
   next window (low HP / sleeping / no armor / festival start /
   just-prestiged), manifests the Tensura body (summon machinery, no
   circles), strips subordinate status, locks target on the player.
4. Boss manifestation with STAT buffs + boss bar + hostile-to-player;
   colony passive-aggressive flag gating our trade/roster/envoy
   surfaces.
5. Death cleanup (existing hooks) — both bodies gone.

**v2 — the power-theft layer (per #2/#3 findings):**
6. EP theft on a successful player-kill: negative stable-id modifiers
   on the player's MAX_MAGICULE/MAX_AURA (+ clamp), positive on the
   assassin. Optionally reversible on boss death ("reclaim your
   power").
7. Skill copy (1 unique / 5 extra / 10 common / ≤15 resistance via
   `getType()` + `instance.copy()` + `learnSkill`): resistances/passives
   work free; CURATED castable whitelist driven by a face-target +
   `onPressed` cast driver; stat-equivalent supplement for
   non-castable copies.

v2's only genuinely open work is curating the castable whitelist — an
in-game testing task, not an architectural risk.
