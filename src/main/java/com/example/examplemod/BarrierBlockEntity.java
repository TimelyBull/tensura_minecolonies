package com.example.examplemod;

import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Magicule-barrier tank + field driver. See docs/raid-system.md.
 *
 * <p><b>Field semantics (v1):</b> while {@code storedMagicule > 0} AND a
 * Tensura raid is active for the colony at this block, RAID-tagged mobs
 * are kept outside {@link #BARRIER_RADIUS} of the block — each tick any
 * raider inside the shell is clamped back to the surface with zeroed
 * horizontal velocity (the proven direct-entity-driving technique from
 * the swap sink/rise animations; clamping avoids jitter).
 *
 * <p>The shell is a <b>vertical cylinder</b> (horizontal distance only),
 * not a literal sphere — a 3D clamp on sloped terrain can bury ground
 * mobs; the cylinder reads identically in play and behaves on hills.
 * (Recorded as a divergence from the investigation sketch.)
 *
 * <p><b>EP-scaled drain:</b> each raider pressing the shell (within
 * {@link #CONTACT_BAND} of the surface) drains
 * {@code EP × BARRIER_DRAIN_COEFFICIENT_PER_SECOND / 20} magicule per
 * tick — a stronger enemy collapses the barrier faster. The EP read is
 * the same {@code IExistence} read the roster / cost gate uses.
 */
public class BarrierBlockEntity extends BlockEntity {

    // ------------------------------------------------------------------
    // Tuning constants (v1 starting values — see docs for the math)
    // ------------------------------------------------------------------

    /** Maximum magicule the tank holds. */
    public static final double BARRIER_CAPACITY = 100_000.0;
    /** Field radius (horizontal, blocks). */
    public static final double BARRIER_RADIUS = 16.0;
    /**
     * THE drain knob: fraction of a raider's EP drained from the barrier
     * PER SECOND while that raider presses the shell. 0.02 = each raider
     * drains 2% of its own EP per second (a 3,000-EP mob → 60/s; an
     * 8-mob wave of those empties a full 100k tank in ~3.5 minutes of
     * constant press).
     */
    public static final double BARRIER_DRAIN_COEFFICIENT_PER_SECOND = 0.02;
    /** EP assumed for a raider whose existence storage can't be read. */
    public static final double FALLBACK_RAIDER_EP = 1_000.0;
    /** How far past the shell surface still counts as "pressing" it. */
    public static final double CONTACT_BAND = 1.5;
    /** Player magicule moved per sneak-right-click channel. */
    public static final double PLAYER_CHANNEL_PER_CLICK = 2_500.0;
    /** Crystal refuel values (low / medium / high quality magic crystal). */
    public static final double CRYSTAL_LOW_MAGICULE    = 2_500.0;
    public static final double CRYSTAL_MEDIUM_MAGICULE = 10_000.0;
    public static final double CRYSTAL_HIGH_MAGICULE   = 40_000.0;

    private double storedMagicule = 0.0;
    /** Edge-detect for the "barrier has fallen" alarm. */
    private boolean fieldWasUp = false;
    /** Per-second cache of "is a raid active for this block's colony". */
    private boolean raidActiveCache = false;

    public BarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ExampleMod.BARRIER_BLOCK_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Tank
    // ------------------------------------------------------------------

    /** Add magicule; returns the amount actually accepted. */
    public double addMagicule(double amount) {
        double accepted = Math.max(0, Math.min(amount, BARRIER_CAPACITY - storedMagicule));
        if (accepted > 0) {
            storedMagicule += accepted;
            setChanged();
            syncChargeState();
        }
        return accepted;
    }

    /** Push the tank's fill stage into the {@link BarrierBlock#CHARGE}
     *  blockstate so the texture tracks the charge. Mapping (per design
     *  review): thirds for the first three textures, and the
     *  fully-charged texture reserved for an actually-FULL tank —
     *  0–33% → 0, 33–66% → 1, 66–<100% → 2, 100% → 3.
     *  No-op when the stage hasn't changed. */
    private void syncChargeState() {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(BarrierBlock.CHARGE)) return;
        int stage;
        if (storedMagicule >= BARRIER_CAPACITY) {
            stage = 3;
        } else {
            stage = (int) Math.min(2, Math.floor(storedMagicule / BARRIER_CAPACITY * 3.0));
        }
        stage = Math.max(0, stage);
        if (state.getValue(BarrierBlock.CHARGE) != stage) {
            level.setBlock(worldPosition, state.setValue(BarrierBlock.CHARGE, stage), 3);
        }
    }

    public boolean isFull() {
        return storedMagicule >= BARRIER_CAPACITY;
    }

    public String fillReadout() {
        return String.format(Locale.ROOT, "%,.0f / %,.0f magicule",
                storedMagicule, BARRIER_CAPACITY);
    }

    /** Sneak-right-click refuel — move up to {@link #PLAYER_CHANNEL_PER_CLICK}
     *  of the PLAYER'S own magicule into the tank. Same read/write the
     *  swap-cost code uses everywhere. Returns the amount moved. */
    public double channelFromPlayer(Player player) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double available = Math.max(0, exist.getMagicule());
        double move = Math.min(PLAYER_CHANNEL_PER_CLICK,
                Math.min(available, BARRIER_CAPACITY - storedMagicule));
        if (move <= 0) return 0;
        exist.setMagicule(available - move);
        exist.markDirty();
        storedMagicule += move;
        setChanged();
        syncChargeState();
        return move;
    }

    // ------------------------------------------------------------------
    // Field driver — every server tick
    // ------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, BarrierBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        long gameTime = serverLevel.getGameTime();

        // Cache the raid-active check once per second (colony scan); the
        // charge-stage texture sync rides the same cadence (covers drain,
        // which changes storedMagicule every tick during a press).
        if (gameTime % 20 == 0) {
            be.syncChargeState();
            be.raidActiveCache = TensuraRaids.isRaidActiveNear(serverLevel, pos);
            if (be.storedMagicule > 0) {
                // Steering reads this to send raiders at the barrier first.
                TensuraRaids.reportActiveBarrier(serverLevel, pos);
            } else {
                TensuraRaids.reportBarrierDown(serverLevel, pos);
            }
        }

        boolean fieldUp = be.storedMagicule > 0 && be.raidActiveCache;

        // Depletion alarm — fires once on the up→down edge during a raid.
        if (be.fieldWasUp && !fieldUp && be.raidActiveCache) {
            serverLevel.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.5f, 0.6f);
            alertNearbyPlayers(serverLevel, pos,
                    Component.literal("The magicule barrier has fallen!")
                            .withStyle(net.minecraft.ChatFormatting.RED));
        }
        be.fieldWasUp = fieldUp;
        if (!fieldUp) return;

        Vec3 center = Vec3.atCenterOf(pos);
        double drainThisTick = 0.0;

        for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class,
                AABB.ofSize(center, (BARRIER_RADIUS + CONTACT_BAND) * 2 + 2, 64,
                        (BARRIER_RADIUS + CONTACT_BAND) * 2 + 2),
                m -> m.isAlive() && m.hasData(Attachments.RAID_TAG.get()))) {

            double dx = mob.getX() - center.x;
            double dz = mob.getZ() - center.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);

            // Pushback — clamp any raider inside the shell back to the
            // surface, horizontal velocity zeroed (vertical kept so
            // gravity still applies; no fall-damage accumulation since
            // the clamp is horizontal-only).
            if (horizDist < BARRIER_RADIUS) {
                double nx, nz;
                if (horizDist < 0.001) { nx = 1; nz = 0; }
                else { nx = dx / horizDist; nz = dz / horizDist; }
                double targetX = center.x + nx * (BARRIER_RADIUS + 0.25);
                double targetZ = center.z + nz * (BARRIER_RADIUS + 0.25);
                mob.setPos(targetX, mob.getY(), targetZ);
                Vec3 vel = mob.getDeltaMovement();
                mob.setDeltaMovement(0, Math.min(0, vel.y), 0);
                horizDist = BARRIER_RADIUS + 0.25;
            }

            // EP-scaled contact drain for raiders pressing the shell.
            if (horizDist <= BARRIER_RADIUS + CONTACT_BAND) {
                ExistenceStorage exist = ExampleMod.readExistence(mob);
                double ep = exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_RAIDER_EP;
                drainThisTick += ep * BARRIER_DRAIN_COEFFICIENT_PER_SECOND / 20.0;

                // Visible "attacking the barrier": once a second the
                // presser faces the block, swings, and crit particles
                // burst at its impact point on the shell. Pure show —
                // the drain above IS the damage.
                if (gameTime % 20 == 0) {
                    mob.getLookControl().setLookAt(center.x, center.y, center.z);
                    mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    double ix = center.x + (dx / Math.max(horizDist, 0.001)) * BARRIER_RADIUS;
                    double iz = center.z + (dz / Math.max(horizDist, 0.001)) * BARRIER_RADIUS;
                    serverLevel.sendParticles(ParticleTypes.CRIT,
                            ix, mob.getY() + mob.getBbHeight() * 0.6, iz,
                            8, 0.2, 0.3, 0.2, 0.05);
                }
            }
        }

        // Pounding audio — one knock per 2 s while ANYTHING presses the
        // shell (per-mob sounds would stack into noise on big waves).
        if (drainThisTick > 0 && gameTime % 40 == 0) {
            serverLevel.playSound(null, pos, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR,
                    SoundSource.HOSTILE, 1.0f, 0.8f);
        }

        if (drainThisTick > 0) {
            be.storedMagicule = Math.max(0, be.storedMagicule - drainThisTick);
            be.setChanged();
        }

        // Particle shell — once per second while the field is up.
        if (gameTime % 20 == 0) {
            spawnShellParticles(serverLevel, center);
        }
    }

    /** Three rings of END_ROD particles marking the field's edge. */
    private static void spawnShellParticles(ServerLevel level, Vec3 center) {
        double[] heights = { 0.5, 2.5, 4.5 };
        int points = 16;
        for (double h : heights) {
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2 * i) / points;
                level.sendParticles(ParticleTypes.END_ROD,
                        center.x + Math.cos(angle) * BARRIER_RADIUS,
                        center.y + h,
                        center.z + Math.sin(angle) * BARRIER_RADIUS,
                        1, 0, 0.02, 0, 0);
            }
        }
    }

    private static void alertNearbyPlayers(ServerLevel level, BlockPos pos, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) < 64 * 64) {
                player.sendSystemMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("storedMagicule", storedMagicule);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storedMagicule = tag.getDouble("storedMagicule");
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TensuraRaids.reportBarrierDown(serverLevel, worldPosition);
        }
        super.setRemoved();
    }
}
