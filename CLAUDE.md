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
        DwarfProfessionLayer.java      # @OnlyIn(CLIENT). Profession-clothes
                                       # overlay for dwarf citizens — bakes
                                       # Tensura's HumanoidModel
                                       # ProfessionClothesLayer.CLOTHES and draws
                                       # tensura:textures/entity/dwarf/profession/
                                       # {name}.png based on the RaceTag's
                                       # profession string. Cosmetic parity with
                                       # the subordinate dwarf's profession look.

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

        # — Reputation system (v1 spine) —
        ReputationManager.java         # THE reputation API — sole door to
                                       # storage. get/getTier/modifyReputation
                                       # (colony, amount, reason)/setReputation/
                                       # isAtLeast/isBelow + per-player twins.
                                       # Clamps 0–100, default 50, logs changes.
        ReputationSavedData.java       # Package-private overworld SavedData
                                       # ("tensura_minecolonies_reputation").
                                       # Map<colonyId,Double> + reserved
                                       # Map<UUID,Double> player store. ONLY
                                       # ReputationManager touches it.
        ReputationTier.java            # Derived ordered tiers (HOSTILE/
                                       # PASSIVEAGGRESSIVE/WARY/NEUTRAL/LOYAL/
                                       # DEVOTED); ALL band thresholds live
                                       # here; per-tier name + colour.
        ReputationReason.java          # Why-enum every modifyReputation call
                                       # passes (BOSS_KILL, BUILDING_COMPLETED,
                                       # CITIZEN_ATTACKED, CITIZEN_KILLED,
                                       # THEFT, QUEST, ADMIN).

        # — Assassin system (v1+v2) —
        Assassins.java                 # Lifecycle driver: daily determination
                                       # (rep below WARY + happiness < 4),
                                       # LURKING/ARMED states, vulnerability-
                                       # triggered strike, boss manifestation
                                       # (buffs + bar + town-hall tether),
                                       # v2 EP theft (reversible) + skill copy
                                       # + cast driver (curated whitelist),
                                       # reclaim-on-kill, /assassin debug.
        AssassinTag.java               # Boss-body attachment (identity,
                                       # colony, target, stolen amounts).
        AssassinSavedData.java         # determination/state per identity,
                                       # cold-shoulder + once-ever choice per
                                       # colony, pending offline reclaims.
        AssassinClientHandler.java     # @OnlyIn(CLIENT). Great-Sage-gated red
                                       # "Assassin" nameplate (flag store +
                                       # RenderLivingEvent.Post).

        # — Raid system (v1 + difficulty levels) —
        TensuraRaidEvent.java          # IColonyRaidEvent impl registered in
                                       # minecolonies:colonyeventtypes —
                                       # raider UUID set, boss bar, timer,
                                       # NBT persistence. Citizen flee/hide
                                       # comes free via isRaided().
        TensuraRaids.java              # Static driver on the 1 s scheduler:
                                       # nightfall trigger (reputation tier
                                       # below NEUTRAL → 15/30/50% chance,
                                       # 3-day cooldown), wave spawn (tiered
                                       # Tensura MONSTER rosters, raid-level
                                       # × rep-deficit scaling), WALK_TARGET
                                       # steering + BrainUtils target assist,
                                       # victory (+8 RAID_REPELLED) /timeout.
        RaidTag.java                   # (colonyId, eventId) attachment on
                                       # every raid mob — the universal
                                       # "is a raider" check.
        RaidSavedData.java             # Per-colony raid cooldown anchors.
        BarrierBlock.java              # Barrier Core block (4 tiers: radius
                                       # 16/28/42/60, base cap 100-250k,
                                       # 4-stage charge sprites). Right-click
                                       # opens the core menu; crystal refuel.
        BarrierBlockEntity.java        # POOL tank (core-first fill, storage
                                       # overflow) + field driver: square
                                       # footprint (outermost layer), pushback
                                       # on barrier_blocked-tagged hostiles +
                                       # raiders, EP-scaled contact drain,
                                       # layers 1-3 (DL/Hero gate, 50/s
                                       # upkeep, outermost-first shedding),
                                       # wall-visibility toggle. Tuning knobs
                                       # are named constants here.
        BarrierFieldRenderer.java      # @OnlyIn(CLIENT). Square wall + roof
                                       # render per active layer, alpha =
                                       # pool fill, half-block tiling.
        BarrierCoreScreen.java         # @OnlyIn(CLIENT). Paper-styled core
                                       # menu (gauge, +/-3k, MIN/MAX, layers,
                                       # wall toggle) — server-snapshot
                                       # driven (OpenBarrierMenuPayload).
        MagiculeStorageBlock.java      # Storage block (4 tiers, +25k-300k):
                                       # overflow tank for a connected core
                                       # (flood-fill network), own fill
                                       # sprites, crystal refuel.
        StorageBlockEntity.java        # The storage block's own tank.

        # — Subordinate commands —
        PatrolOrder.java               # Serialized NeoForge attachment —
                                       # standing order for the "Patrol
                                       # Colony Outskirts" command (colonyId
                                       # + dimension). Presence = command
                                       # active; persists across reload.
        SubordinatePatrol.java         # Owns the 4th command's logic
                                       # (FOLLOW→WANDER→STAY→PATROL→FOLLOW)
                                       # and the per-entity EntityTickEvent
                                       # patrol driver. Brain-native via the
                                       # WALK_TARGET memory; outskirts = outer
                                       # ring of the nearest colony's claimed
                                       # chunks, water avoided. handlePatrolCycle
                                       # is the entry point called by the
                                       # cycleCommands mixin.

        # — Mixin —
        mixin/
          CreateColonyMessageMixin.java # @WrapOperation on MC's
                                        # CreateColonyMessage.onExecute to
                                        # suppress the auto-sent "colony_founded"
                                        # / "colony_reactivated" chat messages
                                        # (re-issued by our race-picker handler).
          ISubordinateCommandMixin.java # @Inject at HEAD of Tensura's
                                        # ISubordinate.cycleCommands (interface
                                        # default method) — inserts the PATROL
                                        # command into the native command cycle
                                        # so it activates exactly like vanilla
                                        # follow/wander/stay. Delegates to
                                        # SubordinatePatrol.handlePatrolCycle.
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
  `tensuraMaxNonColonistEnvoys` (default 4) caps non-colonist races
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
  - **I4 Trade tab** (now CITIZEN-side — see post-Stage-I polish
    below): Yellow "Trade" button for named goblin / lizardman /
    dwarf bodies. Server opens vanilla Merchant screen. Profession /
    merchant level / persisted offers / gossips all round-trip via
    Tensura's NBT. Trade screen viewable 24h; stock refresh fires
    once at dawn (per-dimension `getDayTime()/24000` rollover) via
    `tickDawnRestock` calling each merchant's `restock()`. The
    button was originally on the subordinate's `HumanoidMainScreen`;
    that handler still exists in the source tree but is no longer
    registered. The current implementation is described in the
    "Post-Stage-I polish — trade button on citizen body" section.

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
- Race voices (rewritten to Tensura canon — envoys are generic race reps who revere the player as a powerful protector-ruler): goblin humble-eager-grateful, orc dutiful-solemn-with-atonement, lizardman proud-formal-but-sincere, dwarf gruff-hearty-craftsman, colonist polite-neutral (left as-is; not a Tensura race, no canon profile).
- COUNT / TIMER alternatives DO get snippets — keeps early-game (single-condition) dialogue from feeling flat.
- `EnvoyDialogueScreen` panel grows dynamically to fit the wrapped body line count (clamped to screen height − 20).
- Backward-compat: legacy envoys with no `condMask` decode as mask 0 → base-only dialogue.
- Accept / decline TEXT is also condition-aware for DWARF + TRUE_HERO / TRUE_DEMON_LORD (title acknowledged in the parting line); other races stay flat. HERO precedes DEMON_LORD when both captured. Accept/Decline server mechanics unchanged.
- Post-Stage-J2 polish: every base body and snippet shortened (~half length).
- Canon-voice rewrite (latest): goblin/orc/lizardman/dwarf dialogue reworded to match each race's Tensura voice with envoys revering the player. Functional condition references kept verbatim ("Orc Disaster", "Ifrit", "true demon lord / hero", dwarven village, colony size/age, "twenty days"); invented org/character names ("Elder", "Marsh-Tribe", "Holds", "council", "chroniclers") removed; neutral reverent address ("great one"). Colonist lines unchanged.

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

