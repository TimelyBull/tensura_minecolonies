package com.example.examplemod;

import java.util.Optional;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * STAGE 1 — the first real, data-driven worldgen structure (Option C scaffold).
 * A minimal "anchor": it generates a single {@link FactionAnchorPiece} marker at
 * the chunk-centre surface. The point of Stage 1 is to prove a CUSTOM structure
 * registers in this modpack, generates AHEAD of the player during chunk
 * generation (never retroactively into explored chunks), and is {@code /locate}-
 * and {@code /place}-able — with spacing/biome control coming from its
 * {@code structure_set} + biome tag JSON.
 *
 * <p>The placement rules (where/how often), biome gating and generation step
 * live in the data JSON:
 * <ul>
 *   <li>{@code data/tensura_minecolonies/worldgen/structure/faction_anchor_falmuth.json}</li>
 *   <li>{@code data/tensura_minecolonies/worldgen/structure_set/faction_anchor_falmuth.json}</li>
 *   <li>{@code data/tensura_minecolonies/tags/worldgen/biome/has_structure/faction_anchor.json}</li>
 * </ul>
 *
 * <p>Later stages replace the marker piece with the real settlement populate
 * (reusing {@link RivalColonies#generateColony} + the pack-readiness guard).
 */
public class FactionAnchorStructure extends Structure {

    /** {@code simpleCodec} = just the base StructureSettings (biomes / step /
     *  spawn_overrides / terrain_adaptation); no custom fields yet. */
    public static final MapCodec<FactionAnchorStructure> CODEC =
            simpleCodec(FactionAnchorStructure::new);

    public FactionAnchorStructure(Structure.StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
        ChunkPos chunkPos = context.chunkPos();
        int x = chunkPos.getMiddleBlockX();
        int z = chunkPos.getMiddleBlockZ();
        int y = context.chunkGenerator().getFirstFreeHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        BlockPos pos = new BlockPos(x, y, z);
        return Optional.of(new Structure.GenerationStub(pos,
                builder -> builder.addPiece(new FactionAnchorPiece(pos))));
    }

    @Override
    public StructureType<?> type() {
        return FactionStructures.FACTION_ANCHOR.get();
    }
}
