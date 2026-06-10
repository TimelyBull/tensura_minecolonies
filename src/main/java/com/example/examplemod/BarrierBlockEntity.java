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
    /** Player magicule moved per channel/withdraw click (the menu's ±). */
    public static final double PLAYER_CHANNEL_PER_CLICK = 3_000.0;
    /** Max concentric barrier layers. */
    public static final int MAX_LAYERS = 3;
    /** Ring spacing — each extra layer sits this much further out. */
    public static final double LAYER_SPACING = 5.0;
    /** Passive upkeep per EXTRA layer (magicule/sec). Layer 1 is free —
     *  the base barrier keeps its no-peacetime-bleed behavior; layers 2
     *  and 3 each burn this on top of raider contact drain. */
    public static final double LAYER_UPKEEP_PER_SECOND = 50.0;
    /** Crystal refuel values (low / medium / high quality magic crystal). */
    public static final double CRYSTAL_LOW_MAGICULE    = 2_500.0;
    public static final double CRYSTAL_MEDIUM_MAGICULE = 10_000.0;
    public static final double CRYSTAL_HIGH_MAGICULE   = 40_000.0;

    /** Flood-fill cap on a storage network — performance guard. */
    public static final int MAX_STORAGE_NETWORK = 128;

    private double storedMagicule = 0.0;
    /** Edge-detect for the "barrier has fallen" alarm. */
    private boolean fieldWasUp = false;
    /** Per-second cache of "is a raid active for this block's colony". */
    private boolean raidActiveCache = false;
    /** Last stored value synced to clients — the wall render's alpha
     *  tracks the fill, so clients get a fresh value whenever it moves
     *  by more than ~0.5% of capacity (checked once per second). */
    private double lastSyncedMagicule = -1.0;
    /** Capacity added by the connected Magicule Storage network.
     *  Recomputed once per second by {@link #recomputeStorageBonus} and
     *  synced to clients inside the update tag (for readouts/render). */
    private double storageBonus = 0.0;
    /** Active concentric barrier layers (1..MAX_LAYERS). Layer 1 at the
     *  tier radius; each extra layer +LAYER_SPACING outward. Layers 2–3
     *  are Demon-Lord/Hero-gated (see {@link #trySetLayers}). */
    private int activeLayers = 1;
    /** Who raised the layers above 1 — their DL/Hero status is
     *  re-checked once per second while they're online; observed loss
     *  collapses back to 1 layer. Null until layers are raised. */
    private java.util.UUID layerSetterUuid = null;
    /** Last second's TOTAL drain (upkeep + raider contact), magicule/s —
     *  for the menu's "drain / time-to-empty" readout. Synced. */
    private double lastDrainPerSecond = 0.0;
    /** Accumulates this second's contact drain for the readout. */
    private double contactDrainAccumulator = 0.0;

    // ------------------------------------------------------------------
    // Tier plumbing — radius and capacity come from the core's tier
    // (BarrierBlock.TIER_RADIUS / TIER_BASE_CAPACITY) plus the connected
    // storage network.
    // ------------------------------------------------------------------

    /** This core's tier (1..4); falls back to 1 if the blockstate is
     *  somehow not a BarrierBlock (defensive). */
    public int getTier() {
        return getBlockState().getBlock() instanceof BarrierBlock b ? b.tier() : 1;
    }

    /** Field radius (square half-extent) — by tier. */
    public double getRadius() {
        return getBlockState().getBlock() instanceof BarrierBlock b
                ? b.radius() : BarrierBlock.TIER_RADIUS[0];
    }

    /** Total tank capacity = tier base + connected storage bonuses. */
    public double getCapacity() {
        double base = getBlockState().getBlock() instanceof BarrierBlock b
                ? b.baseCapacity() : BarrierBlock.TIER_BASE_CAPACITY[0];
        return base + storageBonus;
    }

    public int getActiveLayers() {
        return activeLayers;
    }

    public double getStoredMagicule() {
        return storedMagicule;
    }

    public double getLastDrainPerSecond() {
        return lastDrainPerSecond;
    }

    /** Radius of layer {@code index} (0-based): tier radius + 5 per ring. */
    public double getLayerRadius(int index) {
        return getRadius() + LAYER_SPACING * index;
    }

    /** The OUTERMOST active shell's radius — what the pushback field,
     *  the raid steering, and the spawn prevention act on. */
    public double getEffectiveRadius() {
        return getLayerRadius(activeLayers - 1);
    }

    /** True when the player bears the true-demon-lord or true-hero
     *  status — the gate for layers 2–3. Same IExistence read the envoy
     *  conditions use. */
    static boolean isDemonLordOrHero(Player player) {
        io.github.manasmods.tensura.storage.ep.IExistence ex = ExampleMod.readExistenceSafe(player);
        return ex != null && (ex.isTrueDemonLord() || ex.isTrueHero());
    }

    /**
     * Menu request to set the layer count. Clamps to 1..MAX_LAYERS;
     * raising above 1 requires the requesting player to be a true demon
     * lord or true hero (checked HERE server-side — the client's +
     * button being enabled is cosmetic). Records the setter for the
     * per-second status re-check. Returns the applied count.
     */
    public int trySetLayers(int requested, Player who) {
        int clamped = Math.max(1, Math.min(MAX_LAYERS, requested));
        if (clamped > 1 && !isDemonLordOrHero(who)) {
            clamped = 1;
        }
        if (clamped != activeLayers) {
            activeLayers = clamped;
            layerSetterUuid = clamped > 1 ? who.getUUID() : null;
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        return activeLayers;
    }

    /** Fill fraction 0..1 — read client-side by the wall renderer. */
    public float getFillRatio() {
        return (float) Math.max(0.0, Math.min(1.0, storedMagicule / getCapacity()));
    }

    /** Shared footprint test: is (x, z) inside a square of half-extent
     *  {@code radius} around {@code barrierPos}? The field pushback, the
     *  wall render, and the hostile-spawn prevention all use this same
     *  definition. */
    public static boolean isWithinFootprint(BlockPos barrierPos, double radius, double x, double z) {
        double dx = x - (barrierPos.getX() + 0.5);
        double dz = z - (barrierPos.getZ() + 0.5);
        return Math.max(Math.abs(dx), Math.abs(dz)) <= radius;
    }

    public BarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ExampleMod.BARRIER_BLOCK_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Storage network — flood-fill over adjacent MagiculeStorageBlocks
    // ------------------------------------------------------------------

    /**
     * Recompute the connected-storage capacity bonus: BFS from the core
     * over 6-way-adjacent {@link MagiculeStorageBlock}s (a storage block
     * counts if reachable from the core through other storage blocks),
     * capped at {@link #MAX_STORAGE_NETWORK} blocks. Runs once per
     * second from the ticker — placing/breaking storage anywhere in the
     * network is picked up within a second, no neighbor events needed.
     * If capacity shrank below the stored amount (storage broken), the
     * stored magicule clamps down to the new capacity.
     */
    private void recomputeStorageBonus(ServerLevel level) {
        double bonus = 0.0;
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        queue.add(worldPosition);
        visited.add(worldPosition);
        int storageCount = 0;
        while (!queue.isEmpty() && storageCount < MAX_STORAGE_NETWORK) {
            BlockPos cur = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.add(next)) continue;
                if (level.getBlockState(next).getBlock() instanceof MagiculeStorageBlock storage) {
                    bonus += storage.capacityBonus();
                    storageCount++;
                    queue.add(next);
                }
            }
        }
        if (bonus != storageBonus) {
            storageBonus = bonus;
            if (storedMagicule > getCapacity()) {
                storedMagicule = getCapacity();
            }
            setChanged();
            syncChargeState();
            // Capacity changed → fill ratio changed → resync the render.
            lastSyncedMagicule = -1.0;
        }
    }

    // ------------------------------------------------------------------
    // Tank
    // ------------------------------------------------------------------

    /** Add magicule; returns the amount actually accepted. */
    public double addMagicule(double amount) {
        double accepted = Math.max(0, Math.min(amount, getCapacity() - storedMagicule));
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
        if (storedMagicule >= getCapacity()) {
            stage = 3;
        } else {
            stage = (int) Math.min(2, Math.floor(storedMagicule / getCapacity() * 3.0));
        }
        stage = Math.max(0, stage);
        if (state.getValue(BarrierBlock.CHARGE) != stage) {
            level.setBlock(worldPosition, state.setValue(BarrierBlock.CHARGE, stage), 3);
        }
    }

    public boolean isFull() {
        return storedMagicule >= getCapacity();
    }

    public String fillReadout() {
        String base = String.format(Locale.ROOT, "%,.0f / %,.0f magicule",
                storedMagicule, getCapacity());
        return storageBonus > 0
                ? base + String.format(Locale.ROOT, " (+%,.0f from storage)", storageBonus)
                : base;
    }

    /** One-click refuel — move up to {@link #PLAYER_CHANNEL_PER_CLICK}
     *  of the PLAYER'S own magicule into the tank. */
    public double channelFromPlayer(Player player) {
        return channelFromPlayer(player, PLAYER_CHANNEL_PER_CLICK);
    }

    /** Move up to {@code amount} of the player's magicule into the tank
     *  (the menu's + and MAX buttons). Same read/write the swap-cost
     *  code uses everywhere. Returns the amount moved. */
    public double channelFromPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double available = Math.max(0, exist.getMagicule());
        double move = Math.min(amount,
                Math.min(available, getCapacity() - storedMagicule));
        if (move <= 0) return 0;
        exist.setMagicule(available - move);
        exist.markDirty();
        storedMagicule += move;
        setChanged();
        syncChargeState();
        return move;
    }

    /** Move up to {@code amount} magicule BACK to the player (the menu's
     *  − and MIN buttons), capped at the player's own max magicule —
     *  same cap the swap refund path uses. Returns the amount moved. */
    public double withdrawToPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double cur = Math.max(0, exist.getMagicule());
        double room = Math.max(0,
                io.github.manasmods.tensura.util.EnergyHelper.getMaxMagicule(player) - cur);
        double move = Math.min(amount, Math.min(storedMagicule, room));
        if (move <= 0) return 0;
        exist.setMagicule(cur + move);
        exist.markDirty();
        storedMagicule -= move;
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
            // Storage network — connected MagiculeStorageBlocks expand
            // the capacity; placing/breaking is picked up here.
            be.recomputeStorageBonus(serverLevel);

            // Layer gate re-check: if the player who raised the layers is
            // ONLINE and has lost Demon Lord / Hero status, collapse to 1.
            // (Logging off does NOT collapse — only an observed loss.)
            if (be.activeLayers > 1 && be.layerSetterUuid != null) {
                ServerPlayer setter = serverLevel.getServer().getPlayerList()
                        .getPlayer(be.layerSetterUuid);
                if (setter != null && !isDemonLordOrHero(setter)) {
                    be.activeLayers = 1;
                    be.layerSetterUuid = null;
                    be.setChanged();
                    serverLevel.sendBlockUpdated(pos, state, state, 3);
                    setter.sendSystemMessage(Component.literal(
                            "Your barrier collapses to a single layer — the power that "
                            + "sustained the outer rings is gone.")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                }
            }

            // Passive layer upkeep (layer 1 free; +LAYER_UPKEEP_PER_SECOND
            // per extra layer). If the pool can't pay, shed the OUTERMOST
            // layer (graceful degradation) instead of total collapse.
            double upkeep = (be.activeLayers - 1) * LAYER_UPKEEP_PER_SECOND;
            while (upkeep > 0 && be.storedMagicule < upkeep) {
                be.activeLayers--;
                be.setChanged();
                serverLevel.sendBlockUpdated(pos, state, state, 3);
                serverLevel.playSound(null, pos, SoundEvents.GLASS_BREAK,
                        SoundSource.BLOCKS, 1.0f, 0.9f);
                alertNearbyPlayers(serverLevel, pos,
                        Component.literal("The barrier's outermost layer falls — "
                                + "not enough magicule to sustain it!")
                                .withStyle(net.minecraft.ChatFormatting.RED));
                upkeep = (be.activeLayers - 1) * LAYER_UPKEEP_PER_SECOND;
            }
            if (upkeep > 0) {
                be.storedMagicule = Math.max(0, be.storedMagicule - upkeep);
                be.setChanged();
                be.syncChargeState();
            }

            // Drain readout: upkeep + last second's accumulated contact drain.
            be.lastDrainPerSecond = upkeep + be.contactDrainAccumulator;
            be.contactDrainAccumulator = 0.0;

            be.syncChargeState();
            // Client sync for the wall render's fill-driven alpha.
            if (Math.abs(be.storedMagicule - be.lastSyncedMagicule) > be.getCapacity() * 0.005) {
                be.lastSyncedMagicule = be.storedMagicule;
                serverLevel.sendBlockUpdated(pos, state, state, 3);
            }
            be.raidActiveCache = TensuraRaids.isRaidActiveNear(serverLevel, pos);
            if (be.storedMagicule > 0) {
                // Steering reads this to send raiders at the barrier first;
                // the OUTERMOST active shell's radius rides along for
                // footprint checks.
                TensuraRaids.reportActiveBarrier(serverLevel, pos, be.getEffectiveRadius());
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
        // The field acts on the OUTERMOST active shell — raiders meet the
        // outer ring first; inner rings are reserve (they take over as
        // outer layers shed).
        double radius = be.getEffectiveRadius();

        // SQUARE footprint (Chebyshev distance) — the field, the wall
        // render (BarrierFieldRenderer), and the hostile-spawn prevention
        // all share this same per-tier half-extent square.
        for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class,
                AABB.ofSize(center, (radius + CONTACT_BAND) * 2 + 2, 64,
                        (radius + CONTACT_BAND) * 2 + 2),
                m -> m.isAlive() && m.hasData(Attachments.RAID_TAG.get()))) {

            double dx = mob.getX() - center.x;
            double dz = mob.getZ() - center.z;
            double cheb = Math.max(Math.abs(dx), Math.abs(dz));

            // Pushback — clamp any raider inside the square back out
            // through the NEAREST face, horizontal velocity zeroed
            // (vertical kept so gravity still applies).
            if (cheb < radius) {
                if (Math.abs(dx) >= Math.abs(dz)) {
                    double sign = dx >= 0 ? 1 : -1;
                    mob.setPos(center.x + sign * (radius + 0.25), mob.getY(), mob.getZ());
                } else {
                    double sign = dz >= 0 ? 1 : -1;
                    mob.setPos(mob.getX(), mob.getY(), center.z + sign * (radius + 0.25));
                }
                Vec3 vel = mob.getDeltaMovement();
                mob.setDeltaMovement(0, Math.min(0, vel.y), 0);
                cheb = radius + 0.25;
            }

            // EP-scaled contact drain for raiders pressing the wall.
            if (cheb <= radius + CONTACT_BAND) {
                ExistenceStorage exist = ExampleMod.readExistence(mob);
                double ep = exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_RAIDER_EP;
                double drain = ep * BARRIER_DRAIN_COEFFICIENT_PER_SECOND / 20.0;
                drainThisTick += drain;
                // Summed over the second = magicule/s for the readout.
                be.contactDrainAccumulator += drain;

                // Visible "attacking the barrier": once a second the
                // presser faces the block, swings, and crit particles
                // burst at its spot on the wall. Pure show — the drain
                // above IS the damage.
                if (gameTime % 20 == 0) {
                    mob.getLookControl().setLookAt(center.x, center.y, center.z);
                    mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    serverLevel.sendParticles(ParticleTypes.CRIT,
                            mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                            8, 0.2, 0.3, 0.2, 0.05);
                }
            }
        }

        // Pounding audio — one knock per 2 s while ANYTHING presses the
        // wall (per-mob sounds would stack into noise on big waves).
        if (drainThisTick > 0 && gameTime % 40 == 0) {
            serverLevel.playSound(null, pos, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR,
                    SoundSource.HOSTILE, 1.0f, 0.8f);
        }

        if (drainThisTick > 0) {
            be.storedMagicule = Math.max(0, be.storedMagicule - drainThisTick);
            be.setChanged();
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
        // storageBonus is recomputed server-side every second, but it
        // rides the save/update tag so CLIENTS have the true capacity
        // (fill ratio for the wall alpha, readouts).
        tag.putDouble("storageBonus", storageBonus);
        tag.putInt("activeLayers", activeLayers);
        if (layerSetterUuid != null) tag.putUUID("layerSetter", layerSetterUuid);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storedMagicule = tag.getDouble("storedMagicule");
        storageBonus = tag.getDouble("storageBonus");
        activeLayers = tag.contains("activeLayers") ? Math.max(1,
                Math.min(MAX_LAYERS, tag.getInt("activeLayers"))) : 1;
        layerSetterUuid = tag.hasUUID("layerSetter") ? tag.getUUID("layerSetter") : null;
    }

    // Client sync — the wall renderer reads getFillRatio() client-side,
    // so the stored amount travels in the chunk load tag AND in block
    // updates (sendBlockUpdated → getUpdatePacket → loadAdditional).

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TensuraRaids.reportBarrierDown(serverLevel, worldPosition);
        }
        super.setRemoved();
    }
}
