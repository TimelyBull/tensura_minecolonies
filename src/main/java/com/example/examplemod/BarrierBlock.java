package com.example.examplemod;

import io.github.manasmods.tensura.registry.item.TensuraMobDropItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The magicule barrier — a block that, while fueled with magicules AND a
 * Tensura raid is active at the colony, projects a protective field
 * around itself that raid mobs cannot cross. Raiders pressing the field
 * drain it (EP-scaled); when the tank empties the field falls.
 * See {@link BarrierBlockEntity} for the mechanics and
 * docs/raid-system.md for the design.
 *
 * Interactions:
 * <ul>
 *   <li>Right-click (empty hand): status readout (stored / capacity).</li>
 *   <li>Sneak-right-click (empty hand): channel the player's own
 *       magicule into the tank.</li>
 *   <li>Right-click with a Tensura magic crystal: consume one for a
 *       fixed refuel amount (low / medium / high quality).</li>
 * </ul>
 */
public class BarrierBlock extends BaseEntityBlock {

    public BarrierBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(BarrierBlock::new);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // BaseEntityBlock defaults to INVISIBLE
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BarrierBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ExampleMod.BARRIER_BLOCK_ENTITY.get(),
                BarrierBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            double moved = barrier.channelFromPlayer(player);
            if (moved > 0) {
                player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                        "Channeled %,.0f magicule into the barrier (%s)",
                        moved, barrier.fillReadout())), true);
            } else {
                player.displayClientMessage(Component.literal(
                        barrier.isFull() ? "The barrier is fully charged."
                                         : "You have no magicule to channel."), true);
            }
        } else {
            player.displayClientMessage(
                    Component.literal("Magicule barrier: " + barrier.fillReadout()), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        double value = crystalValue(stack);
        if (value <= 0) {
            // Not a crystal — fall through to useWithoutItem (status/channel).
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        double accepted = barrier.addMagicule(value);
        if (accepted <= 0) {
            player.displayClientMessage(Component.literal("The barrier is fully charged."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "The crystal dissolves — +%,.0f magicule (%s)",
                accepted, barrier.fillReadout())), true);
        return ItemInteractionResult.CONSUME;
    }

    /** Refuel value of a Tensura magic crystal, or 0 for any other item. */
    private static double crystalValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.is(TensuraMobDropItems.LOW_QUALITY_MAGIC_CRYSTAL.get()))    return BarrierBlockEntity.CRYSTAL_LOW_MAGICULE;
        if (stack.is(TensuraMobDropItems.MEDIUM_QUALITY_MAGIC_CRYSTAL.get())) return BarrierBlockEntity.CRYSTAL_MEDIUM_MAGICULE;
        if (stack.is(TensuraMobDropItems.HIGH_QUALITY_MAGIC_CRYSTAL.get()))   return BarrierBlockEntity.CRYSTAL_HIGH_MAGICULE;
        return 0;
    }
}
