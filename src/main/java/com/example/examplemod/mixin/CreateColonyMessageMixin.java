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
 * {@code CreateColonyMessage.onExecute}. There are 4 such calls in the
 * method (decompile-verified for MC 1.1.1319):
 * <pre>
 *   ordinal 0 — "notileentity" error (KEEP — colony failed to create)
 *   ordinal 1 — secondary failure path (KEEP — colony failed to create)
 *   ordinal 2 — "colony_reactivated" success (SUPPRESS)
 *   ordinal 3 — "colony_founded" success (SUPPRESS)
 * </pre>
 * We wrap-operation each of ordinals 2 and 3 with a no-op so error
 * messages still reach the player. If a future MC update reorders or
 * adds intermediate sendTo calls the ordinals could shift — failure
 * mode would be either ineffective suppression (player sees both
 * messages) or accidental error-message suppression (player misses an
 * error). Both are recoverable on detection.
 */
@Mixin(CreateColonyMessage.class)
public abstract class CreateColonyMessageMixin {

    @WrapOperation(
            method = "onExecute",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/minecolonies/api/util/MessageUtils$MessageBuilder;sendTo([Lnet/minecraft/world/entity/player/Player;)V",
                    ordinal = 2
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
                    ordinal = 3
            )
    )
    private void tensura$suppressColonyFounded(
            MessageUtils.MessageBuilder instance,
            Player[] players,
            Operation<Void> original) {
        // Suppress — race picker handles the post-creation message itself.
    }
}
