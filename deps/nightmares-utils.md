# Nightmare's Tensura Utils — dependency reference

Pinned to **nightmareutils 0.1.2** (`libs/nightmareutils-0.1.2.jar`). Package
root `dev.shadowako.nightmareutils` (author `shadowako`). Its own `mods.toml`
one-liner is the honest summary: **"Shared Tensura entity and skill utility
framework for addon mods."** It is a *framework/utility library* for the
Tensura addon ecosystem, not primarily a player-content mod — 282 classes, ~35
server-tick services, wired in [`ModLifecycle.init`](../libs/nightmareutils-0.1.2.jar).
All claims `[READ]` from decompiled source (Vineflower 1.10.1) unless marked.

**We use exactly one thing: the `nightmareutils:sentient` skill**, granted to a
mob so it autocasts the Tensura skills it already knows. Everything else below
is *available-but-unused* — but two categories of it fire on OUR mobs anyway
(§3) or shift assumptions OUR code relies on (§4), so they're documented, not
just listed.

## Two reflection substrates, and a version-drift caveat [READ]

The mod hard-links **ManasCore's `SkillAPI`** (compile dep) but reaches
**Tensura** almost entirely by reflection, so it compiles against Tensura
without linking internals. Note it reflects into **two different Tensura
forks**:
- `io.github.manasmods.tensura.*` — the manasmods Tensura we ship
  (`TensuraStorages.getExistenceFrom`, `EnergyHelper`, `RaceAPI`,
  `SubordinateHelper`, `MonsterEntityTypes`, `MagicCircle`, …). This is the
  path that matters for us.
- `io.github.hvnbael.legacytensura.capability.ep.TensuraEPCapability` — a
  *different* Tensura fork, used only as a fallback in
  `SpawnProfileService.trySetEp`. Absent in our runtime → that reflective call
  silently no-ops (fine, since we ship no spawn profiles).

⇒ A Tensura/ManasCore version bump can **silently** break casting or any
reflective service (everything is try/catch, logged only via
`/nightmareutils chatlogging`). First place to look if defenders stop casting
after a dep update.

## The `nightmareutilstest` gamerule gates most of the mod [READ] — CORRECTION

`config/NightmareUtilsGameRules.NIGHTMARE_UTILS_TEST` (gamerule id
`nightmareutilstest`, default **false**). **Earlier notes here said it had "no
behaviour" — that was wrong.** It is the master switch for the mod's test/dev
content: the mimicry skill, all possession/charm/spectator/block/particle test
skills, the otherworlder *test* entities and the ShinroNakubaMaximum superboss,
and several `Npc*` services strip their skills from players (and discard test
entities) every tick while it's off. The **sentient autocaster family (§2) and
the combat-sanity refill (§3) are NOT gated by it** — those run whenever the
mod is present.

---

## §1 — What THIS project consumes (correct & idiomatic) [READ]

Grant `nightmareutils:sentient` to a non-player `Mob` and supply it a
`mob.getTarget()`. That's the whole contract. We do this in:
[`ColonyThreatResponse`](../src/main/java/com/example/examplemod/ColonyThreatResponse.java),
`ExampleMod.grantSentient`/`removeSentient`
(`SENTIENT_SKILL_ID = "nightmareutils:sentient"`), keyed off our own
[`ColonyDefenderTag`](../src/main/java/com/example/examplemod/ColonyDefenderTag.java)
(the `COLONY_DEFENDER` tag is OURS — nightmareutils never reads it; it only
drives our steering + friendly-fire veto). Used by the raid defense swap
([docs/threat-response.md](../docs/threat-response.md)) and the assassin boss
([docs/assassin-system.md](../docs/assassin-system.md)).

You do **not** call `registerAutocaster` yourself — `ModLifecycle.init`
(L68-76) already registers the reflective autocaster keyed on
`SentientSkillService.hasSentient`. Granting the skill enrols the mob; nothing
else is needed.

