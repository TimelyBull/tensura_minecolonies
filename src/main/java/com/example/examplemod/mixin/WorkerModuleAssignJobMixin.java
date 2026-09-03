package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateJobGuard;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a MANUAL hire of a race citizen that is away from the colony as a
 * Tensura subordinate. Companion to {@code CitizenManagerJoblessMixin}, which
 * covers automatic hiring; full root cause in {@link SubordinateJobGuard}.
 *
 * <p>The hire button in a hut's GUI goes straight to
 * {@code HireFireMessage} → {@code IAssignsJob.assignCitizen}, bypassing
 * {@code getJoblessCitizen()} entirely. Without this guard a player could still
 * hand-pick a bodiless subordinate and re-create the softlock by hand.</p>
 *
 * <p><b>Why the override and not the base class:</b>
 * {@code WorkerBuildingModule.assignCitizen} creates the job and calls
 * {@code job.assignTo(...)} BEFORE delegating to
 * {@code AbstractAssignedCitizenModule.assignCitizen}. Cancelling at the base
 * method's HEAD would therefore leave a half-assigned job behind. Cancelling
 * here happens before anything has been written. {@code WorkerModule}'s only
 * subclass, {@code NoPrivateCrafterWorkerModule}, inherits this method and so is
 * covered too; the two job modules that do NOT extend it
 * ({@code CourierAssignmentModule}, {@code QuarryModule}) are handled by
 * {@code AssignedCitizenModuleJobMixin}.</p>
 *
 * <p>The refusal is silent to the player beyond the hire not sticking — there is
 * no player reference at this layer to message. Away subordinates should really
 * be filtered out of the hire list on the client too; recorded as a follow-on in
 * docs/future-ideas.md.</p>
 */
@Mixin(WorkerBuildingModule.class)
public abstract class WorkerModuleAssignJobMixin {

    @Inject(method = "assignCitizen", at = @At("HEAD"), cancellable = true)
    private void tensura_minecolonies$refuseAwaySubordinate(
            ICitizenData citizen, CallbackInfoReturnable<Boolean> cir) {
        if (!SubordinateJobGuard.isAwaySubordinate(citizen)) return;
        cir.setReturnValue(false);
    }
}
