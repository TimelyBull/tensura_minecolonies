# Tensura MineColonies Integration

A NeoForge 1.21.1 mod that bridges **Tensura: Reincarnated** and **MineColonies**. The first feature is allowing a named Tensura goblin to join a MineColonies colony as a citizen.

The developer is a beginner — explain concepts clearly, avoid jargon without explanation, and prefer simple and explicit code over clever abstractions.

## Project planning

Planning documents live in `docs/` and should be kept current as work progresses:

- [docs/roadmap.md](docs/roadmap.md) — milestone checklist and feature tiers; update task status as work completes
- [docs/decisions.md](docs/decisions.md) — record of key architectural and design decisions with reasoning; add new entries when a non-obvious choice is made
- [docs/dependencies.md](docs/dependencies.md) — exact jar versions in `libs/` and confirmed registry IDs; update when new deps are added

## Project layout

```
MDK-1.21.1-ModDevGradle-main/
  build.gradle              # Gradle build config (ModDevGradle 2.0.141)
  gradle.properties         # Mod metadata and version pins
  libs/                     # Local mod JARs (compile + runtime deps; gitignored)
  src/
    main/
      java/com/example/examplemod/
        ExampleMod.java                # Main mod entrypoint (@Mod class).
                                       # Hosts NAMING_EVENT listener (onRaceNamed),
                                       # send/summon helpers, cost gate, swap
                                       # circle visuals, death hooks, the Stage B
                                       # CitizenAddedModEvent intercept + race
                                       # picker server-side handler, /summongoblin
                                       # /raceflip /setcolonyrace commands.
        ExampleModClient.java          # MDK default client entrypoint (legacy).
        Config.java                    # NeoForge config (MDK placeholder).

        # — Race system —
        Race.java                      # Enum: GOBLIN, ORC. Byte ids stable.
        Races.java                     # Central registry: Race ↔ ResourceLocation
                                       # ↔ EntityType. isBlocked() for orc_lord/
                                       # orc_disaster.
        RaceVariantData.java           # Sealed interface permitting Goblin and
                                       # Orc variant records.
        GoblinVariantData.java         # 25-byte goblin variant (gender/skin/face/
                                       # hair/colors/bandages/evolution).
        OrcVariantData.java            # 26-byte orc variant (variant/neck/top/
                                       # colors/bandage/necklace/evolution).
        GoblinTextures.java            # Goblin texture-path resolution.

        # — Identity + tag plumbing —
        RaceIdentitySavedData.java     # Server-global SavedData. RaceIdentity
                                       # records (identityId, citizenId, colonyId,
                                       # mobEntityUUID, mode, entitySnapshot,
                                       # ownerPlayerUUID, race) and PendingRaceMob
                                       # pool. Data key kept as the original
                                       # "tensura_minecolonies_identities" for
                                       # backward compat with existing saves.
        Attachments.java               # NeoForge AttachmentType registration —
                                       # RACE_TAG attached to citizen entities.
        RaceTag.java                   # The race-tag record (identityId, race,
                                       # RaceVariantData). Polymorphic encode.
        RaceTagClientStore.java        # @OnlyIn(CLIENT). UUID→RaceTag client
                                       # mirror, populated by S2C sync. Typed
                                       # accessors getGoblinVariant/getOrcVariant.

        # — Networking —
        Networking.java                # NeoForge payload registration + S2C/C2S
                                       # records: RequestRoster/RosterResponse,
                                       # ActOnIdentity, OpenCollapseConfirm,
                                       # ConfirmCollapse, SyncRaceTagPayload,
                                       # OpenRacePickerPayload, RaceChoicePayload.

        # — UI / Screens —
        ClientEvents.java              # @OnlyIn(CLIENT). Keybind (G), installs
                                       # roster/confirm/raceTag/racePicker handlers,
                                       # registers RenderLivingEvent.Pre
                                       # subscribers.
        ClientRosterHandler.java       # @OnlyIn(CLIENT).
        RosterScreen.java              # @OnlyIn(CLIENT). Mob-roster UI.
        ConfirmCollapseHandler.java    # @OnlyIn(CLIENT).
        ConfirmCollapseScreen.java     # @OnlyIn(CLIENT). Magicule-overspend
                                       # warning dialog (modal panel pattern).
        RacePickerClientHandler.java   # @OnlyIn(CLIENT). 1-tick-deferred screen
                                       # open for Stage B race picker.
        RacePickerScreen.java          # @OnlyIn(CLIENT). Stage B race-pick
                                       # modal (Default/Goblin/Orc).

        # — Per-colony config —
        ColonyRaceConfigSavedData.java # Per-server overworld SavedData.
                                       # Multi-race members (Map<colonyId,
                                       # EnumSet<ColonyMember>>) + pendingChoice
                                       # set + envoy state (acceptedEnvoys,
                                       # colonyCreationTick, lastEnvoyResolveTick,
                                       # activeEnvoyUuid, playerNonColonistEnvoys
                                       # Seen, kill-gate baselines).
        ColonyMember.java              # Enum {COLONIST, GOBLIN, ORC}. Disjoint
                                       # from Race — keeps the Tensura-mob race
                                       # concept separate from the colony
                                       # composition concept; toRace() returns
                                       # Optional for COLONIST.

        # — Envoy system (Stage H) —
        EnvoyTag.java                  # NeoForge data attachment payload:
                                       # (colonyId, member, state ALIVE/
                                       # ACCEPTED/DECLINED). NBT-serialised.
        EnvoyDialogue.java             # Static tables: nameplate text +
                                       # colour, dialogue title + body, accept
                                       # + decline messages per ColonyMember.
                                       # All five (Goblin/Orc/Colonist/
                                       # Lizardman/Dwarf) live in the main maps;
                                       # EXTRA_* maps retained as legacy mirror.
        EnvoyDialogueScreen.java       # @OnlyIn(CLIENT). Custom Screen for
                                       # the diplomacy dialogue (mirrors
                                       # ConfirmCollapseScreen pattern).

        # — Race skill profiles (Stage I1) —
        RaceSkillProfile.java          # Per-Skill (meanBias, spread) record.
                                       # apply() reads MC's randomised level,
                                       # adds bias, clamps to [1,99]. No
                                       # progression interference.
        RaceSkillProfiles.java         # Per-ColonyMember profile registry.
                                       # ORC tanky, GOBLIN flat, LIZARDMAN
                                       # speed, DWARF craftsmanship, COLONIST
                                       # no bias. Hooked at naming-to-citizen
                                       # in onRaceNamed + pending-pool drain.

        # — Lizardman rendering (Stage I2, shadow-entity / orc pattern) —
        LizardmanVariantData.java      # 18-byte sealed-permits record.
        LizardmanCitizenRenderHandler.java # @OnlyIn(CLIENT). Per-citizen
                                       # shadow LizardmanEntity, never tick()'d,
                                       # fed to Tensura's LizardmanRenderer.

        # — Dwarf rendering (Stage I3, biped / goblin pattern) —
        DwarfVariantData.java          # 25-byte sealed-permits record (9
                                       # enum-id bytes + 4 colour ints).
        DwarfTextures.java             # @OnlyIn(CLIENT). Lazy "texture proxy"
                                       # DwarfEntity — built once, never added
                                       # to world. Solves the
                                       # "Tensura needs a DwarfEntity to
                                       # resolve textures, we have a citizen"
                                       # problem without a mixin.
        DwarfOverlayLayer.java         # @OnlyIn(CLIENT). Generic overlay layer
                                       # parameterised by ModelLayerLocation,
                                       # texture/color/predicate fns. Mirror of
                                       # GoblinOverlayLayer for the dwarf set.
        DwarfCitizenRenderer.java      # @OnlyIn(CLIENT). LivingEntityRenderer
                                       # over PlayerModel + 7 DwarfOverlayLayer
                                       # instances. Hardcoded 0.5× scale in
                                       # scale() (NOT SCALE attribute — see
                                       # Wrinkle 2 in docs).
        DwarfCitizenRenderHandler.java # @OnlyIn(CLIENT). RenderLivingEvent.Pre
                                       # cancel+swap for DWARF-tagged citizens.
                                       # Tolerates 5 consecutive render
                                       # failures before invalidating.

        # — Subordinate trade tab (Stage I4) —
        SubordinateTradeButtonHandler.java # @OnlyIn(CLIENT). ScreenEvent.Init
                                       # .Post hook. Reflects HumanoidInventory
                                       # Screen's private humanoid field (one
                                       # shot, cached). Adds a "Trade" Button
                                       # for TensuraMerchantEntity subordinates.
                                       # Click → OpenSubordinateTradePayload →
                                       # server opens vanilla Merchant screen.

        # — Goblin rendering —
        GoblinCitizenRenderer.java     # @OnlyIn(CLIENT). LivingEntityRenderer
                                       # over PlayerModel + 8 GoblinOverlayLayer
                                       # instances + HumanoidArmorLayer +
                                       # ItemInHandLayer.
        GoblinCitizenRenderHandler.java # @OnlyIn(CLIENT). RenderLivingEvent.Pre
                                       # cancel+swap for GOBLIN-tagged citizens.
                                       # Lazy renderer construction.
        GoblinOverlayLayer.java        # @OnlyIn(CLIENT). Generic
                                       # RenderLayer<…, PlayerModel> over Tensura's
                                       # GoblinLayer.* ModelLayerLocations.

        # — Orc rendering (shadow-entity) —
        OrcCitizenRenderHandler.java   # @OnlyIn(CLIENT). RenderLivingEvent.Pre
                                       # cancel+swap for ORC-tagged citizens.
                                       # Per-citizen shadow OrcEntity pool, never
                                       # tick()'d. Animation-driver state
                                       # mirroring + variant + equipment sync.

        # — Mixin —
        mixin/
          CreateColonyMessageMixin.java # @WrapOperation on MC's
                                        # CreateColonyMessage.onExecute to
                                        # suppress the auto-sent "colony_founded"
                                        # / "colony_reactivated" chat messages
                                        # (re-issued by our race-picker handler).
      resources/
        assets/examplemod/lang/en_us.json   # Keybind + UI translations +
                                            # tensura_minecolonies.colony.created.*
                                            # race flavour messages
        tensura_minecolonies.mixins.json    # Mixin config file
      templates/
        META-INF/neoforge.mods.toml         # Mod metadata template — registers
                                            # the mixin config
```

