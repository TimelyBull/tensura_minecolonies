package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.EntityUtils;
import io.github.manasmods.tensura.registry.entity.MonsterEntityTypes;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.tslat.smartbrainlib.util.BrainUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raid v1 driver — trigger scheduling, wave spawning, per-second steering,
 * and resolution for {@link TensuraRaidEvent}s. See docs/raid-system.md.
 *
 * <p>Called once per second from {@code ExampleMod.onServerTickPost}
 * (the same cadence as the envoy scheduler). Two passes per level:
 * <ol>
 *   <li><b>Drive</b> every active {@link TensuraRaidEvent}: prune dead
 *       raiders, steer the living toward the barrier / colony center via
 *       the brain's {@code WALK_TARGET} (the proven SubordinatePatrol
 *       technique), target-assist onto nearby citizens, update the boss
 *       bar, resolve victory / timeout.</li>
 *   <li><b>Trigger</b> on nightfall: colonies whose reputation tier sits
 *       below NEUTRAL roll a raid chance scaled by the tier, gated by a
 *       persisted per-colony cooldown and a one-active-raid check.</li>
 * </ol>
 */
public final class TensuraRaids {

    private static final Logger LOGGER = LoggerFactory.getLogger(TensuraRaids.class);

    // ------------------------------------------------------------------
    // Tuning constants (v1 starting values)
    // ------------------------------------------------------------------

    /** Raid duration — one in-game night. Past this the raid times out. */
    static final long RAID_DURATION_TICKS = 12_000L;
    /** Minimum gap between raid RESOLUTIONS at one colony — 3 in-game days. */
    static final long RAID_COOLDOWN_TICKS = 72_000L;
    /** Reputation reward for repelling a raid (all raiders dead in time). */
    static final double REP_RAID_REPELLED = +8.0;
    /** Per-night raid chance by reputation tier (below NEUTRAL only). */
    static final double RAID_CHANCE_WARY    = 0.15;
    static final double RAID_CHANCE_PASSIVE = 0.30;
    static final double RAID_CHANCE_HOSTILE = 0.50;
    // ------------------------------------------------------------------
    // Difficulty levels — colony strength → raid level 1/2/3
    // ------------------------------------------------------------------

    /** Raid budget = colony strength × this — the raid targets SLIGHTLY
     *  above the colony's measured strength (real but winnable). */
    static final double RAID_STRENGTH_COEFFICIENT = 1.15;
    /** Colony strength = total citizen EP (PRIMARY) + these secondaries. */
    static final double STRENGTH_PER_RAID_LEVEL = 200.0;  // MC building development
    static final double STRENGTH_PER_CITIZEN   = 100.0;   // population
    /** EP assumed for an UNLOADED vanilla citizen (no Tensura snapshot). */
    static final double UNLOADED_CITIZEN_EP = 200.0;
    /** Strength band thresholds: below L2 → Level 1, below L3 → Level 2,
     *  else Level 3. Tuning values. */
    static final double LEVEL_2_STRENGTH = 15_000.0;
    static final double LEVEL_3_STRENGTH = 60_000.0;
    /** Wave size clamps per raid level (index = level − 1). */
    static final int WAVE_MIN = 3;
    static final int[] LEVEL_WAVE_MAX = { 6, 10, 14 };
    /** EP assumed for a spawned raider whose existence can't be read
     *  (budget accounting fallback). */
    static final double FALLBACK_SPAWN_EP = 1_000.0;
    // ------------------------------------------------------------------
    // Orc Disaster — the first lore-event ENCOUNTER (the raid-engine
    // plug-in behind LoreEvents' EncounterFactory seam). Constants per
    // docs/lore-events.md #3, soft-influence form per faction-model #5.
    // ------------------------------------------------------------------

    static final String ORC_DISASTER_EVENT_ID = "orc_disaster";
    /** Lore budget coefficient = BASE + HOSTILITY × hostility01
     *  + min(OFFENSE_MAX, offense × PER_OFFENSE). The hostility term is
     *  CONTINUOUS (soft influence) — supersedes the old binary
     *  "+0.15 if HOSTILE tier". */
    static final double LORE_BUDGET_BASE = 1.15;
    static final double LORE_BUDGET_HOSTILITY = 0.15;
    static final double LORE_BUDGET_OFFENSE_MAX = 0.20;
    static final double LORE_BUDGET_PER_OFFENSE = 0.01;
    /** Composition: one Orc Lord heavy per this much offense, capped —
     *  how much you provoked them is visible in the horde itself. */
    static final double ORC_LORD_PER_OFFENSE = 25.0;
    static final int ORC_LORD_CAP = 3;

    // ------------------------------------------------------------------
    // Stage 3 — ALLY RAID-SUPPORT (the diplomacy ↔ raid bridge).
    // ⚠ BALANCE-CRITICAL, UNPLAYED SEAM: every magnitude below is a
    // first guess that needs playtest tuning — hence all named here.
    // ------------------------------------------------------------------

