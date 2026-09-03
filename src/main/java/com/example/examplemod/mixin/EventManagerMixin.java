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
 * survive save/reload (our {@code tensura_minecolonies:tensura_raid}), and an
 * unresolvable stored event never destroys the colony.
 *
 * <p><b>Bug 1 — namespace erasure (deps/minecolonies.md §7.1):</b> MineColonies'
 * event persistence is a lossy round-trip. {@code EventManager.writeToNBT} stores
 * only {@code event.getEventTypeID().getPath()} under the {@code "name"} key (the
 * namespace is discarded), and {@code EventManager.readFromNBT} reconstructs the
 * id as {@code new ResourceLocation("minecolonies", name)} — a HARDCODED
 * {@code minecolonies} namespace — before looking the type up in the colony-event
 * registry. So any event whose real registered id is NOT in the
 * {@code minecolonies} namespace misses the lookup. Verified by bytecode against
 * MC 1.1.1319.
 *
 * <p><b>Bug 2 — the missed lookup CRASHES colony load (0.2.3):</b> MineColonies
 * handles the miss by null-checking the lookup result and logging
 * "missing registryEntry"… but that branch is unreachable. Every registry made by
 * {@code CommonMinecoloniesAPIImpl.syncedRegistry(key)} is built with
 * {@code .defaultKey(minecolonies:null)}, so it is a {@link
 * net.minecraft.core.DefaultedMappedRegistry} — and nothing ever registers an
 * entry called {@code minecolonies:null}, so its private {@code defaultValue}
 * stays null. {@code DefaultedMappedRegistry.get} answers a miss with
 * {@code this.defaultValue.value()}, which throws
 * {@code NullPointerException: Cannot invoke "Holder$Reference.value()" because
 * "this.defaultValue" is null} instead of returning null.
 *
 * <p>That NPE propagates out of {@code Colony.read} → {@code Colony.loadColony} →
 * {@code BackUpHelper.loadColonyBackup}, so the whole colony fails to load;
 * MineColonies then drops it from {@code colonies.dat} and renames its backup to
 * {@code colonyN.dat.delete}. The colony is gone, and because the next save no
 * longer contains it the failure repeats on every subsequent boot. A colony that
 * was saved with one of our raids in flight was being deleted this way.
 *
 * <p><b>The fix:</b> wrap the registry {@code get(ResourceLocation)} call inside
 * {@code readFromNBT} and never hand the registry a key it does not have.
 * <ol>
 *   <li>Key present (every real {@code minecolonies} event) → call through
 *       untouched.</li>
 *   <li>Key absent → recover by matching the stored PATH against the whole
 *       registry. Event-type paths are unique, so
 *       {@code minecolonies:tensura_raid → tensura_minecolonies:tensura_raid}
 *       resolves cleanly.</li>
 *   <li>No path match either (a genuinely unknown event — e.g. left behind by a
 *       mod that has since been removed) → return null, which is what
 *       MineColonies' own code already expects: it logs the warning, skips that
 *       one event, and the colony loads normally.</li>
 * </ol>
 *
 * <p>Read-side only: it fixes both existing and future saves without changing the
 * on-disk format, and the raid mobs (which persist with their {@code RaidTag})
 * re-link to the rehydrated event by {@code (colonyId, eventId)} exactly as
 * designed.
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

        // NEVER call get() with a key the registry does not have — this registry
        // is defaulted with an unregistered default, so a miss throws NPE rather
        // than returning null (see the class javadoc, Bug 2).
        if (registry.containsKey(id)) {
            return original.call(registry, id); // a real minecolonies event — unchanged
        }

        // The caller forced the namespace to "minecolonies"; recover a
        // foreign-namespace event by its (unique) path.
        String path = id.getPath();
        for (ResourceLocation key : registry.keySet()) {
            if (key.getPath().equals(path)) {
                return original.call(registry, key);
            }
        }

        // Genuinely unknown event. Returning null lets MineColonies log its
        // "missing registryEntry" warning and skip just this event, instead of
        // failing the entire colony load.
        com.minecolonies.api.util.Log.getLogger().warn(
                "[TM] colony event '" + path + "' is not in the colony-event registry; "
                        + "skipping it so the colony still loads.");
        return null;
    }
}