**Post-Stage-I polish — trade button on citizen body:**
- Trade button moved from the subordinate's HumanoidMainScreen to
  the CITIZEN info window. Subordinate-side handler retained but
  unregistered.
- **Now a native BlockUI tab** (was a vanilla overlay — superseded).
  A single `ScreenEvent.Init.Post` hook on `AbstractWindowCitizen`
  (every citizen sub-page) adds a `ButtonImage` tab reusing MC's own
  `tab_left_side3.png` (32×26 at x=0) + a 20×20 icon
  (`tensura_minecolonies:textures/gui/modules/trade.png`) at
  `(5, tabY+3)`, in the first free slot at/after `familyTab`
  (170→196→222, reading `jobTab`/`debugTab` visibility). Routed like
  MC's tabs: `setHandler(window)` + `registerButton(id, runnable)`
  firing the unchanged `OpenCitizenTradePayload`. The earlier vanilla-
  `Button` overlay (`Render.Post` + `MouseButtonPressed.Pre` +
  `getGuiScaledWidth` anchoring) was removed; the off-window /
  vanilla-widget blockers don't apply to an in-window BlockUI child.
  See decisions.md "SUPERSEDED — trade button is now a native BlockUI
  tab". Only GOBLIN/LIZARDMAN/DWARF race citizens get the tab (not orc).
