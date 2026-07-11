package com.example.examplemod.mixin;

import com.example.examplemod.ExampleMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.managers.ReproductionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stage B — race-aware population growth (integrated child route).
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
 * the colony's race — a goblin/orc/dwarf/lizardman child, tied to its real
 * MineColonies parents, that renders as a baby of that race and grows up like
 * any colonist. This keeps the native reproduction/family system intact.
 *
 * Mechanism: {@code @WrapOperation} around the {@code createAndRegisterCivilianData()}
 * call inside {@code trySpawnChild}. We call the original (so the child is
 * created + registered exactly as vanilla expects), then hand the fresh child
 * to {@link ExampleMod#onReproductionChild}, which mints a race identity for it
 * when the colony has a race composition (and no-ops for legacy / COLONIST
 * colonies, leaving an ordinary colonist). We return the child unchanged so the
 * rest of {@code trySpawnChild} (parents, name, child flag, body spawn) runs
 * normally; the body-join / reconcile pass then stamps the race appearance.
 *
 * {@code createAndRegisterCivilianData} is a stable {@code ICitizenManager} API
 * method called exactly once in {@code trySpawnChild} (decompile-verified for
 * MC 1.1.1319). If a future MC build removes or renames it, the default
 * {@code require = 1} makes this mixin fail loudly at load.
 */
@Mixin(ReproductionManager.class)
public abstract class ReproductionManagerMixin {

    @Shadow @Final private Colony colony;

    @WrapOperation(
            method = "trySpawnChild",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/minecolonies/api/colony/managers/interfaces/ICitizenManager;"
                            + "createAndRegisterCivilianData()Lcom/minecolonies/api/colony/ICitizenData;"
            )
    )
    private ICitizenData tensura_minecolonies$breedRaceChild(
            ICitizenManager manager, Operation<ICitizenData> original) {
        ICitizenData child = original.call(manager);
        try {
            ExampleMod.onReproductionChild(colony, child);
        } catch (Throwable t) {
            // Never let our conversion break MineColonies' birth flow.
            ExampleMod.LOGGER.warn("[TM] race growth: onReproductionChild failed", t);
        }
        return child;
    }
}
