package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.UUID;

/**
 * Marks a MineColonies citizen entity as a beast-guard body — parallel
 * to {@link RaceTag} but for {@link Beast}s rather than {@link Race}s.
 *
 * <p>Beast citizens render through {@code KnightSpiderCitizenRenderHandler}
 * (shadow-entity pattern, GeoEntityRenderer), are assigned to
 * {@link JobBeastGuard}, and never participate in the worker-race
 * systems (skill profiles, envoy diplomacy, race-picker). The
 * {@link #identityId} links back to the original {@code RaceIdentity}
 * record in {@link RaceIdentitySavedData} — beast identities reuse the
 * same SavedData with {@code race == null} as the beast discriminator.
 *
 * <p>Stage 1 carries no per-citizen variant data — the spider has
 * no per-instance appearance fields to capture. Future beasts may
 * need one (similar to {@code GoblinVariantData}).
 */
public record BeastTag(UUID identityId, Beast beast) {

    public static final IAttachmentSerializer<CompoundTag, BeastTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public BeastTag read(IAttachmentHolder holder, CompoundTag tag,
                                     HolderLookup.Provider registries) {
                    UUID identityId = tag.hasUUID("identityId")
                            ? tag.getUUID("identityId")
                            : null;
                    Beast beast = Beast.byId(tag.getByte("beast") & 0xFF);
                    return new BeastTag(identityId, beast);
                }

                @Override
                public CompoundTag write(BeastTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    if (value.identityId != null) tag.putUUID("identityId", value.identityId);
                    tag.putByte("beast", (byte) value.beast.getId());
                    return tag;
                }
            };
}
