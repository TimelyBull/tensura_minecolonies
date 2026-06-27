package com.example.examplemod;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * STAGE 1 — code-side registration for the worldgen-structure rework.
 * Registers the custom {@link StructureType} (the structure's codec) and the
 * {@link StructurePieceType} (the marker piece) to their vanilla registries,
 * mirroring the mod's existing DeferredRegister idiom (see ExampleMod's
 * BLOCKS/ITEMS/BLOCK_ENTITIES, Attachments). The actual {@code Structure},
 * {@code StructureSet} and biome tag are data-driven JSON under
 * {@code resources/data/tensura_minecolonies/worldgen|tags}.
 *
 * <p>Wired from {@link ExampleMod}'s constructor via {@link #register}.
 */
public final class FactionStructures {

    private FactionStructures() {}

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, ExampleMod.MODID);

    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, ExampleMod.MODID);

    /** The structure type = a supplier of the structure's MapCodec. */
    public static final Supplier<StructureType<FactionAnchorStructure>> FACTION_ANCHOR =
            STRUCTURE_TYPES.register("faction_anchor",
                    () -> () -> FactionAnchorStructure.CODEC);

    /** The marker piece type — contextless load from NBT. */
    public static final Supplier<StructurePieceType> FACTION_ANCHOR_PIECE =
            STRUCTURE_PIECES.register("faction_anchor",
                    () -> (StructurePieceType.ContextlessType) FactionAnchorPiece::new);

    public static void register(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
        STRUCTURE_PIECES.register(modBus);
    }
}
