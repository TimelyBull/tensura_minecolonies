package com.example.examplemod;

import io.github.manasmods.tensura.registry.item.misc.TensuraDataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Absolute Annihilator — Milim's capstone weapon. It gains power as its
 * Tensura EP climbs (see {@code data/tensura_minecolonies/gear_existence/
 * absolute_annihilator.json} for the stat evolutions). This class layers the
 * NON-attribute "effects" of the EP ladder on top:
 *
 * <ul>
 *   <li><b>{@value #WEAKEN_EP} EP</b> — melee hits inflict brief Weakness.</li>
 *   <li><b>{@value #CHARGE_EP} EP</b> — "charged": the sprite lights up and
 *       right-click unleashes the Drago Nova blast (see
 *       {@link DragoNovaItem#triggerAnnihilatorNova}), on a cooldown, without
 *       consuming the weapon. The blast is the Drago Nova's base damage plus a
 *       multiple of THIS weapon's attack damage, so it grows with the EP
 *       ladder rather than sitting at a fixed number.</li>
 *   <li><b>{@value #LIFESTEAL_EP} EP</b> — melee hits heal the wielder; the nova
 *       cooldown shortens.</li>
 *   <li><b>{@value #SHOCKWAVE_EP} EP</b> — melee hits burst into a small AoE
 *       shockwave; the nova cooldown shortens further.</li>
 * </ul>
 *
 * Below {@value #CHARGE_EP} EP the nova ability is locked; each on-hit effect
 * unlocks at its own EP threshold. All thresholds sit below the weapon's
 * {@code maxEP} (1,000,000) so they are reachable.
 */
public class AbsoluteAnnihilatorItem extends SwordItem {

    /** On-hit Weakness unlocks here. */
    public static final double WEAKEN_EP = 150_000.0;
    /** EP required before the charged nova ability (and the lit sprite) unlock. */
    public static final double CHARGE_EP = 500_000.0;
    /** Lifesteal on hit + a shorter nova cooldown unlock here. */
    public static final double LIFESTEAL_EP = 700_000.0;
    /** On-hit AoE shockwave + the shortest nova cooldown unlock here (max EP). */
    public static final double SHOCKWAVE_EP = 1_000_000.0;

    /** Base nova cooldown (1 minute) — shortens at higher EP tiers. */
    public static final int COOLDOWN_TICKS = 20 * 60;

    /** Shockwave damage = this fraction of the wielder's attack damage. */
    private static final double SHOCKWAVE_DAMAGE_FRACTION = 0.3;
    /** The nova fired by this weapon adds this multiple of its attack damage on
     *  top of the Drago Nova's own blast, so the ability grows with the weapon. */
    private static final double NOVA_WEAPON_DAMAGE_MULTIPLIER = 4.0;

    /** Where the STAT ladder steps, for the tooltip. These are the EPs in
     *  {@code gear_existence/absolute_annihilator.json} — keep them in sync.
     *  Note 400k is a stats-only step; the ability EPs are the constants above. */
    private static final double[] STAT_TIERS = { WEAKEN_EP, 400_000.0, LIFESTEAL_EP, SHOCKWAVE_EP };

    public AbsoluteAnnihilatorItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    // ------------------------------------------------------------------
    // Tooltip — SHIFT reveals the EP ladder; otherwise a hint line, plus the
    // weapon's current EP so you can see how far off the next unlock is.
    // (Mirrors MasterworkItem; appendHoverText is client-only, so the Screen
    //  reference never loads on a dedicated server.)
    // ------------------------------------------------------------------
    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            tooltip.add(net.minecraft.network.chat.Component.literal(
                            "Grows with its Existence Points — " + ep(epOf(stack)) + " / " + ep(SHOCKWAVE_EP) + " EP.")
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            unlock(tooltip, stack, WEAKEN_EP, "hits inflict brief Weakness");
            unlock(tooltip, stack, CHARGE_EP, "charged — right-click fires a Drago Nova blast");
            unlock(tooltip, stack, LIFESTEAL_EP, "hits heal you; the blast recharges faster");
            unlock(tooltip, stack, SHOCKWAVE_EP, "hits burst into a shockwave; fastest recharge");
            tooltip.add(net.minecraft.network.chat.Component.literal(
                            "Raw stats climb at " + ep(STAT_TIERS[0]) + " / " + ep(STAT_TIERS[1])
                                    + " / " + ep(STAT_TIERS[2]) + " / " + ep(STAT_TIERS[3]) + " EP:")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            tooltip.add(net.minecraft.network.chat.Component.literal(
                            "  attack damage + speed, then knockback resistance, then health")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(net.minecraft.network.chat.Component.literal("Hold SHIFT to see abilities")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    /** One ladder line — green once its EP is reached, grey while it's still locked. */
    private static void unlock(java.util.List<net.minecraft.network.chat.Component> tooltip,
                               ItemStack stack, double atEP, String what) {
        boolean reached = epOf(stack) >= atEP;
        tooltip.add(net.minecraft.network.chat.Component.literal(
                        "  " + ep(atEP) + " EP — " + what)
                .withStyle(reached ? net.minecraft.ChatFormatting.GREEN
                        : net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /** 1000000 -> "1,000,000". */
    private static String ep(double value) {
        return String.format("%,d", (long) value);
    }

    /** Hammers forged under 0.2.0's wrong (absolute-not-cumulative) ladder keep
     *  their inflated stats until something rebuilds them — see
     *  {@link GearEvolution#recalibrate}. Cheap and a no-op once correct. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity,
                              int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (level.isClientSide() || level.getGameTime() % 20 != 0) return;
        GearEvolution.recalibrate(stack, level);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(stack.getItem())) {
            return InteractionResultHolder.pass(stack);
        }
        double ep = epOf(stack);
        if (ep < CHARGE_EP) {
            return InteractionResultHolder.pass(stack);
        }
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
            float blastDamage = DragoNovaItem.DRAGO_NOVA_DAMAGE
                    + (float) (NOVA_WEAPON_DAMAGE_MULTIPLIER * WeaponAbilities.weaponAttackDamage(stack));
            DragoNovaItem.triggerAnnihilatorNova(serverLevel, sp, blastDamage);
            player.getCooldowns().addCooldown(stack.getItem(), novaCooldownTicks(ep));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.level() instanceof ServerLevel level) {
            double ep = epOf(stack);
            if (ep >= WEAKEN_EP) {
                // ~5s Weakness so sustained pressure actually matters.
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
            }
            if (ep >= LIFESTEAL_EP) {
                float heal = (float) (0.08 * attacker.getAttributeValue(Attributes.ATTACK_DAMAGE));
                if (heal > 0) attacker.heal(heal);
            }
            if (ep >= SHOCKWAVE_EP) {
                shockwave(level, attacker, target, stack);
            }
        }
        return super.hurtEnemy(stack, target, attacker);
    }

    /** Small AoE burst around the struck enemy — hits nearby hostiles, spares
     *  players/citizens/allies, with a knockback and a sonic-boom flash. */
    private static void shockwave(ServerLevel level, LivingEntity attacker, LivingEntity center,
                                  ItemStack stack) {
        double radius = 4.0;
        double radiusSqr = radius * radius;
        float dmg = (float) (SHOCKWAVE_DAMAGE_FRACTION * WeaponAbilities.abilityBase(attacker, stack));
        // A physical burst from the hammer, credited to the wielder.
        var source = attacker instanceof Player p
                ? level.damageSources().playerAttack(p)
                : level.damageSources().mobAttack(attacker);
        level.sendParticles(ParticleTypes.SONIC_BOOM,
                center.getX(), center.getY() + 1.0, center.getZ(), 1, 0, 0, 0, 0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                center.getBoundingBox().inflate(radius))) {
            if (e == attacker || e == center) continue;
            if (e instanceof Player) continue;                        // no PvP splash
            if (e instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen) continue;
            if (e.hasData(Attachments.ALLY_TAG.get()) || e.hasData(Attachments.RACE_TAG.get())) continue;
            if (e.distanceToSqr(center.position()) > radiusSqr) continue;
            // runItemOnHit = false: this splash IS the on-hit effect, so re-running
            // it here would make every splashed enemy set off another shockwave.
            WeaponAbilities.hit(level, attacker, stack, e, source, dmg, false);
            Vec3 push = e.position().subtract(center.position()).normalize().scale(0.6);
            e.push(push.x, 0.3, push.z);
        }
    }

    /** Nova cooldown shortens as the weapon grows: 60s → 45s → 30s. */
    private static int novaCooldownTicks(double ep) {
        if (ep >= SHOCKWAVE_EP) return 20 * 30;
        if (ep >= LIFESTEAL_EP) return 20 * 45;
        return COOLDOWN_TICKS;
    }

    /** Current Tensura EP on the stack (0 if unstamped). */
    private static double epOf(ItemStack stack) {
        Double ep = stack.get(TensuraDataComponents.EP.get());
        return ep == null ? 0.0 : ep;
    }
}