- Trade now works directly in CITIZEN form. The old "summon them
  back first" advisory is gone. `handleOpenCitizenTrade`
  reconstructs a transient `TensuraMerchantEntity` via
  `EntityType.create(identity.entitySnapshot, level)`, positions it
  on the citizen (bookkeeping only — never `addFreshEntity`), sets
  the trading player and opens the standard MerchantMenu. The
  reconstructed entity is held in `TRANSIENT_MERCHANTS` keyed by
  player UUID; a new `@SubscribeEvent onPlayerContainerClose`
  saves `merchant.save(freshTag)` back to `identity.entitySnapshot`
  via `saved.updateEntitySnapshot` and drops the session. Re-entry
  is blocked while a trade is open. No world-side merchant ever
  appears; the trade UI is the only surface.
- Dawn restock extended to citizen-form snapshots.
  `tickDawnRestock` now runs two passes per dimension: (A) the
  original live-subordinate pass for SUBORDINATE-mode identities
  whose wild form is in the world, and (B) a new snapshot pass for
  IN_COLONY-mode identities — reconstruct merchant via
  `EntityType.create(snapshot)`, call `restock()`, save back to the
  snapshot. Without pass B, the citizen-form trade button would
  drain offers and never refill. Pass B skips any identity that
  has an active `TRANSIENT_MERCHANTS` session to avoid racing the
  close-event persist hook clobbering the restocked snapshot — those
  catch up on the next dawn.
- Summon-time skin fix. `applyVariantToMob(LivingEntity mob, RaceTag tag)`
  now runs in `summonGoblin` immediately after `EntityType.create`
  succeeds — stamps each race-specific appearance field
  (`setGender/setSkin/.../setVariant/...`) from the citizen's
  current `RaceTag` onto the freshly reconstructed wild mob. The
  NBT round-trip via `readAdditionalSaveData` was usually correct
  but the snapshot is older than the RaceTag (snapshot captured at
  first send; RaceTag refreshed on every send), so drift produced
  the reported "summoned mob's skin doesn't match the citizen"
  symptom. Per-race apply methods: `applyGoblinVariant`,
  `applyOrcVariant`, `applyLizardmanVariant`,
  `applyDwarfVariant` (the dwarf one also restores the per-mob
  `Attributes.SCALE`).
- `tensuraMaxNonColonistEnvoys` gamerule default bumped 2 → 4.
  Existing worlds keep their stored value; new worlds start at 4.

