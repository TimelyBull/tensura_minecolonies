package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * STAGE 1 — the single piece of the {@link FactionAnchorStructure} marker.
 * For now it places a short gold-block column at the structure's surface
 * position so the structure is visually findable as well as {@code /locate}-able.
 * In a later stage this is what gets replaced by (or extended to trigger) the
 * real settlement populate; for Stage 1 it just proves a custom structure
 * registers, generates ahead of the player, and is locatable.
 */
public class FactionAnchorPiece extends StructurePiece {

    private static final int MARKER_HEIGHT = 4;

    /** Generation constructor — a small box centred on {@code pos}; the visible
     *  marker is the centre column. Detection no longer depends on this box's
     *  size: {@link RivalColonies#tickWorldgenSettlements} finds the start by
     *  scanning chunks near the player (BB-independent), so a tiny marker box is
     *  fine and works for already-generated anchors too. */
    public FactionAnchorPiece(BlockPos pos) {
        super(FactionStructures.FACTION_ANCHOR_PIECE.get(), 0,
                new BoundingBox(
                        pos.getX() - 1, pos.getY(), pos.getZ() - 1,
                        pos.getX() + 1, pos.getY() + MARKER_HEIGHT, pos.getZ() + 1));
        setOrientation(Direction.NORTH); // identity transform for placeBlock()
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
        // Relative (1, dy, 1) = the box centre column. placeBlock only writes
        // inside `box` (the current chunk's writable region).
        for (int dy = 0; dy < MARKER_HEIGHT; dy++) {
            placeBlock(level, Blocks.GOLD_BLOCK.defaultBlockState(), 1, dy, 1, box);
        }
    }
}
