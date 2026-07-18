package com.example.examplemod;

import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.tensura.ability.battlewill.Battlewill;
import io.github.manasmods.tensura.ability.magic.Magic;
import io.github.manasmods.tensura.storage.ep.IExistence;
import io.github.manasmods.tensura.util.EnergyHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Masterwork weapon line (Dwargon Covenant) — a "legendary" weapon that
 * grows with the wielder. See docs/future-ideas.md for the full design.
 *
 * <ul>
 *   <li>EP growth + EP-backed self-repair — its gear_existence entry (native).
 *       Prestige = emergent: when EP drops the evolution tier drops (damage
 *       falls to the base floor of ~10) and climbs back as EP regrows.</li>
 *   <li>Alignment on-hit ({@link #hurtEnemy}) — MAJIN wielder → slight lifesteal
 *       + a dark burst; NON-MAJIN → brief Regeneration + a light burst.</li>
 *   <li>Branch right-click ({@link #use}) — from the wielder's mastered
 *       Battlewill-vs-Magic spread: PHYSICAL → a sweep (drains aura); MAGIC → a
 *       forward magic slice (drains magicule); BALANCED → no ability.</li>
 *   <li>Mastered-skill QOL ({@link #inventoryTick}) — 10+ magnet, 15+ step
 *       assist; 20+ soulbound (handled in {@code ExampleMod} death/clone hooks).</li>
 * </ul>
 */
public class MasterworkItem extends SwordItem {

    private static final int ABILITY_COOLDOWN = 20 * 30;   // 30 s
    private static final double SWEEP_RADIUS = 3.5;
    private static final double SLICE_RANGE = 9.0;
    /** Ability cost = this fraction of the wielder's MAX aura/magicule (min 100). */
    private static final double ENERGY_COST_FRACTION = 0.05;
    private static final double ENERGY_COST_MIN = 100.0;

    public static final int MAGNET_MASTERED = 10;
    public static final int STEP_MASTERED = 15;
    public static final int SOULBOUND_MASTERED = 20;

    private static final ResourceLocation STEP_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "masterwork_step");

    public enum Branch { PHYSICAL, MAGIC, BALANCED }

    public MasterworkItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    // ------------------------------------------------------------------
    // On-hit — alignment form (majin lifesteal / non-majin regen).
    // ------------------------------------------------------------------
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.level() instanceof ServerLevel level && attacker instanceof ServerPlayer sp) {
            double x = target.getX(), y = target.getY() + target.getBbHeight() * 0.6, z = target.getZ();
            if (WorldReputationManager.isMajinSide(sp)) {
                float heal = (float) (0.05 * attacker.getAttributeValue(Attributes.ATTACK_DAMAGE));
                if (heal > 0) attacker.heal(heal);
                level.sendParticles(ParticleTypes.SMOKE, x, y, z, 12, 0.25, 0.35, 0.25, 0.01);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 4, 0.2, 0.3, 0.2, 0.01);
            } else {
                attacker.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0));
                level.sendParticles(ParticleTypes.END_ROD, x, y, z, 12, 0.25, 0.35, 0.25, 0.02);
            }
        }
        return super.hurtEnemy(stack, target, attacker);
    }

    // ------------------------------------------------------------------
    // Right-click — branch ability (physical sweep / magic slice).
    // ------------------------------------------------------------------
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.pass(stack);
        }
        Branch branch = branchOf(sp);
        if (branch == Branch.BALANCED) return InteractionResultHolder.pass(stack);
        if (player.getCooldowns().isOnCooldown(stack.getItem())) return InteractionResultHolder.pass(stack);

        if (branch == Branch.PHYSICAL) {
            double cost = energyCost(EnergyHelper.getMaxAura(sp));
            if (auraOf(sp) < cost) { notEnough(sp, "aura"); return InteractionResultHolder.pass(stack); }
            EnergyHelper.gainAura(sp, -cost, EnergyHelper.GainType.NORMAL);
            doSweep(serverLevel, sp);
        } else {
            double cost = energyCost(EnergyHelper.getMaxMagicule(sp));
            if (magiculeOf(sp) < cost) { notEnough(sp, "magicule"); return InteractionResultHolder.pass(stack); }
            EnergyHelper.gainMagicule(sp, -cost, EnergyHelper.GainType.NORMAL);
            doMagicSlice(serverLevel, sp);
        }
        player.getCooldowns().addCooldown(stack.getItem(), ABILITY_COOLDOWN);
        return InteractionResultHolder.success(stack);
    }

    /** PHYSICAL branch — an arcing sweep in front that damages + knocks back. */
    private void doSweep(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 center = player.position().add(look.x * 2.0, 1.0, look.z * 2.0);
        float dmg = (float) (0.6 * player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS, 1.2f, 0.8f);
        // sweeping-edge particle arc spread in front
        for (int i = -4; i <= 4; i++) {
            double ang = Math.toRadians(i * 14);
            Vec3 dir = look.yRot((float) ang);
            Vec3 p = player.position().add(dir.x * 2.2, 1.0, dir.z * 2.2);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(center, SWEEP_RADIUS * 2, 3, SWEEP_RADIUS * 2))) {
            if (e == player || isAlly(e)) continue;
            if (e.distanceToSqr(center) > SWEEP_RADIUS * SWEEP_RADIUS) continue;
            e.hurt(level.damageSources().playerAttack(player), dmg);
            e.knockback(0.5, player.getX() - e.getX(), player.getZ() - e.getZ());
        }
    }

    /** MAGIC branch — a slice of energy that flies forward, cutting what it passes. */
    private void doMagicSlice(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 start = player.getEyePosition();
        float dmg = (float) (0.8 * player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG,
                SoundSource.PLAYERS, 1.2f, 1.4f);
        for (double d = 1.0; d <= SLICE_RANGE; d += 0.5) {
            Vec3 p = start.add(look.scale(d));
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
            level.sendParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
        }
        Vec3 end = start.add(look.scale(SLICE_RANGE));
        AABB box = new AABB(start, end).inflate(1.5);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == player || isAlly(e)) continue;
            // near the ray?
            Vec3 toE = e.getBoundingBox().getCenter().subtract(start);
            double t = Math.max(0, Math.min(SLICE_RANGE, toE.dot(look)));
            if (start.add(look.scale(t)).distanceTo(e.getBoundingBox().getCenter()) > 1.6) continue;
            e.hurt(level.damageSources().magic(), dmg);
        }
    }

    // ------------------------------------------------------------------
    // QOL passives — magnet (10+) + step assist (15+). Soulbound (20+) is in
    // ExampleMod's death/clone hooks. Throttled to every 10 ticks.
    // ------------------------------------------------------------------
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer sp)) return;
        if (sp.tickCount % 10 != 0) return;
        int mastered = masteredCount(sp);
        if (mastered >= MAGNET_MASTERED) pullNearbyItems(sp);
        applyStepAssist(sp, selected && mastered >= STEP_MASTERED);
    }

    private static void pullNearbyItems(ServerPlayer player) {
        for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(6.0))) {
            if (item.hasPickUpDelay() || !item.isAlive()) continue;
            Vec3 pull = player.position().add(0, 0.4, 0).subtract(item.position());
            if (pull.lengthSqr() < 1.0) continue;
            item.setDeltaMovement(pull.normalize().scale(0.35));
            item.setPickUpDelay(0);
        }
    }

    private static void applyStepAssist(ServerPlayer player, boolean on) {
        AttributeInstance attr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (attr == null) return;
        boolean present = attr.getModifier(STEP_MODIFIER_ID) != null;
        if (on && !present) {
            attr.addTransientModifier(new AttributeModifier(
                    STEP_MODIFIER_ID, 0.4, AttributeModifier.Operation.ADD_VALUE));
        } else if (!on && present) {
            attr.removeModifier(STEP_MODIFIER_ID);
        }
    }

    // ------------------------------------------------------------------
    // Shared reads — branch, mastery count, energy.
    // ------------------------------------------------------------------

    /** Branch from the mastered Battlewill-vs-Magic spread. */
    public static Branch branchOf(ServerPlayer player) {
        int battlewills = 0, magics = 0;
        try {
            for (var inst : SkillAPI.getSkillsFrom(player).getLearnedSkills()) {
                if (inst.getMastery() < inst.getMaxMastery()) continue;
                var skill = inst.getSkill();
                if (skill instanceof Magic) magics++;
                else if (skill instanceof Battlewill) battlewills++;
            }
        } catch (Throwable ignored) { }
        if (battlewills - magics >= 2) return Branch.PHYSICAL;
        if (magics - battlewills >= 2) return Branch.MAGIC;
        return Branch.BALANCED;
    }

    /** Total number of MASTERED skills (any type). */
    public static int masteredCount(ServerPlayer player) {
        int n = 0;
        try {
            for (var inst : SkillAPI.getSkillsFrom(player).getLearnedSkills()) {
                if (inst.getMastery() >= inst.getMaxMastery()) n++;
            }
        } catch (Throwable ignored) { }
        return n;
    }

    private static double energyCost(double max) {
        return Math.max(ENERGY_COST_MIN, ENERGY_COST_FRACTION * max);
    }

    private static double auraOf(ServerPlayer p) {
        IExistence ex = ExampleMod.readExistenceSafe(p);
        return ex == null ? 0 : ex.getAura();
    }

    private static double magiculeOf(ServerPlayer p) {
        IExistence ex = ExampleMod.readExistenceSafe(p);
        return ex == null ? 0 : ex.getMagicule();
    }

    private static void notEnough(ServerPlayer p, String kind) {
        p.displayClientMessage(Component.literal("Not enough " + kind + "."), true);
    }

    private static boolean isAlly(LivingEntity e) {
        return e instanceof Player
                || e instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen
                || e.hasData(Attachments.ALLY_TAG.get())
                || e.hasData(Attachments.RACE_TAG.get());
    }
}
