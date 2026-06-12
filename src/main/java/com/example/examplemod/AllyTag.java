package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Ally-support marker (diplomacy Stage 3) — stamped on the friendly
 * combatants an ALLIED faction sends when the player's colony is
 * raided. Mirror of {@link RaidTag} on the other side of the fight:
 * the per-second raid drive steers ally-tagged mobs onto the raiders
 * (the same dual-write target-assist), and raid resolution poofs them
 * home. NBT-persisted so a save/reload mid-raid keeps them linked.
 *
 * @param colonyId the raided colony
 * @param eventId  the raid event they were sent to
 * @param factionId which ally sent them (messages/bookkeeping)
 */
public record AllyTag(int colonyId, int eventId, String factionId) {

    public static final IAttachmentSerializer<CompoundTag, AllyTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public AllyTag read(IAttachmentHolder holder, CompoundTag tag,
                                    HolderLookup.Provider registries) {
                    return new AllyTag(tag.getInt("colonyId"), tag.getInt("eventId"),
                            tag.getString("factionId"));
                }

                @Override
                public CompoundTag write(AllyTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    tag.putInt("eventId", value.eventId);
                    tag.putString("factionId", value.factionId);
                    return tag;
                }
            };
}
