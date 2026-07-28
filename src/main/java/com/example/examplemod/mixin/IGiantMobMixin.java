package com.example.examplemod.mixin;

import com.example.examplemod.GriefProtection;
import io.github.manasmods.tensura.entity.template.subclass.IGiantMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Colony block-break protection for Tensura's "giant mobs".
 *
 * <p>{@code IGiantMob} is the interface behind Tensura mobs that break blocks
 * by running into them (Orc Lord, Charybdis, Megalodon, Knight Spider,
 * Armorsaurus, Giant Ant, Black Spider, Greater Spirit, …). Its three grief
 * gates only ever check the vanilla {@code mobGriefing} gamerule and fire no
 * cancellable event, so MineColonies' own colony-boundary protection never
 * gets a chance to stop them — these mobs smash colony builds even inside a
 * "protected" colony. This matters more for THIS mod than upstream Tensura
 * because our raids / faction garrisons / lore bosses are made of exactly
 * these mobs.
 *
 * <p>Two layers, matching the interface's own two-tier gate:
 * <ul>
 *   <li>{@code cantBreakBlock} — coarse early-exit: if the mob itself stands in
 *       a colony, refuse the whole break attempt before any block scanning
 *       (no per-block position is available here, so the mob's own position is
 *       the proxy).
 *   <li>{@code breakableBlocks} / {@code diggableBlocks} — the precise per-block
 *       gate (these DO receive the real {@code BlockPos}), catching the case
 *       where the mob is just outside the boundary but reaching a block inside.
 * </ul>
 *
 * <p>Every injection only ever NARROWS toward "can't break" — it never lets a
 * mob break something Tensura itself would have refused. Gated by
 * {@link GriefProtection#blockMobGrief} (config
 * {@code protectColoniesFromMobGriefing}, default on, read live).
 *
 * <p>Ported/adapted from the sibling mod (Jinjer's tensura-minecolonies-compat).
 */
@Mixin(IGiantMob.class)
public interface IGiantMobMixin {

    @Inject(method = "cantBreakBlock", at = @At("RETURN"), cancellable = true, remap = false)
    private void tm$gateEntityInColony(LivingEntity entity, boolean checkLineOfSight,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return; // already refused for some other reason
        try {
            if (GriefProtection.blockMobGrief(entity.level(), entity.blockPosition())) {
                cir.setReturnValue(true);
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "breakableBlocks", at = @At("RETURN"), cancellable = true, remap = false)
    private void tm$gateBreakableBlock(LivingEntity entity, BlockPos pos, BlockState state,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return; // already not breakable
        try {
            if (GriefProtection.blockMobGrief(entity.level(), pos)) {
                cir.setReturnValue(false);
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "diggableBlocks", at = @At("RETURN"), cancellable = true, remap = false)
    private void tm$gateDiggableBlock(LivingEntity entity, BlockPos pos, BlockState state,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return; // already not diggable
        try {
            if (GriefProtection.blockMobGrief(entity.level(), pos)) {
                cir.setReturnValue(false);
            }
        } catch (Exception ignored) {}
    }
}