**Citizen merchant shops — native tab, restock, leveling, cosmetic
profession (latest):**
- **Trade trigger is a native BlockUI tab** in the citizen window's left
  strip (`CitizenTradeButtonHandler`, `Init.Post` on `AbstractWindowCitizen`),
  reusing MC's `tab_left_side3.png` + a shipped `trade.png` icon
  (dark-brown arrows matched to MC's icon palette). Added at the bottom
  of the z-order so it tucks behind the content panel like MC's own tabs.
  Replaces the old vanilla overlay. GOBLIN / LIZARDMAN / DWARF only.
- **Refresh on open + dawn.** `handleOpenCitizenTrade` calls
  `restockIfPossible()` before showing trades (sold-out trades refill
  without summon+resend), on top of the dawn pass-B restock.
- **Trade level-ups apply citizen-side.** The transient citizen merchant
  never runs `customServerAiStep`, so the trade close hook
  (`onPlayerContainerClose`) now calls `applyPendingMerchantLevelUps`
  (reflective `while shouldIncreaseLevel() (≤ lvl 5): increaseMerchantCareer()`),
  which APPENDS the next tier's trades on reaching the XP threshold. New
  trades show on reopen.
- **Profession is COSMETIC ONLY.** Goblin/lizardman/dwarf each override
  `getPossibleTrades()` with intrinsic, profession-independent trades, so
  the citizen profession pass (`tickCitizenProfessions`, DWARF-only) sets
  a villager profession purely to drive the dwarf profession-clothes
  render (`DwarfProfessionLayer`) and NEVER touches trades. A dwarf gains
  the cosmetic profession from a nearby job-site block, anchored to that
  block (`RaceIdentity.jobSitePos`) so it doesn't flicker as the citizen
  wanders, and loses it when the block is broken. Goblin/lizardman shops
  are byte-identical across the wild → named → colony stages. See
  decisions.md "CORRECTION — citizen merchant professions are COSMETIC
  ONLY".

**Reputation system v1 (foundational spine — latest):**
- Per-colony standing 0–100 (default 50 NEUTRAL) with derived tiers;
  `ReputationManager` is the LOCKED sole-door API every future feature
  (crime/raids/assassins/reclaim/trades) reads and writes through —
  `modifyReputation(colony, amount, ReputationReason)` is the only
  mutator. Per-player (ruler) store plumbed but driven by no v1 mover.
- Movers: boss kill +10 (Orc Disaster/Ifrit, nearest colony), building
  built/upgraded +2, citizen attacked −5 (5 s combo dedupe), citizen
  killed by player −15 (via `LivingDeathEvent`, NOT
  `CitizenDiedModEvent` — that event lacks the killer). Envoys exempt.
- Visible: roster header tier line ("Loyal · 72", tier-coloured),
  envoy-dialogue tone sentence by tier (NEUTRAL appends nothing),
  `/reputation` + `/reputation set` debug commands.
- Records: `docs/reputation-system.md` (as-built),
  `docs/decisions.md` → "Reputation system v1" (locked decisions).

**Assassin system v1+v2 (latest):**
- Mistreated colonies (rep tier below WARY + avg happiness < 4) build a
  chosen citizen's determination (+1/day): LURKING at 2 (Great-Sage-only
  red nameplate; defused if the colony recovers), ARMED at 4 → strikes
  at the owner's first vulnerability (low HP / sleeping / no armor /
  festival start / just-prestiged). The Tensura body manifests as
  "<name>, the Betrayer": ×3 HP, ×2.5 spiritual, ×1.15 speed, ×2.5
  damage, purple boss bar, town-hall tether (32/48), colony cold-shoulder
  (trade + envoys). v2: killing the PLAYER steals half their base max EP
  (reversible stable-id modifiers; slay the boss to reclaim — offline
  reclaim on login) and copies their best skills (1/5/10/≤10 by type);
  resistances work passively, a curated whitelist of actives is cast
  every 5 s (CASTABLE_PRESS/TOGGLE sets in Assassins — extend via smoke
  testing). One assassin per colony EVER; `enableAssassins` config
  kill-switch; `/assassin state|arm|strike|defuse`. Record:
  docs/assassin-system.md.

**Reputation extras:** daily happiness drift toward a resting point
  (piecewise: h≥2 → 30+5×(h−2); punitive below 2 → down to 10 at h0;
  15%/day capped ±2), weighted buildings (amenities +4 / basic +2).

**Barrier/raid expansion (post-v1):** 4 Barrier Core tiers + Magicule
  Storage overflow blocks + square wall render w/ layers 1–3
  (DL/Hero-gated) + core menu; raids have difficulty LEVELS 1–3 scaled
  to colony strength (EP-primary, ×1.15 budget); hostile-spawn
  prevention inside fueled barriers. Records: docs/raid-system.md.

