package com.example.examplemod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Suppresses MineColonies' two automatic chat messages on colony
 * creation — {@code com.minecolonies.coremod.progress.colony_reactivated}
 * and {@code com.minecolonies.coremod.progress.colony_founded}.
 *
 * Replacement logic lives in
 * {@link com.example.examplemod.ExampleMod#handleRaceChoice}: when
 * the player picks DEFAULT colonists, we re-issue MC's
 * {@code colony_founded} message ourselves so the standard flavour
 * still appears. When the player picks GOBLIN/ORC, we send our own
 * race-specific message instead and MC's stays suppressed.
 *
 * Targeting: both messages are sent via
 * {@code MessageBuilder.sendTo(Player[])} inside
 * {@code CreateColonyMessage.onExecute}. There are SEVEN such calls —
 * bytecode-verified against MC 1.1.1368 with {@code javap -c}, and the
 * method body is byte-for-byte identical on 1.1.1319, so this mapping
 * holds for both:
 * <pre>
 *   ordinal 0 — DANGER                                    (KEEP — error)
 *   ordinal 1 — "core.founding.tooclosetospawn"           (KEEP — error)
 *   ordinal 2 — "core.founding.toofarfromspawn"           (KEEP — error)
 *   ordinal 3 — "gui.colony.denied.tooclose"              (KEEP — error)
 *   ordinal 4 — "colony_reactivated"  (IMPORTANT, %s=name) (SUPPRESS)
 *   ordinal 5 — "progress.colony_founded" (IMPORTANT)      (SUPPRESS)
 *   ordinal 6 — DANGER                                    (KEEP — error)
 * </pre>
 * ⚠ HISTORY (fixed 2026-08-20): this mixin previously targeted ordinals
 * 2 and 3 on the belief there were only 4 sendTo calls. That was wrong on
 * BOTH counts — it let {@code colony_founded} through (the player saw MC's
 * stock line before the race picker even opened) AND silently swallowed the
 * two site-rejection errors, so a player founding a colony too far from
 * spawn / too close to another colony got no explanation at all. This was
 * NOT caused by the 1319 -> 1368 MineColonies bump (onExecute is identical
 * across those builds); it was a long-standing off-by-two.
 *
 * We wrap-operation each of ordinals 4 and 5 with a no-op so every error
 * message still reaches the player. If a future MC update reorders or adds
 * intermediate sendTo calls the ordinals could shift again — failure mode is
 * either ineffective suppression (player sees both messages) or accidental
 * error-message suppression (player misses an error). To re-verify, run:
 * <pre>
 *   javap -c -p -classpath &lt;mc-jar-extract&gt; \
 *     com.minecolonies.core.network.messages.server.CreateColonyMessage
 * </pre>
 * and walk the {@code sendTo} call sites against the preceding
 * {@code ldc} lang-key constants.
 */
@Mixin(CreateColonyMessage.class)
public abstract class CreateColonyMessageMixin {

    @WrapOperation(
            method = "onExecute",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/minecolonies/api/util/MessageUtils$MessageBuilder;sendTo([Lnet/minecraft/world/entity/player/Player;)V",
                    ordinal = 4
            )
    )
    private void tensura$suppressColonyReactivated(
            MessageUtils.MessageBuilder instance,
            Player[] players,
            Operation<Void> original) {
        // Suppress — race picker handles the post-creation message itself.
    }

    @WrapOperation(
            method = "onExecute",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/minecolonies/api/util/MessageUtils$MessageBuilder;sendTo([Lnet/minecraft/world/entity/player/Player;)V",
                    ordinal = 5
            )
    )
    private void tensura$suppressColonyFounded(
            MessageUtils.MessageBuilder instance,
            Player[] players,
            Operation<Void> original) {
        // Suppress — race picker handles the post-creation message itself.
    }
}
