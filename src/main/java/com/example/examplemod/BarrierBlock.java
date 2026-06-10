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
     * Visual fill stage, driven by the tank: 0–33% → 0, 33–66% → 1,
     * 66–<100% → 2, exactly full → 3.
     * {@link BarrierBlockEntity#syncChargeState} writes it as stored
     * magicule moves; the blockstate JSON maps each stage to a sprite
     * (4 sprites per tier). Property name kept as {@code charge} for
     * save compatibility with pre-tier worlds.
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty CHARGE =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("charge", 0, 3);

    /** Barrier field radius (square half-extent, blocks) by tier 1..4. */
    public static final double[] TIER_RADIUS = { 16.0, 28.0, 42.0, 60.0 };
    /** Base tank capacity (magicule) by tier 1..4 — before any connected
     *  {@link MagiculeStorageBlock} bonuses. */
    public static final double[] TIER_BASE_CAPACITY = { 100_000.0, 150_000.0, 200_000.0, 250_000.0 };

    private final int tier; // 1..4

    public BarrierBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(getStateDefinition().any().setValue(CHARGE, 0));
    }

    public int tier() {
        return tier;
    }

    public double radius() {
        return TIER_RADIUS[tier - 1];
    }

    public double baseCapacity() {
        return TIER_BASE_CAPACITY[tier - 1];
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(CHARGE);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(props -> new BarrierBlock(this.tier, props));
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
        // Right-click (empty hand) opens the Barrier Core menu — gauge,
        // channel/withdraw buttons, and the layers control. The earlier
        // direct-channel click is superseded by the menu's +/MAX buttons;
        // crystal refuel via useItemOn still works without the menu.
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier)
                || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        Networking.sendBarrierMenuTo(sp, barrier);
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hitResult) {
        double value = crystalValue(stack);
        if (value <= 0) {
            // Not a crystal — fall through to useWithoutItem (the menu).
            // PASS, not SKIP: SKIP_DEFAULT_BLOCK_INTERACTION suppresses
            // useWithoutItem entirely, which made the menu unopenable
            // while holding ANY non-crystal item.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        double accepted = barrier.addToPool(value);
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

    /** Refuel value of a Tensura magic crystal, or 0 for any other item.
     *  Package-visible — MagiculeStorageBlock accepts crystals too. */
    static double crystalValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.is(TensuraMobDropItems.LOW_QUALITY_MAGIC_CRYSTAL.get()))    return BarrierBlockEntity.CRYSTAL_LOW_MAGICULE;
        if (stack.is(TensuraMobDropItems.MEDIUM_QUALITY_MAGIC_CRYSTAL.get())) return BarrierBlockEntity.CRYSTAL_MEDIUM_MAGICULE;
        if (stack.is(TensuraMobDropItems.HIGH_QUALITY_MAGIC_CRYSTAL.get()))   return BarrierBlockEntity.CRYSTAL_HIGH_MAGICULE;
        return 0;
    }
}