## Key identifiers

| Property | Value |
|---|---|
| Mod ID | `tensura_minecolonies` |
| Display name | Tensura MineColonies Integration |
| Version | 0.0.1 |
| Java package | `com.example.examplemod` (needs renaming — see below) |
| Group ID | `com.example.examplemod` (needs renaming) |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Java | 21 |

## Dependencies (all in libs/)

**Compile + runtime** (APIs this mod codes against):
- `minecolonies-1.1.1319-1.21.1.jar`
- `structurize-1.0.830-1.21.1.jar`
- `tensura-neoforge-2.0.1.0.jar`
- `architectury-13.0.8-neoforge.jar`
- `geckolib-neoforge-1.21.1-4.8.4.jar`
- `manascore-neoforge-4.0.0.2.jar`
- `SmartBrainLib-neoforge-1.21.1-1.16.11.jar`
- `TerraBlender-neoforge-1.21.1-4.1.0.8.jar`

**Runtime only** (transitive deps, not coded against):
- `blockui-1.0.209-1.21.1.jar`
- `domum-ornamentum-1.0.231-main.jar`
- `multipiston-1.2.58-1.21.1.jar`
- `towntalk-1.2.0.jar`

## Build and run

```
# Run the game client with all mods loaded
./gradlew runClient

# Build the mod JAR
./gradlew build   # output: build/libs/tensura_minecolonies-0.0.1.jar

# Regenerate data (resources produced by data generators)
./gradlew runData
```

