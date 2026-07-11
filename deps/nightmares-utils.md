# Nightmare's Tensura Utils — dependency reference

Pinned to **nightmareutils 0.1.2** (`libs/nightmareutils-0.1.2.jar`). Package
root `dev.shadowako.nightmareutils`. Builds on Tensura + ManasCore's skill
system. All claims `[READ]` from decompiled source unless marked.

We use exactly one thing from this mod: the **`sentient` skill**, which makes a
mob autocast the Tensura skills it already knows. Everything else here is
available-but-unused.

## Ground truth: `registerAutocaster` vs `sentient` are the SAME machinery

Our own docs have historically described these as if one replaced the other.
They don't — they're two altitudes of one system:

- `api/NightmareUtilsApi` is a real, public, final class of static methods,
  including 7 `registerAutocaster(...)` overloads +
  `registerReflectiveManascoreAutocaster(Predicate<Mob>, Predicate<LivingEntity>,
  Predicate<ResourceLocation>, RandomGenerator, double maxRangeSq,
  ResourceLocation gateId, int cooldownTicks)`. These enrol a predicate +
  `MobSkillAdapter` into `service/MobSkillAutocastService`, whose `onLevelTick`
  reads `mob.getTarget()` and fires the mob's active skills.
- The `nightmareutils:sentient` skill is the **higher-level convenience**:
  `ModLifecycle.init` (L68-76) itself calls
  `registerReflectiveManascoreAutocaster(mob -> SentientSkillService.hasSentient(mob),
  living -> true, id -> !SENTIENT_ID.equals(id), rng, 9216.0,
  "nightmareutils:autocast/sentient", 20)`. So **any mob that has `sentient` is
  already selected by a built-in autocaster registration the mod ships.**

**Verdict:** granting `sentient` is the intended, supported path. You do **not**
call `registerAutocaster` yourself. Our code (grant sentient, never call
`registerAutocaster`) is correct and idiomatic — see
[ColonyThreatResponse.java](../src/main/java/com/example/examplemod/ColonyThreatResponse.java)
and `ExampleMod.grantSentient`/`removeSentient` (`SENTIENT_SKILL_ID =
"nightmareutils:sentient"`, ~ExampleMod.java L7418-7460).

Granting `sentient` does **more than enable casting** — it enrols the mob in a
family of server-side, sentient-gated driver services (all gated on
`SentientSkillService.hasSentient` or `skills.contains(SENTIENT_ID)`), all
auto-wired to NeoForge events in `ModLifecycle.init` (L85-97). So **our
defenders + assassin boss get all of this for free the moment they hold
`sentient`**, not just spell-casting:

1. `service/MobSkillAutocastService.onLevelTick` — the reflective autocaster;
   fires active `onPressed` skills at `mob.getTarget()`.
2. `service/SentientSkillService.onLevelTick` — toggles ON learned non-passive
   toggleable skills in bursts (no target needed).
3. `service/NpcCombatMovementService.onLevelTick` — **combat movement**:
   strafes / repositions the mob relative to its target when 2–12 blocks away
   (gated `skills.contains(SENTIENT_ID)`). This is why our steered defenders also
   juke rather than stand still.
4. `service/NpcPrideReactiveCopyService.onIncomingDamage` — when the sentient mob
   **takes damage**, reflectively tries to learn a sub-skill from the attacker's
   source skill (a Pride/Gluttony-style reactive copy). Fires on our
   defenders/assassin when hit. [READ — reflection into `pride.getSkill().learnSubSkill(...)`]
