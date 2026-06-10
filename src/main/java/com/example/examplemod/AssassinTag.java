package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.UUID;

/**
 * Marker on an ACTIVATED assassin's Tensura body (the boss). Carries
 * what the death/driver hooks need to re-link after save/reload:
 * the identity, the colony (cold-shoulder clear), and the target player.
 * Sibling of {@link EnvoyTag} / {@link RaidTag}. See
 * docs/assassin-system.md.
 */
public record AssassinTag(UUID identityId, int colonyId, UUID targetPlayer) {

    public static final IAttachmentSerializer<CompoundTag, AssassinTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public AssassinTag read(IAttachmentHolder holder, CompoundTag tag,
                                        HolderLookup.Provider registries) {
                    return new AssassinTag(
                            tag.getUUID("identityId"),
                            tag.getInt("colonyId"),
                            tag.hasUUID("target") ? tag.getUUID("target") : null);
                }

                @Override
                public CompoundTag write(AssassinTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putUUID("identityId", value.identityId);
                    tag.putInt("colonyId", value.colonyId);
                    if (value.targetPlayer != null) tag.putUUID("target", value.targetPlayer);
                    return tag;
                }
            };
}