## Known housekeeping debt

The MDK default package (`com.example.examplemod`) and class names (`ExampleMod`, `ExampleModClient`, `Config`) have not been renamed yet. When writing new code, start new classes in a proper package like `com.tensura.minecolonies` and migrate the existing files when convenient. Do not block feature work on this rename.

## Current feature status (Milestone 3 vertical slice)

The "two bodies, one identity" design is largely implemented end-to-end.
See `docs/roadmap.md` for stage-by-stage detail; `docs/decisions.md` for
the design rationale and design-choice history.

**Complete:**
- Stage 1b — pending pool (goblins named before any colony exists are
  promoted on `ColonyCreatedModEvent`).
- Stage A — naming creates `CitizenData` + count without spawning a body.
- Stage B — sneak-right-click send-to-colony swap; full item transfer.
- Stage C1 — `/summongoblin <name>` command brings the goblin back.
- Stage C2a/b — keybind-opened roster Screen (default `G`), two-way
  toggle, ConfirmCollapseScreen for the forced-collapse overspend prompt.
- Stage D — death hooks (`LivingDeathEvent` + `CitizenDiedModEvent`).
- Stage D2 — magicule cost gate (target.EP × 0.25), shared chokepoint.
- Stage D3 — stat sync (bump destination max attributes, then absolute
  copy of aura/magicule/spiritualHealth/HP plus flat copy of counters
  and destiny flags).
- Stage E — Tensura `MagicCircle` visuals (SPACE variant, 3× size,
  spinning), 2-second delay, sink-and-rise animations with X/Z lock and
  fall-damage prevention, refund-on-abort.

- Stage F — goblin renderer for the in-colony citizen body. Tagged
  citizens render through `GoblinCitizenRenderer` (extends
  `LivingEntityRenderer<AbstractEntityCitizen, PlayerModel<…>>`) with
  per-citizen variant appearance — gender, skin, face, hair, hair
  color, conditional bandages — and the right scale + clothing for
  hobgoblins vs base goblins. Plus `HumanoidArmorLayer` and
  `ItemInHandLayer` so the citizen renders armor + weapon. Baby
  goblins propagate as child citizens. Untagged citizens fall through
  to MineColonies' default render. See roadmap.md Stage F (F1–F7).