## §2 — The sentient / NPC-combat machinery [READ]

Granting `sentient` does **more than enable casting**. It opts the mob into a
whole family of server-tick driver services (all gated on `hasSentient` or a
raw `skills.contains(sentient)` check), auto-wired to NeoForge events in
`ModLifecycle.init` (L85-98). Our defenders + assassin boss get **all of this
for free** the moment they hold `sentient`:

1. **`MobSkillAutocastService.onLevelTick`** → **`ReflectiveManascoreMobSkillAdapter`**
   — the cast engine. Every 20 ticks, for every `Mob` within **96 blocks** of a
   player that has a non-null `getTarget()`, reflectively reads its learned
   ManasCore skills, filters to active/castable ones, picks a mode, and fires
   `onPressed` (+ fast-forwarded `onHeld`/`onRelease`). Special-cases Tensura
   **Pride** (adds its sub-skills) and **Creator** (adds sub-skills, 400-tick
   cooldown). `MAX_CASTS_PER_AUTOROLL = 0` + the `chainAdditionalCasts` bound of
   `-1` mean it fires **one** skill per roll (the chaining path is compiled-off).
2. **`SentientSkillService.onLevelTick`** — every 20 ticks toggles ON the mob's
   learned non-passive toggleable skills, in bursts (3 waves × 5 skill-ticks,
   then 60 skill-ticks rest); NBT cadence on `nightmareutils_sentient_bursts`/
   `_next_sk`. Also **strips sentient from body-double-like mobs** every tick
   (type-id/class contains `clone`/`doppel`/`body_double`/`bodydouble`).
3. **`NpcCombatMovementService.onLevelTick`** — every 2 ticks: pathing
   (`EntityPathingUtil`), attack assist (`EntityAttackingUtil`), Tensura
   mobility-skill assist (`TensuraMobilityAssist`), and a light lateral strafe.
   Why our steered defenders juke rather than stand still.
4. **`NpcSpatialAmbushService.onLevelTick`** — a mob with
   `tensura:spatial_manipulation` teleports around/behind its target (~9%/tick,
   line-of-sight checked). [READ]
5. **`NpcSpecialCombatSkillService.onLevelTick`** — a mob with
   `sentientdisintegrate` drops a Tensura `DisintegrationEntity` on its target
   (size 3, height 50, 1000 dmg, 400-tick gate); a mob with
   `tensura:summon_greater_elemental` summons Ifrit/Undine/Sylphide/War
   Gnome/Akash (6000-tick gate) and marks them as its summons. We grant neither
   enabler, so inert for us unless a copied skill provides one.
6. **`NpcPrideReactiveCopyService.onIncomingDamage`** — a sentient mob holding
   Tensura **Pride**, when hit by an ability, reflectively **learns the
   attacker's skill** (`pride.getSkill().learnSubSkill(...)`) and clears Pride's
   cooldowns. Fires on our defenders/assassin if they hold Pride.
7. **`NpcSubordinateAggroService.onLevelTick`** — every 10 ticks, a mob's
   Tensura subordinates within 64 blocks copy that mob's target (via
   `SubordinateHelper.getSubordinateOwner`). [READ — not previously documented]
8. **`NpcCreatorRandomSkillService.onLevelTick`** — a sentient mob with Tensura
   **Creator** is periodically granted a random temp unique skill from Creator's
   config pool (every ≥400 ticks; marker `nightmareutils_creator_generated`).
   [INFERRED tie to our mobs: only if a mob copies Creator — verify if a
   defender ever gains unexpected skills.]

**The reflective adapter's filters** (`ReflectiveManascoreMobSkillAdapter`):
- Skips a skill whose id/class contains any `DISALLOWED_SKILL_TOKENS`:
  `resistance, body_double, nullification, craftsman, reincarnation, analyze,
  doppelganger, escape, storage, portal, gate, absorb_and_dissolve, possession,
  incarnation, farsight, eye, sense, cultivator, hidden_ruler` (plus
  `creation` unless the skill IS Creator, and `tensura:summon_greater_elemental`).
