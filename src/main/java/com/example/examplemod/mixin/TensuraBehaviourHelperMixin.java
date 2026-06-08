package com.example.examplemod.mixin;

import com.example.examplemod.ExampleMod;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.entity.ai.behaviour.TensuraBehaviourHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * Makes colony citizens unconditional prey for innately-hostile Tensura
 * mobs, alongside the hardcoded {@code EntityType.PLAYER} check.
 *
 * Target: {@code TensuraBehaviourHelper.getAnimalPreyPredicate(LivingEntity)}
 * — the {@code Predicate<LivingEntity>} consulted by every mob registering
 * {@code getPreyTargeting(this)} with no preyPredicate override (i.e. wild
 * hostile mobs). Goblin and orc pass {@code entity -> false} as preyPredicate
 * and therefore never consult this method — they are unaffected.
 *
 * Why {@code @ModifyReturnValue}: we want to augment the returned predicate
 * without re-implementing Tensura's HP-gated tag check. {@code .or(...)} is
 * evaluated short-circuit, so the original predicate runs first; only when
 * it returns false do we add the citizen-as-prey branch. Citizens become
 * prey regardless of the mob's HP (bypasses {@code hostileHPMultiplier}) —
 * this is the deliberate trade-off vs the datapack-tag (Option A) approach.
 *
 * Collision audit vs subordinate veto: this hostility path is consulted only
 * on the {@code owner == null && !isTame()} branch of
 * {@code ISubordinate.shouldTarget}. The subordinate veto in
 * {@code ExampleMod.onSubordinateChangeTarget} fires only when the
 * subordinate has a non-null owner UUID matching the citizen's colony
 * owner. Mutually exclusive code paths.
 *
 * Verification: the first invocation logs once at INFO so we can confirm
 * the mixin applied and weaved. Look for "[TM] hostile-prey mixin applied"
 * in the log after spawning any innately-hostile Tensura mob.
 */
@Mixin(TensuraBehaviourHelper.class)
public abstract class TensuraBehaviourHelperMixin {

    private static final Logger TM$LOG = LogUtils.getLogger();
    private static volatile boolean TM$LOGGED_APPLY = false;
    private static volatile boolean TM$LOGGED_HIT = false;

    @ModifyReturnValue(
            method = "getAnimalPreyPredicate",
            at = @At("RETURN")
    )
    private static Predicate<LivingEntity> tensura$includeCitizens(Predicate<LivingEntity> original) {
        if (!TM$LOGGED_APPLY) {
            TM$LOGGED_APPLY = true;
            TM$LOG.info("[TM] hostile-prey mixin applied — citizens added as prey for innately-hostile Tensura mobs (gamerule-gated)");
        }
        return original.or(target -> {
            if (!(target instanceof AbstractEntityCitizen)) return false;
            // Gamerule gate. Null Key means commonSetup hasn't run yet
            // (shouldn't happen on a live server, but be defensive).
            if (ExampleMod.RULE_HOSTILE_TO_CITIZENS == null) return true;
            Level level = target.level();
            if (!level.getGameRules().getBoolean(ExampleMod.RULE_HOSTILE_TO_CITIZENS)) return false;
            if (!TM$LOGGED_HIT) {
                TM$LOGGED_HIT = true;
                TM$LOG.info("[TM] hostile-prey mixin: first citizen accepted as prey ({})", target.getType());
            }
            return true;
        });
    }
}