- Stage G — race system foundation. `Race` enum + `Races` registry,
  sealed `RaceVariantData` (Goblin and Orc variants), `RaceTag` with
  the race discriminator, race-aware naming/send/summon. Orc
  rendering via a per-citizen shadow-`OrcEntity` pool fed to Tensura's
  own `OrcRenderer`, with animation driven by mirroring the citizen's
  walkAnimation/pose/sprint state (HARD RULE: shadow never `tick()`).
  Per-orc variants set on the shadow each frame; Tensura's accessory
  layers read them automatically. Equipment slots mirrored from the
  citizen to the shadow. Orc lord and orc disaster blocked from the
  pipeline (separate EntityTypes; per-tier shadow pools deferred).
  See roadmap.md Stage G (G1–G4).
- Stage B — race-aware population spawn. `ColonyRaceConfigSavedData`
  tracks each colony's chosen race plus a `pendingChoice` set.
  `CitizenAddedModEvent(INITIAL)` interception discards MC's
  about-to-spawn citizen + spawns a wild Tensura mob of the colony's
  chosen race instead (pending colonies suppress growth entirely
  until choice). Town-hall race-picker modal opens on
  `ColonyCreatedModEvent` (parent-stacking + 1-tick defer to capture
  MC's town hall UI as parent). Re-engagement on right-click or
  login. MC's auto-sent "colony_founded" / "colony_reactivated" chat
  messages suppressed via Mixin; replayed by our race-picker handler
  on DEFAULT pick, race-specific flavour text on GOBLIN/ORC.
- Stage H — envoy system (H1-H3b, all four sub-stages). Diplomatic
  emissaries from COLONIST / GOBLIN / ORC factions periodically arrive
  at each colony offering to add their race to its spawn set.
  `EnvoyTag` NeoForge attachment marks the entity; per-race entities
  are GoblinEntity / OrcEntity / `VisitorCitizen` (registered via
  `VisitorManager` so the visitor-handler's discard-on-zero-citizen-id
  doesn't kill it). 15-block roam radius via `restrictTo`. Custom
  `EnvoyDialogueScreen` with race-flavoured copy (chosen over MC's
  `IInteractionResponseHandler` reuse — that interface is hard-bound
  to `ICitizenData`). Accept adds the race to the spawn set,
  marks the race permanently envoy-locked. Per-second scheduler with
  3-day post-resolve cooldown. Unlock conditions: COLONIST = colony age
  ≥ 3 days, GOBLIN = ≥ 3 named goblins, ORC = ≥ 25 citizens. Gamerule
  `tensuraMaxNonColonistEnvoys` (default 2) caps non-colonist races
  per player. Kill-gate (Stage H3b): killing an unaccepted race resets
  its condition for every colony you own, with per-shape semantics
  (timer / cumulative / current-value). Orc lord / orc disaster
  excluded from the kill-gate. Full as-built record in
  `docs/envoy-system.md`. Debug commands: `/spawnenvoy`,
  `/envoystate`, `/envoyforce`, `/envoyresetcooldown`.
- Stage I — Lizardman + Dwarf races, citizen-skill profiles,
  subordinate trade tab (4 sub-stages, all landed).
  - **I1**: `RaceSkillProfile` per-Skill bias system layered on MC's
    randomised init. Applied ONCE at naming-to-citizen; normal MC
    progression continues untouched. Per-race profiles defined for
    all five members (ORC tanky, GOBLIN flat, LIZARDMAN speed,
    DWARF craftsmanship, COLONIST baseline).
  - **I2 Lizardman**: GeckoLib path, reuses orc shadow-entity render
    pattern. 18-byte variant record. Envoy unlock ≥15 citizens.
    Yellow nameplate.
  - **I3 Dwarf**: Vanilla biped path, reuses goblin PlayerModel +
    overlay-layer pattern. 29-byte variant record (25-byte trailing
    `float scale`; 25-byte legacy decodes default to 0.9375f).
    Three wrinkles solved: (1) `can_be_named` datapack tag merge
    (dwarf isn't in Tensura's tag); (2) SCALE attribute dropped from
    the citizen body entirely — scaling is renderer-only, base
    `0.9375f` × per-citizen captured `Attributes.SCALE` from the
    source wild dwarf (which Tensura randomises in [0.7, 1.0] biased
    low), so each citizen dwarf matches the wild dwarf it was named
    from; hitbox stays full citizen size; (3) lazy "texture proxy"
    `DwarfEntity` for Tensura's static texture helpers. Gold
    nameplate. Envoy unlock PLACEHOLDER (≥30 citizens AND a Miner's
    Hut built); real conditions deferred-content.
  - **I4 Subordinate trade tab**: Yellow "Trade" button on
    `HumanoidMainScreen` (the armor + weapons page — the page the
    player lands on when right-clicking) for named goblin /
    lizardman / dwarf subordinates. Server opens vanilla Merchant
    screen. Profession / merchant level / persisted offers / gossips
    all round-trip via Tensura's NBT and survive naming.
    `ScreenEvent.Init.Post` hook + one reflective field read; no
    mixin. Trade screen viewable 24h; stock refresh fires once at
    dawn (per-dimension `getDayTime()/24000` rollover) via
    `tickDawnRestock` calling each merchant's `restock()`.

**Stage L3-hotfix-6 — three coupled bugs + one polish:**
- Bug E: `JobBeastGuard.entry` null on legacy/reloaded citizens (the produceJob fix only helps new assignments; reloaded jobs deserialise without going through it). Fix: set entry in `JobBeastGuard`'s constructor too — defensive, covers every construction path.
- Bug F: spider visual lost on walk-away/return. MC respawns a fresh `EntityCitizen` on chunk reload with no data attachments. Fix: extend `onStartTracking` to RE-ATTACH the BeastTag if missing — look up identity via `RaceIdentitySavedData.getByCitizenId`, attach with stored beast kind + identityId, pin SCALE=1.0.
- Bug G: hardcoded "your goblin" / "that goblin" strings in user-facing messages — replaced with race/beast-neutral wording ("citizen", "subordinate"). Logs untouched.
- Polish: `BeastGuardCombatAI.doAttack` overrides to add a leap-toward-target on ~35% of swings (4-second cooldown, only when 4+ blocks out). Manual port of `LeapToTarget` from `KnightSpiderEntity.getFightTasks()` since MC citizens don't run SmartBrainLib. Ranged spit / slam-from-fall deferred — they need their own state-machine work.

**Stage L3-hotfix-5 — `JobBeastGuard.getJobRegistryEntry() == null` NPE spam:**
- Symptom: every spider kill logs `NPE: JobEntry.getKey() because IJob.getJobRegistryEntry() is null` from `KnightCombatAI.onTargetDied` → `CitizenSkillHandler.levelUp` → `SoundUtils.playSoundAtCitizenWith`. Combat continues (damage lands first); XP-grant + log get hosed.
- Root cause: `assignBeastToTower` constructed `new JobBeastGuard(citizenData)` directly. The job's internal `entry` field gets populated by `JobEntry.produceJob` via `setRegistryEntry(this)` — bypassing that path left `entry` null forever.
- Fix: construct via `ModJobsRegistry.BEAST_GUARD.get().produceJob(citizenData)` instead of the direct constructor.

**Stage L3-hotfix-4 — assignment silently failing → spurious advisory + paused beast:**
- Three silent-reject conditions in `module.assignCitizen` were causing the "no tower" advisory + pause to fire even when a tower existed:
  1. `findFirstGuardTower` only matched `BuildingGuardTower`, missing `BuildingBarracksTower` / `BuildingArchery` / future subclasses.
  2. Level-0 (just-placed) tower has `sizeLimit == 0` → `isFull()` true → assignment rejected.
  3. Re-send with persisted `JobBeastGuard` failed `assignTo`'s JobEntry match (beast_guard ≠ knight).
- Fix: broaden `findFirstGuardTower` to return `AbstractBuildingGuards` (parent of all guard buildings). Rewrite `assignBeastToTower` to BYPASS `module.assignCitizen` entirely — directly add citizen to the module's list via reflection, clear the citizen's existing job via reflection (skips setJob's onRemoval cascade), then `setJob(JobBeastGuard.preLinkWorkBuilding(tower))`. Old `reAddCitizenToModuleList` helper subsumed by `addCitizenToModuleListIfMissing` (same reflection, earlier in flow).

**Stage L3-hotfix-3 — beasts don't need a sword:**
- Bug C: `AbstractEntityAIFight.equipInventoryArmor` has the same `itemsNeeded.get(level - 1)` IOOB as `atBuildingActions`. Called FIRST in `prepare()`, so L3-hotfix-2's `atBuildingActions` override never ran. Fix: also override `equipInventoryArmor` as a no-op on `EntityAIBeastGuard`.
- Bug D: `KnightCombatAI.canAttack` searches the citizen's empty inventory for a sword → false → no attacks. `getAttackDamage` starts at 0 and only grows from a held weapon. Fix: override both on `BeastGuardCombatAI` — `canAttack` always true, `getAttackDamage` reads `user.getAttributeValue(ATTACK_DAMAGE)` (the slot Stage L3a writes the spider × EP-multiplier value into).

**Stage L3 polish — beast doesn't move unless hired:**
- After `assignSpawnedBeastToTower` returns, check `citizenData.getJob() instanceof JobBeastGuard`. If not (no tower in colony / assignment rejected), `citizenData.setPaused(true)` so the spider stands still instead of wandering as a generic idle vanilla citizen. Hired branch defensively un-pauses. Player gets a yellow advisory about needing to build a Guard Tower and re-send.

**Stage L3-hotfix-2 — beast inert + relog visual lost:**
- Bug A (no patrol, no combat, ignored by hostiles) root cause: `EntityAIBeastGuard`'s state machine reaches PREPARING → `AbstractEntityAIFight.prepare()` → `atBuildingActions()` reads `itemsNeeded.get(buildingLevel - 1)` on an empty list (KnightAI populates `itemsNeeded`; we don't) → IndexOutOfBoundsException. `AbstractAISkeleton.onException` is `{ return; }` — silently swallowed. State machine stuck in PREPARING forever; combat AI on same state machine, also stuck. Fix: override `atBuildingActions()` as a no-op (beasts have no gear/chest work; nothing legitimate for the parent to do).
- Bug B (relog → plain humanoid): `onStartTracking` only re-synced RaceTag, never BeastTag. Client `BeastTagClientStore` wipes on logout; without a re-sync on re-track the renderer sees no tag → plain humanoid. Fix: extend `onStartTracking` to mirror the race-tag block for BeastTag.

**Stage L3-hotfix — beast send crash + untagged citizen + placement:**
- Crash root cause: `assignBeastToTower`'s `setJob(new JobBeastGuard)` triggered MC's `setJob` → `JobKnight.onRemoval` → unassigned citizen → cleared knight's workBuilding → new BeastGuard AI ctor read `citizenData.job.getWorkBuilding()` = `JobBeastGuard.workBuilding` = null → NPE at `AbstractEntityAIGuard.<init>:187`.
- Issue #3 same bug: Stage L2 placed `assignSpawnedBeastToTower` OUTSIDE the rollback try/catch in `sendGoblinToColony`. Crash escaped → no rollback → citizen body persisted but BeastTag (attached in the later try block) never attached → plain humanoid visual on relog.
- Fix #2: new `JobBeastGuard.preLinkWorkBuilding(IBuilding)` writes directly to the protected `AbstractJob.workBuilding` field BEFORE setJob fires AI build. New `reAddCitizenToModuleList` reflects on `AbstractAssignedCitizenModule.assignedCitizen` to re-add the citizen after the swap (prevents auto-hire of a replacement knight). Module's assigned-list now contains a beast-jobbed citizen — type mismatch acceptable for L3 (tower is just a structural colony anchor).
- Fix #3: moved `assignSpawnedBeastToTower` INSIDE the existing try/catch; attaches BeastTag BEFORE the assignment so visual works even if a future regression breaks assignment. Rollback discards body + tag together on failure.
- Fix #1: `beastMaterialisePos` now calls new `findSafeSpotNear` which scans concentric perimeter rings (r=3..6) for surface-ground + 2-block humanoid clearance + Y within ±2 of tower anchor. Beast lands NEAR the tower, not inside it. Falls back to tower anchor if no spot found.

**Stage L3a — beast-guard combat reach + level-scaled stats:**
- Combat reach: new `BeastGuardCombatAI extends KnightCombatAI` overrides `getAttackDistance() → 4.0` (vanilla 2.0; half spider footprint + margin). `EntityAIBeastGuard` instantiates one in its constructor — this ALSO fixes the Stage 1 bug where no combat AI was constructed at all (parent doesn't make one; the concrete subclass has to).
- Level scaling reads `IExistence.getEP()` (fallback: aura+magicule+SH sum) at send time. Multiplier `1.0 + log10(max(EP, 100)/100) × 0.5`, clamped `[1.0, 4.0]`. Spider baseline ~11k EP → ~2× scale.
- DIRECT (multiplied combat attributes via tracked `BEAST_LEVEL_SCALE_ID` modifier): MAX_HEALTH, ATTACK_DAMAGE, ARMOR (capped at 30).
- INDIRECT modest: KNOCKBACK_RESISTANCE — `max(spider's natural, (scale-1)×0.3)`, capped 0.95. Single safe translation; no other "common-sense" indirect transfer.
- Stricter than races (which only match max attributes via `bumpBodyMaxAttributes`). Beasts get bump PLUS EP multiplier on combat attributes.
- Distinct modifier id from `SWAP_ENERGY_BOOST_ID` — no conflict. Permanent operation, removed-and-re-applied on every send so no compounding. `copyStats` untouched.

**Stage L2 — beasts join the identity/swap lifecycle (guard-oriented send-back):**
- `RaceIdentity` extended with nullable `Beast beast` field; exactly one of `race` / `beast` is non-null. NBT save/load handles both; legacy saves still decode race-only correctly. `isBeast()` convenience.
- Naming flow rewritten — `handleBeastNaming` now mirrors race naming: creates CitizenData, stores SUBORDINATE identity with `beast=KNIGHT_SPIDER`, leaves the wild spider alive at the player's side. Stage 1's instant-citizen behaviour replaced.
- Send pipeline (`sendGoblinToColony` — kept the name, handles both) branches at four points on `identity.isBeast()`: variant capture skipped, materialise position → first Guard Tower (TH fallback), item transfer skipped, tag attach uses `BeastTag` (which also pins `SCALE=1.0`). Post-spawn `assignSpawnedBeastToTower` runs the tower-assignment + JobBeastGuard override that Stage 1 did at naming.
- Summon pipeline (`summonGoblin`) branches at two points: item transfer skipped, tag clear dual-aware (BeastTag first then RaceTag). Snapshot reconstruction handles entity type via `EntityType.create(snapshot, level)` naturally.
- Roster integration: beasts already appeared via `RaceIdentitySavedData.all()` iteration; send/summon toggle works through the standard `handleMenuAction` chokepoint.
- Trade-snapshot reconstruction (Stage 2 item 6) DEFERRED to Stage 3 — Stage 1 fallback ("summon first to trade") stays.

**Stage L — beast-guard (knight spider) + trade button flip:**
- New `Beast` enum + `Beasts` registry — parallel to `Race` / `Races`, disjoint. Naming probes Beasts first. Knight spider (`tensura:knight_spider`) is the Stage 1 sole entry.
- `BeastTag` attachment + `BeastTagClientStore` + `Networking.SyncBeastTagPayload` — mirrors the race-tag pair.
- `JobBeastGuard extends AbstractJobGuard<JobBeastGuard>` registered via `ModJobsRegistry` (DeferredRegister against `CommonMinecoloniesAPIImpl.JOBS`).
- `EntityAIBeastGuard extends AbstractEntityAIGuard<JobBeastGuard, AbstractBuildingGuards>` — overrides `decide()` to always `patrol()`, `guardMovement()` as no-op. PATROL-locked; never returns to tower. Combat/sleep/regen inherited.
- `KnightSpiderCitizenRenderHandler` — shadow-entity GeckoLib path (parallel to lizardman). Per-citizen `KnightSpiderEntity` shadow pool, never `tick()`'d. Citizen `Attributes.SCALE = 1.0` enforced — humanoid hitbox for pathfinding; spider visual is decoration (5w × 3.75h, clips through walls — accepted).
- Naming pipeline: `handleBeastNaming` creates CitizenData, finds first Guard Tower, assigns citizen via its WorkerBuildingModule, overrides job with `JobBeastGuard`, spawns body at town hall, attaches BeastTag, discards wild spider. No identity store for beasts in Stage 1 — subordinate↔citizen swap deferred.
- **Trade button FLIP**: `CitizenTradeButtonHandler` injects a Trade button into MC's `MainWindowCitizen` (via `BOScreen` hook on `ScreenEvent.Init.Post` — `BOScreen extends Screen`). `SubordinateTradeButtonHandler` no longer registered. Server `handleOpenCitizenTrade` validates ownership + opens trade if subordinate body is in world. BlockUI promoted to `implementation` in build.gradle.

**Stage K — earned-race skill partition + Orc removed from picker:**
- STARTERS (Colonist, Goblin) — picker-available, flat baselines, unchanged.
- EARNED (Orc, Dwarf, Lizardman) — envoy-only; skill biases re-partitioned so each owns a distinct lane (orc=physical, dwarf=mental/craft, lizardman=precision+mystic).
- Orc kept: HIGH Strength/Athletics/Stamina, LOW Knowledge/Intelligence/Creativity.
- Dwarf re-aligned: HIGH Knowledge/Intelligence/Creativity (Intelligence in place of Dexterity), LOW Athletics/Strength/Agility (Strength added).
- Lizardman expanded: HIGH Agility/Dexterity/Focus + **Mana** (only race covering Mana → unlocks Healer/Alchemist/Enchanter at race-bias parity), LOW Strength/Stamina. 4↑/2↓ asymmetry kept (Mana is a narrow gate).
- Adaptability stays starter-only (goblin's mild positive is the only earned/starter signal on it).
- `RacePickerScreen`: Orc button removed, two-button centered layout (Default / Goblin), description reframed; closing line points players to diplomacy for the other races.
- `Networking.RaceChoicePayload.CHOICE_ORC = 2` and server handler case retained as defensive paths (legacy in-flight payloads, cheap re-enable). `/setcolonyrace` admin command unaffected. Orc fully exists; just not a STARTER.

**Stage J2 — condition-dependent envoy dialogue:**
- `EnvoyCondition` enum (7 values, 1-byte bitmask) captures which alternatives were satisfied at spawn.
- Captured per envoy in `captureMetConditions`; persisted on `EnvoyTag.conditionMask` (NBT byte) and threaded through `OpenEnvoyDialoguePayload` to the client.
- Dialogue text = `base + " " + snippet(member, condA) + " " + snippet(member, condB)` ... — each snippet a complete sentence in the race's voice, joined with a space, no inter-snippet references. No combinatorial explosion.
- Race voices: goblin humble, orc simple-thinking (NOT simple-vocabulary — short declaratives in normal words), lizardman condescending-but-impressed, dwarf well-spoken-formal, colonist polite-neutral.
- COUNT / TIMER alternatives DO get snippets — keeps early-game (single-condition) dialogue from feeling flat.
- `EnvoyDialogueScreen` panel grows dynamically to fit the wrapped body line count (clamped to screen height − 20).
- Backward-compat: legacy envoys with no `condMask` decode as mask 0 → base-only dialogue.
- Accept / decline TEXT is also condition-aware for DWARF + TRUE_HERO / TRUE_DEMON_LORD (title acknowledged in the parting line); other races stay flat. HERO precedes DEMON_LORD when both captured. Accept/Decline server mechanics unchanged.
- Post-Stage-J2 polish: every base body and snippet shortened (~half length), key identifiers ("Orc Disaster" by name, "Elder", "Marsh-Tribe", "Holds", "Ifrit", "true hero / demon lord mantle", "twenty days", "our kin in their own hold") kept throughout; orc voice rewritten to simple-thinking-not-simple-vocabulary.

**Stage J — deferred-content envoy conditions (eligibility + kill-gate, no dialogue yet):**
- ORC alternative: Orc Disaster defeated (per-player, permanent flag, immune to all resets).
- LIZARDMAN alternative: Ifrit defeated (per-player, cleared on lizardman kill).
- DWARF alternatives (any one qualifies, in addition to the existing 30-citizens+Miner placeholder): 20 in-game days no owner death (per-colony, −10 day penalty on dwarf kill), dwarven-village entered (per-player flag, cleared on dwarf kill), true demon lord (gated by per-player disable flag), true hero (same shape as demon lord).
- Detection: `LivingDeathEvent` on OrcDisasterEntity / IfritEntity / owning ServerPlayer; structure-bbox poll per scheduler tick; live `IExistence` read; `LivingEntityUseItemEvent.Finish` on `ResetScrollItem` with `RESET_ALL` for the demon-lord/hero disable clear; status-currently-false fallback in the scheduler.
- Storage extended on `ColonyRaceConfigSavedData` — one per-colony Long map + five `Set<UUID>` per-player flag sets; NBT-persistent, backward-compatible.
- Stage 2 (condition-dependent dialogue copy) deferred.

**Post-Stage-I polish:**
- Subordinate sneak-right-click send removed — roster menu (G) is
  now the only send path. Envoy right-click branch still active.
- Dwarf facial hair: dedicated `DwarfFacialHairLayer` (vanilla
  `HumanoidModel`, not `PlayerModel`) — Tensura's
  `FACIAL_HAIR_LAYER` is the only dwarf overlay built from
  `HumanoidModel.createMesh`, so the generic PlayerModel-based
  `DwarfOverlayLayer` was failing the bake and silently dropping
  the beard.

**Pending:**
- Stage G6 — orc lord and orc disaster as separate shadow types in the
  citizen pipeline. Currently blocked from naming/send via
  `Races.isBlocked`.
- Higher evolved goblin tiers (Enlightened Hobgoblin, Hobgoblin
  Saint). Currently both render with the hobgoblin scale + assets;
  finer per-tier differentiation lives here.

**Open future-work notes recorded in decisions.md:**
- Multi-colony policy for pending pool drain and colony lookup.
- Equalisation between Tensura stats and MineColonies citizen skills.
- Per-race stat profiles (the picker menu currently shows placeholder
  stat blurbs `[placeholder stats]` until real stats land).
- Subordinate-mob-attacks-citizens targeting bug (out of scope of the
  picker menu work; flagged for separate investigation). Investigated
  2026-06-06 — see [docs/subordinate-citizen-targeting.md](docs/subordinate-citizen-targeting.md)
  for the root cause and the recommended fix (a ManasCore
  `LIVING_CHANGE_TARGET` listener that vetoes citizens of the owner's
  colony). No code written yet.
