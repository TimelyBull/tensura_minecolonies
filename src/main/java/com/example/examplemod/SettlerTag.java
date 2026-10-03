package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Settler marker — stamped on every wild, UNNAMED race mob this mod spawns
 * for a colony (the initial-settler replacement, free immigration, and the
 * envoy drop-in). It records which colony the mob came for.
 *
 * The marker exists so the colony can COUNT the mobs still waiting to be
 * named: a race's arrivals are capped at "named citizens + waiting settlers",
 * and without a marker a wild goblin that merely wandered past would be
 * indistinguishable from one we spawned. NBT-persisted, so the count survives
 * a save/reload.
 *
 * Once the player names the mob it becomes a citizen (it gains an identity
 * record) and is counted as a citizen instead; the marker is then ignored.
 *
 * The marker also means "no naming penalty": a mob carrying it joins WITHOUT
 * the named-citizen happiness penalty when the player names it.
 *
 * @param colonyId the colony this settler was spawned for
 */
public record SettlerTag(int colonyId) {

    public static final IAttachmentSerializer<CompoundTag, SettlerTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public SettlerTag read(IAttachmentHolder holder, CompoundTag tag,
                                       HolderLookup.Provider registries) {
                    return new SettlerTag(tag.getInt("colonyId"));
                }

                @Override
                public CompoundTag write(SettlerTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    return tag;
                }
            };
}