5. `service/NpcSpecialCombatSkillService.onLevelTick` — disintegrate / summon
   specials (only fully active if the mob also has `sentientdisintegrate`, which
   we don't grant).

`service/NpcCreatorRandomSkillService` is also sentient-adjacent but gated on a
separate `nightmareutils_creator_generated` marker [INFERRED: tied to
nightmareutils' NPC-creator content, not our defenders — verify if a defender
ever gains unexpected skills].

## Registries [READ]

- Skills `registry/NightmareUtilsSkills`: `nightmareutils:sentient` →
  `skill/SentientSkill`, `nightmareutils:sentientdisintegrate` →
  `skill/SentientDisintegrateSkill`, plus dev/test skills (charm_test,
  possession_test, block_place_test, mimicry_test, …).
- Entities `registry/NightmareUtilsEntities`: `otherworlder`,
  `dummy_workstation_trader`, `hihiirokane_barter_trader`,
  `shinro_nakuba_maximum`.
- Menus `registry/NightmareUtilsMenuTypes`; game rule
  `config/NightmareUtilsGameRules` (`nightmareutilstest`, no behaviour);
  configs `AutocastConfig`/`SkillRewardConfig`/`SpawnProfileConfig`/
  `MobTradingConfig` (JSON under `config/nightmareutils/`).
- Command `/nightmareutils` (perm 2): `chatlogging` (turns on cast logging —
  the debug lever when casting silently fails), `spawnotherworlder`,
  `givetestskill` (refuses to grant sentient to players), etc.

## Public API surface [READ]

- `api/NightmareUtilsApi` — the register/unregister methods above, plus
  `tickReflectiveTensuraMobilityAssist`, `tickReflectiveToggleAndHoldAssist`,
  `tryPossessWithPolicy`, `autocastConfig()`.
- Consumer interfaces: `api/MobSkillAdapter`, `SkillCapableMob`, `SkillSelector`,
  `SkillExecutionContext`, `RegistrationHandle`, `LevelTickLogic`.
- `api/EntitySkillUsePolicy` (policy door): `hasSkillUseLogic(entity)` (==
  `hasSentient`), `castCooldownReady`/`markCast` (shared 20-tick gate),
  `isPassiveOrAmbientSkill`, `isLearnedForUse` (mastery≥0),
  **`clearSentientCadence(mob)`** (wipes stale burst NBT), `MIN_CAST_INTERVAL_TICKS=20`.
- `service/SentientSkillService`: `SENTIENT_ID`, `hasSentient`,
  `hasSentientDisintegrate`, `purgeSentientFromPlayer`.
- `skill/AbstractNpcOnlySentientSkill extends io.github.manasmods.tensura.ability.skill.Skill`
  — learn/EP cost `Double.MAX_VALUE`; **auto-forgets itself on Players** → NPC-only.
- Large **unused** surface worth knowing exists: `Possession*`, `awakening`,
  `otherworlder`, `weapon`, `ownership`, `SchematicPlacementUtil`, `MimicryUtil`,
  `EntityAttackingUtil`, `EntityPathingUtil`, `ParticleShapeUtil`,
  `LevelEntitySnapshots`.

## Events [READ]

Defines **no custom events**. Only listens (LevelTick/PlayerTick/login/logout/
death/damage/FinalizeSpawn/RegisterCommands). The extension points are the API
registration methods, not events. `mixin/` hooks ManasCore/Tensura internals.

## Invariants — the checklist to make a mob autocast [READ]

For Driver A (the useful one) to fire skills, ALL must hold:
1. entity is a non-Player `Mob`;
2. it has learned `nightmareutils:sentient`;
3. **it has a `getTarget()`** — Driver A no-ops on a null target (this is why
   [ColonyThreatResponse](../src/main/java/com/example/examplemod/ColonyThreatResponse.java)
   sets `mob.setTarget(nearestRaider)` every steer);
4. within **96 blocks** of a player, with **line of sight**, and **not allied**
   to the target (`CombatTargeting.isValidCombatTarget`);
5. the mob **already knows active, non-passive, mastery≥0 Tensura skills** —
   sentient grants none; it only drives what's there;
6. the mob's entity type/class name does **not** contain `clone`/`doppel`/
   `body_double` — Driver B strips sentient from those every 20 ticks;
7. server-side.

Denylist: the reflective adapter (`integration/ReflectiveManascoreMobSkillAdapter`)
excludes passive/ambient skills + tokens like resistance/body_double/storage/
gate/possession/eye/menu/teleport, and sentient itself.

## State / capabilities [READ]

No capabilities/attachments. All per-mob state is vanilla `getPersistentData()`
NBT: `nightmareutils_last_entity_skill_cast`, `nightmareutils_sentient_next_sk`/
`_bursts`, `nightmareutils_spawn_particle_guard_until`. Skill ownership lives in
ManasCore `SkillAPI` storage (see [tensura.md](tensura.md)).

## Data-driven surface [READ]

`config/nightmareutils/autocast.json` (`AutocastConfig`): `enabled`,
`defaultCooldownTicks=20`, allow/denylists, `fastCooldownPaths`,
`carefulCastPaths`, `globalCooldownMultiplier=0.34`, `carefulMaxRangeBlocks=28`,
`pressedToggleEnsureOn`. [INFERRED] the built-in sentient path filters via its
own token lists, not this file's allowlist; only `pressedToggleEnsureOn` is
consumed by that path.

## Beyond the autocaster — the rest of the mod [READ]

nightmareutils is a broad mod (30+ services, ~23-class `api/`). We consume only
`sentient` (grep for `nightmareutils`/`shadowako` in our source finds nothing
else). But some of its **auto-wired** services fire on our mobs implicitly, and a
little public API is available-but-unused — those are worth knowing.

### Config-gated services that CAN touch OUR spawned mobs
Both auto-run via `ModLifecycle` NeoForge listeners; neither is something we call.

- `service/SpawnProfileService.onFinalizeSpawn` (`config/SpawnProfileConfig`) —
  **`enabled=true` AND `applyOnlyNaturalSpawns=false` by DEFAULT.** For any entity
  whose type id has a configured `SpawnProfile`, it applies EP overrides / skills
  on spawn (idempotent via persistent-data flag `nightmareutils.spawn_profile_applied`).
  **⇒ our garrison/raid/population mobs are NOT exempt** (they spawn via SPAWN_EGG,
  not NATURAL, but the natural-only guard is off by default) — a server-configured
  profile for e.g. `tensura:orc` would alter our spawned mobs. The default config
  ships no profiles, so it's a no-op until configured, but the seam is live. If our
  spawned mobs ever come out unexpectedly buffed, look here first.
- `service/SkillRewardService.onLivingDeath` (`config/SkillRewardConfig`) —
  **`enabled=false` by default** (`playerKillsOnly=true`). When enabled, grants the
  killer a weighted-pool skill on a kill. Could fire when a player kills our raid /
  garrison mobs. Off by default → low concern; independent of our faction Covenant
  skill rewards ([DealSpec](../src/main/java/com/example/examplemod/DealSpec.java)/`ConquestPayoff`).

### Public API beyond the autocaster (available-but-unused)
On `api/NightmareUtilsApi` (static):
- `tickReflectiveTensuraMobilityAssist(LivingEntity mob, LivingEntity target)` —
  drives a Tensura mob's **own mobility skills** (flight, instant transmission…)
  toward a target. Plausibly useful: our defender/garrison/raider steering is raw
  `WALK_TARGET`, which paths flying Tensura mobs poorly — calling this each tick
  alongside our steer could fix that. Not adopted.
- `tickReflectiveToggleAndHoldAssist` / `tickReflectiveToggleCycleAssist` — finer
  toggle-skill control (sentient's Driver B already bursts toggles; marginal).
- `registerLevelTickLogic(ResourceLocation, LevelTickLogic)` — generic per-level
  tick hook; `unregister(id)`.
- `tryPossessWithPolicy(...)` — possession; irrelevant to us.

### Rules out a false overlap
`service/MobTradingService.onFinalizeSpawn` gates **strictly** on nightmareutils'
own entities (`NightmareUtilsEntities.DUMMY_WORKSTATION_TRADER` /
`HIHIIROKANE_BARTER_TRADER`). It does **not** touch our Tensura merchant citizens
— our citizen-merchant system (see [tensura.md](tensura.md) §6) has no interaction
with it.

### Self-contained content systems — exist, NOT compat surface
The bulk of the mod is player-facing content unrelated to this compat layer, and
is deliberately not documented in depth: **weapon** (`api/weapon/*`, weapon skill
presets + mastery), **mimicry** + **morph** (player mimic/disguise), **possession**
(`api/Possession*`, `NpcPossessionControlService`), **otherworlder** (spawn +
profile), **itemstatus** (item→skill bridge), **ownership**, **spectator**. None
appears in our consumption; revisit only if we build a feature that overlaps one.

## Gotchas (0.1.2) [READ]

- **Reflection-heavy** ManasCore/Tensura adapter → a Tensura or ManasCore
  version bump can **silently** break casting (all try/catch, logged only via
  `/nightmareutils chatlogging`). This is the first place to look if defenders
  stop casting after a dep update.
- Sentient is aggressively purged from players every tick; clone/body-double
  bodies get it stripped.
- Driver A tethers to a 96-block player range; server thread only; grant at
  runtime/spawn (registry must be built).

## What we consume ↔ available-but-unused

- **Consumed (correct & idiomatic):** grant `nightmareutils:sentient` +
  supply `mob.getTarget()`.
  [`ColonyThreatResponse`](../src/main/java/com/example/examplemod/ColonyThreatResponse.java),
  `ExampleMod.grantSentient/removeSentient`,
  [`ColonyDefenderTag`](../src/main/java/com/example/examplemod/ColonyDefenderTag.java).
  Note: `COLONY_DEFENDER`/`ColonyDefenderTag` are **ours** — nightmareutils
  never reads them; they only drive our own steering + friendly-fire veto.
  Used by the raid defense swap (see [docs/threat-response.md](../docs/threat-response.md))
  and the assassin boss (see [docs/assassin-system.md](../docs/assassin-system.md)).
  Note also (see "Beyond the autocaster"): granting `sentient` also gives our
  mobs combat movement + reactive skill-copy for free — a benefit we get without
  asking, but also behavior to keep in mind when a defender acts on its own.
- **Available-but-unused worth adopting:** `EntitySkillUsePolicy.clearSentientCadence(mob)`
  inside `removeSentient` (wipe stale burst NBT when we reuse a body);
  `SentientSkillService.hasSentient` instead of a raw skill-registry lookup;
  `NightmareUtilsApi.tickReflectiveTensuraMobilityAssist(mob, target)` to improve
  steering of flying Tensura defenders/garrison.
- **Interactions to watch (not consumed, but auto-fire):** `SpawnProfileService`
  (on by default — a configured profile would alter our spawned mobs) and
  `SkillRewardService` (off by default). See "Beyond the autocaster".
- **No place our code fights intended usage.**
