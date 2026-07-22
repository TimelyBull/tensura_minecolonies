package com.example.examplemod;

import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.tensura.registry.skill.ExtraSkills;
import io.github.manasmods.tensura.registry.skill.UniqueSkills;
import io.github.manasmods.tensura.storage.ep.IExistence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;


/**
 * Drago Nova — Milim's Covenant gift: a one-use area detonation
 * (obtainable once per REAL-LIFE hour via the Diplomacy tab).
 *
 * <p>Using it starts a ~2.5s charge-up ({@link #CHARGE_TICKS}): the item
 * lifts out of the hand and hovers, blue energy particles stream inward,
 * a blue bubble swells around it, and when it reaches head height a massive
 * blast fires. The charge is driven per-tick by {@link #tickCharges} from
 * the server tick handler; {@link #blast} is the detonation itself.
 *
 * <ul>
 *   <li>Never harms the USER... unless the user is NOT a true demon
 *       lord / true hero — then the blast claims THEM (Milim's power is
 *       not for the unworthy).</li>
 *   <li>Sage / Great Sage holders get a WARNING screen first (the
 *       collapse-confirm pattern) — the skill foresees the cost.</li>
 *   <li>Config: {@code dragoNovaHarmAllies} (allies/citizens caught in
 *       the blast?) and {@code dragoNovaBreakBlocks} (terrain damage?).</li>
 * </ul>
 */
public class DragoNovaItem extends Item {

    /** Blast radius (blocks) — tunable. */
    public static final double DRAGO_NOVA_RADIUS = 12.0;
    /** Base magic damage dealt to everything caught in the blast. The item deals
     *  exactly this; the Absolute Annihilator's charged ability passes a bigger,
     *  weapon-scaled value (see {@link #triggerAnnihilatorNova}). */
    public static final float DRAGO_NOVA_DAMAGE = 150.0f;
    /** Vanilla explosion power used when terrain damage is enabled. */
    public static final float DRAGO_NOVA_BLOCK_POWER = 8.0f;

    /** Charge-up duration (ticks) — the orb rises + the bubble grows over this,
     *  then it detonates. 50 ticks = 2.5 seconds. */
    public static final int CHARGE_TICKS = 50;
    /** How far the orb rises over the charge (blocks). */
    private static final double ORB_RISE = 1.2;
    /** Peak radius of the growing blue bubble (blocks). */
    private static final double BUBBLE_MAX_RADIUS = 2.0;
    /** Radius the converging blue particles stream in from (blocks). */
    private static final double CONVERGE_RADIUS = 4.5;

    /** One in-progress Drago Nova charge (the floating-orb ritual). */
    private static final class Charge {
        final UUID caster;
        final ResourceKey<Level> dimension;
        final ItemEntity orb;
        final double x, baseY, z;
        final boolean lethal;
        /** Blast damage this charge will deal — the flat item value, or a bigger
         *  weapon-scaled one when fired by the Absolute Annihilator. */
        final float damage;
        int ticks;
        Charge(UUID caster, ResourceKey<Level> dimension, ItemEntity orb,
               double x, double baseY, double z, boolean lethal, float damage) {
            this.caster = caster; this.dimension = dimension; this.orb = orb;
            this.x = x; this.baseY = baseY; this.z = z; this.lethal = lethal;
            this.damage = damage;
        }
    }

    /** All charges currently animating. Drained by {@link #tickCharges}. */
    private static final List<Charge> ACTIVE_CHARGES = new ArrayList<>();

