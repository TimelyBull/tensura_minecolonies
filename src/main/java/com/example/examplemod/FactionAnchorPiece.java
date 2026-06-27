package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * The single piece of the {@link FactionAnchorStructure} — an INVISIBLE anchor.
 * It places NO blocks; it exists only so the structure produces a valid,
 * {@code /locate}-able structure start that {@link RivalColonies#tickWorldgenSettlements}
 * can detect (via the chunk's structure data, not any blocks) to grow the real
 * settlement when a player approaches AND the faction system is enabled.
 *
 * <p>It used to place a gold-block "marker" column (Stage-1 debug visualisation).
 * That was REMOVED in 0.1.2: because worldgen can't read the runtime config, the
 * marker generated even with the faction system OFF (the default), littering
 * worlds with bare gold pillars that never became settlements. With no marker,
 * an un-populated anchor is invisible — nothing shows until a town actually
 * builds (faction system on + player nearby).
 */
public class FactionAnchorPiece extends StructurePiece {

    /** Tiny bounding box — just enough to record a valid structure start. */
    public FactionAnchorPiece(BlockPos pos) {
        super(FactionStructures.FACTION_ANCHOR_PIECE.get(), 0,
                new BoundingBox(
                        pos.getX() - 1, pos.getY(), pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
        setOrientation(Direction.NORTH);
    }

    /** Load constructor — the bounding box is restored by the base class. */
    public FactionAnchorPiece(CompoundTag tag) {
        super(FactionStructures.FACTION_ANCHOR_PIECE.get(), tag);
        setOrientation(Direction.NORTH);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        // No extra state — the base class persists the bounding box + orientation.
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pos) {
        // Intentionally empty — the anchor places nothing. The real settlement
        // (buildings + boss + garrison) is stamped at runtime on first approach,
        // gated by enableFactionSystem (see RivalColonies.populateSettlementAt).
    }
}