- Skips modes whose id/name contains `DISALLOWED_MODE_TOKENS`: `menu, copy,
  refine, craft, enchant, synthesis, separation, selection, clone, imaginary,
  storage, gui, screen, teleport, warp, appraisal, open, gate`.
- Also skips passive/ambient (`EntitySkillUsePolicy`), unlearned-for-use
  (mastery < 0), and `sentient`/`sentientdisintegrate` themselves.

**Invariants — to make a mob autocast, ALL must hold** [READ]:
1. non-Player `Mob`; 2. has learned `nightmareutils:sentient`; 3. **has a
`getTarget()`** (adapter no-ops on null — this is why ColonyThreatResponse sets
`mob.setTarget(nearestRaider)` every steer); 4. within **96 blocks** of a
player; 5. already knows active, non-passive, mastery≥0, non-denied Tensura
skills (sentient grants none — it only drives what's there); 6. type/class does
**not** contain `clone`/`doppel`/`body_double` (else sentient is stripped every
20 ticks); 7. server-side. Shared 20-tick cast gate + a per-mob
`nightmareutils_spawn_particle_guard_until` suppress casting briefly after spawn.

## §3 — Un-gated behaviors that touch OUR spawned mobs [READ]

These run whenever the mod is loaded, are **not** sentient-gated, and CAN alter
mobs we spawn (raiders, garrisons, assassin boss, population). Know they exist:

- **`NpcSkillCombatSanityService.onLevelTick` — GLOBAL, every 2 ticks, over
  ALL mobs. ⚠ The big one.** It (a) strips `tensura:magicule_poison` and
  `tensura:fatal_poison` from every mob, and (b) **refills aura + magicule to
  their max** for any mob that has a Tensura `IExistence`. Net effect on any
  server with this mod: **every Tensura mob has effectively infinite
  aura/magicule and is immune to those two poisons.** Our raiders / garrison /
  assassin bodies inherit this passively — relevant to any balance reasoning
  about draining a boss's energy or poisoning enemies. Not previously
  documented here.
- **`SpawnProfileService.onFinalizeSpawn`** (`config/SpawnProfileConfig`;
  `enabled=true`, `applyOnlyNaturalSpawns=false` by DEFAULT). For any entity
  whose type-id has a configured `SpawnProfile`, applies EP override + weighted
  skills on spawn (idempotent flag `nightmareutils.spawn_profile_applied`). The
  natural-only guard is OFF, so our SPAWN_EGG mobs are NOT exempt — a
  server-configured profile for e.g. `tensura:orc` would buff them. Ships **no
  profiles** → no-op until configured, but the seam is live. If our spawned
  mobs come out unexpectedly buffed, look here first.
- **`SkillRewardService.onLivingDeath`** (`config/SkillRewardConfig`;
  `enabled=false`, `grantChance=0.1`, `playerKillsOnly=true` by default). When
  enabled, grants a killer a weighted-pool skill from the victim. Could fire
  when a player kills our raid/garrison mobs. Off by default → low concern;
  independent of our faction Covenant skill rewards
  ([DealSpec](../src/main/java/com/example/examplemod/DealSpec.java)/`ConquestPayoff`).
- **`MobTradingService.onFinalizeSpawn`** gates strictly on nightmareutils'
  OWN trader entities — does **not** touch our Tensura merchant citizens.

## §4 — Cross-cutting mixins that shift OUR assumptions [READ]

nightmareutils ships **48 mixins** (`nightmareutils.mixins.json`) into
ManasCore/Tensura/TrNightmare internals. Two families change invariants our
code leans on:

- **Multi-owner ownership tree.** `ExistenceStorageOwnershipMixin` +
  `service/ownership/*` replace Tensura's single `permanentOwner`/
  `temporaryOwner` with `LinkedHashSet<UUID>` and inject a **transitive owner
  graph** (`OwnershipTreeService.collectEffectiveOwners`, depth ≤12) into
  Tensura/TR-Nightmare ally/subordinate checks
  (`SubordinateHelperOwnershipMixin`, `FoodChainHelperOwnershipMixin`). ⇒ On a
  server with this mod, "is X an ally/subordinate of Y" can be true via a
  *transitive* owner chain, not just a direct owner. Relevant to our
  subordinate-vs-citizen targeting investigation
  ([docs/subordinate-citizen-targeting.md](../docs/subordinate-citizen-targeting.md)).
- **Custom awakening → True-Hero/True-Demon-Lord flags.**
  `ExistenceStorageAwakeningMixin` forces `IExistence.isTrueDemonLord()`/
  `isTrueHero()`/`isDemonLordSeed()`/`isHeroEgg()` to return `true` when the
  player has a matching nightmareutils *custom* awakening (`api/awakening/*`,
  `CustomAwakeningRegistry`). ⇒ Our dwarf-envoy "true demon lord / true hero"
  conditions (which read `IExistence`) can be satisfied by a nightmareutils
  awakening, not only native Tensura awakening. Worth knowing; the demo type
  `nightmareutils:true_testing_lord` is registered by default but only
  triggers via its own progress counters.

Other mixin families are self-contained to the mod's own content (weapon deep
slots, mimicry rendering, item-status GUI, name-tree) — see §5.

