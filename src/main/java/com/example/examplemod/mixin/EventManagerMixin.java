package com.example.examplemod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.core.colony.managers.EventManager;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fix — colony events registered outside the {@code minecolonies} namespace
 * survive save/reload (our {@code tensura_minecolonies:tensura_raid}).
 *
 * <p><b>The bug (deps/minecolonies.md §7.1):</b> MineColonies' event persistence
 * is a namespace-erasure round-trip. {@code EventManager.writeToNBT} stores only
 * {@code event.getEventTypeID().getPath()} under the {@code "name"} key (the
 * namespace is discarded), and {@code EventManager.readFromNBT} reconstructs the
 * id as {@code new ResourceLocation("minecolonies", name)} — a HARDCODED
 * {@code minecolonies} namespace — before looking the type up in the colony-event
 * registry ({@code Registry.get}). So any event whose real registered id is NOT
 * in the {@code minecolonies} namespace misses the lookup, MC logs
 * "missing registryEntry", and the in-progress event is silently dropped on
 * reload (for our raid: the boss bar, the tracked-raider set, and the
 * {@code isRaided()} citizen flee/hide state all vanish). Verified by bytecode
 * against MC 1.1.1319.
 *
 * <p><b>The fix:</b> wrap the registry {@code get(ResourceLocation)} call inside
 * {@code readFromNBT}. If the hardcoded-namespace lookup succeeds (every real
 * {@code minecolonies} event), we return its result untouched. Only when it
 * returns null do we recover by matching the stored PATH against the whole
 * registry — event-type paths are unique, so
 * {@code minecolonies:tensura_raid → tensura_minecolonies:tensura_raid} resolves
 * cleanly, and no normally-resolving event ever reaches the scan. Read-side only:
 * it fixes both existing and future saves without changing the on-disk format,
 * and the raid mobs (which persist with their {@code RaidTag}) re-link to the
 * rehydrated event by {@code (colonyId, eventId)} exactly as designed.
 *
 * <p>Target confirmed at offset 88 of {@code readFromNBT}:
 * {@code invokeinterface net/minecraft/core/Registry.get
 * (Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;}. The default
 * {@code require = 1} makes this fail loudly at load if a future MC build changes
 * that call.
 */
@Mixin(EventManager.class)
public abstract class EventManagerMixin {

    @WrapOperation(
            method = "readFromNBT",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Registry;get("
                            + "Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;"
            )
    )
    private Object tensura_minecolonies$resolveEventTypeAnyNamespace(
            Registry<?> registry, ResourceLocation id, Operation<Object> original) {
        Object entry = original.call(registry, id);
        if (entry != null) {
            return entry; // a real minecolonies event — unchanged
        }
        // The caller forced the namespace to "minecolonies"; recover a
        // foreign-namespace event by its (unique) path.
        String path = id.getPath();
        for (ResourceLocation key : registry.keySet()) {
            if (key.getPath().equals(path)) {
                return original.call(registry, key);
            }
        }
        return null; // genuinely unknown — MC's original null-handling applies
    }
}
