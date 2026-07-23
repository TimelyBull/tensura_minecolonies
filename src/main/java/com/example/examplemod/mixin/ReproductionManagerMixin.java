package com.example.examplemod.mixin;

import com.example.examplemod.ExampleMod;
import com.llamalad7.mixinextras.sugar.Local;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.managers.ReproductionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage B — race-aware population growth (integrated child route), now with
 * PARENT-based race inheritance.
 *
 * MineColonies grows a colony's population through
 * {@code ReproductionManager.trySpawnChild()}, which creates and registers a
 * new citizen WITHOUT firing {@code CitizenAddedModEvent}. The event-based
 * Stage B interception in {@code ExampleMod.onCitizenAdded} therefore never
 * sees grown citizens — only the INITIAL town-hall top-up path fires that
 * event. Without this mixin, a race colony grows plain human colonists the
 * moment it passes {@code initialCitizenAmount}. (Reported 2026-06-29 — see
 * docs/user-bug-reports.md.)
 *
 * Rather than cancel the birth and drop a wild mob at the town hall, we let
 * MineColonies create the child normally and then CONVERT it into a citizen of
 * the race it inherits from its parents.
 *
 * <p><b>Why inject at {@code spawnOrCreateCitizen} rather than at
 * {@code createAndRegisterCivilianData}</b> (the earlier hook point): the
 * parents don't exist yet when the child is first registered — {@code
 * trySpawnChild} picks {@code firstParent} / {@code secondParent} AFTER that.
 * Injecting just before the body spawns gives us the assigned parents (captured
 * as {@code @Local}s — verified live at this call: {@code newCitizen} slot 5,
 * {@code firstParent} slot 6, {@code secondParent} slot 7, MC 1.1.1319), the
 * child's FINAL name (post-{@code generateName}), and the parent-inherited
 * skills already applied — while still running before the body exists, which
 * {@code mintRaceChildCitizen} needs so the body-join handler can stamp the
 * race appearance. Either parent may be null (colony with a lone breeder).</p>
 *
 * {@code spawnOrCreateCitizen} is a stable {@code ICitizenManager} API method
 * called exactly once in {@code trySpawnChild} (decompile-verified for MC
 * 1.1.1319). The default {@code require = 1} makes this mixin fail loudly at
 * load if a future MC build removes or renames it.
 */
@Mixin(ReproductionManager.class)
public abstract class ReproductionManagerMixin {

    @Shadow @Final private Colony colony;

    @Inject(
            method = "trySpawnChild",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/minecolonies/api/colony/managers/interfaces/ICitizenManager;"
                            + "spawnOrCreateCitizen(Lcom/minecolonies/api/colony/ICitizenData;"
                            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)"
                            + "Lcom/minecolonies/api/colony/ICitizenData;"
            )
    )
    private void tensura_minecolonies$breedRaceChild(
            CallbackInfo ci,
            @Local(ordinal = 0) ICitizenData newCitizen,
            @Local(ordinal = 1) ICitizenData firstParent,
            @Local(ordinal = 2) ICitizenData secondParent) {
        try {
            ExampleMod.onReproductionChild(colony, newCitizen, firstParent, secondParent);
        } catch (Throwable t) {
            // Never let our conversion break MineColonies' birth flow.
            ExampleMod.LOGGER.warn("[TM] race growth: onReproductionChild failed", t);
        }
    }
}
