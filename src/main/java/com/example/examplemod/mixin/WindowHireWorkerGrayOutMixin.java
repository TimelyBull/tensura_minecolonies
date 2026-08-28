package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateClientStore;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.WindowHireWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Grays out citizens who are away as Tensura subordinates in a hut's hiring
 * window, so a player can see they exist but can't pick one.
 *
 * <p>They stay VISIBLE deliberately — a player who summoned a worker out should
 * still find them in the list and understand why they can't be hired, rather
 * than watch a resident silently vanish from the colony's roster.</p>
 *
 * <p>{@code WindowHireWorker.canAssign} is the exact lever: the window's row
 * adapter calls it per row and, when it returns false, calls {@code off()} on
 * that row's hire button — which is what BlockUI renders as a disabled, dimmed,
 * un-clickable control. So refusing here produces both the gray look and the
 * un-clickability with no layout work. MineColonies also feeds the same answer
 * into {@code getCitizenPriority}, which sorts un-assignable citizens to the
 * bottom of the list — a free bonus.</p>
 *
 * <p>Cosmetic only. The server refuses the hire regardless (see
 * {@code WorkerModuleAssignJobMixin}), so if the client's id set is momentarily
 * stale the worst case is a row that looks clickable and then declines.</p>
 */
@Mixin(WindowHireWorker.class)
public abstract class WindowHireWorkerGrayOutMixin {

    // MixinExtras passes the target method's own arguments after the return
    // value, which is how the citizen being judged reaches us.
    @ModifyReturnValue(method = "canAssign", at = @At("RETURN"))
    private boolean tensura_minecolonies$grayOutAwaySubordinates(
            boolean original, ICitizenDataView citizen) {
        if (!original || citizen == null) return original;
        return !SubordinateClientStore.isAway(citizen.getColonyId(), citizen.getId());
    }
}