## §5 — The rest of the mod (exists; not a compat surface unless we build into it) [READ]

Broad content, overwhelmingly `nightmareutilstest`-gated and reflective. None
is consumed by our integration. Map so future work knows what's there:

- **Sentient skills** — `nightmareutils:sentient` (drives §2) +
  `sentientdisintegrate` (enables the disintegrate special). Both are
  `AbstractNpcOnlySentientSkill`: `learningCost`/`getObtainingEpCost` =
  `Double.MAX_VALUE`, and **auto-forget on any Player** (structurally NPC-only;
  players are also purged every tick).
- **Mimicry / Morph** (`mimicry/`, `morph/`, skill `mimicry_test`) — a player
  disguise system with 4 modes (Entity / Race / Player / Analysis): analyze a
  target to store a catalog entry (in the skill instance's NBT), then mimic its
  **skin, model/body shape, race, attributes, granted skills, even a boss
  bar**. `morph/` = the costume/render layer; `mimicry/` = costume + emulated
  stats/skills + the learned catalog. `NpcMimicryControlService` is just a
  gamerule janitor (strips test skills from players when off), **not** an NPC
  disguise driver.
- **Weapon / Item-status** (`api/weapon/*`, `service/weapon/*`, `api/itemstatus/*`)
  — turn any item into a "Nightmare Weapon" that stores copies of Tensura
  skills; holding/wearing it **projects temp copies of those skills onto the
  player** (`ItemSkillBridgeService`, `removeTime=-2`, tagged, evicted on
  drop), executed against the item's own instance so cooldown/mastery/acquired
  sub-skills persist on the item. Adds a per-item ability bar (slots 3–5,
  keybinds `Weapon Deep Slot F/G/H`), 9 presets × 3 slots shadow-keyed to
  Tensura's active preset, an armor mode, EP on the item, and mastery gain (on
  kill for held/bar weapons, on being hit for worn nightmare armor,
  `WeaponMasteryService`). Item-status = the discovery/stat UI layer.
- **Otherworlders** (`entity/NightmareOtherworlderEntity`, `api/otherworlder/*`,
  `OtherworlderProfileService`) — a custom `PlayerLikeEntity` driven by
  **datapack JSON profiles** (`<world>/datapacks/*/otherworlders/*.json`;
  reloaded on every FinalizeSpawn). Fields: EP range, maxHealth, armor, skills
  (explicit ids + fuzzy name tokens), texture, weapon, behavior
  (neutral/hostile/demonic/holy), boss bar, nameable/charmable, and a
  `spawnLike`/`replaceEntityId` bridge so they piggyback on vanilla/Tensura
  spawns. Ships one example profile (`weak_shinro_nakuba`).
  **`ShinroNakubaMaximumEntity`** — a hardcoded test superboss (10k HP, EP
  1e7–1.5e7, TR-Nightmare scythe, Pride+Gluttony+sentient+sentientdisintegrate);
  self-discards unless the test gamerule is on; replaces
  lesser/greater daemons at 1/200.
