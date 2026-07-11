# Tensura: Reincarnated (+ ManasCore) — dependency reference

Pinned to **tensura-neoforge 2.0.1.0** (`libs/tensura-neoforge-2.0.1.0.jar`) on
**ManasCore 4.0.0.2** (`libs/manascore-*-neoforge-4.0.0.2.jar`). Package roots
`io.github.manasmods.tensura` and `io.github.manasmods.manascore`. All claims
`[READ]` from decompiled source unless marked `[INFERRED]`.

**Bus reminder:** Tensura + ManasCore use the **Architectury** event bus and
Architectury `DeferredRegister`/`RegistrySupplier`, NOT NeoForge — see
[README.md](README.md#the-one-cross-cutting-fact-three-substrates-dont-mix-them).
`@SubscribeEvent` never fires for a Tensura event.

---

## 0. ManasCore Storage — the substrate for EP/skills/race

Tensura keeps per-entity EP, skills, and race in **ManasCore "Storage"
capabilities** on any `LivingEntity`, not in NeoForge attachments.

- Universal access door: `entity.manasCore$getStorage(StorageKey)`
  (`manascore.storage.mixin.MixinEntity implements StorageHolder`). In practice
  use the typed static accessors below.
- **Sync rides `entity.tick()`:** `MixinEntity.onTickSyncCheck` runs at the end of
  `tick()` — if a storage `isDirty()`, it pushes to trackers. Full sync on
  `PLAYER_JOIN/RESPAWN/CHANGE_DIMENSION`; `PLAYER_CLONE` copies NBT across death.
  **⇒ You MUST call `markDirty()` after any storage write, or clients desync.**
- `Storage` base: `save/load(CompoundTag)`, `markDirty/isDirty/clearDirty`.
  `StorageKey<S>` is the typed handle; storages register via
  `StorageEvents.REGISTER_ENTITY_STORAGE`.

This is also **why our shadow entities are safe never being `tick()`'d**: they're
client-only render props, so there's no storage to sync (see §7).

### What our mod actually touches (scope guard) [READ]

We consume ManasCore/Tensura storage **only at the surface** — read a typed
storage off an entity, call its getters/setters, `markDirty()`. We do **not**
touch the storage internals (no `Storage` subclass, no `StorageKey`
registration, no `CombinedStorage`/delta packets, no `StorageManager`), so those
are deliberately not documented here — they're ManasCore-internal plumbing. The
surface accessors we use:

| Accessor | Returns | Our consumer |
|---|---|---|
| `TensuraStorages.getExistenceFrom(e)` | `IExistence` (EP/magicule/aura, alignment, flags — §2) | EP reads/writes across assassin, threat-response, barrier, garrison |
| `holder.manasCore$getStorage(ExistenceStorage.getKey())` | `ExistenceStorage` (same thing, low-level) | `ExampleMod.readExistence` — one call, equivalent to the above |
| `TensuraStorages.getSpiritFrom(e)` | `ISpiritWielder` (`storage.spirit`; `getSpiritLevelId(element)`) | [`LuminousSpirits`](../src/main/java/com/example/examplemod/LuminousSpirits.java) — Luminous 3-spirits reward |
| `IExistence.getAlignment()` | `storage.Alignment` enum (DEFAULT/MAJIN/HOLY/CHAOS) | [`WorldReputationManager`](../src/main/java/com/example/examplemod/WorldReputationManager.java) faction side-classification |

**Our own persistence is NOT ManasCore storage.** The "two bodies, one identity"
mechanic and every other persisted feature use **our own NeoForge machinery** —
[`RaceIdentitySavedData`](../src/main/java/com/example/examplemod/RaceIdentitySavedData.java)
+ 9 other `SavedData` classes + NeoForge `AttachmentType`
([`Attachments`](../src/main/java/com/example/examplemod/Attachments.java)/`RaceTag`).
Don't assume ManasCore storage backs identity. **Tensura's spatial storage is
not integrated at all.**

## 1. Registries & content [READ]

Architectury `DeferredRegister.create("tensura", Registries.X)` → `RegistrySupplier<T>`
(`.get()`).

- **Attributes** `registry.attribute.TensuraAttributes` — real vanilla
  `Holder<Attribute>` (NOT storage). `MAX_MAGICULE` / `MAX_AURA` default 50, min
  10, max 1.0E9, syncable; `LIMITED_SPIRITUAL_MAX_MAGICULE/AURA` default 0 (cap
  effective max when > 0). ~120 element BOOST/RESISTANCE attributes, regen
  multipliers, presence/dodge. Stable modifier ids in `TensuraGlobalAttributeIds`.
  We multiply `MAX_MAGICULE`/`MAX_AURA` with our own stable-id modifiers.
- **Skills** — registry `SkillAPI.getSkillRegistry():Registrar<ManasSkill>`.
  Content classes (all `RegistrySupplier`, ns `tensura`): `CommonSkills`
  (`SELF_REGENERATION`, `WATER_BLADE`), `ExtraSkills`, `UniqueSkills`,
  `IntrinsicSkills` (`BODY_ARMOR`, `CHARM`), `ResistanceSkills`
  (`PHYSICAL_ATTACK_RESISTANCE`, `MAGIC_RESISTANCE`, `*_NULLIFICATION`).
- **Races** — registry `RaceAPI.getRaceRegistry():Registrar<ManasRace>`.
  `TensuraRaces` (~70): `GOBLIN/HOBGOBLIN/ENLIGHTENED_HOBGOBLIN/HOBGOBLIN_SAINT`,
  `ORC/HIGH_ORC/ORC_LORD/ORC_DISASTER`, `LIZARDMAN/DRAGONEWT/…`, dwarves, etc.
  Each `race/<family>/*Race.java extends TensuraRace extends ManasRace`.
- **Entities** `registry.entity.{Monster,Human,Misc}EntityTypes` — see §6.
- **Items** across 7 `Tensura*Items` classes (`TensuraMaterialItems` incl.
  `BRONZE_COIN`/`SILVER_COIN` = merchant currency + barrier fuel; Tool/Armor/
  Consumable/MobDrop/SmithingSchematic/SpawnEgg).
- Also `TensuraBlocks`, `TensuraMobEffects` (e.g. `RAMPAGE`), sounds, particles,
  menus, `TensuraVillagerProfessions`.

## 2. EP / magicule / aura  [READ]

Two axes: **`aura` + `magicule`**. `getEP()` is their **live sum, not a stored
field** — re-read after any mutation. CURRENT pools live on `IExistence`; MAX
lives on the `MAX_AURA`/`MAX_MAGICULE` attribute base value.

- Read the storage: `TensuraStorages.getExistenceFrom(entity):IExistence` (key
  `tensura:existence_storage`). We wrap this in `readExistence`/`readExistenceSafe`
  (try/catch — see §7 capability-not-ready).
- **`IExistence`** (`storage.ep`, impl `ExistenceStorage`): `getEP()` (derived),
  `getAura/getMagicule/setAura/setMagicule` (clamp ≤ 2.147E9, **no max-clamp**),
  `setEP(a)` → aura = magicule = a/2 (**splits!**), `getSpiritualHealth`; identity
  & flags `getName/setName`, `isNameable`, `getAlignment/setAlignment`
  (DEFAULT/MAJIN/HOLY/CHAOS — drives our faction side-classification),
  `isTrueDemonLord/isTrueHero/isBlessed/isDemonLordSeed`, `getSoulPoints/
  getHumanKill`, owner UUIDs (`setPermanentOwner/setTemporaryOwner/setSummoner` —
  **refuse the entity's own UUID**), `markDirty()`.
- **`EnergyHelper`** (`tensura.util`) is the door for all cap/pool math
  (limit-aware): reads `getMaxEP/getMaxMagicule/getMaxAura`, `getBaseMax*`; cap
  writes `setMaxMagicule/setMaxAura`, `increaseMaxEP` (+both), `multiplyMaxEP`
  (×both); current-pool `gainAura/gainMagicule(e, amt, GainType{NONE/NORMAL/
  NORMAL_EXCEED_MAX/MAX})`; steal `drainEnergy(src, @Nullable attacker, amt,
  boolean percentage, DrainType{AURA/MAX_AURA/MAGICULE/MAX_MAGICULE/EP/MAX_EP},
  GainType):boolean` — fires the cancellable `ENERGY_DRAIN_EVENT`, honors immunity
  + the ENERGY_PROTECTION enchant, and transfers to the attacker.
- **Recipe / invariants:** read caps via `EnergyHelper` (limit-aware); to change a
  cap, write the attribute base / `EnergyHelper` **then clamp the current pool
  yourself** (lowering a cap does NOT clamp current); add current via `gain*`;
  steal via `drainEnergy`; **always `markDirty()`**; server-side only. Regen runs
  server-side via `ExistenceStorage` on `LIVING_POST_TICK`. Mobs default
  `skippingEPDrop=true` (no EP drop on death).
- We consume this in the assassin EP-theft, threat-response gating, garrison
  boss-EP scaling, etc. See [docs/assassin-system.md](../docs/assassin-system.md),
  [docs/threat-response.md](../docs/threat-response.md).

## 3. Skills  [READ]

- `SkillAPI.getSkillsFrom(entity):SkillStorage` (`implements Skills`). Holds
  `ManasSkillInstance`s keyed by skill id, **deduped** (`learnSkill` returns false
  on a dup).
- Behavior lives on the **singleton** `ManasSkill`/`TensuraSkill`
  (onPressed/Held/Toggle/Tick, damage/target/death hooks, `getModes`,
  `getMaxMastery`, held-attr modifiers). Per-entity **mutable** state lives on the
  `ManasSkillInstance`: `getMastery/setMastery`, cooldown, `isToggled/setToggled`,
  modes. **`instance.copy()` preserves mastery/cooldown** — used by our skill-copy.
- `Skills`: `learnSkill(instance, MutableComponent)` (+ RL/ManasSkill overloads),
  `getSkill(...):Optional`, `getLearnedSkills()`, `forgetSkill`, `updateSkill`.
- `TensuraSkill extends ManasSkill`: `getAuraCost/getMagiculeCost(e, inst, mode)`,
  `isOutOfEnergy`, `addLearnPoint`, slotting.
- Skill **type** enum on `ability.skill.Skill`: `RESISTANCE/INTRINSIC/COMMON/
  EXTRA/UNIQUE/ULTIMATE` (our skill-copy tiers by this).
- **Passive vs active invariant:** RESISTANCE + passive skills **auto-work the
  moment they're learned** (they hook damage). **Active/toggle skills on a MOB do
  nothing on their own — they need a driver.** For mob autocasting we grant the
  Nightmare's Utils `sentient` skill; see [nightmares-utils.md](nightmares-utils.md).

## 4. Races & evolution  [READ]

- `RaceAPI.getRaceFrom(entity):Races`. `Races.getRace():Optional<ManasRaceInstance>`,
  `setRace(inst, boolean evolution, boolean teleportToSpawn, @Nullable component)`,
  `evolveRace(...)`, `markDirty`.
- `ManasRaceInstance`: `getRace/getRaceId/getDifficulty`, `copy`, intrinsic-skill
  management, **evolution graph** (`getNextEvolutions/getEvolutionProgress/
  onRaceEvolution`), attribute modifiers, `getRespawnDimension`.
- `TensuraRace`: `getBaseAuraRange/getBaseMagiculeRange` (Pair), `getAlignment`,
  `getEvolutionRequirements`. Helpers `RaceHelper` (`evolveRace`, `awakening`,
  `applyMajinChance`, `applyBaseAttribute`), `RaceUtils` (`isSpiritual/isUndead/
  canAwaken`).

## 5. Attributes  [READ]

Real vanilla `Holder<Attribute>` (§1). 1.21 modifier idiom: `AttributeModifier`
keyed by `ResourceLocation` + `Operation` — matches our stable-id modifier
pattern for reversible EP multiply/add over `MAX_MAGICULE`/`MAX_AURA`.

## 6. Entities: subordinate / merchant hierarchy + variants  [READ]

Hierarchy: `TamableAnimal+NeutralMob → TensuraTamableEntity →
TensuraHumanoidEntity → PlayerLikeEntity (SmartBrainLib biped) →
TensuraMerchantEntity → {GoblinEntity, DwarfEntity, LizardmanEntity}`.
**`OrcEntity extends PlayerLikeEntity directly — it is NOT a merchant**, which is
why the trade tab is Goblin/Lizardman/Dwarf only.

- **`ISubordinate`** (`entity.template.subclass.ISubordinate extends
  OwnableEntity`, default-method interface): owner/tame
  (`isTame/setTame/getOwnerUUID/isOwnedBy/tame(Player)/resetOwner`), commands
  (`getBehaviour/setBehaviour(int)` 0 passive→1 wander→2 aggressive→3 protect,
  `getOwnerCommand`, `isWandering`, `getWanderPos`), **`cycleCommands(Mob, Player)`**
  (server-only 3-state ring follow→wander→stay). Our
  [`ISubordinateCommandMixin`](../src/main/java/com/example/examplemod/mixin/ISubordinateCommandMixin.java)
  `@Inject`s at HEAD to add a 4th PATROL command →
  [`SubordinatePatrol.handlePatrolCycle`](../src/main/java/com/example/examplemod/SubordinatePatrol.java).
  Targeting arbitration `shouldTarget/shouldStopTarget` — **this is the hook for
  the subordinate-attacks-citizen bug** (see §8 and
  [docs/subordinate-citizen-targeting.md](../docs/subordinate-citizen-targeting.md)).
  Util `SubordinateHelper` (`getSubordinateOwner`, `isAlly`, order setters,
  `removeTarget`).
- **`TensuraMerchantEntity`** (`Merchant`): `getMerchantLevel/setMerchantLevel`,
  `getProfession/setProfession`, `getOffers/setOffers`, `setTradingPlayer`,
  `getVillagerXp`; career (**protected**) `shouldIncreaseLevel()`,
  `increaseMerchantCareer()`, `updateTrades()`; restock `restock()`,
  `restockIfPossible()`; `getPossibleTrades():Int2ObjectMap<ItemListing[]>` (base
  null; Goblin overrides with `OneForOneTrade` + BRONZE_COIN; **Dwarf =
  `DwarfProfession.getProfessionTrades(profession)` — profession-driven by
  default**). ⚠ **`customServerAiStep` drives career/restock/gossip — it never
  runs on our transient (unticked) merchant, so we force career + restock
  manually** (reflection for the protected methods), on trade-open + a dawn pass.
- **Variants** (`entity.variant.*`) are **enums + `EntityDataAccessor`
  (`SynchedEntityData`), NBT'd as named ints/booleans — NOT packed bytes.** Our
  `*VariantData` records are our own encoding, mapped field-by-field onto the
  entity setters. `GoblinVariant` (Gender/Skin/Face/Hair/Head/Top/Bottom nested
  enums + ARGB int colors + Bandages bool + `INameEvolution`), `OrcVariant`
  (Ham/Honey/…/RoyalLord + Neck/Top), `LizardmanVariant`, `DwarfVariant`
  (+ **scale = vanilla `Attributes.SCALE`**, randomized in `finalizeSpawn` to
  [0.7,1.0] biased low), `MagicCircleVariant` (21 values incl. `SPACE`).

## 7. Rendering  [READ]

Two paths:
- **Vanilla biped (Goblin, Dwarf):** `PlayerLikeRenderer` over `PlayerModel` +
  N overlay `RenderLayer`s bound to `ModelLayerLocation`s (`GoblinLayer.*`,
  `DwarfLayer.*`) + `ProfessionClothesLayer`. We render our RaceTag directly over
  MC's `AbstractEntityCitizen` by reimplementing these layers
  (`GoblinCitizenRenderer`/`GoblinOverlayLayer`, `DwarfCitizenRenderer`, etc.).
  Only needs variant getters + walkAnimation from the subject.
- **GeckoLib shadow-entity (Orc, Lizardman):** `GeoEntityRenderer` reads
  animation controllers + variant off a **real** Orc/Lizardman entity, so we feed
  it a per-citizen **shadow** entity. **HARD RULE: never `tick()` the shadow** —
  ticking runs its AI/brain/EP side-effects and desyncs storage. Mirror
  walkAnimation/pose/sprint/variant/equipment each frame instead
  (`OrcCitizenRenderHandler`, `LizardmanCitizenRenderHandler`).

Model-bake gotchas: match the **source model class** per layer — the dwarf
`FACIAL_HAIR_LAYER` is the only dwarf overlay built from `HumanoidModel.createMesh`
(not `PlayerModel`), so a generic PlayerModel layer silently drops the beard
(hence our dedicated `DwarfFacialHairLayer`); goblin Face/Head are `HumanoidModel`
too. See [docs/lizardman-dwarf-and-skills.md](../docs/lizardman-dwarf-and-skills.md).

## 8. Events (Architectury) + what we consume ↔ available-but-unused  [READ]

Register with `SomeEvent.EVENT.register(...)`; cancellable events return
`EventResult` and mutate payload via `Changeable<T>`; server-side, in-tick.

- **`TensuraEntityEvents`** — **`NAMING_EVENT.name(LivingEntity, @Nullable Player,
  Changeable<Double>, Changeable<Double>, Changeable<NamingType>,
  Changeable<String>)`** is our primary intake (`ExampleMod.onRaceNamed`). Also
  `ENERGY_DRAIN_EVENT`, `AWAKENING_EVENT`, `POST_TAME_EVENT`, `ADD_FRESH`
  (finalizeSpawn), `EQUIPMENT_CHANGE_EVENT`, ~20 more.
- **`TensuraSkillEvents`**: `SKILL_LEARNING`, `SKILL_PLUNDER`, …
- **ManasCore `EntityEvents`**: `LIVING_PRE/POST_TICK`, **`LIVING_CHANGE_TARGET`
  (+`_EARLY`/`_LATE`)**, damage hooks, 5 death priorities
  `DEATH_EVENT_FIRST/HIGH/NORMAL/LOW/LAST`.
- **`SkillEvents`** (mastery/learn/activate), **`RaceEvents`** (`SET_RACE`,
  RACE_PRE/POST_TICK), **`StorageEvents`** (register storages).

Consumption map:
- **Consumed correctly:** `readExistence`/`getExistenceFrom` + `EnergyHelper`;
  stable-id modifier multiply/add over `MAX_MAGICULE`/`MAX_AURA`;
  `SkillAPI...learnSkill` (resistances passive) + skill-copy via `copy()` tiered
  by `Skill` type; the `sentient` autocaster for actives
  ([nightmares-utils.md](nightmares-utils.md)); `SubordinateHelper`;
  `ISubordinate.cycleCommands` mixin; `MagicCircle` `SPACE` visuals; variant
  setters; GeckoLib via shadow; the `can_be_named` datapack merge for dwarf.
- **Fights the API (justified):** the assassin EP-theft hand-rolls **reversible**
  modifiers + a manual current-pool clamp instead of `EnergyHelper.drainEnergy` —
  it needs reversibility for offline reclaim, which `drainEnergy` doesn't give,
  but this bypasses `ENERGY_DRAIN_EVENT` + the protection enchant.
- **Fights the API (fragile):** the transient merchant is never ticked, so we
  drive career/restock/gossip manually (§6). Watch `customServerAiStep` /
  offer-persistence across Tensura updates.
- **Reimplemented (no upstream counterpart):** `RaceSkillProfiles` — Tensura has
  no citizen-skill concept, so the MC-skill bias layer is ours.
- **Already adopted:** `EntityEvents.LIVING_CHANGE_TARGET` — we register
  `ExampleMod.onSubordinateChangeTarget` (`ExampleMod.java:362`) to veto a
  subordinate's target change when the target is a citizen / friendly race (the
  subordinate-attacks-citizen fix). Note this hook can only VETO/REDIRECT an
  existing target transition — it cannot *add* a target, so the separate
  "make hostile mobs target citizens" case uses a datapack tag instead (see
  [docs/hostile-mob-targets-citizens.md](../docs/hostile-mob-targets-citizens.md)).
- **Available-but-unused worth adopting:** `RaceEvents.SET_RACE` (would replace
  our majin-flip *polling* with an event — `DiplomacyManager.tickSideWatch`);
  `ManasRaceInstance` evolution graph (a real citizen-evolution feature);
  `drainEnergy` percentage + `GainType`; `SkillEvents.SKILL_MASTERY`. Tracked in
  decisions.md → "Available-but-unused upstream surface (adoption index)".

## 9. Data-driven surface & config  [READ]

- Entity tag **`tensura:can_be_named`** = `TensuraEntityTags.NAMEABLE` — contains
  goblin/orc/lizardman; **NOT dwarf, orc_lord, orc_disaster** → we datapack-merge
  `tensura:dwarf`, and orc_lord/orc_disaster are blocked from the pipeline
  (`Races.isBlocked`).
- Race tags `TensuraRaceTags` (`can_glide`, `human_like`, `spawn_as_spiritual`,
  `limited_ep_in_central`, …). Skill tags `TensuraSkillTags` (`copiable_magic`,
  `resistance_skills`, `magic`, `tome_copy_excluded` — useful filters for copyable
  skills).
- Merchant trades are **code, not datapack**; variant appearance is in-code enums,
  not datapack.
- Config via `ConfigRegistry.getConfig(EnergyConfig{minAura/minMagicule=10,
  maxAura/maxMagicule=1e9}/AbilityConfig/RaceConfig/AreaMagiculeConfig)`.

## 10. Gotchas (2.0.1.0)  [READ]

1. **`markDirty()` after every storage write** (sync rides `tick()`, §0).
2. Capability-not-ready → NPE; guard reads (`readExistenceSafe` try/catch). [INFERRED cause]
3. Architectury bus, not `@SubscribeEvent` (§0, §8).
4. `setEP` **splits** the value 50/50 across aura/magicule — use `gain*`/cap
   setters when you don't want that.
5. Lowering a MAX cap does **not** clamp the current pool.
6. `IExistence` owner setters refuse the entity's own UUID.
7. `getEP()` is recomputed — re-read after any mutation.
8. `finalizeSpawn` randomizes appearance (+ dwarf `SCALE`); stamp variant fields
   **after** `EntityType.create` to override drift (our `applyVariantToMob`; the
   dwarf apply re-sets `SCALE`).
9. Match `HumanoidModel` vs `PlayerModel` per render layer (§7).
10. Shadow entities are safe ONLY as client render props — never add/`tick()` them.
