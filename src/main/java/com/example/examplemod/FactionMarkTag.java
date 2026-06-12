package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Faction-significance marker (docs/faction-model.md #3) — stamped on a
 * boss the FACTION ARRANGED (a lore event's lead boss, a future faction
 * raid spawn, the {@code /worldrep mark} debug command). Sibling of
 * {@link RaidTag} / {@link EnvoyTag}.
 *
 * <p><b>Only marked kills carry faction consequences.</b> The world-rep
 * movers check this tag FIRST and read the faction id FROM the tag (the
 * tag is the authority — an addon can mark any entity for any faction id
 * without it appearing in our boss map). Wild and player-summoned bosses
 * never carry it, so "kill all bosses" progression stays consequence-free
 * on the faction layer (the colony +10 and envoy unlocks are separate
 * systems and fire either way).
 *
 * <p>Marking also titles the entity in the faction's color
 * ("Clayman's Orc Disaster") so consequence is VISIBLE before the swing
 * — see {@code WorldReputationManager.markBoss}.
 *
 * @param factionId     the wronged faction's string id ({@link BossFaction#id()}
 *                      for v1 factions; addon ids ride through untouched)
 * @param sourceEventId which system placed the mark ("debug", a lore
 *                      event id, ...) — bookkeeping/log aid only
 */
public record FactionMarkTag(String factionId, String sourceEventId) {

    public static final IAttachmentSerializer<CompoundTag, FactionMarkTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public FactionMarkTag read(IAttachmentHolder holder, CompoundTag tag,
                                           HolderLookup.Provider registries) {
                    return new FactionMarkTag(tag.getString("factionId"),
                            tag.getString("sourceEventId"));
                }

                @Override
                public CompoundTag write(FactionMarkTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("factionId", value.factionId);
                    tag.putString("sourceEventId", value.sourceEventId);
                    return tag;
                }
            };
}
