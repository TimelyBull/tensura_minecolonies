package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;

/**
 * Magicule Storage — expands a Barrier Core's tank.
 *
 * <p>A storage block contributes its {@link #capacityBonus} to any
 * {@link BarrierBlock} core it is CONNECTED to: connection = a chain of
 * 6-way-adjacent storage blocks rooted at one touching the core (the
 * core flood-fills its network once per second — see
 * {@link BarrierBlockEntity#recomputeStorageBonus}). Placing or breaking
 * storage anywhere in the network therefore updates the core's capacity
 * within a second, with no neighbor-event plumbing.
 *
 * <p>Four tiers (registered as four blocks): +25k / +75k / +150k / +300k
 * magicule. No BlockEntity — the block is pure passive capacity; the
 * core's tank holds the magicule. Single sprite per tier (no fill
 * states). Right-click reports the tier's bonus.
 */
public class MagiculeStorageBlock extends Block {

    /** Capacity added to a connected core, by tier index 0..3. */
    public static final double[] STORAGE_BONUS = { 25_000.0, 75_000.0, 150_000.0, 300_000.0 };

    private final int tier; // 1..4

    public MagiculeStorageBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    public double capacityBonus() {
        return STORAGE_BONUS[tier - 1];
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "Magicule Storage (Tier %d): +%,.0f capacity to a connected barrier core",
                tier, capacityBonus())), true);
        return InteractionResult.CONSUME;
    }
}
