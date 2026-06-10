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
    /** Wave size clamps after scaling. */
    static final int WAVE_MIN = 3;
    static final int WAVE_MAX = 12;
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
    // Mob rosters by colony raid level (MobCategory.MONSTER types only,
    // so MineColonies guard towers auto-list them).
    //
    // DIVERGENCE from the investigation sketch: Tensura has no Ogre
    // entity — the top tier uses Knight Spider / Blade Tiger instead.
    // ------------------------------------------------------------------

    /** Colony raid level below which tier 0 applies; below 2× → tier 1. */
    private static final int TIER_1_RAID_LEVEL = 10;
    private static final int TIER_2_RAID_LEVEL = 20;

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

    private static final Map<GlobalPos, Long> ACTIVE_BARRIERS = new ConcurrentHashMap<>();
    private static final long BARRIER_STALE_TICKS = 60L;

    /** Tensura's curated "attacks on sight" entity-type tag — the
     *  classification the hostile-spawn prevention uses (NOT
     *  MobCategory.MONSTER, which wrongly includes goblins/orcs). */
    static final net.minecraft.tags.TagKey<EntityType<?>> HOSTILE_MONSTER_TAG =
            net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ENTITY_TYPE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", "hostile_monster"));

    static void reportActiveBarrier(ServerLevel level, BlockPos pos) {
        ACTIVE_BARRIERS.put(GlobalPos.of(level.dimension(), pos.immutable()), level.getGameTime());
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
        Iterator<Map.Entry<GlobalPos, Long>> it = ACTIVE_BARRIERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, Long> e = it.next();
            if (now - e.getValue() > BARRIER_STALE_TICKS) { it.remove(); continue; }
            if (!e.getKey().dimension().equals(level.dimension())) continue;
            if (BarrierBlockEntity.isWithinFootprint(e.getKey().pos(), x, z)) return true;
        }
        return false;
    }

    /** Nearest fueled barrier within 96 blocks of {@code center}, or null. */
    static BlockPos nearestActiveBarrier(ServerLevel level, BlockPos center) {
        long now = level.getGameTime();
        BlockPos best = null;
        double bestDist = 96 * 96;
        Iterator<Map.Entry<GlobalPos, Long>> it = ACTIVE_BARRIERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, Long> e = it.next();
            if (now - e.getValue() > BARRIER_STALE_TICKS) { it.remove(); continue; }
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
     * Start a raid NOW (trigger roll already passed, or forced via
     * {@code /tensuraraid}). Spawns the single v1 wave, registers the
     * event with MC's event manager, and announces it.
     */
    static TensuraRaidEvent startRaid(ServerLevel level, IColony colony) {
        BlockPos spawnPos = computeSpawnPos(level, colony);
        int raidLevel = colony.getRaiderManager().getColonyRaidLevel();
        int tier = raidLevel >= TIER_2_RAID_LEVEL ? 2
                 : raidLevel >= TIER_1_RAID_LEVEL ? 1 : 0;

        // Wave size: MC's own raid math × the reputation deficit (how far
        // below the NEUTRAL floor the colony sits → ×1.0 .. ×2.0).
        int base = Math.max(WAVE_MIN, colony.getRaiderManager().calculateRaiderAmount(raidLevel));
        double rep = ReputationManager.getReputation(colony);
        double neutralFloor = ReputationTier.NEUTRAL.minInclusive();
        double deficit = Math.max(0.0, Math.min(1.0, (neutralFloor - rep) / neutralFloor));
        int waveSize = (int) Math.round(base * (1.0 + deficit));
        waveSize = Math.max(WAVE_MIN, Math.min(WAVE_MAX, waveSize));

        TensuraRaidEvent event = new TensuraRaidEvent(colony);
        event.setup(colony.getEventManager().getAndTakeNextEventID(),
                spawnPos,
                level.getGameTime() + RAID_DURATION_TICKS,
                tier);

        EntityType<? extends Mob>[] roster = rosters()[tier];
        int spawned = 0;
        for (int i = 0; i < waveSize; i++) {
            EntityType<? extends Mob> type = roster[i % roster.length];
            Mob mob = spawnRaider(level, type, spawnPos, colony.getID(), event.getID());
            if (mob != null) {
                event.addRaider(mob);
                spawned++;
            }
        }
        if (spawned == 0) {
            LOGGER.warn("[TM] raid: colony {} — no raiders could spawn, aborting raid", colony.getID());
            return null;
        }

        colony.getEventManager().addEvent(event);
        event.setStatus(EventStatus.PROGRESSING);

        LOGGER.info("[TM] raid: STARTED at colony {} ('{}') — wave {} (tier {}, raidLevel {}, rep {})",
                colony.getID(), colony.getName(), spawned, tier, raidLevel,
                String.format("%.1f", ReputationManager.getReputation(colony)));
        messageColonyOwner(level, colony,
                Component.literal("Hostile monsters are massing against " + colony.getName()
                        + "! The colony's low standing has drawn a raid.")
                        .withStyle(net.minecraft.ChatFormatting.RED));
        return event;
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
        event.setStatus(EventStatus.DONE);
        event.onFinish();
        RaidSavedData.get(level).setLastRaidResolveTick(colony.getID(), level.getGameTime());
        LOGGER.info("[TM] raid: TIMEOUT at colony {} ('{}') — leftover raiders withdrew",
                colony.getID(), colony.getName());
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
