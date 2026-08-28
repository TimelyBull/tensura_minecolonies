package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateJobGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.managers.CitizenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Stops MineColonies' AUTOMATIC hiring from picking a race citizen that is away
 * from the colony as a Tensura subordinate — the citizen has no body, so the
 * job it were given could never be done. Full root cause in
 * {@link SubordinateJobGuard}.
 *
 * <p>{@code getJoblessCitizen()} is the single entry point automatic hiring
 * uses: {@code WorkerBuildingModule.onColonyTick} →
 * {@code BuildingUtils.canAutoHire} → here. Upstream it returns the first
 * citizen matching {@code getWorkBuilding() == null && !isChild()} — no entity
 * check, no travelling check — which is exactly why a bodiless subordinate
 * qualifies.</p>
 *
 * <p><b>Why the returned value is patched rather than the method replaced:</b>
 * a HEAD-cancel would mean re-implementing MineColonies' choice of candidate and
 * silently inheriting none of its future changes. Instead the normal answer is
 * left completely alone, and we only step in when the citizen MineColonies
 * picked is one of ours that cannot work — then we hand back the next candidate
 * that can. In a colony with no away subordinates this mixin changes
 * nothing.</p>
 *
 * <p>Returning {@code null} when every remaining candidate is an away
 * subordinate is correct and is what upstream does when nobody is jobless: the
 * hut simply doesn't hire this tick.</p>
 */
@Mixin(CitizenManager.class)
public abstract class CitizenManagerJoblessMixin {

    @Shadow public abstract List<ICitizenData> getCitizens();

    @ModifyReturnValue(method = "getJoblessCitizen", at = @At("RETURN"))
    private ICitizenData tensura_minecolonies$skipAwaySubordinates(ICitizenData original) {
        if (original == null || !SubordinateJobGuard.isAwaySubordinate(original)) {
            return original;
        }
        // Same filter upstream applies, plus ours.
        for (ICitizenData candidate : getCitizens()) {
            if (candidate.getWorkBuilding() == null
                    && !candidate.isChild()
                    && !SubordinateJobGuard.isAwaySubordinate(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
