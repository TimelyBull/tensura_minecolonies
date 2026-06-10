package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Magicule Storage — expands a Barrier Core's capacity by holding the
 * overflow in its OWN tank ({@link StorageBlockEntity}).
 *
 * <p>How the system works behind the scenes: the core's UI shows one
 * big pool (base + connected storage capacities), but physically the
 * core's base tank fills FIRST and any further charge is disbursed into
 * connected storage blocks (literal overflow storage). Drain pulls the
 * core first, then the storage reserves. Connection = a chain of
 * 6-way-adjacent storage blocks rooted at the core (the core walks the
 * network once per second).
 *
 * <p>Four tiers: capacity +25k / +75k / +150k / +300k. Right-click
 * reports this block's own contents; right-click with a magic crystal
 * charges the NETWORK pool (sacrificing the crystal), or this block's
 * own buffer if it isn't connected to any core. The FILL blockstate
 * sprite reflects this block's OWN contents.
 */
public class MagiculeStorageBlock extends Block implements EntityBlock {

    /** Capacity added to a connected core, by tier index 0..3 — and
     *  exactly this block's own tank size. */
    public static final double[] STORAGE_BONUS = { 25_000.0, 75_000.0, 150_000.0, 300_000.0 };

    /**
     * Visual fill stage of THIS block's own tank: 0 = base sprite
     * (under 33%), 1 = 33%+, 2 = 66%+, 3 = full. Written by
     * {@link StorageBlockEntity#syncFillSprite} whenever its contents
     * move.
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty FILL =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("fill", 0, 3);

    private final int tier; // 1..4

    public MagiculeStorageBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(getStateDefinition().any().setValue(FILL, 0));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILL);
    }

    public int tier() {
        return tier;
    }

    public double capacityBonus() {
        return STORAGE_BONUS[tier - 1];
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof StorageBlockEntity be) {
            player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                    "Magicule Storage (Tier %d): %,.0f / %,.0f magicule",
                    tier, be.getStored(), be.getCapacity())), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        double value = BarrierBlock.crystalValue(stack);
        if (value <= 0) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;

        // Prefer the connected core's pool (core-first fill, overflow
        // back into storage); orphan storage charges its own buffer.
        double accepted;
        BarrierBlockEntity core = findNetworkCore(level, pos);
        if (core != null) {
            accepted = core.addToPool(value);
        } else if (level.getBlockEntity(pos) instanceof StorageBlockEntity be) {
            accepted = be.fill(value);
        } else {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (accepted <= 0) {
            player.displayClientMessage(Component.literal("Storage is fully charged."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "The crystal dissolves — +%,.0f magicule", accepted)), true);
        return ItemInteractionResult.CONSUME;
    }

    /** BFS through adjacent storage blocks for the network's Barrier
     *  Core (mirror of the core's own walk, from the storage side). */
    @Nullable
    static BarrierBlockEntity findNetworkCore(Level level, BlockPos start) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        queue.add(start);
        visited.add(start);
        int seen = 0;
        while (!queue.isEmpty() && seen < BarrierBlockEntity.MAX_STORAGE_NETWORK) {
            BlockPos cur = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.add(next)) continue;
                BlockState ns = level.getBlockState(next);
                if (ns.getBlock() instanceof BarrierBlock
                        && level.getBlockEntity(next) instanceof BarrierBlockEntity core) {
                    return core;
                }
                if (ns.getBlock() instanceof MagiculeStorageBlock) {
                    seen++;
                    queue.add(next);
                }
            }
        }
        return null;
    }
}