    public DragoNovaItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.consume(stack);
        }
        boolean lethal = !isWorthy(sp);
        if (hasSageInsight(sp)) {
            // The Sage foresees what this will cost — confirm first. The
            // confirm payload routes back to confirmAndDetonate.
            PacketDistributor.sendToPlayer(sp,
                    new Networking.OpenDragoNovaWarningPayload(lethal));
            return InteractionResultHolder.consume(stack);
        }
        beginCharge(serverLevel, sp, stack, lethal, DRAGO_NOVA_DAMAGE);
        return InteractionResultHolder.consume(stack);
    }

    /** The confirm payload's path — re-resolve the held item and fire. */
    static void confirmAndDetonate(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof DragoNovaItem)) {
            held = player.getOffhandItem();
            if (!(held.getItem() instanceof DragoNovaItem)) return;
        }
        beginCharge(player.serverLevel(), player, held, !isWorthy(player), DRAGO_NOVA_DAMAGE);
    }

    /**
     * Begin the charge-up ritual instead of detonating instantly: spawn a
     * floating Drago Nova orb (a no-gravity, un-pickable {@link ItemEntity})
     * that rises while blue particles stream inward and a bubble swells around
     * it, then detonates at the top of the rise. Driven by {@link #tickCharges}.
     */
    private static void beginCharge(ServerLevel level, ServerPlayer user, ItemStack stack,
                                    boolean lethalToUser, float damage) {
        stack.shrink(1);
        startCharge(level, user, lethalToUser, damage);
    }

    /**
     * Start the charge-up ritual WITHOUT consuming any item — the shared core
     * used both by the Drago Nova item and by the Absolute Annihilator's
     * charged ability (see {@link #triggerAnnihilatorNova}).
     */
    static void startCharge(ServerLevel level, ServerPlayer user, boolean lethalToUser, float damage) {
        double x = user.getX();
        double baseY = user.getY() + 0.6;   // starts around waist height
        double z = user.getZ();
        ItemEntity orb = new ItemEntity(level, x, baseY, z,
                new ItemStack(ExampleMod.DRAGO_NOVA.get()));
        orb.setNoGravity(true);
        orb.setDeltaMovement(Vec3.ZERO);
        orb.setNeverPickUp();
        orb.setUnlimitedLifetime();
        orb.setInvulnerable(true);
        level.addFreshEntity(orb);
        ACTIVE_CHARGES.add(new Charge(user.getUUID(), level.dimension(), orb, x, baseY, z,
                lethalToUser, damage));
        level.playSound(null, user.blockPosition(), SoundEvents.CONDUIT_ACTIVATE,
                SoundSource.PLAYERS, 2.0f, 0.7f);
        ExampleMod.LOGGER.info("[TM] drago nova: charge begun by {} (lethal {})",
                user.getGameProfile().getName(), lethalToUser);
    }

    /**
     * Fire the Drago Nova charge-up from the Absolute Annihilator's charged
     * ability. Same effect as the Drago Nova item (worthiness/lethal check
     * included) but consumes nothing — the caller applies the cooldown and
     * passes the weapon-scaled blast damage.
     */
    static void triggerAnnihilatorNova(ServerLevel level, ServerPlayer user, float damage) {
        startCharge(level, user, !isWorthy(user), damage);
    }

    /**
     * Per-tick driver for every in-progress charge — called each server tick
     * from {@code ExampleMod.onServerTickPost}. Rises the orb, spawns the
     * converging particles + growing bubble, and detonates when the charge
     * completes. No-op (cheap early return) when nothing is charging.
     */
    static void tickCharges(MinecraftServer server) {
        if (ACTIVE_CHARGES.isEmpty()) return;
        Iterator<Charge> it = ACTIVE_CHARGES.iterator();
        while (it.hasNext()) {
            Charge c = it.next();
            ServerLevel level = server.getLevel(c.dimension);
            if (level == null || c.orb == null || !c.orb.isAlive()) {
                if (c.orb != null) c.orb.discard();
                it.remove();
                continue;
            }
            c.ticks++;
            double progress = Math.min(1.0, c.ticks / (double) CHARGE_TICKS);
            double orbY = c.baseY + ORB_RISE * progress;
            // Pin the orb to its rising point — no drift, no gravity.
            c.orb.setPos(c.x, orbY, c.z);
            c.orb.setDeltaMovement(Vec3.ZERO);
            c.orb.setNoGravity(true);
            spawnChargeParticles(level, c.x, orbY, c.z, progress);
            if (c.ticks % 12 == 0) {
                level.playSound(null, BlockPos.containing(c.x, orbY, c.z),
                        SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS,
                        1.2f, 0.5f + 0.8f * (float) progress);
            }
            if (c.ticks >= CHARGE_TICKS) {
                c.orb.discard();
                ServerPlayer caster = server.getPlayerList().getPlayer(c.caster);
                blast(level, caster, c.lethal, c.x, orbY, c.z, c.damage);
                it.remove();
            }
        }
    }

    /** Blue particles streaming inward + a growing blue bubble shell around the orb. */
    private static void spawnChargeParticles(ServerLevel level, double x, double y, double z,
                                             double progress) {
        RandomSource rnd = level.random;
        // 1) Converging streams — spawn on an outer shell, velocity aimed at the orb.
        //    count == 0 makes the (dx,dy,dz) the velocity, scaled by the last arg.
        for (int i = 0; i < 8; i++) {
            double[] dir = randomUnit(rnd);
            double px = x + dir[0] * CONVERGE_RADIUS;
            double py = y + dir[1] * CONVERGE_RADIUS;
            double pz = z + dir[2] * CONVERGE_RADIUS;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, px, py, pz,
                    0, -dir[0], -dir[1], -dir[2], 0.35);
        }
        // 2) Growing bubble — a shell of soft blue orbs whose radius tracks progress.
        double r = BUBBLE_MAX_RADIUS * progress;
        int shell = 14 + (int) (progress * 20);
        for (int i = 0; i < shell; i++) {
            double[] dir = randomUnit(rnd);
            level.sendParticles(ParticleTypes.GLOW,
                    x + dir[0] * r, y + dir[1] * r, z + dir[2] * r,
                    1, 0, 0, 0, 0);
        }
        // 3) Core glow right at the orb.
        level.sendParticles(ParticleTypes.SOUL, x, y, z, 2, 0.1, 0.1, 0.1, 0.01);
    }

    /** A uniformly-random unit vector on the sphere. */
    private static double[] randomUnit(RandomSource rnd) {
        double theta = rnd.nextDouble() * Math.PI * 2.0;
        double u = rnd.nextDouble() * 2.0 - 1.0;
        double s = Math.sqrt(Math.max(0.0, 1.0 - u * u));
        return new double[]{ s * Math.cos(theta), u, s * Math.sin(theta) };
    }

    /**
     * The actual detonation, fired at the orb's position (x,y,z) at the end of
     * the charge. {@code user} may be null if they logged out mid-charge — the
     * AoE still fires; only the lethal-to-user backlash is skipped.
     */
    private static void blast(ServerLevel level, ServerPlayer user, boolean lethalToUser,
                              double x, double y, double z, float damage) {
        level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.DRAGON_FIREBALL_EXPLODE,
                SoundSource.PLAYERS, 4.0f, 0.6f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z,
                8, DRAGO_NOVA_RADIUS / 3, 2, DRAGO_NOVA_RADIUS / 3, 0);
        boolean harmAllies = Config.DRAGO_NOVA_HARM_ALLIES.get();
        Vec3 center = new Vec3(x, y, z);
        // Credit the caster when they're still around: an ownerless source is
        // treated as environmental damage — no kill credit, no EP, and none of
        // Tensura's attacker-aware handling.
        var source = user != null
                ? WeaponAbilities.magicSource(level, user)
                : level.damageSources().magic();
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(center, DRAGO_NOVA_RADIUS * 2, DRAGO_NOVA_RADIUS * 2,
                        DRAGO_NOVA_RADIUS * 2))) {
            if (user != null && target == user) continue;
            if (target.distanceToSqr(x, y, z) > DRAGO_NOVA_RADIUS * DRAGO_NOVA_RADIUS) continue;
            if (!harmAllies && isAllyOfUser(target)) continue;
            target.invulnerableTime = 0;
            target.hurt(source, damage);
        }
        if (Config.DRAGO_NOVA_BREAK_BLOCKS.get()) {
            level.explode(user, x, y, z, DRAGO_NOVA_BLOCK_POWER, Level.ExplosionInteraction.TNT);
        }
        if (lethalToUser && user != null) {
            user.hurt(level.damageSources().magic(), Float.MAX_VALUE);
        }
        ExampleMod.LOGGER.info("[TM] drago nova: detonated at ({}, {}, {}) damage={} lethal={}",
                String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
                String.format("%.1f", damage), lethalToUser);
    }

    private static boolean isAllyOfUser(LivingEntity target) {
        return target instanceof Player
                || target instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen
                || target.hasData(Attachments.ALLY_TAG.get())
                || target.hasData(Attachments.RACE_TAG.get());
    }

    /** True demon lord or true hero — the only safe wielders. */
    private static boolean isWorthy(ServerPlayer player) {
        try {
            IExistence ex = ExampleMod.readExistenceSafe(player);
            return ex != null && (ex.isTrueDemonLord() || ex.isTrueHero());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sage or Great Sage — the warning-screen gate. */
    private static boolean hasSageInsight(ServerPlayer player) {
        try {
            for (var instance : SkillAPI.getSkillsFrom(player).getLearnedSkills()) {
                var skill = instance.getSkill();
                if (skill == ExtraSkills.SAGE.get() || skill == UniqueSkills.GREAT_SAGE.get()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}
