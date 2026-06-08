package com.example.examplemod.mixin;

import com.example.examplemod.SubordinatePatrol;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inserts the "Patrol Colony Outskirts" command into Tensura's native
 * subordinate command cycle.
 *
 * Target: the default method {@code ISubordinate.cycleCommands(Mob, Player)},
 * which rotates FOLLOW → WANDER → STAY. Tensura reaches this method ONLY after
 * its own interaction gating has passed (sneak + right-click for humanoids,
 * food/hipokute items consumed first, inventory-vs-command routing, etc.), so
 * by injecting at its HEAD we add PATROL into the same cycle activated the
 * exact same way as the vanilla commands — no empty-hand requirement, and no
 * need to reproduce Tensura's gating in an interaction handler.
 *
 * {@link SubordinatePatrol#handlePatrolCycle} handles only the two edges that
 * touch PATROL (STAY → PATROL, PATROL → FOLLOW) and returns true to cancel the
 * native cycle for those; for every other edge it returns false and Tensura's
 * own {@code cycleCommands} runs unchanged (FOLLOW → WANDER / WANDER → STAY,
 * including its native AQUA command message).
 *
 * Interface mixin: the injector handler is {@code private} (a Java-21 private
 * interface method), which is how Mixin attaches injectors to an interface
 * target's default method.
 */
@Mixin(ISubordinate.class)
public interface ISubordinateCommandMixin {

    @Inject(method = "cycleCommands", at = @At("HEAD"), cancellable = true)
    private void tensura_minecolonies$insertPatrolCommand(Mob mob, Player player, CallbackInfo ci) {
        if (SubordinatePatrol.handlePatrolCycle(mob, player)) {
            ci.cancel();
        }
    }
}
