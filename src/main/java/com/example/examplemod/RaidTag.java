package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Raid-mob marker — NeoForge data attachment stamped on every Tensura
 * mob spawned by a {@link TensuraRaidEvent}. Sibling of {@link EnvoyTag}.
 *
 * <p>It is the universal "is this a raider" check used by:
 * <ul>
 *   <li>the per-second steering / target-assist pass,</li>
 *   <li>the barrier block's pushback + EP-scaled drain,</li>
 *   <li>raid death bookkeeping.</li>
 * </ul>
 *
 * <p>NBT-persisted, so a save/reload mid-raid keeps the mobs linked to
 * their rehydrated event (matched by {@link #colonyId} + {@link #eventId}).
 */
public record RaidTag(int colonyId, int eventId) {

    public static final IAttachmentSerializer<CompoundTag, RaidTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public RaidTag read(IAttachmentHolder holder, CompoundTag tag,
                                    HolderLookup.Provider registries) {
                    return new RaidTag(tag.getInt("colonyId"), tag.getInt("eventId"));
                }

                @Override
                public CompoundTag write(RaidTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    tag.putInt("eventId", value.eventId);
                    return tag;
                }
            };
}