    /** Fighters each PACT-tier faction sends to a raid (the base). */
    static final int ALLY_SUPPORT_PER_PACT = 2;
    /** +1 fighter per this much standing above the ALLIED floor (80). */
    static final double ALLY_SUPPORT_STANDING_STEP = 10.0;
    /** Per-faction cap on sent fighters. */
    static final int ALLY_SUPPORT_MAX_PER_FACTION = 4;
    /** Total ally fighters per raid across ALL pact factions. */
    static final int ALLY_SUPPORT_TOTAL_CAP = 8;
    /** Falmuth's Covenant reward: its ally support is multiplied by
     *  this (war-faction specialty — markedly better than base PACT). */
    static final int FALMUTH_COVENANT_SUPPORT_MULT = 2;
    /** A COVENANT faction (any) sends this multiple of its PACT count
     *  — the alliance deepened into a covenant fields more help. */
    static final int COVENANT_SUPPORT_BONUS = 1;

    /** Which entity each faction sends — PASSIVE-category Tensura mobs
     *  on purpose: MineColonies guards auto-engage MONSTER-category
     *  types, and the orc horde proved passive mobs fight fine once the
     *  target-assist drives them. Unmapped factions fall back to
     *  goblin auxiliaries. */
    private static EntityType<?> allyTypeFor(String factionId) {
        String path = switch (factionId) {
            case "dwargon" -> "dwarf";
            case "milim", "carrion" -> "lizardman";
            default -> "goblin";
        };
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", path));
    }

    /** Day-time tick (mod 24000) at which night "begins" for the trigger. */
    private static final long NIGHT_START = 13_000L;
    /** Steering walk speed / close-enough, mirroring SubordinatePatrol. */
    private static final float RAID_WALK_SPEED = 1.1f;
    private static final int RAID_CLOSE_ENOUGH = 2;
    /** Target-assist range — max distance (blocks) from a raider to a
     *  roster citizen for the assist to lock on. Generous on purpose:
     *  small colonies put every citizen well inside one wave-approach. */
    private static final double TARGET_ASSIST_RADIUS = 64.0;

