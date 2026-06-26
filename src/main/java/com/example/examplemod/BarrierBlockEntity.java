package com.example.examplemod;

import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.util.EnergyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Magicule-barrier tank + field driver. See docs/raid-system.md.
 *
 * <p><b>Spherical sectional barrier (redesign).</b> The barrier is a faceted
 * SPHERE per concentric layer, divided into {@link #SECTION_COUNT} patches
 * (a "quad-sphere": cube subdivided 6×(2×2)=24, projected to the radius).
 * Each section has its OWN health; attacks deplete the pressed section and the
 * shared pool together. A section fades through three opacity stages then
 * BREAKS into a collision HOLE (mobs path through opportunistically); the rest
 * of the layer stays up. A broken section regenerates in steps (paid from the
 * pool). The whole barrier only falls when the shared pool hits zero.
 *
 * <p>The point→section lookup is O(1) (dominant axis + sign quantize, no trig)
 * and is shared verbatim by collision, render, and state so a visible hole IS
 * a collision gap. The shell is a true 3D sphere that clips into terrain (the
 * buried lower hemisphere is intended); terrain-following is gone.
 *
 * <p><b>EP-scaled drain:</b> each hostile pressing an intact section drains
 * {@code attackDamage × (EP × BARRIER_DRAIN_EP_MULTIPLIER) / 20} magicule per
 * tick from BOTH that section's health AND the pool. The EP read is the same
 * {@code ExistenceStorage} read the roster / cost gate uses.
 */
public class BarrierBlockEntity extends BlockEntity {

    // ------------------------------------------------------------------
    // Tuning constants
    // ------------------------------------------------------------------

    /** LEGACY (replaced by the damage-proportional formula below; kept for
     *  reference in docs): flat EP-fraction drain per second. */
    public static final double BARRIER_DRAIN_COEFFICIENT_PER_SECOND = 0.02;

    /** The fraction of the attacker's EP that forms the per-damage-point
     *  drain multiplier. 0.001 → a 3 000-EP attacker with 6 attack drains
     *  6 × (3000 × 0.001) = 18/s. */
    public static final double BARRIER_DRAIN_EP_MULTIPLIER = 0.001;
    /** Attack damage assumed when the attribute is missing. */
    public static final double FALLBACK_ATTACK_DAMAGE = 3.0;

    // --- Cumulative tier FUNCTIONS:
    //     T1 = WALL (blocks/pushes), T2 = wall + HEAL inside,
    //     T3+ = wall + heal + the +10% player magicule-regen buff inside.
    //     (The old T3 EJECT was REMOVED in the sphere redesign.) ---
    /** Core tier at which the healing aura activates. */
    public static final int BARRIER_HEAL_TIER = 2;
    /** Core tier at which the player magicule-regen buff activates (replaces
     *  the removed eject). */
    public static final int BARRIER_REGEN_BUFF_TIER = 3;
    /** Boundary band (blocks) the wall acts across — a mob within this band of
     *  an intact section's surface is pushed back out. */
    public static final double WALL_BAND = 1.5;
    /** Healing applied inside (Regeneration I, refreshed each second). */
    public static final int BARRIER_HEAL_DURATION_TICKS = 60;
    /** EP assumed for a hostile whose existence storage can't be read. */
    public static final double FALLBACK_RAIDER_EP = 1_000.0;
    /** How far past a section's surface still counts as "pressing" it. */
    public static final double CONTACT_BAND = 1.5;
    /** Player magicule moved per channel/withdraw click (the menu's ±). */
    public static final double PLAYER_CHANNEL_PER_CLICK = 3_000.0;
    /** Max concentric barrier layers (each its own sphere). */
    public static final int MAX_LAYERS = 3;
    /** Ring spacing — each extra layer sphere sits this much further out. */
    public static final double LAYER_SPACING = 5.0;
    /** Passive upkeep per EXTRA layer (magicule/sec), drained once per second
     *  from the pool. Layer 1 is free; 2 layers cost 50/s, 3 layers 100/s.
     *  Upkeep STACKS with attack drain. Based on the CONFIGURED layer count. */
    public static final double LAYER_UPKEEP_PER_SECOND = 50.0;
    /** Crystal refuel values (low / medium / high quality magic crystal). */
    public static final double CRYSTAL_LOW_MAGICULE    = 2_500.0;
    public static final double CRYSTAL_MEDIUM_MAGICULE = 10_000.0;
    public static final double CRYSTAL_HIGH_MAGICULE   = 40_000.0;

    /** Flood-fill cap on a storage network — performance guard. */
    public static final int MAX_STORAGE_NETWORK = 128;

    /** Section health (and pool) drained when an enemy projectile is absorbed
     *  by an intact section. Flat per shot (projectiles hit once), so sustained
     *  fire chips a section open like melee contact does. Tunable. */
    public static final double PROJECTILE_SECTION_DAMAGE = 200.0;

    /** Section (and pool) damage when an enemy SKILL/attack is stopped at an
     *  intact section — beams, breaths, AoE, hitscan: anything that deals
     *  damage but isn't blockable as a moving projectile entity. Charged per
     *  damage tick, so a sustained beam wears the section open. Tunable. */
    public static final double ATTACK_SECTION_DAMAGE = 100.0;

    // --- Quad-sphere sectioning (shared by collision + render + state) ---
    /** Faces of the cube (6). */
    public static final int SECTION_FACES = 6;
    /** Grid subdivision per face axis (2 → 2×2 cells per face). */
    public static final int SECTION_GRID = 2;
    /** Sections per layer sphere: 6 faces × 2×2 = 24. */
    public static final int SECTION_COUNT = SECTION_FACES * SECTION_GRID * SECTION_GRID;

    /** Per-section max health by core tier (1..4): 10k / 20k / 40k / 60k. */
    public static final double[] SECTION_HEALTH_BY_TIER = {
            10_000.0, 20_000.0, 40_000.0, 60_000.0
    };

    // --- Section regeneration ---
    /** A section waits this long after its last hit before it starts healing
     *  (and the gap between regen steps): 15 seconds. Each step rebuilds it to
     *  the next opacity phase and costs the EXACT health restored from the pool
     *  (1:1) — see {@link #regenSections}. */
    public static final int REGEN_DELAY_TICKS = 300;
    /** When the pool can't pay a regen step, retry this soon. */
    public static final int REGEN_STALL_RETRY_TICKS = 20;

    // --- T3 player magicule-regen buff ---
    /** Extra fraction of natural magicule regen granted to players inside a
     *  T3+ barrier (+10%). */
    public static final double T3_MAGICULE_REGEN_BONUS = 0.10;
    /** Shared per-player magicule baseline for the delta-mirror buff. Static so
     *  overlapping T3 barriers can't double-count: the first barrier to process
     *  a player consumes the natural-gain delta; others see none. */
    private static final java.util.Map<UUID, Double> T3_MAGICULE_BASELINE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** The CORE's OWN base tank (up to {@code baseCapacity()}). */
    private double storedMagicule = 0.0;
    /** Cached combined pool (core + network storage contents). */
    private double poolStoredCache = 0.0;
    /** Network storage positions from the last per-second walk. */
    private final java.util.List<BlockPos> networkStorageCache = new java.util.ArrayList<>();
    /** Edge-detect for the "barrier has fallen" alarm + refuel-reset. */
    private boolean fieldWasUp = false;
    /** Last stored value synced to clients (fill-alpha + gauge). */
    private double lastSyncedMagicule = -1.0;
    /** Capacity added by the connected Magicule Storage network. */
    private double storageBonus = 0.0;
    /** Active concentric barrier layers (1..MAX_LAYERS), each a full sphere.
     *  Layers 2–3 are Demon-Lord/Hero-gated (see {@link #trySetLayers}). */
    private int activeLayers = 1;
    /** Who raised the layers above 1 — DL/Hero re-checked once per second. */
    private UUID layerSetterUuid = null;
    /** Player toggle: render the wall shells or keep them invisible (the field
     *  still works either way). */
    private boolean wallVisible = true;
    /** Last second's measured pool drain from REPAIRS (magicule/s) — the
     *  readout's variable part (upkeep is added live). Attacks no longer drain
     *  the pool, so this now tracks section-rebuild spend, not contact. */
    private double lastContactDrainPerSecond = 0.0;
    /** Accumulates this second's repair pool-spend for the readout. */
    private double contactDrainAccumulator = 0.0;

    // --- Per-section state (MAX_LAYERS × SECTION_COUNT, indexed layer*24+sec) ---
    /** Current health of each section. */
    private double[] sectionHealth = null;
    /** Game-tick at which each section may next take a regen step. */
    private long[] sectionRegenTick = null;
    /** SERVER-only: last opacity stage synced to clients per section, for the
     *  change-only sync diff (not persisted, unused client-side). */
    private int[] lastSyncedStage = null;

    // ------------------------------------------------------------------
    // Tier plumbing
    // ------------------------------------------------------------------

    /** This core's tier (1..4); falls back to 1 if the blockstate isn't a
     *  BarrierBlock (defensive). */
    public int getTier() {
        return getBlockState().getBlock() instanceof BarrierBlock b ? b.tier() : 1;
    }

    /** Field radius (base sphere radius) — by tier. */
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

    /** Combined pool contents (core + network storage). */
    public double getPoolStored() {
        return poolStoredCache;
    }

    /** Menu drain readout, magicule/s: live layer upkeep + last second's
     *  measured repair spend (attacks no longer drain the pool). */
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

    /** Radius of layer {@code index} (0-based): tier radius + spacing per ring. */
    public double getLayerRadius(int index) {
        return getRadius() + LAYER_SPACING * index;
    }

    /** The OUTERMOST layer sphere's radius — what raid steering, spawn-report,
     *  and the render bounding box use. (Layers no longer shed from drain; only
     *  the whole barrier falls at pool 0.) */
    public double getEffectiveRadius() {
        return getLayerRadius(activeLayers - 1);
    }

    /** Per-section max health for this core's tier. */
    public double maxSectionHealth() {
        int t = Math.max(1, Math.min(4, getTier()));
        return SECTION_HEALTH_BY_TIER[t - 1];
    }

    // ------------------------------------------------------------------
    // Quad-sphere section geometry (shared by collision + render)
    // ------------------------------------------------------------------

    /** Map a direction from the center to its section index 0..23. Dominant
     *  axis picks the cube face; the signs of the two tangent components pick
     *  the 2×2 cell. O(1), no trig. Render uses the SAME face/cell convention
     *  ({@link #cubePoint}) so a hole lines up with the collision gap. */
    public static int sectionIndex(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        int face;
        double u, v;
        if (ax >= ay && ax >= az) {            // +X / -X face: u=Z, v=Y
            face = dx >= 0 ? 0 : 1;
            double d = ax < 1e-9 ? 1e-9 : ax;
            u = dz / d; v = dy / d;
        } else if (ay >= ax && ay >= az) {     // +Y / -Y face: u=X, v=Z
            face = dy >= 0 ? 2 : 3;
            double d = ay < 1e-9 ? 1e-9 : ay;
            u = dx / d; v = dz / d;
        } else {                                // +Z / -Z face: u=X, v=Y
            face = dz >= 0 ? 4 : 5;
            double d = az < 1e-9 ? 1e-9 : az;
            u = dx / d; v = dy / d;
        }
        int cu = u < 0 ? 0 : 1;
        int cv = v < 0 ? 0 : 1;
        return face * (SECTION_GRID * SECTION_GRID) + cv * SECTION_GRID + cu;
    }

    /** Section index from a face (0..5) and its 2×2 cell (cu,cv ∈ {0,1}).
     *  Render iterates faces/cells with this; must match {@link #sectionIndex}. */
    public static int cellSection(int face, int cu, int cv) {
        return face * (SECTION_GRID * SECTION_GRID) + cv * SECTION_GRID + cu;
    }

    /** Cube-space point for face {@code face} at tangent params (a=u-axis,
     *  b=v-axis), each in [-1,1]. Normalize this to get the sphere direction.
     *  The tangent-axis assignment mirrors {@link #sectionIndex}. */
    public static Vec3 cubePoint(int face, double a, double b) {
        switch (face) {
            case 0:  return new Vec3( 1,  b,  a); // +X : u=Z, v=Y
            case 1:  return new Vec3(-1,  b,  a); // -X
            case 2:  return new Vec3( a,  1,  b); // +Y : u=X, v=Z
            case 3:  return new Vec3( a, -1,  b); // -Y
            case 4:  return new Vec3( a,  b,  1); // +Z : u=X, v=Y
            default: return new Vec3( a,  b, -1); // -Z
        }
    }

    // ------------------------------------------------------------------
    // Per-section state accessors
    // ------------------------------------------------------------------

    /** Lazily allocate the per-section arrays, all sections full. */
    private void ensureSections() {
        int n = MAX_LAYERS * SECTION_COUNT;
        if (sectionHealth == null || sectionHealth.length != n) {
            sectionHealth = new double[n];
            sectionRegenTick = new long[n];
            lastSyncedStage = new int[n];
            double maxH = maxSectionHealth();
            for (int i = 0; i < n; i++) {
                sectionHealth[i] = maxH;
                lastSyncedStage[i] = 0;
            }
        }
    }

    private int sectionIdx(int layer, int section) {
        return layer * SECTION_COUNT + section;
    }

    /** A section is intact (blocks/pushes) while its health is above zero. */
    public boolean isSectionIntact(int layer, int section) {
        if (sectionHealth == null) return true;
        int idx = sectionIdx(layer, section);
        return idx >= 0 && idx < sectionHealth.length && sectionHealth[idx] > 0;
    }

    /** Opacity stage of a section: 0 full, 1 fade1, 2 fade2, 3 fade3,
     *  4 broken (a hole — the renderer skips it). Quartile thresholds. */
    public int getSectionStage(int layer, int section) {
        if (sectionHealth == null) return 0;
        int idx = sectionIdx(layer, section);
        if (idx < 0 || idx >= sectionHealth.length) return 0;
        return stageOf(sectionHealth[idx], maxSectionHealth());
    }

    private static int stageOf(double health, double maxHealth) {
        if (health <= 0) return 4;          // broken (hole)
        double r = maxHealth <= 0 ? 0 : health / maxHealth;
        if (r > 0.75) return 0;             // full
        if (r > 0.50) return 1;             // fade1
        if (r > 0.25) return 2;             // fade2
        return 3;                            // fade3 (faintest)
    }

    /** Apply {@code damage} to one section. Marks it broken when health hits 0,
     *  and pushes its heal-start back (REGEN_DELAY_TICKS after the last hit). */
    private void damageSection(int layer, int section, double damage, long now) {
        ensureSections();
        int idx = sectionIdx(layer, section);
        if (idx < 0 || idx >= sectionHealth.length || sectionHealth[idx] <= 0) return;
        sectionHealth[idx] = Math.max(0.0, sectionHealth[idx] - damage);
        sectionRegenTick[idx] = now + REGEN_DELAY_TICKS;
        setChanged();
    }

    /** Reset every section to full (used when the barrier refuels after a full
     *  pool-zero collapse). */
    private void resetAllSectionsFull() {
        ensureSections();
        double maxH = maxSectionHealth();
        for (int i = 0; i < sectionHealth.length; i++) {
            sectionHealth[i] = maxH;
            sectionRegenTick[i] = 0;
        }
        setChanged();
    }

    /** Per-second regen sweep over the ≤72 sections: any section below full that
     *  has waited its delay is rebuilt up to the NEXT opacity phase boundary
     *  (25% / 50% / 75% / 100% of the tier's section health), and the pool is
     *  debited EXACTLY the health restored (1:1). Pool-limited — if the pool can
     *  only afford part of the step it restores that much; an empty pool stalls.
     *  This is the pool's ONLY section cost now: attacks damage sections but no
     *  longer drain the pool (decoupled model), so the pool is spent purely on
     *  upkeep + rebuilding what was broken. */
    private void regenSections(long now) {
        ensureSections();
        double maxH = maxSectionHealth();
        for (int i = 0; i < sectionHealth.length; i++) {
            double cur = sectionHealth[i];
            if (cur >= maxH) continue;
            if (now < sectionRegenTick[i]) continue;
            // Health needed to reach the next opacity phase boundary.
            double frac = cur / maxH;
            double nextFrac = frac < 0.25 ? 0.25 : frac < 0.5 ? 0.5 : frac < 0.75 ? 0.75 : 1.0;
            double need = nextFrac * maxH - cur;
            if (need <= 0) continue;
            double afford = Math.min(need, poolStoredCache);
            if (afford <= 0) {
                // No magicule to rebuild — stall and retry shortly.
                sectionRegenTick[i] = now + REGEN_STALL_RETRY_TICKS;
                continue;
            }
            drainFromPool(afford);                 // exact magicules = health restored
            sectionHealth[i] = cur + afford;
            contactDrainAccumulator += afford;     // pool-drain readout (repairs)
            sectionRegenTick[i] = now + REGEN_DELAY_TICKS;
            setChanged();
        }
    }

    /** Change-only client sync: if any section's opacity stage changed since
     *  the last push, resync the block (carries the section healths). */
    private void maybeSyncStages(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        ensureSections();
        double maxH = maxSectionHealth();
        boolean changed = false;
        for (int i = 0; i < sectionHealth.length; i++) {
            int s = stageOf(sectionHealth[i], maxH);
            if (s != lastSyncedStage[i]) {
                lastSyncedStage[i] = s;
                changed = true;
            }
        }
        if (changed) {
            setChanged();
            serverLevel.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /** The hostile set the barrier acts on: raid-tagged mobs, wild
     *  {@code HOSTILE_MONSTER_TAG} mobs, and MineColonies raiders. Shared by
     *  the mob field sweep AND the projectile-owner check. */
    static boolean isBlockableHostile(Entity e) {
        if (e == null) return false;
        if (e instanceof com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider) return true;
        if (e.hasData(Attachments.RAID_TAG.get())) return true;
        return e.getType().builtInRegistryHolder().is(TensuraRaids.HOSTILE_MONSTER_TAG);
    }

    /** True when the player bears true-demon-lord or true-hero status — the
     *  gate for layers 2–3. */
    static boolean isDemonLordOrHero(Player player) {
        io.github.manasmods.tensura.storage.ep.IExistence ex = ExampleMod.readExistenceSafe(player);
        return ex != null && (ex.isTrueDemonLord() || ex.isTrueHero());
    }

    /** Menu request to set the layer count (1..MAX_LAYERS); 2–3 require the
     *  requester to be a true demon lord or true hero (validated here). */
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

    /** Shared 2D footprint test (square half-extent) — used by the
     *  hostile-spawn prevention, which stays 2D (NOT spherical). */
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
            lastSyncedMagicule = -1.0;
        }
        if (storedMagicule > getBaseCapacity()) {
            storedMagicule = getBaseCapacity();
            setChanged();
        }
        refreshPoolCache(level);
        syncChargeState();
    }

    private void refreshPoolCache(ServerLevel level) {
        double pool = storedMagicule;
        for (BlockPos sp : networkStorageCache) {
            if (level.getBlockEntity(sp) instanceof StorageBlockEntity sbe) {
                pool += sbe.getStored();
            }
        }
        poolStoredCache = pool;
    }

    /** The 0..3 fill stage of the POOL — drives the core's charge sprite.
     *  Clean quartiles: 0–25% → 0, 25–50% → 1, 50–75% → 2, 75–100% → 3. */
    private int currentChargeStage() {
        double capacity = getCapacity();
        if (capacity <= 0) return 0;
        double fillRatio = poolStoredCache / capacity;
        return Math.max(0, Math.min(3, (int) Math.floor(fillRatio * 4.0)));
    }

    // ------------------------------------------------------------------
    // Pool operations — core tank fills FIRST; overflow into storage. Drain
    // pulls the core first, then storage reserves.
    // ------------------------------------------------------------------

    public double addToPool(double amount) {
        if (amount <= 0) return 0;
        double remaining = amount;
        double coreRoom = Math.max(0, getBaseCapacity() - storedMagicule);
        double toCore = Math.min(remaining, coreRoom);
        if (toCore > 0) {
            storedMagicule += toCore;
            remaining -= toCore;
        }
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

    public double channelFromPlayer(Player player) {
        return channelFromPlayer(player, PLAYER_CHANNEL_PER_CLICK);
    }

    public double channelFromPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double available = Math.max(0, exist.getMagicule());
        double offer = Math.min(amount, available);
        if (offer <= 0) return 0;
        double accepted = addToPool(offer);
        if (accepted <= 0) return 0;
        exist.setMagicule(available - accepted);
        exist.markDirty();
        return accepted;
    }

    public double withdrawToPlayer(Player player, double amount) {
        ExistenceStorage exist = ExampleMod.readExistence(player);
        if (exist == null) return 0;
        double cur = Math.max(0, exist.getMagicule());
        double room = Math.max(0, EnergyHelper.getMaxMagicule(player) - cur);
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
        be.ensureSections();

        // Once-per-second housekeeping.
        if (gameTime % 20 == 0) {
            be.recomputeStorageBonus(serverLevel);

            // Layer gate re-check: online setter who lost DL/Hero → collapse to 1.
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

            // Passive layer upkeep (layer 1 free; +LAYER_UPKEEP_PER_SECOND per
            // extra configured layer), once per second from the pool.
            double upkeep = (be.activeLayers - 1) * LAYER_UPKEEP_PER_SECOND;
            if (upkeep > 0) {
                be.drainFromPool(upkeep);
            }

            // Section regen (paid from the pool).
            be.regenSections(gameTime);

            // Drain readout latch.
            be.lastContactDrainPerSecond = be.contactDrainAccumulator;
            be.contactDrainAccumulator = 0.0;

            be.syncChargeState();
            if (Math.abs(be.poolStoredCache - be.lastSyncedMagicule) > be.getCapacity() * 0.005) {
                be.lastSyncedMagicule = be.poolStoredCache;
                serverLevel.sendBlockUpdated(pos, state, state, 3);
            }
            if (be.poolStoredCache > 0) {
                TensuraRaids.reportActiveBarrier(serverLevel, pos, be.getEffectiveRadius());
            } else {
                TensuraRaids.reportBarrierDown(serverLevel, pos);
            }
        }

        boolean fieldUp = be.poolStoredCache > 0;

        // Depletion alarm — fires once on the up→down edge (whole barrier fall).
        if (be.fieldWasUp && !fieldUp) {
            serverLevel.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.5f, 0.6f);
            alertNearbyPlayers(serverLevel, pos,
                    Component.literal("The magicule barrier has fallen!")
                            .withStyle(net.minecraft.ChatFormatting.RED));
            // FORCE a client sync on the fall. The throttled per-second sync
            // only fires when the pool moves >0.5% of capacity, so the tiny
            // final step to exactly 0 can slip under the threshold and leave
            // the client with a sliver of fuel — the sphere then keeps
            // rendering faintly even though the field is down. Push 0 now.
            be.lastSyncedMagicule = be.poolStoredCache; // = 0
            be.setChanged();
            serverLevel.sendBlockUpdated(pos, state, state, 3);
        }
        // Refuel after a full collapse → all sections reset to full.
        if (!be.fieldWasUp && fieldUp) {
            be.resetAllSectionsFull();
            serverLevel.sendBlockUpdated(pos, state, state, 3);
        }
        be.fieldWasUp = fieldUp;
        if (!fieldUp) return;

        Vec3 center = Vec3.atCenterOf(pos);
        double outerR = be.getEffectiveRadius();
        double reach = outerR + CONTACT_BAND;

        // SPHERICAL collision with per-section holes. For each hostile, walk
        // the layers OUTER→INNER: the first INTACT section it presses blocks +
        // damages it; a BROKEN section is a gap it passes through to the next.
        // Attacks damage SECTION HEALTH only — the pool is NOT drained by
        // attacks (decoupled model); the pool pays only upkeep + repairs.
        for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class,
                AABB.ofSize(center, reach * 2 + 2, reach * 2 + 2, reach * 2 + 2),
                m -> m.isAlive() && isBlockableHostile(m))) {

            double dx = mob.getX() - center.x;
            double dy = (mob.getY() + mob.getBbHeight() * 0.5) - center.y;
            double dz = mob.getZ() - center.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int section = sectionIndex(dx, dy, dz);

            for (int layer = be.activeLayers - 1; layer >= 0; layer--) {
                double R = be.getLayerRadius(layer);
                if (dist > R + CONTACT_BAND) break; // outside this shell (and all inner)
                if (!be.isSectionIntact(layer, section)) continue; // hole → pass to inner
                // Intact shell. Only the boundary band acts (no eject of
                // deep-inside mobs). A mob well inside this shell falls through
                // to the inner layers so they can still block it.
                if (dist < R - WALL_BAND) continue;

                pushFromShell(mob, center, R, dx, dy, dz, dist);

                // EP-scaled contact damage → this section's health ONLY.
                ExistenceStorage exist = ExampleMod.readExistence(mob);
                double ep = exist != null && exist.getEP() > 0 ? exist.getEP() : FALLBACK_RAIDER_EP;
                double attack = FALLBACK_ATTACK_DAMAGE;
                try {
                    if (mob.getAttributes().hasAttribute(
                            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
                        attack = mob.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                    }
                } catch (Throwable ignored) { }
                double drain = attack * (ep * BARRIER_DRAIN_EP_MULTIPLIER) / 20.0;
                be.damageSection(layer, section, drain, gameTime);

                if (gameTime % 20 == 0) {
                    mob.getLookControl().setLookAt(center.x, center.y, center.z);
                    mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    serverLevel.sendParticles(ParticleTypes.CRIT,
                            mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                            8, 0.2, 0.3, 0.2, 0.05);
                }
                break; // handled the shell it's crossing
            }
        }

        // Enemy PROJECTILES — absorb them at intact sections (they still pass
        // through broken-section holes). Crossing is detected against the
        // projectile's PREVIOUS position (xo/yo/zo) so a fast shot can't tunnel
        // the thin shell in one tick. Friendly-owned projectiles (players /
        // citizens) are NOT blocked, so defenders can shoot outward.
        for (Projectile proj : serverLevel.getEntitiesOfClass(Projectile.class,
                AABB.ofSize(center, reach * 2 + 4, reach * 2 + 4, reach * 2 + 4),
                p -> p.isAlive() && (p.getOwner() == null || isBlockableHostile(p.getOwner())))) {

            double pnx = proj.getX() - center.x;
            double pny = proj.getY() - center.y;
            double pnz = proj.getZ() - center.z;
            double pox = proj.xo - center.x;
            double poy = proj.yo - center.y;
            double poz = proj.zo - center.z;
            double distNew = Math.sqrt(pnx * pnx + pny * pny + pnz * pnz);
            double distOld = Math.sqrt(pox * pox + poy * poy + poz * poz);
            int psec = sectionIndex(pnx, pny, pnz);

            for (int layer = be.activeLayers - 1; layer >= 0; layer--) {
                double R = be.getLayerRadius(layer);
                boolean crossedInward = distOld >= R && distNew < R;
                if (crossedInward) {
                    if (be.isSectionIntact(layer, psec)) {
                        // Absorbed — chip the SECTION only (not the pool),
                        // spark, remove it.
                        be.damageSection(layer, psec, PROJECTILE_SECTION_DAMAGE, gameTime);
                        serverLevel.sendParticles(ParticleTypes.CRIT,
                                proj.getX(), proj.getY(), proj.getZ(), 6, 0.1, 0.1, 0.1, 0.02);
                        serverLevel.playSound(null, proj.blockPosition(),
                                SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 0.5f, 1.4f);
                        proj.discard();
                        break;
                    }
                    continue; // through the hole → check inner shell
                }
                if (distNew >= R) break;       // outside this (and all inner) shells
                // else already inside this shell → check inner layers
            }
        }

        // Push section opacity changes (break/fade/regen) to clients, change-only.
        be.maybeSyncStages(serverLevel, pos, state);

        // T2+ HEALING — friendlies inside get gentle regeneration each second.
        if (be.getTier() >= BARRIER_HEAL_TIER && gameTime % 20 == 0) {
            for (net.minecraft.world.entity.LivingEntity living
                    : serverLevel.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            AABB.ofSize(center, outerR * 2, outerR * 2, outerR * 2))) {
                double hx = living.getX() - center.x;
                double hy = (living.getY() + living.getBbHeight() * 0.5) - center.y;
                double hz = living.getZ() - center.z;
                if (hx * hx + hy * hy + hz * hz >= outerR * outerR) continue;
                if (living.hasData(Attachments.RAID_TAG.get())) continue;
                if (living.getType().builtInRegistryHolder().is(TensuraRaids.HOSTILE_MONSTER_TAG)) continue;
                if (living instanceof com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider) continue;
                living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.REGENERATION,
                        BARRIER_HEAL_DURATION_TICKS, 0, true, false, false));
            }
        }

        // T3+ buff — players inside get +10% personal Tensura magicule regen.
        if (be.getTier() >= BARRIER_REGEN_BUFF_TIER) {
            applyT3RegenBuff(serverLevel, center, outerR);
        }
    }

    /**
     * Should an incoming attack from {@code attackerPos} to a victim at
     * {@code victimPos} be stopped by this barrier? True when the victim is
     * inside the barrier AND the attacker is outside an INTACT section in its
     * direction — chips that section + pool and the caller cancels the damage.
     * This is what stops enemy SKILLS (beams, breaths, AoE) that aren't
     * blockable as moving projectile entities. Attacks aimed through a broken
     * section (a hole) get through, consistent with the collision model.
     */
    public boolean tryBlockIncomingAttack(Vec3 attackerPos, Vec3 victimPos, long gameTime) {
        ensureSections();
        if (poolStoredCache <= 0) return false;
        Vec3 c = Vec3.atCenterOf(worldPosition);
        double outerR = getEffectiveRadius();
        if (victimPos.distanceToSqr(c) >= outerR * outerR) return false; // victim not inside
        double ax = attackerPos.x - c.x, ay = attackerPos.y - c.y, az = attackerPos.z - c.z;
        double aDist = Math.sqrt(ax * ax + ay * ay + az * az);
        int sec = sectionIndex(ax, ay, az);
        for (int layer = activeLayers - 1; layer >= 0; layer--) {
            double R = getLayerRadius(layer);
            if (aDist >= R) {                       // attacker is outside this shell
                if (isSectionIntact(layer, sec)) {
                    // Decoupled: chip the SECTION only; the pool isn't drained
                    // by attacks (only upkeep + repairs spend it).
                    damageSection(layer, sec, ATTACK_SECTION_DAMAGE, gameTime);
                    return true;                    // stopped at this intact shell
                }
                // hole in the attacker's direction → attack enters; try inner shell
            }
            // attacker inside this shell → no block from it; try inner
        }
        return false;
    }

    /** Push a mob back to just outside an intact section's surface — ALWAYS
     *  HORIZONTAL (Y preserved). Mobs are shoved straight back from the
     *  barrier, never launched up the dome (the old radial push pointed partly
     *  upward) nor driven down into terrain. Upward velocity is cancelled so an
     *  airborne mob just falls; {@code dy}/{@code dist} are unused now. */
    private static void pushFromShell(Mob mob, Vec3 center, double R,
                                      double dx, double dy, double dz, double dist) {
        double target = R + 0.25;
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd < 1e-3) {
            // Mob ~directly above/below the core — nudge out along +X so it
            // isn't left stuck inside; still no vertical movement.
            mob.setPos(center.x + target, mob.getY(), mob.getZ());
        } else {
            mob.setPos(center.x + (dx / hd) * target, mob.getY(),
                    center.z + (dz / hd) * target);
        }
        Vec3 vel = mob.getDeltaMovement();
        mob.setDeltaMovement(0, Math.min(0, vel.y), 0);
    }

    /** Delta-mirror T3 buff: for each player inside the outer sphere, add 10%
     *  of whatever natural magicule they gained since last tick. The shared
     *  baseline map self-dedupes across overlapping T3 barriers. */
    private static void applyT3RegenBuff(ServerLevel serverLevel, Vec3 center, double outerR) {
        double r2 = outerR * outerR;
        for (ServerPlayer player : serverLevel.players()) {
            double dx = player.getX() - center.x;
            double dy = (player.getY() + player.getBbHeight() * 0.5) - center.y;
            double dz = player.getZ() - center.z;
            if (dx * dx + dy * dy + dz * dz >= r2) continue;
            ExistenceStorage exist = ExampleMod.readExistence(player);
            if (exist == null) continue;
            double cur = exist.getMagicule();
            Double prev = T3_MAGICULE_BASELINE.get(player.getUUID());
            if (prev != null && cur > prev) {
                double gain = cur - prev;
                double bonus = gain * T3_MAGICULE_REGEN_BONUS;
                double max = EnergyHelper.getMaxMagicule(player);
                double newVal = Math.min(max, cur + bonus);
                if (newVal > cur) {
                    exist.setMagicule(newVal);
                    exist.markDirty();
                    cur = newVal;
                }
            }
            T3_MAGICULE_BASELINE.put(player.getUUID(), cur);
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
        tag.putDouble("storageBonus", storageBonus);
        tag.putDouble("poolStored", poolStoredCache);
        tag.putInt("activeLayers", activeLayers);
        if (layerSetterUuid != null) tag.putUUID("layerSetter", layerSetterUuid);
        tag.putBoolean("wallVisible", wallVisible);

        // Per-section state.
        ensureSections();
        ListTag healthList = new ListTag();
        for (double h : sectionHealth) healthList.add(DoubleTag.valueOf(h));
        tag.put("sectionHealth", healthList);
        tag.putLongArray("sectionRegenTick", sectionRegenTick.clone());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storedMagicule = tag.getDouble("storedMagicule");
        storageBonus = tag.getDouble("storageBonus");
        poolStoredCache = tag.contains("poolStored")
                ? tag.getDouble("poolStored") : storedMagicule;
        activeLayers = tag.contains("activeLayers") ? Math.max(1,
                Math.min(MAX_LAYERS, tag.getInt("activeLayers"))) : 1;
        layerSetterUuid = tag.hasUUID("layerSetter") ? tag.getUUID("layerSetter") : null;
        wallVisible = !tag.contains("wallVisible") || tag.getBoolean("wallVisible");

        // Per-section state — pre-redesign saves have none → init all full.
        int n = MAX_LAYERS * SECTION_COUNT;
        sectionHealth = new double[n];
        sectionRegenTick = new long[n];
        lastSyncedStage = new int[n];
        double maxH = maxSectionHealth();
        ListTag healthList = tag.contains("sectionHealth")
                ? tag.getList("sectionHealth", Tag.TAG_DOUBLE) : null;
        long[] regenArr = tag.contains("sectionRegenTick")
                ? tag.getLongArray("sectionRegenTick") : null;
        for (int i = 0; i < n; i++) {
            sectionHealth[i] = (healthList != null && i < healthList.size())
                    ? healthList.getDouble(i) : maxH;
            sectionRegenTick[i] = (regenArr != null && i < regenArr.length) ? regenArr[i] : 0L;
            lastSyncedStage[i] = stageOf(sectionHealth[i], maxH);
        }
    }

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
