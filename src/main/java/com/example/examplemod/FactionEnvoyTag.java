package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.UUID;

/**
 * FACTION-envoy marker (docs/diplomacy.md #1) — a diplomatic emissary
 * from a WORLD faction offering to open relations with a PLAYER.
 * Deliberately separate from {@link EnvoyTag} (the RACE envoys change a
 * colony's spawn sets; faction envoys change world relations — same
 * spawn/dialogue machinery, different state).
 *
 * @param targetPlayer the player whose relations this envoy offers
 * @param factionId    the sending faction's string id
 */
public record FactionEnvoyTag(UUID targetPlayer, String factionId) {

    public static final IAttachmentSerializer<CompoundTag, FactionEnvoyTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public FactionEnvoyTag read(IAttachmentHolder holder, CompoundTag tag,
                                            HolderLookup.Provider registries) {
                    return new FactionEnvoyTag(tag.getUUID("targetPlayer"),
                            tag.getString("factionId"));
                }

                @Override
                public CompoundTag write(FactionEnvoyTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putUUID("targetPlayer", value.targetPlayer);
                    tag.putString("factionId", value.factionId);
                    return tag;
                }
            };
}
