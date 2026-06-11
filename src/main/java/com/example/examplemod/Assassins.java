package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.tslat.smartbrainlib.util.BrainUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assassin system v1 — the social/manifestation layer. See
 * docs/assassin-system.md (v2 = EP theft + skill copy, NOT built here).
 *
 * Lifecycle: a mistreated colony (reputation below WARY AND average
 * happiness below {@link #HAPPINESS_LOW}) builds DETERMINATION on one of
 * its named race-citizens (+1/day, −1/day decay while not mistreated).
 * At {@link #LURK_THRESHOLD} the citizen turns LURKING (Great Sage
 * players see a red "Assassin" nameplate; raising happiness defuses).
 * At {@link #ARM_THRESHOLD} it's ARMED: the next time the colony OWNER
 * is vulnerable (any one of: low HP, sleeping, no armor, festival start,
 * just-prestiged) it STRIKES — the citizen body is replaced by the
 * Tensura body (or the live subordinate flips in place), ownership is
 * stripped, stat buffs + boss bar applied, target locked on the player.
 * Death cleanup rides the existing race-mob death hook.
 */
public final class Assassins {

    private static final Logger LOGGER = LoggerFactory.getLogger(Assassins.class);

    // ------------------------------------------------------------------
    // Tuning constants (user-confirmed)
    // ------------------------------------------------------------------

    /** Determination gained per in-game day while the colony qualifies. */
    static final double DETERMINATION_PER_DAY = 1.0;
    /** Determination lost per day (pre-LURKING) when it doesn't. */
    static final double DETERMINATION_DECAY_PER_DAY = 1.0;
    /** Days of misery to LURKING / to ARMED (guaranteed strike). */
    static final double LURK_THRESHOLD = 2.0;
    static final double ARM_THRESHOLD = 4.0;
    /** "Low happiness" bar (avg colony happiness, 0–10). */
    static final double HAPPINESS_LOW = 4.0;
    /** Boss stat multipliers (v1 power — stats only, no theft). */
    static final double BUFF_HEALTH = 3.0;
    static final double BUFF_SPIRITUAL_HEALTH = 2.5;
    static final double BUFF_SPEED = 1.15;
    static final double BUFF_DAMAGE = 2.5;
    /** Boss tether — the assassin haunts the colony: beyond this distance
     *  from the town hall it walks back; beyond +16 it also drops its
     *  chase (the patrol-recall pattern). */
    static final double TETHER_RADIUS = 32.0;
    /** Vulnerability: HP fraction at/below which the player is exposed. */
    static final double LOW_HP_FRACTION = 0.35;
    /** Festival-start / just-prestiged vulnerability window (ticks). */
    static final long EVENT_WINDOW_TICKS = 1_200L; // 60 s

    // ------------------------------------------------------------------
    // v2 — EP theft + skill copy (docs/assassin-system.md v2)
    // ------------------------------------------------------------------

    /** Fraction of the player's base max EP stolen on a successful kill. */
    static final double EP_STEAL_FRACTION = 0.5;
    /** Cast driver: cooldown between casts and max cast range. */
    static final long CAST_COOLDOWN_TICKS = 100L; // 5 s
    static final double CAST_RANGE = 24.0;
    /** Skill copy limits by Tensura SkillType (highest mastery first). */
    static final int COPY_UNIQUE = 1, COPY_EXTRA = 5, COPY_COMMON = 10, COPY_RESISTANCE = 10;

    /** Player-side theft modifiers (stable ids — removed on reclaim). */
    private static final ResourceLocation THEFT_MAGICULE_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_stolen_magicule");
    private static final ResourceLocation THEFT_AURA_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_stolen_aura");
    /** Assassin-side gain modifiers. */
    private static final ResourceLocation GAIN_MAGICULE_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_gained_magicule");
    private static final ResourceLocation GAIN_AURA_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_gained_aura");

    /**
     * CURATED CASTABLE WHITELIST (the v2 open task — extend via in-game
     * smoke testing). PRESS skills are fired by the cast driver; TOGGLE
     * buffs are switched on once at theft time. Copied skills NOT listed
     * here are held (flavor / future) but never cast.
     */
    private static final java.util.Set<ResourceLocation> CASTABLE_PRESS = java.util.Set.of(
            io.github.manasmods.tensura.registry.skill.CommonSkills.WATER_BLADE.getId(),
            io.github.manasmods.tensura.registry.skill.CommonSkills.VOICE_CANNON.getId(),
            io.github.manasmods.tensura.registry.skill.CommonSkills.PARALYSIS.getId(),
            io.github.manasmods.tensura.registry.skill.CommonSkills.POISON.getId(),
            io.github.manasmods.tensura.registry.skill.ExtraSkills.BLACK_FLAME.getId(),
            io.github.manasmods.tensura.registry.skill.ExtraSkills.BLACK_LIGHTNING.getId(),
            io.github.manasmods.tensura.registry.skill.ExtraSkills.HEAT_WAVE.getId());
    private static final java.util.Set<ResourceLocation> CASTABLE_TOGGLE = java.util.Set.of(
            io.github.manasmods.tensura.registry.skill.CommonSkills.STRENGTH.getId(),
            io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION.getId(),
            io.github.manasmods.tensura.registry.skill.ExtraSkills.MAGIC_AURA.getId());

    /** Per-assassin cast pacing (mob UUID → next allowed cast tick) and
     *  round-robin index. In-memory; resets on reload harmlessly. */
    private static final Map<UUID, Long> nextCastTick = new HashMap<>();
    private static final Map<UUID, Integer> castRotation = new HashMap<>();

    private static final ResourceLocation BUFF_HEALTH_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_health");
    private static final ResourceLocation BUFF_SPIRIT_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_spirit");
    private static final ResourceLocation BUFF_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_speed");
    private static final ResourceLocation BUFF_DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "assassin_damage");

    /** Player UUID → gameTime of festival-start / prestige (vulnerability
     *  windows). In-memory; a reload mid-window just closes it. */
    private static final Map<UUID, Long> festivalWindow = new HashMap<>();
    private static final Map<UUID, Long> prestigeWindow = new HashMap<>();

    /** Active boss bars by assassin mob UUID. Recreated lazily after
     *  reload by the per-second driver. */
    private static final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();

    private Assassins() {}

    // ------------------------------------------------------------------
    // Vulnerability windows (called from our own festival/prestige hooks)
    // ------------------------------------------------------------------

    static void markFestivalStart(ServerPlayer player) {
        festivalWindow.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    static void markPrestiged(ServerPlayer player) {
        prestigeWindow.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    /** ANY one window exposes the player (user-confirmed). */
    static boolean isVulnerable(ServerPlayer player) {
        if (player.getHealth() <= player.getMaxHealth() * LOW_HP_FRACTION) return true;
        if (player.isSleeping()) return true;
        boolean noArmor = player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
        if (noArmor) return true;
        long now = player.serverLevel().getGameTime();
        Long fest = festivalWindow.get(player.getUUID());
        if (fest != null && now - fest <= EVENT_WINDOW_TICKS) return true;
        Long prest = prestigeWindow.get(player.getUUID());
        return prest != null && now - prest <= EVENT_WINDOW_TICKS;
    }

    static boolean isColdShouldered(ServerLevel level, int colonyId) {
        return AssassinSavedData.get(level).isColdShouldered(colonyId);
    }

    // ------------------------------------------------------------------
    // Daily pass — determination buildup / decay / defuse.
    // Called per colony from the reputation drift loop (already daily).
    // ------------------------------------------------------------------

    static void tickDaily(ServerLevel level, IColony colony, double happiness) {
        AssassinSavedData data = AssassinSavedData.get(level);
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);

        // Config kill-switch: disabled → no buildup, and any existing
        // LURKING/ARMED plot for this colony defuses (treated exactly
        // like a recovered colony). ACTIVE bosses are left alone.
        boolean enabled = Config.ENABLE_ASSASSINS.get();

        boolean miserable = enabled
                && ReputationManager.isBelow(colony, ReputationTier.WARY)
                && happiness < HAPPINESS_LOW;

        // Find this colony's tracked candidate (at most one non-ACTIVE).
        RaceIdentitySavedData.RaceIdentity candidate = null;
        for (UUID id : data.trackedIdentities()) {
            RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
            if (identity == null) { data.clearIdentity(id); continue; } // prune stale
            if (identity.colonyId != colony.getID()) continue;
            if (data.getState(id) == AssassinSavedData.STATE_ACTIVE) continue;
            candidate = identity;
            break;
        }

        if (miserable) {
            if (candidate == null) {
                candidate = pickCandidate(identities, colony.getID());
                if (candidate == null) return; // no named race-citizens here
            }
            double det = data.getDetermination(candidate.identityId) + DETERMINATION_PER_DAY;
            data.setDetermination(candidate.identityId, det);
            byte state = data.getState(candidate.identityId);
            if (det >= ARM_THRESHOLD && state != AssassinSavedData.STATE_ARMED) {
                data.setState(candidate.identityId, AssassinSavedData.STATE_ARMED);
                LOGGER.info("[TM] assassin: identity {} (colony {}) is ARMED (determination {})",
                        candidate.identityId, colony.getID(), det);
            } else if (det >= LURK_THRESHOLD && state == AssassinSavedData.STATE_NONE) {
                data.setState(candidate.identityId, AssassinSavedData.STATE_LURKING);
                syncLurkFlag(level, candidate, true);
                LOGGER.info("[TM] assassin: identity {} (colony {}) is LURKING (determination {})",
                        candidate.identityId, colony.getID(), det);
            }
        } else if (candidate != null) {
            byte state = data.getState(candidate.identityId);
            if (state == AssassinSavedData.STATE_LURKING || state == AssassinSavedData.STATE_ARMED) {
                // DEFUSE — happiness recovered before the strike.
                data.clearIdentity(candidate.identityId);
                syncLurkFlag(level, candidate, false);
                LOGGER.info("[TM] assassin: identity {} (colony {}) DEFUSED — colony recovered",
                        candidate.identityId, colony.getID());
            } else {
                double det = data.getDetermination(candidate.identityId) - DETERMINATION_DECAY_PER_DAY;
                data.setDetermination(candidate.identityId, det);
            }
        }
    }

    private static RaceIdentitySavedData.RaceIdentity pickCandidate(
            RaceIdentitySavedData identities, int colonyId) {
        List<RaceIdentitySavedData.RaceIdentity> pool = new ArrayList<>();
        for (RaceIdentitySavedData.RaceIdentity identity : identities.all()) {
            if (identity.colonyId == colonyId) pool.add(identity);
        }
        if (pool.isEmpty()) return null;
        return pool.get(new java.util.Random().nextInt(pool.size()));
    }

    // ------------------------------------------------------------------
    // Per-second driver — ARMED strike checks + ACTIVE boss upkeep.
    // Called from the 1 s scheduler.
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            AssassinSavedData data = AssassinSavedData.get(level.getServer().overworld());
            if (level != server.overworld()) continue; // single SavedData walk
            RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);

            for (UUID id : data.trackedIdentities()) {
                RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
                if (identity == null) { data.clearIdentity(id); continue; }
                byte state = data.getState(id);
                try {
                    if (state == AssassinSavedData.STATE_ARMED) {
                        // Config kill-switch: no strikes while disabled
                        // (the daily pass will defuse the plot shortly).
                        if (!Config.ENABLE_ASSASSINS.get()) continue;
                        ServerPlayer owner = identity.ownerPlayerUUID == null ? null
                                : server.getPlayerList().getPlayer(identity.ownerPlayerUUID);
                        if (owner != null && isVulnerable(owner)) {
                            activate(owner.serverLevel(), identity, owner, data);
                        }
                    } else if (state == AssassinSavedData.STATE_ACTIVE) {
                        driveBoss(server, identity, data);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[TM] assassin: tick failed for identity {}", id, t);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Activation — the strike
    // ------------------------------------------------------------------

    private static void activate(ServerLevel level,
                                 RaceIdentitySavedData.RaceIdentity identity,
                                 ServerPlayer owner,
                                 AssassinSavedData data) {
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
        String name = "The Assassin";

        LivingEntity mob;
        if (identity.mode == RaceIdentitySavedData.Mode.IN_COLONY) {
            // Discard the citizen body; suppress MC's respawn loop; rebuild
            // the Tensura body from the snapshot BEHIND the player (no
            // circles, no cost — assassins don't announce themselves).
            if (colony != null) {
                ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
                if (cd != null) {
                    name = cd.getName();
                    cd.getEntity().ifPresent(Entity::discard);
                    colony.getTravellingManager().startTravellingTo(
                            cd, colony.getCenter(), Integer.MAX_VALUE);
                }
            }
            Entity created = EntityType.create(identity.entitySnapshot, level).orElse(null);
            if (!(created instanceof LivingEntity living)) {
                LOGGER.warn("[TM] assassin: snapshot reconstruction failed for {}", identity.identityId);
                data.clearIdentity(identity.identityId);
                return;
            }
            mob = living;
            mob.setUUID(UUID.randomUUID());
            Vec3 behind = owner.position().subtract(owner.getLookAngle().scale(2.0));
            mob.moveTo(behind.x, owner.getY(), behind.z, owner.getYRot(), 0);
            level.addFreshEntity(mob);
            identities.updateMobUUID(identity, mob.getUUID());
            identities.updateMode(identity, RaceIdentitySavedData.Mode.SUBORDINATE);
        } else {
            // Already at the player's side — the scariest case: flip in place.
            Entity e = level.getEntity(identity.mobEntityUUID);
            if (!(e instanceof LivingEntity living) || !living.isAlive()) {
                return; // body not loaded — strike waits for the next window
            }
            mob = living;
            name = mob.getName().getString();
        }

        // Strip ownership so Tensura's owner-protection can't veto the
        // target (investigation #1 wrinkle).
        try {
            ExistenceStorage exist = ExampleMod.readExistence(mob);
            if (exist != null) {
                exist.setPermanentOwner(null);
                exist.setTemporaryOwner(null);
                exist.markDirty();
            }
            if (mob instanceof net.minecraft.world.entity.TamableAnimal tamable) {
                tamable.setOwnerUUID(null);
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] assassin: ownership strip partial for {}", identity.identityId, t);
        }

        // Boss manifestation: tag, buffs (v1 power = stats only), heal,
        // hostile lock, bar, cold shoulder.
        mob.setData(Attachments.ASSASSIN_TAG.get(),
                new AssassinTag(identity.identityId, identity.colonyId, owner.getUUID()));
        multiplyAttribute(mob, Attributes.MAX_HEALTH, BUFF_HEALTH_ID, BUFF_HEALTH);
        try {
            multiplyAttribute(mob,
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_SPIRITUAL_HEALTH,
                    BUFF_SPIRIT_ID, BUFF_SPIRITUAL_HEALTH);
        } catch (Throwable ignored) { }
        multiplyAttribute(mob, Attributes.MOVEMENT_SPEED, BUFF_SPEED_ID, BUFF_SPEED);
        multiplyAttribute(mob, Attributes.ATTACK_DAMAGE, BUFF_DAMAGE_ID, BUFF_DAMAGE);
        mob.setHealth(mob.getMaxHealth());

        String bossName = name + ", the Betrayer";
        mob.setCustomName(Component.literal(bossName)
                .withStyle(net.minecraft.ChatFormatting.DARK_RED));
        mob.setCustomNameVisible(true);

        lockTarget(mob, owner);

        // Idle wander stays bounded near the town hall (the envoy
        // restrictTo pattern); the per-second tether handles chases.
        if (colony != null && mob instanceof Mob m) {
            m.restrictTo(colony.getCenter(), (int) TETHER_RADIUS);
        }

        data.setState(identity.identityId, AssassinSavedData.STATE_ACTIVE);
        data.setColdShoulder(identity.colonyId, true);
        syncLurkFlag(level, identity, false); // the red tell is over — it's here

        level.playSound(null, mob.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0f, 0.5f);
        owner.sendSystemMessage(Component.literal(
                name + " steps from the shadows — a blade meant for YOU. "
                + "The colony watches in silence.")
                .withStyle(net.minecraft.ChatFormatting.DARK_RED));
        LOGGER.info("[TM] assassin: ACTIVATED — identity {} ('{}') striking {} (colony {})",
                identity.identityId, bossName, owner.getName().getString(), identity.colonyId);
    }

    /** The raid target-assist dual-write: vanilla slot + brain memory. */
    private static void lockTarget(LivingEntity mob, ServerPlayer target) {
        if (mob instanceof Mob m) {
            m.setTarget(target);
            try {
                BrainUtils.setTargetOfEntity(m, target);
            } catch (Throwable ignored) { }
        }
    }

    /** ×factor as a stable-id ADD modifier (the stat-sync pattern):
     *  delta = current × (factor − 1); never compounds (remove-first). */
    private static void multiplyAttribute(LivingEntity mob, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                                          ResourceLocation id, double factor) {
        AttributeInstance instance = mob.getAttribute(attr);
        if (instance == null) return;
        instance.removeModifier(id);
        double delta = instance.getValue() * (factor - 1.0);
        instance.addPermanentModifier(new AttributeModifier(id, delta,
                AttributeModifier.Operation.ADD_VALUE));
    }

    // ------------------------------------------------------------------
    // ACTIVE boss upkeep — bar + hostility re-assert
    // ------------------------------------------------------------------

    private static void driveBoss(MinecraftServer server,
                                  RaceIdentitySavedData.RaceIdentity identity,
                                  AssassinSavedData data) {
        // Find the body in whatever level it's in (it follows the player).
        LivingEntity mob = null;
        ServerLevel mobLevel = null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(identity.mobEntityUUID);
            if (e instanceof LivingEntity living && living.isAlive()) {
                mob = living;
                mobLevel = level;
                break;
            }
        }
        if (mob == null) return; // unloaded — bar waits; death is handled by the hook

        AssassinTag tag = mob.getData(Attachments.ASSASSIN_TAG.get());

        // Town-hall tether — the assassin haunts the colony rather than
        // chasing across the world (the patrol-recall pattern). Beyond
        // the tether it walks back; well beyond, it also drops its chase.
        IColony colony = tag != null
                ? IColonyManager.getInstance().getColonyByWorld(tag.colonyId(), mobLevel) : null;
        boolean recalled = false;
        if (colony != null && mob instanceof Mob m) {
            net.minecraft.core.BlockPos center = colony.getCenter();
            double dist = Math.sqrt(mob.blockPosition().distSqr(center));
            if (dist > TETHER_RADIUS) {
                recalled = true;
                if (dist > TETHER_RADIUS + 16) {
                    m.setTarget(null); // stop the chase, come home
                }
                m.getBrain().setMemory(
                        net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                        new net.minecraft.world.entity.ai.memory.WalkTarget(center, 1.1f, 8));
            }
        }

        ServerPlayer target = tag != null && tag.targetPlayer() != null
                ? server.getPlayerList().getPlayer(tag.targetPlayer()) : null;
        if (!recalled && target != null && target.serverLevel() == mobLevel) {
            LivingEntity current = mob instanceof Mob m ? m.getTarget() : null;
            if (current == null || !current.isAlive()) {
                lockTarget(mob, target);
            }

            // v2 CAST DRIVER — face the target and fire one whitelisted
            // copied skill on a cooldown (costs draw from the assassin's
            // stolen magicule pool; canInteractSkill gates each attempt).
            if (mob.distanceToSqr(target) <= CAST_RANGE * CAST_RANGE
                    && tag.hasStolen()) {
                long now = mobLevel.getGameTime();
                if (now >= nextCastTick.getOrDefault(mob.getUUID(), 0L)) {
                    boolean cast = tryCastSkill(mob, target);
                    nextCastTick.put(mob.getUUID(),
                            now + (cast ? CAST_COOLDOWN_TICKS : 40L));
                }
            }
        }

        // Boss bar — progress = HP fraction; players within 64 see it.
        final LivingEntity barMob = mob;
        ServerBossEvent bar = bossBars.computeIfAbsent(mob.getUUID(), u ->
                new ServerBossEvent(
                        barMob.getCustomName() != null ? barMob.getCustomName()
                                : Component.literal("The Assassin"),
                        BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS));
        bar.setProgress(Math.max(0f, Math.min(1f, mob.getHealth() / mob.getMaxHealth())));
        for (ServerPlayer player : mobLevel.players()) {
            boolean near = player.distanceToSqr(mob) < 64 * 64;
            if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
            else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
        }
    }

    // ------------------------------------------------------------------
    // Death cleanup — called from onLivingDeath BEFORE the identity
    // cleanup (case A removes citizen-data + identity; both bodies are
    // already one body post-activation, so that's the whole spec).
    // ------------------------------------------------------------------

    static void onAssassinDeath(ServerLevel level, LivingEntity victim) {
        if (!victim.hasData(Attachments.ASSASSIN_TAG.get())) return;
        AssassinTag tag = victim.getData(Attachments.ASSASSIN_TAG.get());
        if (tag == null) return;
        ServerBossEvent bar = bossBars.remove(victim.getUUID());
        if (bar != null) bar.removeAllPlayers();
        nextCastTick.remove(victim.getUUID());
        castRotation.remove(victim.getUUID());
        AssassinSavedData data = AssassinSavedData.get(level);
        data.clearIdentity(tag.identityId());
        data.setColdShoulder(tag.colonyId(), false);

        // v2 RECLAIM — the boss's death restores everything it stole
        // (full restoration, user-confirmed). Offline victim → applied
        // on their next login.
        if (tag.hasStolen() && tag.targetPlayer() != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(tag.targetPlayer());
            if (player != null) {
                reclaimPower(player);
            } else {
                data.setPendingReclaim(tag.targetPlayer(), true);
            }
        }
        LOGGER.info("[TM] assassin: '{}' slain — colony {} stands down",
                victim.getName().getString(), tag.colonyId());
    }

    /**
     * One cast attempt: rotate through the assassin's learned PRESS-
     * whitelisted skills, fire the first that passes canInteractSkill.
     * The (1, 0) activation args = keybind slot 1, mode 0 — the defaults
     * a fresh skill instance uses; per-skill quirks are exactly what the
     * whitelist smoke-testing curates.
     */
    private static boolean tryCastSkill(LivingEntity mob, ServerPlayer target) {
        List<io.github.manasmods.manascore.skill.api.ManasSkillInstance> castable =
                new ArrayList<>();
        try {
            for (var instance : io.github.manasmods.manascore.skill.api.SkillAPI
                    .getSkillsFrom(mob).getLearnedSkills()) {
                if (CASTABLE_PRESS.contains(instance.getSkillId())) {
                    castable.add(instance);
                }
            }
        } catch (Throwable t) {
            return false;
        }
        if (castable.isEmpty()) return false;

        int start = castRotation.getOrDefault(mob.getUUID(), 0);
        for (int i = 0; i < castable.size(); i++) {
            int idx = (start + i) % castable.size();
            var instance = castable.get(idx);
            try {
                if (!instance.canInteractSkill(mob)) continue;
                // Face the target, then fire — skills read the caster's
                // look vector for aim (the barrier-pounding look pattern).
                if (mob instanceof Mob m) {
                    m.getLookControl().setLookAt(target, 30.0f, 30.0f);
                }
                mob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        target.getEyePosition());
                instance.onPressed(mob, 1, 0);
                castRotation.put(mob.getUUID(), idx + 1);
                LOGGER.debug("[TM] assassin: cast {} at {}", instance.getSkillId(),
                        target.getName().getString());
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[TM] assassin: cast of {} threw — consider removing it "
                        + "from the whitelist", instance.getSkillId(), t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // LURKING flag sync (the Great Sage red nameplate)
    // ------------------------------------------------------------------

    /** Push the lurk flag for this identity's CURRENT body to all
     *  tracking clients. */
    static void syncLurkFlag(ServerLevel level, RaceIdentitySavedData.RaceIdentity identity,
                             boolean lurking) {
        Entity body = resolveBody(level, identity);
        if (body != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(body,
                    new Networking.SyncAssassinFlagPayload(body.getUUID(), lurking));
        }
    }

    /** StartTracking resync — if this entity is a LURKING identity's
     *  body, tell the newly-tracking player. */
    static void resyncFlagOnTracking(ServerPlayer player, Entity target, ServerLevel level) {
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = identities.getByMobUUID(target.getUUID());
        if (identity == null && target instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen
                && citizen.getCitizenData() != null) {
            identity = identities.getByCitizenId(citizen.getCitizenData().getId());
        }
        if (identity == null) return;
        byte state = AssassinSavedData.get(level).getState(identity.identityId);
        if (state == AssassinSavedData.STATE_LURKING || state == AssassinSavedData.STATE_ARMED) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new Networking.SyncAssassinFlagPayload(target.getUUID(), true));
        }
    }

    // ------------------------------------------------------------------
    // v2 — successful assassination: EP theft + skill copy.
    // Called from onLivingDeath when an ASSASSIN-tagged mob kills a player.
    // ------------------------------------------------------------------

    static void onSuccessfulAssassination(ServerLevel level, LivingEntity assassin,
                                          ServerPlayer victim) {
        AssassinTag tag = assassin.getData(Attachments.ASSASSIN_TAG.get());
        if (tag == null || tag.hasStolen()) return; // steal once

        // ---- EP THEFT: half of base max EP = half magicule + half aura
        // (getBaseMaxEP is literally their sum). Negative stable-id
        // modifiers on the PLAYER (persist in player NBT, reversible),
        // positive mirrors on the assassin. The Stage-D3 attribute
        // machinery, negative delta.
        double stolenMagicule = 0, stolenAura = 0;
        try {
            stolenMagicule = io.github.manasmods.tensura.util.EnergyHelper
                    .getBaseMaxMagicule(victim) * EP_STEAL_FRACTION;
            stolenAura = io.github.manasmods.tensura.util.EnergyHelper
                    .getBaseMaxAura(victim) * EP_STEAL_FRACTION;
            applyAdd(victim,
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_MAGICULE,
                    THEFT_MAGICULE_ID, -stolenMagicule);
            applyAdd(victim,
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_AURA,
                    THEFT_AURA_ID, -stolenAura);
            // Clamp currents to the new maxes (cost-gate idiom); keep
            // magicule ≥ 1 so the Sleep Mode pipeline never fires.
            ExistenceStorage victimExist = ExampleMod.readExistence(victim);
            if (victimExist != null) {
                double maxMag = io.github.manasmods.tensura.util.EnergyHelper.getMaxMagicule(victim);
                double maxAura = io.github.manasmods.tensura.util.EnergyHelper.getMaxAura(victim);
                victimExist.setMagicule(Math.max(1.0, Math.min(victimExist.getMagicule(), maxMag)));
                victimExist.setAura(Math.max(0.0, Math.min(victimExist.getAura(), maxAura)));
                victimExist.markDirty();
            }
            // The assassin GAINS what was taken, pools refilled — its
            // casting (below) runs on your stolen magicule.
            applyAdd(assassin,
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_MAGICULE,
                    GAIN_MAGICULE_ID, stolenMagicule);
            applyAdd(assassin,
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_AURA,
                    GAIN_AURA_ID, stolenAura);
            ExistenceStorage assassinExist = ExampleMod.readExistence(assassin);
            if (assassinExist != null) {
                assassinExist.setMagicule(
                        io.github.manasmods.tensura.util.EnergyHelper.getMaxMagicule(assassin));
                assassinExist.setAura(
                        io.github.manasmods.tensura.util.EnergyHelper.getMaxAura(assassin));
                assassinExist.markDirty();
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] assassin: EP theft failed (continuing with skill copy)", t);
        }

        // ---- SKILL COPY: 1 unique / 5 extra / 10 common / ≤15 resistance,
        // highest mastery first; copies keep mastery (instance.copy()).
        List<String> stolenSkillNames = new ArrayList<>();
        try {
            stolenSkillNames = copySkills(victim, assassin);
        } catch (Throwable t) {
            LOGGER.warn("[TM] assassin: skill copy failed", t);
        }

        assassin.setData(Attachments.ASSASSIN_TAG.get(),
                tag.withStolen(stolenMagicule, stolenAura));

        victim.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                "%s has stolen your power — %,.0f magicule and %,.0f aura are GONE"
                + (stolenSkillNames.isEmpty() ? "" : ", and it has COPIED your skills: "
                        + String.join(", ", stolenSkillNames))
                + ". Slay it to reclaim what is yours.",
                assassin.getName().getString(), stolenMagicule, stolenAura))
                .withStyle(net.minecraft.ChatFormatting.DARK_RED));
        LOGGER.info("[TM] assassin: THEFT — {} took {} magicule / {} aura / {} skills from {}",
                assassin.getName().getString(),
                String.format("%.0f", stolenMagicule), String.format("%.0f", stolenAura),
                stolenSkillNames.size(), victim.getName().getString());
    }

    /** Copy the victim's best skills per category onto the assassin;
     *  switch on whitelisted toggles immediately. Returns display names. */
    private static List<String> copySkills(ServerPlayer victim, LivingEntity assassin) {
        var victimSkills = io.github.manasmods.manascore.skill.api.SkillAPI
                .getSkillsFrom(victim).getLearnedSkills();
        var assassinStorage = io.github.manasmods.manascore.skill.api.SkillAPI
                .getSkillsFrom(assassin);

        Map<io.github.manasmods.tensura.ability.skill.Skill.SkillType,
                List<io.github.manasmods.manascore.skill.api.ManasSkillInstance>> byType =
                new HashMap<>();
        for (var instance : victimSkills) {
            if (instance.getSkill() instanceof io.github.manasmods.tensura.ability.skill.Skill skill) {
                byType.computeIfAbsent(skill.getType(), k -> new ArrayList<>()).add(instance);
            }
        }
        List<String> names = new ArrayList<>();
        record Quota(io.github.manasmods.tensura.ability.skill.Skill.SkillType type, int limit) {}
        List<Quota> quotas = List.of(
                new Quota(io.github.manasmods.tensura.ability.skill.Skill.SkillType.UNIQUE, COPY_UNIQUE),
                new Quota(io.github.manasmods.tensura.ability.skill.Skill.SkillType.EXTRA, COPY_EXTRA),
                new Quota(io.github.manasmods.tensura.ability.skill.Skill.SkillType.COMMON, COPY_COMMON),
                new Quota(io.github.manasmods.tensura.ability.skill.Skill.SkillType.RESISTANCE, COPY_RESISTANCE));
        for (Quota quota : quotas) {
            List<io.github.manasmods.manascore.skill.api.ManasSkillInstance> pool =
                    byType.getOrDefault(quota.type(), List.of());
            pool.stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            io.github.manasmods.manascore.skill.api.ManasSkillInstance::getMastery)
                            .reversed())
                    .limit(quota.limit())
                    .forEach(instance -> {
                        try {
                            var copy = instance.copy();
                            assassinStorage.learnSkill(copy, Component.literal(""));
                            names.add(instance.getSkill().getName() != null
                                    ? instance.getSkill().getName().getString()
                                    : String.valueOf(instance.getSkillId()));
                            // Whitelisted toggle buffs switch on NOW.
                            if (CASTABLE_TOGGLE.contains(instance.getSkillId())) {
                                copy.onToggleOn(assassin);
                            }
                        } catch (Throwable t) {
                            LOGGER.warn("[TM] assassin: failed to copy skill {}",
                                    instance.getSkillId(), t);
                        }
                    });
        }
        return names;
    }

    /** Stable-id ADD modifier (remove-first; never compounds). */
    private static void applyAdd(LivingEntity entity,
                                 Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                                 ResourceLocation id, double delta) {
        AttributeInstance instance = entity.getAttribute(attr);
        if (instance == null) return;
        instance.removeModifier(id);
        instance.addPermanentModifier(new AttributeModifier(id, delta,
                AttributeModifier.Operation.ADD_VALUE));
    }

    /** Reclaim: strip the theft modifiers off the player — full
     *  restoration. Called on boss death (online) or next login. */
    static void reclaimPower(ServerPlayer player) {
        boolean removed = false;
        try {
            AttributeInstance mag = player.getAttribute(
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_MAGICULE);
            AttributeInstance aura = player.getAttribute(
                    io.github.manasmods.tensura.registry.attribute.TensuraAttributes.MAX_AURA);
            if (mag != null && mag.getModifier(THEFT_MAGICULE_ID) != null) {
                mag.removeModifier(THEFT_MAGICULE_ID);
                removed = true;
            }
            if (aura != null && aura.getModifier(THEFT_AURA_ID) != null) {
                aura.removeModifier(THEFT_AURA_ID);
                removed = true;
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] assassin: reclaim failed for {}", player.getName().getString(), t);
        }
        if (removed) {
            player.sendSystemMessage(Component.literal(
                    "Your stolen power floods back into you.")
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
            LOGGER.info("[TM] assassin: {} reclaimed their stolen EP",
                    player.getName().getString());
        }
    }

    /** Login hook — apply a reclaim owed while the player was offline. */
    static void onPlayerLogin(ServerPlayer player) {
        AssassinSavedData data = AssassinSavedData.get(player.serverLevel());
        if (data.hasPendingReclaim(player.getUUID())) {
            data.setPendingReclaim(player.getUUID(), false);
            reclaimPower(player);
        }
    }

    // ------------------------------------------------------------------
    // Debug helpers — the /assassin command (mirrors /envoystate's role)
    // ------------------------------------------------------------------

    /** Human-readable state lines for the colony's tracked identities. */
    static List<String> debugState(ServerLevel level, IColony colony) {
        AssassinSavedData data = AssassinSavedData.get(level);
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        List<String> out = new ArrayList<>();
        for (UUID id : data.trackedIdentities()) {
            RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
            if (identity == null || identity.colonyId != colony.getID()) continue;
            String stateName = switch (data.getState(id)) {
                case AssassinSavedData.STATE_LURKING -> "LURKING";
                case AssassinSavedData.STATE_ARMED -> "ARMED";
                case AssassinSavedData.STATE_ACTIVE -> "ACTIVE";
                default -> "building";
            };
            out.add(String.format(java.util.Locale.ROOT,
                    "identity %s — %s, determination %.1f (lurk at %.0f, armed at %.0f)",
                    id.toString().substring(0, 8), stateName,
                    data.getDetermination(id), LURK_THRESHOLD, ARM_THRESHOLD));
        }
        if (out.isEmpty()) {
            out.add("no assassin activity in '" + colony.getName() + "'");
            out.add(String.format(java.util.Locale.ROOT,
                    "(qualifies when rep tier below WARY AND happiness < %.1f — currently rep %.1f / happiness %.1f)",
                    HAPPINESS_LOW, ReputationManager.getReputation(colony),
                    colony.getOverallHappiness()));
        }
        out.add("cold shoulder: " + data.isColdShouldered(colony.getID()));
        return out;
    }

    /** Force the colony's candidate (picked if none) straight to ARMED. */
    static String debugArm(ServerLevel level, IColony colony) {
        if (!Config.ENABLE_ASSASSINS.get()) {
            return "assassin system is DISABLED in the config (enableAssassins=false)";
        }
        AssassinSavedData data = AssassinSavedData.get(level);
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity candidate = null;
        for (UUID id : data.trackedIdentities()) {
            RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
            if (identity != null && identity.colonyId == colony.getID()
                    && data.getState(id) != AssassinSavedData.STATE_ACTIVE) {
                candidate = identity;
                break;
            }
        }
        if (candidate == null) candidate = pickCandidate(identities, colony.getID());
        if (candidate == null) return "no named race-citizens in this colony to turn";
        data.setDetermination(candidate.identityId, ARM_THRESHOLD);
        data.setState(candidate.identityId, AssassinSavedData.STATE_ARMED);
        syncLurkFlag(level, candidate, true);
        return "identity " + candidate.identityId.toString().substring(0, 8)
                + " is now ARMED — it strikes at your next vulnerability"
                + " (low HP / sleep / no armor / festival / prestige)";
    }

    /** Clear every non-ACTIVE assassin entry for the colony. */
    static String debugDefuse(ServerLevel level, IColony colony) {
        AssassinSavedData data = AssassinSavedData.get(level);
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        int cleared = 0;
        for (UUID id : data.trackedIdentities()) {
            RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
            if (identity == null || identity.colonyId != colony.getID()) continue;
            if (data.getState(id) == AssassinSavedData.STATE_ACTIVE) continue;
            data.clearIdentity(id);
            syncLurkFlag(level, identity, false);
            cleared++;
        }
        return cleared > 0 ? "defused " + cleared + " plot(s)" : "nothing to defuse";
    }

    /** Force the ARMED candidate to strike NOW, ignoring vulnerability. */
    static String debugStrike(ServerLevel level, IColony colony, ServerPlayer owner) {
        AssassinSavedData data = AssassinSavedData.get(level);
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        for (UUID id : data.trackedIdentities()) {
            RaceIdentitySavedData.RaceIdentity identity = identities.getById(id);
            if (identity == null || identity.colonyId != colony.getID()) continue;
            if (data.getState(id) != AssassinSavedData.STATE_ARMED) continue;
            activate(owner.serverLevel(), identity, owner, data);
            return data.getState(id) == AssassinSavedData.STATE_ACTIVE
                    ? "the assassin strikes!"
                    : "strike attempted but the body wasn't available (subordinate unloaded?)";
        }
        return "no ARMED assassin in this colony — run /assassin arm first";
    }

    private static Entity resolveBody(ServerLevel level, RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
            return level.getEntity(identity.mobEntityUUID);
        }
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
        if (colony == null) return null;
        ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
        return cd == null ? null : cd.getEntity().orElse(null);
    }
}