    // ------------------------------------------------------------------
    // Mob rosters by raid difficulty LEVEL (index = level − 1).
    // MobCategory.MONSTER types only, so MineColonies guard towers
    // auto-list them. (Tensura has no Ogre entity — the top tier uses
    // Knight Spider / Blade Tiger instead.)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob>[][] rosters() {
        return new EntityType[][] {
                { MonsterEntityTypes.GIANT_ANT.get(), MonsterEntityTypes.BLACK_SPIDER.get() },
                { MonsterEntityTypes.HOUND_DOG.get(), MonsterEntityTypes.EVIL_CENTIPEDE.get(),
                  MonsterEntityTypes.DIREWOLF.get() },
                { MonsterEntityTypes.KNIGHT_SPIDER.get(), MonsterEntityTypes.BLADE_TIGER.get(),
                  MonsterEntityTypes.EVIL_CENTIPEDE.get() },
        };
    }

    /** Roster lookup for {@link TensuraRaidEvent}'s raider-type getters. */
    static EntityType<?> rosterType(int tier, int slot) {
        EntityType<? extends Mob>[][] all = rosters();
        EntityType<? extends Mob>[] roster = all[Math.max(0, Math.min(tier, all.length - 1))];
        return roster[Math.max(0, Math.min(slot, roster.length - 1))];
    }

    // ------------------------------------------------------------------
    // Active-barrier registry — BarrierBlockEntity refreshes its entry
    // every tick while fueled; readers treat entries stale after 60 ticks
    // as gone (covers chunk unload / block break without explicit
    // deregistration).
    // ------------------------------------------------------------------

    /** One fueled barrier's registry entry — refresh time + field radius
     *  (per-tier, needed for footprint checks). */
    record BarrierEntry(long lastSeen, double radius) {}

    private static final Map<GlobalPos, BarrierEntry> ACTIVE_BARRIERS = new ConcurrentHashMap<>();
    private static final long BARRIER_STALE_TICKS = 60L;

    /** The barrier's "blocked hostiles" entity-type tag — our own
     *  {@code tensura_minecolonies:barrier_blocked}, which includes
     *  Tensura's curated attacks-on-sight {@code tensura:hostile_monster}
     *  tag PLUS the vanilla hostiles Tensura's tag omits (illagers,
     *  witches, phantoms, piglin brutes). Used by the field pushback AND
     *  the hostile-spawn prevention. NOT MobCategory.MONSTER, which
     *  wrongly includes goblins/orcs. */
    static final net.minecraft.tags.TagKey<EntityType<?>> HOSTILE_MONSTER_TAG =
            net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ENTITY_TYPE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            ExampleMod.MODID, "barrier_blocked"));

    static void reportActiveBarrier(ServerLevel level, BlockPos pos, double radius) {
        ACTIVE_BARRIERS.put(GlobalPos.of(level.dimension(), pos.immutable()),
                new BarrierEntry(level.getGameTime(), radius));
    }

    static void reportBarrierDown(ServerLevel level, BlockPos pos) {
        ACTIVE_BARRIERS.remove(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    /**
     * Hostile-only spawn prevention: true when (x, z) lies inside the
     * square footprint of ANY fueled barrier in this dimension. Uses the
     * same footprint definition as the field and the wall render
     * ({@link BarrierBlockEntity#isWithinFootprint}); "fueled" because
     * the registry only carries barriers with stored magicule > 0.
     */
    static boolean isInsideFueledBarrier(ServerLevel level, double x, double z) {
        long now = level.getGameTime();
        Iterator<Map.Entry<GlobalPos, BarrierEntry>> it = ACTIVE_BARRIERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, BarrierEntry> e = it.next();
            if (now - e.getValue().lastSeen() > BARRIER_STALE_TICKS) { it.remove(); continue; }
            if (!e.getKey().dimension().equals(level.dimension())) continue;
            if (BarrierBlockEntity.isWithinFootprint(e.getKey().pos(), e.getValue().radius(), x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Nearest fueled barrier within 160 blocks of {@code center}, or null. */
    static BlockPos nearestActiveBarrier(ServerLevel level, BlockPos center) {
        long now = level.getGameTime();
        BlockPos best = null;
        double bestDist = 160 * 160;
        Iterator<Map.Entry<GlobalPos, BarrierEntry>> it = ACTIVE_BARRIERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, BarrierEntry> e = it.next();
            if (now - e.getValue().lastSeen() > BARRIER_STALE_TICKS) { it.remove(); continue; }
            if (!e.getKey().dimension().equals(level.dimension())) continue;
            double d = e.getKey().pos().distSqr(center);
            if (d < bestDist) { bestDist = d; best = e.getKey().pos(); }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Per-dimension nightfall detection
    // ------------------------------------------------------------------

    private static final Map<ResourceKey<Level>, Long> lastDayPhase = new HashMap<>();

    private TensuraRaids() {}

    // ------------------------------------------------------------------
    // Entry point — once per second from ExampleMod.onServerTickPost
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<IColony> colonies = IColonyManager.getInstance().getColonies(level);
            if (colonies.isEmpty()) continue;

            boolean nightfall = detectNightfall(level);
            for (IColony colony : colonies) {
                try {
                    TensuraRaidEvent active = findActiveRaid(colony);
                    if (active != null) {
                        driveRaid(level, colony, active);
                    } else if (nightfall) {
                        maybeTriggerNightRaid(level, colony);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[TM] raid: tick failed for colony {}", colony.getID(), t);
                }
            }

            // Lore-event phase — per ONLINE player, after the colony-rep
            // phase (a colony that just drew a generic raid is no longer
            // eligible via the one-active-raid gate). Faction-gated inside.
            if (nightfall) {
                try {
                    LoreEvents.onNightfall(level);
                } catch (Throwable t) {
                    LOGGER.warn("[TM] lore: nightfall phase failed", t);
                }
            }
        }
    }

    /** True exactly once per dimension when day-time crosses into night. */
    private static boolean detectNightfall(ServerLevel level) {
        long phase = level.getDayTime() % 24_000L;
        Long prev = lastDayPhase.put(level.dimension(), phase);
        if (prev == null) return false;
        return prev < NIGHT_START && phase >= NIGHT_START;
    }

    /** The colony's active TensuraRaidEvent, or null. */
    static TensuraRaidEvent findActiveRaid(IColony colony) {
        if (colony == null) return null;
        for (IColonyEvent event : colony.getEventManager().getEvents().values()) {
            if (event instanceof TensuraRaidEvent raid
                    && raid.getStatus() != EventStatus.DONE
                    && raid.getStatus() != EventStatus.CANCELED) {
                return raid;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Trigger
    // ------------------------------------------------------------------

    private static void maybeTriggerNightRaid(ServerLevel level, IColony colony) {
        ReputationTier tier = ReputationManager.getTier(colony);
        double chance = switch (tier) {
            case HOSTILE -> RAID_CHANCE_HOSTILE;
            case PASSIVEAGGRESSIVE -> RAID_CHANCE_PASSIVE;
            case WARY -> RAID_CHANCE_WARY;
            default -> 0.0; // NEUTRAL and above never raid
        };
        if (chance <= 0.0) return;

        // Cooldown — measured from the last raid RESOLUTION at this colony.
        RaidSavedData saved = RaidSavedData.get(level);
        long lastResolve = saved.getLastRaidResolveTick(colony.getID(), Long.MIN_VALUE / 2);
        if (level.getGameTime() - lastResolve < RAID_COOLDOWN_TICKS) return;

        if (level.getRandom().nextDouble() >= chance) {
            LOGGER.info("[TM] raid: colony {} ('{}') rolled no raid tonight (tier {}, chance {})",
                    colony.getID(), colony.getName(), tier, chance);
            return;
        }
        startRaid(level, colony);
    }

    /**
     * Colony strength — the difficulty metric. PRIMARY: total citizen EP
     * (live entity reads for loaded citizens; transient snapshot
     * reconstruction for unloaded race-citizens — the dawn-restock /
     * citizen-trade pattern; a flat default for unloaded vanilla
     * citizens). SECONDARY: MC's building-development raid level and the
     * population, weighted in.
     */
    static double computeColonyStrength(ServerLevel level, IColony colony) {
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        double totalEP = 0;
        int citizens = 0;
        for (com.minecolonies.api.colony.ICitizenData data
                : colony.getCitizenManager().getCitizens()) {
            citizens++;
            net.minecraft.world.entity.LivingEntity live = data.getEntity().orElse(null);
            if (live != null) {
                ExistenceStorage exist = ExampleMod.readExistence(live);
                totalEP += exist != null && exist.getEP() > 0 ? exist.getEP() : UNLOADED_CITIZEN_EP;
                continue;
            }
            // Unloaded: our race-citizens carry a full entity snapshot —
            // reconstruct transiently (never added to the world) and read
            // the EP off the ManasCore storage, like the trade restock does.
            RaceIdentitySavedData.RaceIdentity identity = identities.getByCitizenId(data.getId());
            double ep = UNLOADED_CITIZEN_EP;
            if (identity != null && identity.entitySnapshot != null) {
                net.minecraft.world.entity.Entity ghost =
                        EntityType.create(identity.entitySnapshot, level).orElse(null);
                if (ghost instanceof net.minecraft.world.entity.LivingEntity ghostLiving) {
                    ExistenceStorage exist = ExampleMod.readExistence(ghostLiving);
                    if (exist != null && exist.getEP() > 0) ep = exist.getEP();
                    ghost.discard();
                }
            }
            totalEP += ep;
        }
        int raidLevel = colony.getRaiderManager().getColonyRaidLevel();
        double strength = totalEP
                + STRENGTH_PER_RAID_LEVEL * raidLevel
                + STRENGTH_PER_CITIZEN * citizens;
        LOGGER.info("[TM] raid: colony {} strength = {} (EP {} + raidLevel {}×{} + pop {}×{})",
                colony.getID(), String.format("%.0f", strength),
                String.format("%.0f", totalEP), raidLevel, (int) STRENGTH_PER_RAID_LEVEL,
                citizens, (int) STRENGTH_PER_CITIZEN);
        return strength;
    }

    /** Strength → raid difficulty level 1..3 (band thresholds above). */
    static int raidLevelForStrength(double strength) {
        if (strength >= LEVEL_3_STRENGTH) return 3;
        if (strength >= LEVEL_2_STRENGTH) return 2;
        return 1;
    }

    /**
     * Start a raid NOW (trigger roll already passed, or forced via
     * {@code /tensuraraid}). Difficulty is read from the colony's CURRENT
     * strength at trigger time: the strength picks the level (roster +
     * wave cap), and the wave is spawned mob by mob until the spawned
     * EP total reaches {@code strength × RAID_STRENGTH_COEFFICIENT} —
     * the raid meets and slightly exceeds the colony. Reputation governs
     * only WHETHER a raid fires, not its contents.
     */
    static TensuraRaidEvent startRaid(ServerLevel level, IColony colony) {
        BlockPos spawnPos = computeSpawnPos(level, colony);

        double strength = computeColonyStrength(level, colony);
        int raidLevel = raidLevelForStrength(strength);
        int tier = raidLevel - 1; // roster index
        double budget = strength * RAID_STRENGTH_COEFFICIENT;
        int waveCap = LEVEL_WAVE_MAX[tier];

        TensuraRaidEvent event = new TensuraRaidEvent(colony);
        event.setup(colony.getEventManager().getAndTakeNextEventID(),
                spawnPos,
                level.getGameTime() + RAID_DURATION_TICKS,
                tier);

        EntityType<? extends Mob>[] roster = rosters()[tier];
        int spawned = 0;
        double spawnedEP = 0;
        while (spawned < waveCap && (spawnedEP < budget || spawned < WAVE_MIN)) {
            EntityType<? extends Mob> type = roster[spawned % roster.length];
            Mob mob = spawnRaider(level, type, spawnPos, colony.getID(), event.getID());
            if (mob == null) break;
            event.addRaider(mob);
            spawned++;
            ExistenceStorage exist = ExampleMod.readExistence(mob);
            spawnedEP += exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_SPAWN_EP;
        }
        if (spawned == 0) {
            LOGGER.warn("[TM] raid: colony {} — no raiders could spawn, aborting raid", colony.getID());
            return null;
        }

        colony.getEventManager().addEvent(event);
        event.setStatus(EventStatus.PROGRESSING);
        spawnAllySupport(level, colony, event);

        LOGGER.info("[TM] raid: STARTED at colony {} ('{}') — LEVEL {} — wave {} ({} EP vs budget {}, rep {})",
                colony.getID(), colony.getName(), raidLevel, spawned,
                String.format("%.0f", spawnedEP), String.format("%.0f", budget),
                String.format("%.1f", ReputationManager.getReputation(colony)));
        messageColonyOwner(level, colony,
                Component.literal("Hostile monsters are massing against " + colony.getName()
                        + "! (Level " + raidLevel + " raid — the colony's low standing has drawn them.)")
                        .withStyle(net.minecraft.ChatFormatting.RED));
        return event;
    }

    /**
     * The Orc Disaster ENCOUNTER — LoreEvents' raid-engine plug-in
     * (EncounterFactory). A parameterized {@link TensuraRaidEvent}:
     * Geld (a real, MARKED OrcDisasterEntity) leads a horde of plain
     * orcs — RAID_TAG + the target-assist make the passive-natured
     * fodder genuinely hostile — with offense-scaled Orc Lord heavies.
     * Killing Geld breaks the horde (the bespoke resolution); the boss
     * bar binds to his HP. Everything else (steering, barrier, timeout,
     * persistence, citizen flee/hide) is the unchanged engine.
     */
    static TensuraRaidEvent startOrcDisaster(ServerLevel level, ServerPlayer player,
                                             IColony colony, LoreEvents.LoreEvent lore) {
        BlockPos spawnPos = computeSpawnPos(level, colony);

        double strength = computeColonyStrength(level, colony);
        int raidLevel = raidLevelForStrength(strength);
        int tier = raidLevel - 1;
        double offense = WorldReputationManager.getOffense(level, player.getUUID(), lore.faction());
        double standing = WorldReputationManager.getStanding(level, player.getUUID(), lore.faction());
        double coefficient = LORE_BUDGET_BASE
                + LORE_BUDGET_HOSTILITY * LoreEvents.hostility01(standing)
                + Math.min(LORE_BUDGET_OFFENSE_MAX, offense * LORE_BUDGET_PER_OFFENSE);
        double budget = strength * coefficient;
        int waveCap = LEVEL_WAVE_MAX[tier];
        int orcLords = (int) Math.min(ORC_LORD_CAP, offense / ORC_LORD_PER_OFFENSE);

        TensuraRaidEvent event = new TensuraRaidEvent(colony);
        event.setup(colony.getEventManager().getAndTakeNextEventID(),
                spawnPos,
                level.getGameTime() + RAID_DURATION_TICKS,
                tier);

        // The lead boss — a real OrcDisasterEntity, MARKED for Clayman
        // (faction-colored "Clayman's Orc Disaster" title: consequence
        // visible before the swing; the kill routes through the Layer-1
        // marked-kill fan-out automatically).
        Mob geld = spawnRaider(level, MonsterEntityTypes.ORC_DISASTER.get(),
                spawnPos, colony.getID(), event.getID());
        if (geld == null) {
            LOGGER.warn("[TM] lore: {} — lead boss could not spawn, aborting", lore.id());
            return null;
        }
        WorldReputationManager.markBoss(geld, lore.faction().id(), lore.id(), true);
        event.addRaider(geld);
        event.setLoreEvent(lore.id(), geld.getUUID());
        double spawnedEP = readSpawnEP(geld);
        int spawned = 1;

        // Offense-scaled heavies, then plain-orc fodder until the budget.
        for (int i = 0; i < orcLords && spawned < waveCap; i++) {
            Mob lord = spawnRaider(level, MonsterEntityTypes.ORC_LORD.get(),
                    spawnPos, colony.getID(), event.getID());
            if (lord == null) break;
            event.addRaider(lord);
            spawned++;
            spawnedEP += readSpawnEP(lord);
        }
        while (spawned < waveCap && (spawnedEP < budget || spawned < WAVE_MIN)) {
            Mob orc = spawnRaider(level, MonsterEntityTypes.ORC.get(),
                    spawnPos, colony.getID(), event.getID());
            if (orc == null) break;
            event.addRaider(orc);
            spawned++;
            spawnedEP += readSpawnEP(orc);
        }

        colony.getEventManager().addEvent(event);
        event.setStatus(EventStatus.PROGRESSING);
        spawnAllySupport(level, colony, event);

        LOGGER.info("[TM] lore: {} MARCHES on colony {} ('{}') — wave {} (+{} lords), {} EP vs budget {} (coeff {}, offense {}, standing {})",
                lore.id(), colony.getID(), colony.getName(), spawned, orcLords,
                String.format("%.0f", spawnedEP), String.format("%.0f", budget),
                String.format("%.2f", coefficient), String.format("%.1f", offense),
                String.format("%.1f", standing));
        messageColonyOwner(level, colony,
                Component.literal("Clayman's retribution marches on " + colony.getName()
                        + " — Geld, the Orc Disaster, leads the horde!")
                        .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        return event;
    }

    /** Budget accounting for one spawned raider. */
    private static double readSpawnEP(Mob mob) {
        ExistenceStorage exist = ExampleMod.readExistence(mob);
        return exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_SPAWN_EP;
    }

    /** MC's own raid spawn-point math, with fallbacks. */
    private static BlockPos computeSpawnPos(ServerLevel level, IColony colony) {
        BlockPos pos = null;
        try {
            pos = colony.getRaiderManager().calculateSpawnLocation();
        } catch (Throwable t) {
            LOGGER.warn("[TM] raid: calculateSpawnLocation threw, falling back", t);
        }
        if (pos == null) {
            pos = EntityUtils.getSpawnPoint(level, colony.getCenter().offset(32, 0, 32));
        }
        if (pos == null) {
            pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                    colony.getCenter().offset(32, 0, 32));
        }
        return pos;
    }

    /**
     * Stage 3 — ALLY SUPPORT: the raided player's PACT-tier factions
     * send friendly fighters (count scales with standing — the
     * ALLY_SUPPORT_* constants above). Called after every raid start
     * (generic AND lore events). Faction-gated inside via the manager.
     */
    static void spawnAllySupport(ServerLevel level, IColony colony, TensuraRaidEvent event) {
        if (!WorldReputationManager.isFactionSystemEnabled()) return;
        UUID owner = colony.getPermissions().getOwner();
        if (owner == null) return;
        BlockPos center = colony.getCenter();
        int total = 0;
        for (BossFaction faction : BossFaction.values()) {
            if (total >= ALLY_SUPPORT_TOTAL_CAP) break;
            // PACT and COVENANT factions both aid; lower tiers don't.
            RelationsState relState = DiplomacyManager.getState(level, owner, faction);
            if (relState != RelationsState.PACT && relState != RelationsState.COVENANT) continue;
            double standing = WorldReputationManager.getStanding(level, owner, faction);
            int count = ALLY_SUPPORT_PER_PACT + (int) Math.max(0,
                    (standing - FactionTier.ALLIED.minInclusive()) / ALLY_SUPPORT_STANDING_STEP);
            // Covenant fields more help; Falmuth's covenant specializes
            // in war support (its reward — markedly stronger than base).
            if (relState == RelationsState.COVENANT) {
                count += COVENANT_SUPPORT_BONUS;
                if (faction == BossFaction.FALMUTH) count *= FALMUTH_COVENANT_SUPPORT_MULT;
            }
            count = Math.min(count, faction == BossFaction.FALMUTH
                    && relState == RelationsState.COVENANT
                    ? ALLY_SUPPORT_MAX_PER_FACTION * FALMUTH_COVENANT_SUPPORT_MULT
                    : ALLY_SUPPORT_MAX_PER_FACTION);
            count = Math.min(count, ALLY_SUPPORT_TOTAL_CAP - total);
            int spawned = 0;
            for (int i = 0; i < count; i++) {
                EntityType<?> type = allyTypeFor(faction.id());
                Entity created = type.create(level);
                if (!(created instanceof Mob ally)) {
                    if (created != null) created.discard();
                    break;
                }
                int dx = level.getRandom().nextInt(13) - 6;
                int dz = level.getRandom().nextInt(13) - 6;
                BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                        center.offset(dx, 0, dz));
                ally.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                        level.getRandom().nextFloat() * 360f, 0f);
                ally.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                        MobSpawnType.SPAWN_EGG, null);
                ally.setPersistenceRequired();
                ally.setCustomName(net.minecraft.network.chat.Component
                        .literal(faction.displayName() + " Ally").withStyle(faction.color()));
                ally.setData(Attachments.ALLY_TAG.get(),
                        new AllyTag(colony.getID(), event.getID(), faction.id()));
                if (!level.addFreshEntity(ally)) break;
                event.addAlly(ally);
                spawned++;
                total++;
            }
            if (spawned > 0) {
                LOGGER.info("[TM] raid: ally support — {} sent {} fighters to colony {} (standing {})",
                        faction.id(), spawned, colony.getID(), String.format("%.0f", standing));
                messageColonyOwner(level, colony,
                        Component.literal(faction.displayName() + " honors the pact — "
                                + spawned + " fighters arrive to defend " + colony.getName() + "!")
                                .withStyle(faction.color()));
            }
        }
    }

    /** Per-second ally drive: keep each ally fighting the nearest living
     *  raider (the raiders' own dual-write idiom, inverted). */
    private static void steerAllies(ServerLevel level, TensuraRaidEvent event, List<Mob> raiders) {
        Iterator<UUID> it = event.allyUuids().iterator();
        while (it.hasNext()) {
            Entity e = level.getEntity(it.next());
            if (e == null) continue; // unloaded — keep
            if (e.isRemoved() || !(e instanceof Mob ally) || !ally.isAlive()) {
                it.remove();
                continue;
            }
            LivingEntity target = BrainUtils.getTargetOfEntity(ally);
            if (target != null && target.isAlive()
                    && target.hasData(Attachments.RAID_TAG.get())) {
                if (ally.getTarget() != target) ally.setTarget(target);
                continue;
            }
            Mob nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (Mob raider : raiders) {
                double d = raider.distanceToSqr(ally);
                if (d < bestDist) { bestDist = d; nearest = raider; }
            }
            if (nearest != null) {
                BrainUtils.setTargetOfEntity(ally, nearest);
                ally.setTarget(nearest);
            }
        }
    }

    /** Resolution cleanup — the allies go home (the envoy poof). */
    private static void dismissAllies(ServerLevel level, TensuraRaidEvent event) {
        for (UUID uuid : new ArrayList<>(event.allyUuids())) {
            Entity e = level.getEntity(uuid);
            if (e != null && !e.isRemoved()) {
                level.sendParticles(ParticleTypes.POOF,
                        e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ(),
                        24, 0.4, 0.4, 0.4, 0.02);
                e.discard();
            }
        }
        event.allyUuids().clear();
    }

    /** Envoy/population spawn pattern: create + finalizeSpawn + persist +
     *  RAID_TAG + addFreshEntity, scattered around the wave spawn point. */
    private static Mob spawnRaider(ServerLevel level, EntityType<? extends Mob> type,
                                   BlockPos around, int colonyId, int eventId) {
        Mob mob = type.create(level);
        if (mob == null) return null;
        int dx = level.getRandom().nextInt(9) - 4;
        int dz = level.getRandom().nextInt(9) - 4;
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, around.offset(dx, 0, dz));
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        // SPAWN_EGG (not EVENT/NATURAL): triggers Tensura's variant
        // randomisation in finalizeSpawn AND marks non-despawnable —
        // same reasoning as the envoy / population spawn paths.
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null);
        mob.setPersistenceRequired();
        // Highlight: raiders carry the vanilla glowing outline so the
        // defenders can track the wave through walls and darkness.
        mob.setGlowingTag(true);
        mob.setData(Attachments.RAID_TAG.get(), new RaidTag(colonyId, eventId));
        if (!level.addFreshEntity(mob)) {
            return null;
        }
        return mob;
    }

    // ------------------------------------------------------------------
    // Per-second drive: prune, steer, assist, resolve
    // ------------------------------------------------------------------

    private static void driveRaid(ServerLevel level, IColony colony, TensuraRaidEvent event) {
        long now = level.getGameTime();

        // Prune confirmed-dead raiders. An UNRESOLVED uuid (entity not
        // loaded) stays — it still counts as alive.
        List<Mob> living = new ArrayList<>();
        Iterator<UUID> it = event.raiderUuids().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Entity e = level.getEntity(uuid);
            if (e == null) continue; // unloaded — keep
            if (e.isRemoved() || !(e instanceof Mob mob) || !mob.isAlive()) {
                it.remove();
                continue;
            }
            living.add(mob);
        }

        // Victory — all raiders confirmed dead.
        if (event.raiderUuids().isEmpty()) {
            resolveVictory(level, colony, event);
            return;
        }

        // Timeout — the night is over; leftovers withdraw.
        if (now >= event.endTick()) {
            resolveTimeout(level, colony, event);
            return;
        }

        // Steer + target-assist the loaded raiders.
        BlockPos barrier = nearestActiveBarrier(level, colony.getCenter());
        BlockPos destination = barrier != null ? barrier : colony.getCenter();
        for (Mob mob : living) {
            steerRaider(level, mob, colony, destination);
        }

        // Stage 3 — keep the ally fighters on the raiders.
        steerAllies(level, event, living);

        event.updateRaidBar(level);
    }

    /**
     * One raider's per-second drive. Combat first: if the mob has no
     * live attack target, aim it at the nearest LOADED citizen of the
     * raided colony — searched through the colony's own citizen ROSTER
     * (not a small AABB scan, which missed citizens in compact colonies
     * where the wave spawns well outside the houses). The target is
     * written to BOTH the SmartBrain {@code ATTACK_TARGET} memory
     * (what Tensura's combat behaviours read) AND the vanilla
     * {@code Mob#setTarget} — Tensura's target-invalidation predicate
     * ({@code ISubordinate.shouldTarget}) short-circuits TRUE when
     * {@code mob.getTarget() == target}, so the vanilla write is what
     * keeps the brain from dropping a citizen it wouldn't normally
     * consider prey. Re-asserted every second by this pass.
     *
     * <p>Otherwise keep its {@code WALK_TARGET} fed toward the
     * destination (barrier first, else colony center), exactly like
     * SubordinatePatrol — Tensura's idle wander only fires when
     * WALK_TARGET is absent.
     */
    private static void steerRaider(ServerLevel level, Mob mob, IColony colony, BlockPos destination) {
        LivingEntity target = BrainUtils.getTargetOfEntity(mob);
        if (target != null && target.isAlive()) {
            // Keep the vanilla slot in sync so Tensura's invalidation
            // check (getTarget() == target) keeps approving the target.
            if (mob.getTarget() != target) mob.setTarget(target);
            return; // native combat owns the mob while it has a live target
        }

        // Target assist — nearest loaded citizen from the colony ROSTER.
        AbstractEntityCitizen nearest = null;
        double bestDist = TARGET_ASSIST_RADIUS * TARGET_ASSIST_RADIUS;
        for (com.minecolonies.api.colony.ICitizenData data
                : colony.getCitizenManager().getCitizens()) {
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || !citizen.isAlive()) continue;
            double d = citizen.distanceToSqr(mob);
            if (d < bestDist) { bestDist = d; nearest = citizen; }
        }
        if (nearest != null) {
            BrainUtils.setTargetOfEntity(mob, nearest);
            mob.setTarget(nearest);
            return;
        }

        // No combat — march on the destination (barrier first, else center).
        if (mob.blockPosition().distSqr(destination) > 9) {
            mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(destination, RAID_WALK_SPEED, RAID_CLOSE_ENOUGH));
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private static void resolveVictory(ServerLevel level, IColony colony, TensuraRaidEvent event) {
        dismissAllies(level, event);
        event.setStatus(EventStatus.DONE);
        event.onFinish();
        RaidSavedData.get(level).setLastRaidResolveTick(colony.getID(), level.getGameTime());
        double newRep = ReputationManager.modifyReputation(colony, REP_RAID_REPELLED,
                ReputationReason.RAID_REPELLED);
        LOGGER.info("[TM] raid: REPELLED at colony {} ('{}') — reputation now {}",
                colony.getID(), colony.getName(), String.format("%.1f", newRep));
        messageColonyOwner(level, colony,
                Component.literal("The raid on " + colony.getName()
                        + " has been repelled! Word of the defense spreads — reputation rises.")
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
    }

    /**
     * Lore-event bespoke resolution — the LEAD BOSS fell, so the horde
     * breaks and FLEES (the Disaster was the horde's will). The colony
     * is paid the standard repelled reward; the lore consequences
     * (defeated-forever, offense reset, clamp, diplomacy flag) are
     * applied by the caller ({@code LoreEvents.onPotentialLeadBossDeath}).
     */
    static void resolveLoreBreak(ServerLevel level, IColony colony, TensuraRaidEvent event) {
        for (UUID uuid : new ArrayList<>(event.raiderUuids())) {
            Entity e = level.getEntity(uuid);
            if (e != null && !e.isRemoved()) {
                level.sendParticles(ParticleTypes.POOF,
                        e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ(),
                        24, 0.4, 0.4, 0.4, 0.02);
                e.discard();
            }
            event.removeRaider(uuid);
        }
        level.playSound(null, colony.getCenter(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0f, 0.6f);
        dismissAllies(level, event);
        event.setStatus(EventStatus.DONE);
        event.onFinish();
        RaidSavedData.get(level).setLastRaidResolveTick(colony.getID(), level.getGameTime());
        double newRep = ReputationManager.modifyReputation(colony, REP_RAID_REPELLED,
                ReputationReason.RAID_REPELLED);
        LOGGER.info("[TM] lore: horde BROKEN at colony {} ('{}') — reputation now {}",
                colony.getID(), colony.getName(), String.format("%.1f", newRep));
        messageColonyOwner(level, colony,
                Component.literal("Geld has fallen! The horde breaks and flees "
                        + colony.getName() + " — the Disaster was its will.")
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
    }

    static void resolveTimeout(ServerLevel level, IColony colony, TensuraRaidEvent event) {
        // Leftover raiders withdraw with the envoy poof treatment.
        for (UUID uuid : new ArrayList<>(event.raiderUuids())) {
            Entity e = level.getEntity(uuid);
            if (e != null && !e.isRemoved()) {
                level.sendParticles(ParticleTypes.POOF,
                        e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ(),
                        24, 0.4, 0.4, 0.4, 0.02);
                e.discard();
            }
            event.removeRaider(uuid);
        }
        level.playSound(null, colony.getCenter(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        dismissAllies(level, event);
        event.setStatus(EventStatus.DONE);
        event.onFinish();
        RaidSavedData.get(level).setLastRaidResolveTick(colony.getID(), level.getGameTime());
        LOGGER.info("[TM] raid: TIMEOUT at colony {} ('{}') — leftover raiders withdrew",
                colony.getID(), colony.getName());
        if (event.isLoreEvent()) {
            // Lore recurrence: the march regroups (cooldown) and the
            // faction's retribution is spent (offense reset).
            LoreEvents.onTimeout(level, colony, event);
            messageColonyOwner(level, colony,
                    Component.literal("The Orc Disaster's horde withdraws from "
                            + colony.getName() + " into the night... for now.")
                            .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
            return;
        }
        messageColonyOwner(level, colony,
                Component.literal("The raiders besieging " + colony.getName()
                        + " have withdrawn into the night.")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** Raid-mob death bookkeeping — called from ExampleMod.onLivingDeath. */
    static void onRaidMobDeath(ServerLevel level, LivingEntity victim) {
        if (!victim.hasData(Attachments.RAID_TAG.get())) return;
        RaidTag tag = victim.getData(Attachments.RAID_TAG.get());
        if (tag == null) return;
        IColony colony = IColonyManager.getInstance().getColonyByWorld(tag.colonyId(), level);
        if (colony == null) return;
        TensuraRaidEvent event = findActiveRaid(colony);
        if (event != null && event.getID() == tag.eventId()) {
            event.removeRaider(victim.getUUID());
        }
    }

    /** True when the colony owning {@code pos} (closest colony) has an
     *  active Tensura raid — the barrier's "raid is on" gate. */
    static boolean isRaidActiveNear(ServerLevel level, BlockPos pos) {
        IColony colony = IColonyManager.getInstance().getClosestColony(level, pos);
        return colony != null && findActiveRaid(colony) != null;
    }

    private static void messageColonyOwner(ServerLevel level, IColony colony, Component message) {
        UUID owner = colony.getPermissions().getOwner();
        if (owner == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
