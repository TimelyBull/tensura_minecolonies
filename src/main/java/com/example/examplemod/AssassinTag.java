package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.UUID;

/**
 * Marker on an ACTIVATED assassin's Tensura body (the boss). Carries
 * what the death/driver hooks need to re-link after save/reload:
 * the identity, the colony (cold-shoulder clear), the target player,
 * and — v2 — the EP stolen on a successful assassination
 * ({@code stolenMagicule}/{@code stolenAura} > 0 means the theft
 * happened; killing the boss restores the player). Sibling of
 * {@link EnvoyTag} / {@link RaidTag}. See docs/assassin-system.md.
 */
public record AssassinTag(UUID identityId, int colonyId, UUID targetPlayer,
                          double stolenMagicule, double stolenAura) {

    /** v1-shape constructor (no theft yet). */
    public AssassinTag(UUID identityId, int colonyId, UUID targetPlayer) {
        this(identityId, colonyId, targetPlayer, 0.0, 0.0);
    }

    public boolean hasStolen() {
        return stolenMagicule > 0 || stolenAura > 0;
    }

    public AssassinTag withStolen(double magicule, double aura) {
        return new AssassinTag(identityId, colonyId, targetPlayer, magicule, aura);
    }

    public static final IAttachmentSerializer<CompoundTag, AssassinTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public AssassinTag read(IAttachmentHolder holder, CompoundTag tag,
                                        HolderLookup.Provider registries) {
                    return new AssassinTag(
                            tag.getUUID("identityId"),
                            tag.getInt("colonyId"),
                            tag.hasUUID("target") ? tag.getUUID("target") : null,
                            tag.getDouble("stolenMagicule"),   // absent (v1 tags) → 0
                            tag.getDouble("stolenAura"));
                }

                @Override
                public CompoundTag write(AssassinTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putUUID("identityId", value.identityId);
                    tag.putInt("colonyId", value.colonyId);
                    if (value.targetPlayer != null) tag.putUUID("target", value.targetPlayer);
                    tag.putDouble("stolenMagicule", value.stolenMagicule);
                    tag.putDouble("stolenAura", value.stolenAura);
                    return tag;
                }
            };
}