- **Possession** (`api/PossessionUtil`, `NpcPossessionControlService`, skills
  `possession_test`/`possession_copy_test`/`charm_test`) — a reflective wrapper
  over Tensura's `PossessionSkill`: drop into a target body, optionally copy its
  skills by policy. NPCs holding the skill try to possess targets (~8%/tick).
- **Spectator** (`SpectatorService`, `spectator_test`) — a leashed
  spectator-camera skill (free-fly within a 16-block anchor sphere, or
  `setCamera` onto a looked-at entity).
- **Ownership + name tree** (`service/ownership/*`) — the multi-owner tree of
  §4 plus a naming-lineage genealogy (`NameTreeSavedData`
  `nightmareutils_name_trees`, per-player "life" UUIDs, generations, re-connect
  on rename). `PlayerWorldProfileSavedData` snapshots offline player profiles.
- **Awakening** (`api/awakening/*`) — the custom-awakening registry of §4.
- **Traders** (`entity/DummyWorkstationTraderEntity`,
  `HihiirokaneBarterTraderEntity`, `MobTradingService`,
  `config/mob_trading.json`) — a workstation-profession vanilla Merchant
  (scribe/forgemaster by nearby block, levels 1→5) and an item-drop barter mob
  (throw `hihiirokane_block` → weighted loot). Zombie-based, no natural spawn.

## §6 — Registries / commands / config / data [READ]

- **Skills** `registry/NightmareUtilsSkills`: `sentient`, `sentientdisintegrate`
  + test skills `charm_test`, `possession_test`, `possession_copy_test`,
  `block_place_test`, `block_break_test`, `particle_shape_test`, `mimicry_test`,
  `programmable_menu_test`, `spectator_test`.
- **Entities** `registry/NightmareUtilsEntities`: `dummy_workstation_trader`,
  `hihiirokane_barter_trader`, `otherworlder`, `shinro_nakuba_maximum` (all
  MONSTER, 0.6×1.95).
- **Command** `/nightmareutils` (perm 2): `chatlogging` (toggles cast
  logging — the debug lever when casting silently fails), `spawnotherworlder`,
  `givetestskill`/`give_test_sword` (refuses to grant sentient to players),
  `weapon`/`weaponarmor`/`weaponskillpreset`/`itemedit`/`itemrestore`,
  `mimicry`/`nametree`/`awakening`/`grant`/`revoke`/`snapshot`/`progress`/
  `status`/`mastery`/`cooldown`/`removetime`/`toggle`/`setowner` etc.
- **Configs** under `config/nightmareutils/`: `AutocastConfig`
  (`enabled=true`, `defaultCooldownTicks=20`, `globalCooldownMultiplier=0.34`,
  `carefulMaxRangeBlocks=28`, allow/deny + fast/careful path lists — the
  built-in sentient path filters via its own token lists, only
  `pressedToggleEnsureOn` is consumed by it), `SkillRewardConfig`,
  `SpawnProfileConfig`, `MobTradingConfig` (§3/§5 defaults).
- **Data**: ships `data/tensura/tags/manascore_skill/skills/no_plundering.json`
  listing all 11 nightmareutils skills → Tensura's Gluttony/plunder can't steal
  them.
- **Per-mob state** is vanilla `getPersistentData()` NBT
  (`nightmareutils_last_entity_skill_cast`, `nightmareutils_sentient_next_sk`/
  `_bursts`, `nightmareutils_spawn_particle_guard_until`,
  `nightmareutils_special_combat`, …); skill ownership lives in ManasCore
  `SkillAPI` storage. SavedData: `nightmareutils_player_profiles`,
  `nightmareutils_name_trees`, `nightmareutils_item_cache`.
