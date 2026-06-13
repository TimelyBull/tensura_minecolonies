package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Garrison-member marker — NeoForge data attachment stamped on every
 * defender (and the anchor boss) of a rival-colony {@link Settlement}
 * (rival-colony arc, Stage B). Sibling of {@link RaidTag}.
 *
 * <p>It is the universal "is this a garrison member" check used by:
 * <ul>
 *   <li>the per-second tether pass (defenders don't wander off),</li>
 *   <li>the assault death-tally (the 60%-win bookkeeping —
 *       {@link #isBoss} routes a death to the boss-down flag vs the
 *       defender-kill counter).</li>
 * </ul>
 *
 * <p>NBT-persisted, so a save/reload keeps every defender linked to its
 * settlement (matched by {@link #settlementId}). The boss additionally
 * carries a {@link FactionMarkTag} for the Layer-1 marked-kill fan-out —
 * the two attachments serve different systems and coexist.
 */
public record GarrisonTag(int settlementId, boolean isBoss) {

    public static final IAttachmentSerializer<CompoundTag, GarrisonTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public GarrisonTag read(IAttachmentHolder holder, CompoundTag tag,
                                        HolderLookup.Provider registries) {
                    return new GarrisonTag(tag.getInt("settlementId"), tag.getBoolean("isBoss"));
                }

                @Override
                public CompoundTag write(GarrisonTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("settlementId", value.settlementId);
                    tag.putBoolean("isBoss", value.isBoss);
                    return tag;
                }
            };
}
