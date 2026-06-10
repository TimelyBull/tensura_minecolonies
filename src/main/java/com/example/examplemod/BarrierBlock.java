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

    /**
     * Visual fill stage, driven by the tank: 0 = faint charge (0–25%),
     * 1 = half (25–50%), 2 = nearly charged (50–75%), 3 = full (75–100%).
     * {@link BarrierBlockEntity#syncChargeState} writes it whenever the
     * stored amount crosses a quarter boundary; the four player-supplied
     * textures map one per stage in the blockstate JSON.
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty CHARGE =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("charge", 0, 3);

    public BarrierBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(CHARGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(CHARGE);
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
        // Channel on ANY empty-hand right-click (sneaking or not).
        //
        // The original design gated channeling behind sneak-right-click,
        // but vanilla's interaction pipeline skips block interactions
        // entirely when a sneaking player holds ANY item in either hand
        // (ServerPlayerGameMode checks isSecondaryUseActive before the
        // block ever sees the click) — so with a torch/shield in the
        // offhand the channel never fired. Plain right-click is the
        // reliable path; the fill readout rides along in every message,
        // which also covers the old status-only readout.
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier)) {
            return InteractionResult.PASS;
        }
        double moved = barrier.channelFromPlayer(player);
        if (moved > 0) {
            player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                    "Channeled %,.0f magicule into the barrier (%s)",
                    moved, barrier.fillReadout())), true);
        } else if (barrier.isFull()) {
            player.displayClientMessage(Component.literal(
                    "The barrier is fully charged (" + barrier.fillReadout() + ")"), true);
        } else {
            player.displayClientMessage(Component.literal(
                    "You have no magicule to channel — barrier: " + barrier.fillReadout()), true);
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
