package com.example.examplemod.mixin;

import com.example.examplemod.Config;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.entity.ai.behaviour.TensuraBehaviourHelper;
import net.minecraft.world.entity.LivingEntity;
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
 * Aggression level is config-gated ({@code citizenAggression}: OFF / MEDIUM /
 * HIGH, default OFF — replaces the old {@code tensuraHostileToCitizens}
 * gamerule). OFF = citizens never added as prey; HIGH = unconditional prey;
 * MEDIUM = a stable ~50% split per (mob, citizen) pair.
 *
 * Verification: the first invocation logs once at INFO so we can confirm
 * the mixin applied and weaved. Look for "[TM] citizen-aggression mixin
 * applied" in the log after spawning any innately-hostile Tensura mob.
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
    private static Predicate<LivingEntity> tensura$includeCitizens(Predicate<LivingEntity> original, LivingEntity mob) {
        if (!TM$LOGGED_APPLY) {
            TM$LOGGED_APPLY = true;
            TM$LOG.info("[TM] citizen-aggression mixin applied — citizens added as prey for innately-hostile Tensura mobs (config-gated)");
        }
        return original.or(target -> {
            if (!(target instanceof AbstractEntityCitizen)) return false;
            // Config gate (single source of truth — replaces the old
            // tensuraHostileToCitizens gamerule). OFF = no added aggression;
            // HIGH = unconditional prey; MEDIUM = about half.
            Config.AggressionLevel level = Config.citizenAggression();
            switch (level) {
                case OFF:
                    return false;
                case HIGH:
                    if (!TM$LOGGED_HIT) {
                        TM$LOGGED_HIT = true;
                        TM$LOG.info("[TM] citizen-aggression (HIGH): first citizen accepted as prey ({})", target.getType());
                    }
                    return true;
                case MEDIUM:
                default:
                    // Stable ~50% split per (mob, citizen) pair from their
                    // entity ids — deterministic, so a given mob consistently
                    // sees a given citizen as prey-or-not (no per-tick
                    // re-roll / flicker). Across the population this halves
                    // how often mobs lock onto colonists.
                    boolean prey = ((mob.getId() * 31 + target.getId()) & 1) == 0;
                    if (prey && !TM$LOGGED_HIT) {
                        TM$LOGGED_HIT = true;
                        TM$LOG.info("[TM] citizen-aggression (MEDIUM): first citizen accepted as prey ({})", target.getType());
                    }
                    return prey;
            }
        });
    }
}
