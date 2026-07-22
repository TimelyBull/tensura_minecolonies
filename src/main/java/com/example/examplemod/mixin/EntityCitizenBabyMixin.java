package com.example.examplemod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes a citizen render as a child from its FIRST frame instead of popping
 * from adult to child a second or two after it appears.
 *
 * <p>Reported after the 0.2.1 baby fixes: a child sent home "looks like an adult
 * for a moment and changes when the animation is complete". The animation is a
 * red herring — the timing merely coincides. The real chain is:</p>
 *
 * <ol>
 *   <li>{@code EntityCitizen.isBaby()} returns a private cached field
 *       ({@code child}), not the synced {@code DATA_IS_CHILD} value.</li>
 *   <li>On the CLIENT that field is assigned in exactly one place —
 *       {@code CitizenColonyHandler.updateColonyClient()}.</li>
 *   <li>That runs from the entity's {@code ACTIVE_CLIENT} state, which a freshly
 *       spawned body only reaches after leaving {@code EntityState.INIT} — and
 *       that transition is checked on a <b>40-tick</b> timer. So for up to two
 *       seconds the client believes a child is an adult.</li>
 *   <li>{@code LivingEntityRenderer.render} sets {@code model.young =
 *       entity.isBaby()} every frame, so the body renders full-size until the
 *       flag catches up — and our citizen renderers extend that class.</li>
 * </ol>
 *
 * <p>The synced value is correct from the moment the body is tracked (
 * {@code CitizenData.initEntityValues} writes both at spawn), so on the client we
 * fall back to it whenever the cached field still says "adult". Server-side
 * behaviour is untouched: there the two are always written together by
 * {@code setIsChild}, so the fallback can never disagree. Growing up is safe for
 * the same reason — when a citizen matures BOTH go false.</p>
 */
@Mixin(EntityCitizen.class)
public abstract class EntityCitizenBabyMixin {

    @ModifyReturnValue(method = "isBaby()Z", at = @At("RETURN"))
    private boolean tensura_minecolonies$babyFromSyncedData(boolean original) {
        EntityCitizen self = (EntityCitizen) (Object) this;
        // Only ever turns adult -> child, and only on the client, where the
        // cached field is the one that lags.
        if (original || !self.level().isClientSide()) return original;
        return self.getEntityData().get(AbstractEntityCitizen.DATA_IS_CHILD);
    }
}
