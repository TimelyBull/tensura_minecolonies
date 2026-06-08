package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Standing-order marker for the "Patrol Colony Outskirts" subordinate
 * command.
 *
 * Attached to a named Tensura subordinate (any {@code ISubordinate}) when
 * the player cycles its right-click command to PATROL. Presence of the
 * attachment IS the command: while it's present the
 * {@link SubordinatePatrol} tick driver keeps the mob patrolling the outer
 * area of the recorded colony; cycling the command away (PATROL → FOLLOW)
 * removes it.
 *
 * Two fields pin the patrol to the colony that was nearest to the PLAYER at
 * the moment the order was issued, so the subordinate keeps patrolling that
 * colony even after the player walks away:
 * <ul>
 *   <li>{@link #colonyId} — MineColonies colony id (resolved each tick via
 *       {@code IColonyManager.getColonyByWorld(colonyId, level)}).</li>
 *   <li>{@link #dimension} — the colony's dimension, so a subordinate that
 *       wanders / is summoned across dimensions doesn't try to patrol a
 *       colony that isn't in its level.</li>
 * </ul>
 *
 * Persists across save/load, entity unload/reload, and relog via the NBT
 * serializer — the same mechanism {@link RaceTag} and {@link EnvoyTag} use.
 * That, plus Tensura persisting its own {@code isWandering}/{@code behaviour}
 * flags on the entity, is what makes the order a true standing order: on
 * reload {@code EntityTickEvent.Post} sees the attachment again and resumes.
 *
 * {@code entity.hasData(Attachments.PATROL_ORDER.get())} is the authoritative
 * presence check (default value supplier returns null).
 */
public record PatrolOrder(int colonyId, ResourceLocation dimension) {

    /** @return the colony's dimension as a level {@link ResourceKey}. */
    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension);
    }

    public static final IAttachmentSerializer<CompoundTag, PatrolOrder> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public PatrolOrder read(IAttachmentHolder holder, CompoundTag tag,
                                        HolderLookup.Provider registries) {
                    int colonyId = tag.getInt("colonyId");
                    ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
                    if (dim == null) dim = Level.OVERWORLD.location();
                    return new PatrolOrder(colonyId, dim);
                }

                @Override
                public CompoundTag write(PatrolOrder value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    tag.putString("dimension", value.dimension.toString());
                    return tag;
                }
            };
}
