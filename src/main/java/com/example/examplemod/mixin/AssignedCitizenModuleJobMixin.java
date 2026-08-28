package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateJobGuard;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.core.colony.buildings.modules.AbstractAssignedCitizenModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The other half of {@code WorkerModuleAssignJobMixin} — covers the job modules
 * that do NOT extend {@code WorkerBuildingModule} and so inherit this base
 * implementation of {@code assignCitizen} unchanged:
 * {@code CourierAssignmentModule} and {@code QuarryModule}. A courier or
 * quarrier with no body softlocks its hut exactly like a builder does. Full root
 * cause in {@link SubordinateJobGuard}.
 *
 * <p><b>The {@code IAssignsJob} test is load-bearing.</b> This base class also
 * backs the HOUSING module, and an away subordinate is supposed to keep its bed
 * and its housing slot — that is long-standing intended behaviour, not part of
 * the bug. Only modules that hand out JOBS are guarded.</p>
 *
 * <p>Reaching here from a {@code WorkerBuildingModule} means that mixin already
 * let the citizen through, so the same answer is reached twice and nothing
 * conflicts.</p>
 */
@Mixin(AbstractAssignedCitizenModule.class)
public abstract class AssignedCitizenModuleJobMixin {

    @Inject(method = "assignCitizen", at = @At("HEAD"), cancellable = true)
    private void tensura_minecolonies$refuseAwaySubordinate(
            ICitizenData citizen, CallbackInfoReturnable<Boolean> cir) {
        if (!(((Object) this) instanceof IAssignsJob)) return;   // housing: untouched
        if (!SubordinateJobGuard.isAwaySubordinate(citizen)) return;
        cir.setReturnValue(false);
    }
}