- **No custom events** — the mod only listens (LevelTick/PlayerTick/login/
  logout/death/damage/FinalizeSpawn/RegisterCommands). Extension points are the
  `NightmareUtilsApi` registration methods, not events.

## §7 — Public API surface (available-but-unused) [READ]

`api/NightmareUtilsApi` (static): 7 `registerAutocaster` overloads +
`registerReflectiveManascoreAutocaster` (what the mod itself calls for
sentient), `registerLevelTickLogic`/`unregister`,
`tickReflectiveTensuraMobilityAssist(mob, target)` (drives a Tensura mob's own
flight/instant-transmission toward a target — plausibly useful to fix our raw
`WALK_TARGET` steering of flying defenders/garrison; not adopted),
`tickReflectiveToggleAndHoldAssist`/`tickReflectiveToggleCycleAssist`,
`tryPossessWithPolicy`, `autocastConfig()`. Policy door
`api/EntitySkillUsePolicy`: `hasSkillUseLogic` (== `hasSentient`),
`castCooldownReady`/`markCast` (shared 20-tick gate), `isPassiveOrAmbientSkill`,
`isLearnedForUse`, **`clearSentientCadence(mob)`** (wipe stale burst NBT),
`MIN_CAST_INTERVAL_TICKS=20`.

## §8 — Cross-mod compat shims [READ]

Reflective `isLoaded()`-guarded compat with the wider Tensura-addon family —
confirms nightmareutils is the shared base layer for it:
- `compat/TrOriginsCompat` — TR Origins (`trorigins`: true_dragon,
  mystic_angel_of_origin, primordial_daemon_lord races).
- `compat/MysticismCompat` — Mysticism (`mysticism`: dragonoid race).
- `compat/TrNightmareCompat` — TR Nightmare (`trnightmare`: body_double,
  ultimate deep storage).

## What we consume ↔ available-but-unused ↔ interactions to watch

- **Consumed (correct & idiomatic):** grant `nightmareutils:sentient` + supply
  `mob.getTarget()`. §1.
- **Fires on our mobs implicitly:** the §2 driver family (combat movement +
  reactive copy + subordinate aggro) the moment a mob holds sentient; the §3
  **global aura/magicule refill + poison strip on ALL Tensura mobs** (not
  sentient-gated) — behavior to keep in mind for balance.
- **Shifts our assumptions:** §4 multi-owner ally tree (targeting/ally checks)
  and the awakening→True-Hero/Demon-Lord mixin (our dwarf-envoy conditions).
- **Available-but-unused worth adopting:**
  `EntitySkillUsePolicy.clearSentientCadence(mob)` inside `removeSentient`
  (wipe stale burst NBT when reusing a body);
  `SentientSkillService.hasSentient` instead of a raw registry lookup;
  `NightmareUtilsApi.tickReflectiveTensuraMobilityAssist(mob, target)` for
  flying-defender steering.
- **No place our code fights intended usage.**

## Gotchas (0.1.2) [READ]

- Reflection-heavy ManasCore/Tensura adapter → a dep bump can **silently**
  break casting or any reflective service (try/catch, `/nightmareutils
  chatlogging` is the only surface). First suspect after a dep update.
- Sentient is aggressively purged from players every tick; clone/body-double
  bodies get it stripped every 20 ticks.
- Driver A tethers to a 96-block player range; server thread only; grant at
  runtime/spawn (skill registry must be built).
- The `nightmareutilstest` gamerule (default false) silently disables the whole
  mimicry/possession/spectator/otherworlder-test surface — don't expect those
  features to work in a normal world without enabling it.
- The global combat-sanity refill (§3) means "drain the enemy's magicule/aura"
  strategies don't work against any Tensura mob while this mod is loaded.
