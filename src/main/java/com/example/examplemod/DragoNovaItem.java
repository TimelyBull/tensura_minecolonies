package com.example.examplemod;

import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.tensura.registry.skill.ExtraSkills;
import io.github.manasmods.tensura.registry.skill.UniqueSkills;
import io.github.manasmods.tensura.storage.ep.IExistence;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;


/**
 * Drago Nova — Milim's Covenant gift: a one-use area detonation
 * (obtainable once per REAL-LIFE hour via the Diplomacy tab).
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
    /** Magic damage dealt to everything caught in the blast. */
    public static final float DRAGO_NOVA_DAMAGE = 150.0f;
    /** Vanilla explosion power used when terrain damage is enabled. */
    public static final float DRAGO_NOVA_BLOCK_POWER = 8.0f;

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
        detonate(serverLevel, sp, stack, lethal);
        return InteractionResultHolder.consume(stack);
    }

    /** The confirm payload's path — re-resolve the held item and fire. */
    static void confirmAndDetonate(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof DragoNovaItem)) {
            held = player.getOffhandItem();
            if (!(held.getItem() instanceof DragoNovaItem)) return;
        }
        detonate(player.serverLevel(), player, held, !isWorthy(player));
    }

    private static void detonate(ServerLevel level, ServerPlayer user, ItemStack stack,
                                 boolean lethalToUser) {
        stack.shrink(1);
        double x = user.getX(), y = user.getY(), z = user.getZ();
        level.playSound(null, user.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE,
                SoundSource.PLAYERS, 4.0f, 0.6f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 1, z,
                8, DRAGO_NOVA_RADIUS / 3, 2, DRAGO_NOVA_RADIUS / 3, 0);
        boolean harmAllies = Config.DRAGO_NOVA_HARM_ALLIES.get();
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(user.position(), DRAGO_NOVA_RADIUS * 2, DRAGO_NOVA_RADIUS * 2,
                        DRAGO_NOVA_RADIUS * 2))) {
            if (target == user) continue;
            if (target.distanceToSqr(user) > DRAGO_NOVA_RADIUS * DRAGO_NOVA_RADIUS) continue;
            if (!harmAllies && isAllyOfUser(target)) continue;
            target.hurt(level.damageSources().magic(), DRAGO_NOVA_DAMAGE);
        }
        if (Config.DRAGO_NOVA_BREAK_BLOCKS.get()) {
            level.explode(user, x, y, z, DRAGO_NOVA_BLOCK_POWER,
                    Level.ExplosionInteraction.TNT);
        }
        if (lethalToUser) {
            user.hurt(level.damageSources().magic(), Float.MAX_VALUE);
        }
        ExampleMod.LOGGER.info("[TM] drago nova: detonated by {} (lethal {})",
                user.getGameProfile().getName(), lethalToUser);
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
