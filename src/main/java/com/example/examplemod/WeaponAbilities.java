package com.example.examplemod;

import io.github.manasmods.tensura.damage.TensuraDamageTypes;
import io.github.manasmods.tensura.enchantment.TensuraEnchantmentHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Shared plumbing for our custom weapons' right-click abilities, so an ability
 * hit behaves like a real Tensura weapon hit.
 *
 * <p>Three things every ability needs and none of them used to do:</p>
 *
 * <ol>
 *   <li><b>Clear the invulnerability frames first.</b> Vanilla only lets the
 *       BIGGEST hit inside a 10-tick window through: a second hit deals
 *       {@code amount - lastHurt} and is dropped entirely when it isn't bigger.
 *       Since players naturally swing and then immediately right-click, the
 *       ability was landing a few points of leftover damage (or none) no matter
 *       how strong the weapon was. Abilities reset the timer so they always
 *       land their full damage.</li>
 *   <li><b>Run Tensura's on-hit pipeline.</b> Engravings only fire from the
 *       hooks Tensura installs on a normal attack, so a hit dealt straight
 *       through {@code hurt()} triggers no engraving at all. This mirrors what
 *       Tensura's own Battlewill arts do after they damage something.</li>
 *   <li><b>Name an attacker on the damage source.</b> An ownerless source is
 *       treated as environmental damage: no kill credit, no EP gain, no
 *       ally/subordinate checks, and no way for the wielder's own magicule to
 *       push through a target's magic interference.</li>
 * </ol>
 */
final class WeaponAbilities {

    private WeaponAbilities() { }

    /**
     * The attack damage the WEAPON itself contributes, read off the stack so
     * the EP evolutions Tensura stamps onto it are included. Mirrors Tensura's
     * own {@code TensuraDamageHelper.getWeaponBaseDamage}.
     */
    static double weaponAttackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null || modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        double damage = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (!entry.attribute().equals(Attributes.ATTACK_DAMAGE)) continue;
            if (entry.slot() != EquipmentSlotGroup.MAINHAND
                    && entry.slot() != EquipmentSlotGroup.HAND
                    && entry.slot() != EquipmentSlotGroup.ANY) continue;
            if (entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                damage += entry.modifier().amount();
            }
        }
        return damage;
    }

    /**
     * Base damage an ability scales from: the wielder's attack damage while
     * holding the weapon, floored by the weapon's own attack damage so the
     * ability still scales when the weapon is in the off-hand (where the
     * wielder's attack-damage attribute doesn't include it).
     */
    static float abilityBase(LivingEntity wielder, ItemStack weapon) {
        double fromWielder = wielder.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double fromWeapon = 1.0 + weaponAttackDamage(weapon);
        return (float) Math.max(fromWielder, fromWeapon);
    }

    /** A magic damage source credited to {@code attacker}. */
    static DamageSource magicSource(ServerLevel level, LivingEntity attacker) {
        return TensuraDamageTypes.getEntityDamageSource(level, TensuraDamageTypes.MAGIC_GENERIC, attacker);
    }

    /**
     * Land one ability hit on {@code target} and run everything a normal weapon
     * hit would run. Returns whether the target actually took damage.
     */
    static boolean hit(ServerLevel level, LivingEntity attacker, ItemStack weapon,
                       LivingEntity target, DamageSource source, float damage) {
        return hit(level, attacker, weapon, target, source, damage, true);
    }

    /**
     * As {@link #hit}, but {@code runItemOnHit} can skip the weapon's own on-hit
     * effect. Splash damage spawned FROM that on-hit effect must skip it, or the
     * effect would trigger itself over and over.
     */
    static boolean hit(ServerLevel level, LivingEntity attacker, ItemStack weapon,
                       LivingEntity target, DamageSource source, float damage,
                       boolean runItemOnHit) {
        // Don't let the swing that came just before this ability eat its damage.
        target.invulnerableTime = 0;
        boolean hurt = target.hurt(source, damage);
        if (hurt) {
            if (runItemOnHit) weapon.getItem().hurtEnemy(weapon, target, attacker);
            EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, weapon);
            TensuraEnchantmentHelper.doAdditionalAfterDamage(level, target, attacker, source, weapon, damage);
        }
        TensuraEnchantmentHelper.doAdditionalAfterAttack(level, target, attacker, source, weapon, damage);
        return hurt;
    }
}
