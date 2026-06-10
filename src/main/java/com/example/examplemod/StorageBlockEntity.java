package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A Magicule Storage block's OWN tank — the behind-the-scenes system
 * that makes storage "increase the core's capacity": the core's base
 * tank fills first, and overflow is disbursed into these per-block
 * buffers (literal overflow storage). Drain pulls the core first, then
 * these reserves.
 *
 * <p>Deliberately dumb: no ticker, no network logic. The connected
 * Barrier Core orchestrates everything through its once-per-second
 * network walk and its pool operations
 * ({@link BarrierBlockEntity#addToPool} / {@code drainFromPool}); this
 * BE just holds magicule (capacity = the block's tier bonus) and keeps
 * its FILL sprite in sync with ITS OWN contents.
 */
public class StorageBlockEntity extends BlockEntity {

    private double stored = 0.0;

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        super(ExampleMod.STORAGE_BLOCK_ENTITY.get(), pos, state);
    }

    /** This block's own capacity — the tier's bonus value. */
    public double getCapacity() {
        return getBlockState().getBlock() instanceof MagiculeStorageBlock b
                ? b.capacityBonus() : MagiculeStorageBlock.STORAGE_BONUS[0];
    }

    public double getStored() {
        return stored;
    }

    /** Accepts up to {@code amount}; returns what was taken in. */
    public double fill(double amount) {
        double accepted = Math.max(0, Math.min(amount, getCapacity() - stored));
        if (accepted > 0) {
            stored += accepted;
            setChanged();
            syncFillSprite();
        }
        return accepted;
    }

    /** Removes up to {@code amount}; returns what came out. */
    public double drain(double amount) {
        double taken = Math.max(0, Math.min(amount, stored));
        if (taken > 0) {
            stored -= taken;
            setChanged();
            syncFillSprite();
        }
        return taken;
    }

    /** FILL sprite stage from THIS block's own contents — base sprite
     *  under 33%, then the three fill versions (66%, full). */
    void syncFillSprite() {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(MagiculeStorageBlock.FILL)) return;
        int stage;
        if (stored >= getCapacity()) stage = 3;
        else stage = Math.max(0, (int) Math.min(2, Math.floor(stored / getCapacity() * 3.0)));
        if (state.getValue(MagiculeStorageBlock.FILL) != stage) {
            level.setBlock(worldPosition, state.setValue(MagiculeStorageBlock.FILL, stage), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("storedMagicule", stored);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored = tag.getDouble("storedMagicule");
    }
}
