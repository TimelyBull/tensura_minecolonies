package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Colony-defender marker — NeoForge data attachment stamped on a Tensura
 * subordinate body that was place-swapped IN from its colonist form to
 * fight off a threat (see {@link ColonyThreatResponse}). Sibling of
 * {@link RaidTag} / {@link AllyTag}.
 *
 * <p>It is the autocaster predicate key: the Nightmare's Tensura Utils
 * autocaster registered in {@link ColonyThreatResponse#registerAutocaster()}
 * drives spell use on any mob carrying this tag. {@link #colonyId} lets the
 * per-second steering pass find the right colony's raiders to target.
 *
 * <p>NBT-persisted, so a save/reload mid-defense keeps the body recognised
 * as a defender (the autocaster keeps firing without waiting for the
 * evaluator to re-tag it). The authoritative "is this citizen defending"
 * record is still the persistent {@code defendingColony} flag on its
 * {@link RaceIdentitySavedData.RaceIdentity}; this tag is the per-tick O(1)
 * presence check on the live body.
 */
public record ColonyDefenderTag(int colonyId) {

    public static final IAttachmentSerializer<CompoundTag, ColonyDefenderTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public ColonyDefenderTag read(IAttachmentHolder holder, CompoundTag tag,
                                              HolderLookup.Provider registries) {
                    return new ColonyDefenderTag(tag.getInt("colonyId"));
                }

                @Override
                public CompoundTag write(ColonyDefenderTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    return tag;
                }
            };
}