**Diplomacy Stage 1 (the builder's-path spine — latest):**
- `RelationsState {NONE, OPEN, PACT}` per (player, faction) in
  `DiplomacySavedData` behind the `DiplomacyManager` sole door (every
  standing write via WorldReputationManager, DIPLOMACY reason live).
  DIPLOMACY = OPEN (not band-locked); ALLIANCE = PACT via the
  alliance-pact milestone deal (offered at ALLIED 80+). Collapse:
  standing below WARY shatters relations (per-second check, derived
  purely from Layer-1 standing — the Orc Disaster clamp breaks Clayman
  relations for free).
- Entry: outbound Send-envoy tab button (± 8-gold gift +2, 1-day
  persisted reply, accept floor standing ≥ 20) + inbound
  `FactionEnvoyTag` Villager envoys (10%/day, min standing 40, 3-day
  cooldown), race-gated via `sendsEnvoysToHuman/Majin` on
  FactionProfile + isMajinSide (Holy bloc never sends to majin).
  `isDiplomacyClosed` is the first entry check.
- Deals: `DealSpec` registry (sealed Requirement: SupplyItems /
  BuildingLevel / Population / Happiness; LendCitizens = Stage 2 seam)
  + persisted `ActiveDeal` (ACTIVE → AWAITING_PAYOFF → READY; deadline
  fail −5). Detection: Deliver-button inventory consume, the existing
  BuildingConstructionModEvent hook + offer-time filter, 1s polls.
  Six starter deals incl. the pact. Movers: +2 open, +4/+6 success,
  +10 pact, −1 offer expiry; decay 0.5/day OPEN / 0.1 PACT, idle days,
  positive earned only. UI: [Roster | Diplomacy] tab strip →
  `DiplomacyScreen` (snapshot/action payloads, live refresh) +
  `FactionEnvoyScreen`. Debug `/diplomacy`. All behind
  `factionSystemEnabled`. Records: docs/diplomacy.md (as-built header).

**Orc Disaster lore event (Layer 2, consuming the faction model):**
- `LoreEvents` = the shared spine (descriptor map + `EncounterFactory`
  seam, nightfall trigger per online player, provocation arming via
  `isProvoked`, soft-influence roll `10% + 30%×hostility` — NO hard rep
  gate, recurrence + resolution consequences). The Orc Disaster plugs
  the raid engine in: `TensuraRaidEvent` gained NBT-optional
  `loreEventId`/`leadBossUuid` (absent = generic raid); a MARKED Geld
  ("Clayman's Orc Disaster") leads orc fodder + offense-scaled Orc Lord
  heavies; boss bar bound to Geld's HP; killing Geld breaks the horde
  (poof-flee + colony +8). The Layer-1 marked-kill fan-out fires the
  two-sided ripple automatically; the lore layer adds the
  forced-HOSTILE clamp + the RECOVERABLE diplomacy-closed flag (mending
  ritual deferred to the diplomacy build) + flavor. Timeout → 8-day
  cooldown; slain → never recurs; offense resets either way. Whole
  event behind `factionSystemEnabled`. Debug: `/tensuraraid disaster`.
  Charybdis/Ifrit deferred as future EncounterFactory plug-ins.
  Records: docs/lore-events.md (as-built header).

**Faction model v1 (expanded world-reputation spine):**
- Per-player × boss-faction standing is now `effective = clamp(liveBase
  + earnedDelta)`: the base comes from `FactionProfile` dispositions ×
  the player's CURRENT race side (5-step majin/human classifier over
  Tensura's Alignment + race tags + our shipped `human_side` race tag),
  so race changes shift the world's posture live. Stored value = earned
  delta (default 0).
- MARKED bosses only (`FactionMarkTag` attachment + faction-colored
  title): a marked kill fans out two-sided through
  `WorldReputationManager.applyMarkedBossKill` — victim faction
  −30×importance (KEYSTONE/MAJOR/NOTABLE/MINOR), allies −50% of that,
  enemies +40%, each × the target's swing multiplier. Attacks (−3×w,
  deduped) don't ripple. Wild/unmarked boss kills: zero faction effect
  (colony +10 + envoy unlocks unchanged). Offense ledger (+10/+1 ×w,
  no decay) + derived per-faction provocation thresholds.
- Config: `factionSystemEnabled` (default true) — whole faction layer
  dormant when off, colony-level systems untouched; `enableAssassins`
  is the separate assassin kill-switch. Debug: expanded `/worldrep`
  (base/earned/effective/offense/provoked) + `/worldrep mark`.
  Records: docs/faction-model.md (as-built header), decisions.md.

**Raid system v1:**
- Reputation-triggered Tensura raids: nightfall + tier below NEUTRAL →
  chance roll (WARY 15% / PASSIVEAGGRESSIVE 30% / HOSTILE 50%), 3-day
  cooldown. `TensuraRaidEvent implements IColonyRaidEvent` registered in
  MC's `colonyeventtypes` registry (citizen flee/hide + persistence free);
  mobs are plain Tensura MONSTER types (guards auto-engage) steered via
  WALK_TARGET + SmartBrainLib target assist. Single wave, boss bar,
  one-night timer. Victory → `modifyReputation(+8, RAID_REPELLED)`.
- Magicule barrier block: fueled BlockEntity (100k), cylinder field r=16,
  EP-scaled contact drain (`BARRIER_DRAIN_COEFFICIENT_PER_SECOND = 0.02`),
  player-magicule channel + magic-crystal refuel, falls at 0.
- Debug: `/tensuraraid`, `/tensuraraid end`. Records:
  `docs/raid-system.md`, decisions.md → "Raid system v1".

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
