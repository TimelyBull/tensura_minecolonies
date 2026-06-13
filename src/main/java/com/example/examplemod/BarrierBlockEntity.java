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
    /** LEGACY (replaced by the damage-proportional formula below; kept
     *  for reference in docs): flat EP-fraction drain per second. */
    public static final double BARRIER_DRAIN_COEFFICIENT_PER_SECOND = 0.02;

    // --- Damage-proportional drain (the rework): drain/second =
    //     attackDamage × (attacker EP × BARRIER_DRAIN_EP_MULTIPLIER).
    //     The EP core is KEPT (higher EP → higher multiplier) but the
    //     base is LOWER, and a hard hitter now hurts the barrier more
    //     than a tanky pacifist of equal EP. All named + tunable. ---
    /** The fraction of the attacker's EP that forms the per-damage-point
     *  drain multiplier. 0.002 → a 3 000-EP raider with 6 attack drains
     *  6 × 6 = 36/s (the old flat formula charged 60/s). */
    public static final double BARRIER_DRAIN_EP_MULTIPLIER = 0.002;
    /** Attack damage assumed when the attribute is missing. */
    public static final double FALLBACK_ATTACK_DAMAGE = 3.0;

    // --- Cumulative tier FUNCTIONS (docs/raid-system.md):
    //     T1 = WALL (two-way; mobs inside at activation stay trapped),
    //     T2 = wall + HEALING inside, T3/T4 = wall + heal + EJECT. ---
    /** Core tier at which the healing aura activates. */
    public static final int BARRIER_HEAL_TIER = 2;
    /** Core tier at which hostiles inside are TELEPORTED OUT (the old
     *  universal behavior, now top-tier only). */
    public static final int BARRIER_EJECT_TIER = 3;
    /** Boundary band (blocks) the T1/T2 wall acts across — entrants are
     *  bounced back out; trapped mobs nudged back in. */
    public static final double WALL_BAND = 1.5;
    /** Healing applied inside (Regeneration I, refreshed each second). */
    public static final int BARRIER_HEAL_DURATION_TICKS = 60;
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

    /** The CORE's OWN base tank (up to {@code baseCapacity()}). The UI
     *  shows the combined POOL (core + connected storage tanks); this
     *  field is just the core's share. Fill order: core first, overflow
     *  disbursed into the storage network; drain pulls core first, then
     *  the storage reserves. */
    private double storedMagicule = 0.0;
    /** Cached combined pool (core + network storage contents). Refreshed
     *  by every pool op + the per-second walk; synced to clients (wall
     *  alpha, menu gauge). */
    private double poolStoredCache = 0.0;
    /** Network storage positions from the last per-second walk. */
    private final java.util.List<BlockPos> networkStorageCache = new java.util.ArrayList<>();
    /** Edge-detect for the "barrier has fallen" alarm. */
    private boolean fieldWasUp = false;
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
    /** Player toggle from the core menu: render the wall shells or keep
     *  the barrier invisible (the FIELD/spawn-protection still works —
     *  this is purely visual). Synced via the update tag. */
    private boolean wallVisible = true;
    /** Last second's RAIDER-CONTACT drain (magicule/s) — the upkeep part
     *  of the readout is computed live from the CURRENT layer count so
     *  the menu reflects a layer change immediately. */
    private double lastContactDrainPerSecond = 0.0;
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

    /** The core's OWN tank size (tier base, without storage). */
    public double getBaseCapacity() {
        return getBlockState().getBlock() instanceof BarrierBlock b
                ? b.baseCapacity() : BarrierBlock.TIER_BASE_CAPACITY[0];
    }

    /** Total POOL capacity = tier base + connected storage capacities. */
    public double getCapacity() {
        return getBaseCapacity() + storageBonus;
    }

    public int getActiveLayers() {
        return activeLayers;
    }

    /** Combined pool contents (core + network storage). What the UI
     *  gauge, the wall alpha, and the field's "fueled" check read. */
    public double getPoolStored() {
        return poolStoredCache;
    }

    /** Menu drain readout, magicule/s: the CURRENT layer upkeep (live —
     *  reflects a layer +/− immediately, not at the next tick) plus the
     *  last second's measured raider-contact drain. */
    public double getLastDrainPerSecond() {
        return (activeLayers - 1) * LAYER_UPKEEP_PER_SECOND + lastContactDrainPerSecond;
    }

    public boolean isWallVisible() {
        return wallVisible;
    }

    /** Menu toggle — show/hide the wall render (visual only). */
    public void setWallVisible(boolean visible) {
        if (this.wallVisible != visible) {
            this.wallVisible = visible;
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
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

    /** POOL fill fraction 0..1 — read client-side by the wall renderer. */
    public float getFillRatio() {
        return (float) Math.max(0.0, Math.min(1.0, poolStoredCache / getCapacity()));
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
        java.util.ArrayList<BlockPos> networkStorage = new java.util.ArrayList<>();
        queue.add(worldPosition);
        visited.add(worldPosition);
        while (!queue.isEmpty() && networkStorage.size() < MAX_STORAGE_NETWORK) {
            BlockPos cur = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.add(next)) continue;
                if (level.getBlockState(next).getBlock() instanceof MagiculeStorageBlock storage) {
                    bonus += storage.capacityBonus();
                    networkStorage.add(next.immutable());
                    queue.add(next);
                }
            }
        }
        networkStorageCache.clear();
        networkStorageCache.addAll(networkStorage);

        if (bonus != storageBonus) {
            storageBonus = bonus;
            setChanged();
            // Capacity changed → fill ratio changed → resync the render.
            lastSyncedMagicule = -1.0;
        }
        // Core's own tank never exceeds its base — overflow lives in the
        // storage blocks themselves.
        if (storedMagicule > getBaseCapacity()) {
            storedMagicule = getBaseCapacity();
            setChanged();
        }
        refreshPoolCache(level);
        syncChargeState();
    }

    /** Recompute the combined pool from the core + live network tanks. */
    private void refreshPoolCache(ServerLevel level) {
        double pool = storedMagicule;
        for (BlockPos sp : networkStorageCache) {
            if (level.getBlockEntity(sp) instanceof StorageBlockEntity sbe) {
                pool += sbe.getStored();
            }
        }
        poolStoredCache = pool;
    }

    /** The 0..3 fill stage of the POOL — drives the core's charge sprite. */
    private int currentChargeStage() {
        if (poolStoredCache >= getCapacity()) return 3;
        return Math.max(0, (int) Math.min(2, Math.floor(poolStoredCache / getCapacity() * 3.0)));
    }

    // ------------------------------------------------------------------
    // Pool operations — core tank fills FIRST; overflow disburses into
    // the connected storage blocks (literal overflow storage). Drain
    // pulls the core first, then the storage reserves.
    // ------------------------------------------------------------------

    /** Add magicule to the POOL (core first, then storage overflow).
     *  Returns the amount actually accepted. */
    public double addToPool(double amount) {
        if (amount <= 0) return 0;
        double remaining = amount;
        // 1) the core's own tank, up to its base capacity
        double coreRoom = Math.max(0, getBaseCapacity() - storedMagicule);
        double toCore = Math.min(remaining, coreRoom);
        if (toCore > 0) {
            storedMagicule += toCore;
            remaining -= toCore;
        }
        // 2) overflow into the network storage tanks, walk order
        if (remaining > 0 && level instanceof ServerLevel serverLevel) {
            for (BlockPos sp : networkStorageCache) {
                if (remaining <= 0) break;
                if (serverLevel.getBlockEntity(sp) instanceof StorageBlockEntity sbe) {
                    remaining -= sbe.fill(remaining);
                }
            }
        }
        double accepted = amount - remaining;
        if (accepted > 0) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) refreshPoolCache(serverLevel);
            else poolStoredCache += accepted;
            syncChargeState();
        }
        return accepted;
    }

    /** Drain magicule from the POOL (core first, then storage reserves —
     *  the storage "supplies the core"). Returns the amount removed. */
    public double drainFromPool(double amount) {
        if (amount <= 0) return 0;
        double remaining = amount;
        double fromCore = Math.min(remaining, storedMagicule);
        if (fromCore > 0) {
            storedMagicule -= fromCore;
            remaining -= fromCore;
        }
        if (remaining > 0 && level instanceof ServerLevel serverLevel) {
            for (BlockPos sp : networkStorageCache) {
                if (remaining <= 0) break;
                if (serverLevel.getBlockEntity(sp) instanceof StorageBlockEntity sbe) {
                    remaining -= sbe.drain(remaining);
                }
            }
        }
        double removed = amount - remaining;
        if (removed > 0) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) refreshPoolCache(serverLevel);
            else poolStoredCache = Math.max(0, poolStoredCache - removed);
            syncChargeState();
        }
        return removed;
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
        int stage = currentChargeStage();
        if (state.getValue(BarrierBlock.CHARGE) != stage) {
            level.setBlock(worldPosition, state.setValue(BarrierBlock.CHARGE, stage), 3);
        }
    }

    public boolean isFull() {
        return poolStoredCache >= getCapacity();
    }

    public String fillReadout() {
        String base = String.format(Locale.ROOT, "%,.0f / %,.0f magicule",
                poolStoredCache, getCapacity());
        return storageBonus > 0
                ? base + String.format(Locale.ROOT, " (+%,.0f from storage)", storageBonus)
                : base;
    }

    /** One-click refuel — move up to {@link #PLAYER_CHANNEL_PER_CLICK}
     *  of the PLAYER'S own magicule into the tank. */
    public double channelFromPlayer(Player player) {
        return channelFromPlayer(player, PLAYER_CHANNEL_PER_CLICK);
    }

    /** Move up to {@code amount} of the player's magicule into the POOL
     *  (the menu's + and MAX buttons): the player is debited EXACTLY what
     *  the pool accepted — core first, overflow into storage. Same
     *  read/write the swap-cost code uses everywhere. */
    public double channelFromPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double available = Math.max(0, exist.getMagicule());
        double offer = Math.min(amount, available);
        if (offer <= 0) return 0;
        double accepted = addToPool(offer);
        if (accepted <= 0) return 0;
        // Debit the player by exactly what the pool took.
        exist.setMagicule(available - accepted);
        exist.markDirty();
        return accepted;
    }

    /** Move up to {@code amount} magicule from the POOL back to the
     *  player (the menu's − and MIN buttons): drained core-first, then
     *  storage; the player is credited EXACTLY what came out, capped at
     *  their own max magicule (the swap-refund cap). */
    public double withdrawToPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double cur = Math.max(0, exist.getMagicule());
        double room = Math.max(0,
                io.github.manasmods.tensura.util.EnergyHelper.getMaxMagicule(player) - cur);
        double take = Math.min(amount, room);
        if (take <= 0) return 0;
        double removed = drainFromPool(take);
        if (removed <= 0) return 0;
        exist.setMagicule(cur + removed);
        exist.markDirty();
        return removed;
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
            // Outer layers shed quietly (sound only) — the ONLY chat
            // alert is the final "barrier has fallen" when the pool
            // empties and the last layer drops.
            while (upkeep > 0 && be.poolStoredCache < upkeep) {
                be.activeLayers--;
                be.setChanged();
                serverLevel.sendBlockUpdated(pos, state, state, 3);
                serverLevel.playSound(null, pos, SoundEvents.GLASS_BREAK,
                        SoundSource.BLOCKS, 1.0f, 0.9f);
                upkeep = (be.activeLayers - 1) * LAYER_UPKEEP_PER_SECOND;
            }
            if (upkeep > 0) {
                be.drainFromPool(upkeep);
            }

            // Drain readout: latch the second's measured contact drain
            // (the upkeep part is computed live in getLastDrainPerSecond).
            be.lastContactDrainPerSecond = be.contactDrainAccumulator;
            be.contactDrainAccumulator = 0.0;

            be.syncChargeState();
            // Client sync for the wall render's fill-driven alpha.
            if (Math.abs(be.poolStoredCache - be.lastSyncedMagicule) > be.getCapacity() * 0.005) {
                be.lastSyncedMagicule = be.poolStoredCache;
                serverLevel.sendBlockUpdated(pos, state, state, 3);
            }
            if (be.poolStoredCache > 0) {
                // Steering reads this to send raiders at the barrier first;
                // the OUTERMOST active shell's radius rides along for
                // footprint checks.
                TensuraRaids.reportActiveBarrier(serverLevel, pos, be.getEffectiveRadius());
            } else {
                TensuraRaids.reportBarrierDown(serverLevel, pos);
            }
        }

        // The field is up whenever the POOL is fueled — it blocks raid
        // mobs AND wild hostile-tagged mobs at all times, not just during
        // raids.
        boolean fieldUp = be.poolStoredCache > 0;

        // Depletion alarm — fires once on the up→down edge.
        if (be.fieldWasUp && !fieldUp) {
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
                m -> m.isAlive() && (m.hasData(Attachments.RAID_TAG.get())
                        || m.getType().builtInRegistryHolder()
                               .is(TensuraRaids.HOSTILE_MONSTER_TAG)
                        // MineColonies' own raiders (barbarians/pirates/…)
                        // are blocked too.
                        || m instanceof com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider))) {

            boolean isRaider = mob.hasData(Attachments.RAID_TAG.get());

            double dx = mob.getX() - center.x;
            double dz = mob.getZ() - center.z;
            double cheb = Math.max(Math.abs(dx), Math.abs(dz));

            // CUMULATIVE TIER FUNCTIONS.
            // T3+: the EJECT — any hostile inside is clamped back out
            // through the nearest face (the old universal behavior).
            // T1/T2: a true WALL — only the boundary band acts: an
            // entrant is bounced OUT, a trapped mob brushing the wall
            // from inside is nudged back IN. Mobs deep inside at
            // activation stay trapped.
            if (cheb < radius) {
                boolean eject = be.getTier() >= BARRIER_EJECT_TIER;
                if (eject) {
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
                } else if (cheb > radius - WALL_BAND) {
                    // Two-way wall: shove to the nearer side of the band.
                    boolean outward = cheb > radius - WALL_BAND / 2;
                    double target = outward ? radius + 0.25 : radius - WALL_BAND - 0.25;
                    if (Math.abs(dx) >= Math.abs(dz)) {
                        double sign = dx >= 0 ? 1 : -1;
                        mob.setPos(center.x + sign * target, mob.getY(), mob.getZ());
                    } else {
                        double sign = dz >= 0 ? 1 : -1;
                        mob.setPos(mob.getX(), mob.getY(), center.z + sign * target);
                    }
                    Vec3 vel = mob.getDeltaMovement();
                    mob.setDeltaMovement(0, Math.min(0, vel.y), 0);
                    if (outward) cheb = radius + 0.25;
                }
            }

            // EP-scaled contact drain — RAID mobs only. Wild hostiles are
            // blocked for free, so the peacetime barrier doesn't bleed
            // from a stray zombie leaning on it; raids stay the drain
            // mechanic.
            if (isRaider && cheb <= radius + CONTACT_BAND) {
                ExistenceStorage exist = ExampleMod.readExistence(mob);
                double ep = exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_RAIDER_EP;
                // Damage-proportional drain: what the raider HITS for ×
                // an EP-derived multiplier (a fraction of its own EP).
                double attack = FALLBACK_ATTACK_DAMAGE;
                try {
                    if (mob.getAttributes().hasAttribute(
                            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
                        attack = mob.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                    }
                } catch (Throwable ignored) { }
                double drain = attack * (ep * BARRIER_DRAIN_EP_MULTIPLIER) / 20.0;
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

        // (No recurring drain audio — the pounding knock was removed by
        // request; the CRIT particles remain the drain telegraph.)
        if (drainThisTick > 0) {
            be.drainFromPool(drainThisTick);
        }

        // T2+ HEALING — everyone friendly INSIDE the field gets a gentle
        // regeneration, refreshed each second (hostiles excluded).
        if (be.getTier() >= BARRIER_HEAL_TIER && gameTime % 20 == 0) {
            for (net.minecraft.world.entity.LivingEntity living
                    : serverLevel.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            AABB.ofSize(center, radius * 2, 64, radius * 2))) {
                double hx = Math.abs(living.getX() - center.x);
                double hz = Math.abs(living.getZ() - center.z);
                if (Math.max(hx, hz) >= radius) continue;
                if (living.hasData(Attachments.RAID_TAG.get())) continue;
                if (living.getType().builtInRegistryHolder().is(TensuraRaids.HOSTILE_MONSTER_TAG)) continue;
                if (living instanceof com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider) continue;
                living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.REGENERATION,
                        BARRIER_HEAL_DURATION_TICKS, 0, true, false, false));
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
        // storageBonus + poolStored are recomputed server-side, but ride
        // the save/update tag so CLIENTS have the true capacity + pool
        // (fill ratio for the wall alpha, readouts).
        tag.putDouble("storageBonus", storageBonus);
        tag.putDouble("poolStored", poolStoredCache);
        tag.putInt("activeLayers", activeLayers);
        if (layerSetterUuid != null) tag.putUUID("layerSetter", layerSetterUuid);
        tag.putBoolean("wallVisible", wallVisible);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storedMagicule = tag.getDouble("storedMagicule");
        storageBonus = tag.getDouble("storageBonus");
        // Legacy saves (pre-pool) have no poolStored — the core's own
        // tank IS the pool until the first network walk refreshes it.
        poolStoredCache = tag.contains("poolStored")
                ? tag.getDouble("poolStored") : storedMagicule;
        activeLayers = tag.contains("activeLayers") ? Math.max(1,
                Math.min(MAX_LAYERS, tag.getInt("activeLayers"))) : 1;
        layerSetterUuid = tag.hasUUID("layerSetter") ? tag.getUUID("layerSetter") : null;
        // Absent on pre-toggle saves → visible (the original behavior).
        wallVisible = !tag.contains("wallVisible") || tag.getBoolean("wallVisible");
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
