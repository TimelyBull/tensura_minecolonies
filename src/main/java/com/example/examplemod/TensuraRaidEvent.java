package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A Tensura-themed colony raid — hostile Tensura mobs assault a colony
 * whose reputation has fallen below NEUTRAL.
 *
 * <p><b>Architecture (see docs/raid-system.md):</b> this extends
 * MineColonies' raid EVENT framework, not its raider entities/AI/scheduler.
 * Implementing {@link IColonyRaidEvent} and being registered in the
 * {@code minecolonies:colonyeventtypes} registry buys us, for free:
 * <ul>
 *   <li>{@code RaidManager.isRaided()} returns true → MC's citizen
 *       flee/hide raid behaviors activate;</li>
 *   <li>NBT persistence + rehydration across save/reload through MC's
 *       own {@code EventManager};</li>
 *   <li>per-colony-tick {@code onUpdate} safety callbacks.</li>
 * </ul>
 * The mobs are plain Tensura MONSTER-category entities (guard towers
 * auto-list those), marked with {@link RaidTag}; per-second steering /
 * resolution is driven by {@link TensuraRaids#tick}, not colony ticks.
 *
 * <p>v1 is a single wave with a timer: victory = all raiders dead before
 * the timer (reputation reward via
 * {@code ReputationManager.modifyReputation(..., RAID_REPELLED)}),
 * timeout = leftovers despawn. Multi-wave, lore variants, and building
 * damage are deferred.
 */
public class TensuraRaidEvent implements IColonyRaidEvent {

    public static final ResourceLocation TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "tensura_raid");

    private IColony colony;
    private int id;
    private EventStatus status = EventStatus.STARTING;
    private BlockPos spawnPoint = BlockPos.ZERO;

    /** UUIDs of the still-alive raid mobs. Pruned by the death hook and
     *  the steering pass. Entities resolve live via
     *  {@code ServerLevel.getEntity(uuid)} — an unloaded entity stays in
     *  the set (counts as alive) until confirmed dead. */
    private final Set<UUID> raiderUuids = new HashSet<>();
    /** Total mobs spawned for this raid — boss-bar denominator. */
    private int totalSpawned = 0;
    /** Game-time deadline. Past it the raid resolves as a timeout. */
    private long endTick = 0L;
    /** Roster tier the wave was drawn from (kept for the type getters). */
    private int rosterTier = 0;

    private final ServerBossEvent raidBar = new ServerBossEvent(
            Component.literal("Tensura Raid"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private boolean barCleared = false;

    public TensuraRaidEvent(IColony colony) {
        this.colony = colony;
    }

    /** Registry-entry deserializer (TriFunction shape MC's
     *  {@code ColonyEventTypeRegistryEntry} expects). */
    public static TensuraRaidEvent loadFromNBT(IColony colony, CompoundTag tag,
                                               HolderLookup.Provider provider) {
        TensuraRaidEvent event = new TensuraRaidEvent(colony);
        event.deserializeNBT(provider, tag);
        return event;
    }

    // ------------------------------------------------------------------
    // Setup (called by TensuraRaids at spawn time)
    // ------------------------------------------------------------------

    void setup(int id, BlockPos spawnPoint, long endTick, int rosterTier) {
        this.id = id;
        this.spawnPoint = spawnPoint;
        this.endTick = endTick;
        this.rosterTier = rosterTier;
    }

    void addRaider(Entity entity) {
        raiderUuids.add(entity.getUUID());
        totalSpawned++;
    }

    Set<UUID> raiderUuids() {
        return raiderUuids;
    }

    int totalSpawned() {
        return totalSpawned;
    }

    long endTick() {
        return endTick;
    }

    int rosterTier() {
        return rosterTier;
    }

    /** Drop a raider (death or confirmed removal). */
    void removeRaider(UUID uuid) {
        raiderUuids.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Boss bar — alive/total, shown to players near the colony center.
    // ------------------------------------------------------------------

    void updateRaidBar(ServerLevel level) {
        if (barCleared) return;
        float progress = totalSpawned == 0
                ? 0f
                : (float) raiderUuids.size() / (float) totalSpawned;
        raidBar.setProgress(Math.max(0f, Math.min(1f, progress)));
        BlockPos center = colony.getCenter();
        for (ServerPlayer player : level.players()) {
            boolean near = player.blockPosition().distSqr(center) < 96 * 96;
            if (near && !raidBar.getPlayers().contains(player)) {
                raidBar.addPlayer(player);
            } else if (!near && raidBar.getPlayers().contains(player)) {
                raidBar.removePlayer(player);
            }
        }
    }

    void clearRaidBar() {
        if (!barCleared) {
            raidBar.removeAllPlayers();
            barCleared = true;
        }
    }

    // ------------------------------------------------------------------
    // IColonyEvent
    // ------------------------------------------------------------------

    @Override public EventStatus getStatus() { return status; }

    @Override public void setStatus(EventStatus status) { this.status = status; }

    @Override public int getID() { return id; }

    @Override public ResourceLocation getEventTypeID() { return TYPE_ID; }

    @Override public void setColony(IColony colony) { this.colony = colony; }

    public IColony getColony() { return colony; }

    @Override
    public void onStart() {
        // The wave is spawned by TensuraRaids.startRaid BEFORE the status
        // flips to PROGRESSING; this callback only covers the path where
        // MC's EventManager starts a STARTING event itself.
        status = EventStatus.PROGRESSING;
    }

    @Override
    public void onUpdate() {
        // Per-second logic (steering, resolution, boss bar) is driven by
        // TensuraRaids.tick — colony ticks are too coarse. Nothing here.
    }

    @Override
    public void onFinish() {
        clearRaidBar();
    }

    // ------------------------------------------------------------------
    // IColonySpawnEvent
    // ------------------------------------------------------------------

    @Override public void setSpawnPoint(BlockPos spawnPoint) { this.spawnPoint = spawnPoint; }

    @Override public BlockPos getSpawnPos() { return spawnPoint; }

    // ------------------------------------------------------------------
    // IColonyEntitySpawnEvent
    // ------------------------------------------------------------------

    @Override
    public List<Entity> getEntities() {
        List<Entity> out = new ArrayList<>();
        if (colony != null && colony.getWorld() instanceof ServerLevel level) {
            for (UUID uuid : raiderUuids) {
                Entity e = level.getEntity(uuid);
                if (e != null && !e.isRemoved()) out.add(e);
            }
        }
        return out;
    }

    @Override
    public void registerEntity(Entity entity) {
        raiderUuids.add(entity.getUUID());
    }

    @Override
    public void unregisterEntity(Entity entity) {
        raiderUuids.remove(entity.getUUID());
    }

    @Override
    public void onEntityDeath(LivingEntity entity) {
        raiderUuids.remove(entity.getUUID());
    }

    // ------------------------------------------------------------------
    // IColonyRaidEvent — the type getters report the wave's roster so any
    // MC code inspecting the raid sees real entity types.
    // ------------------------------------------------------------------

    @Override
    public EntityType<?> getNormalRaiderType() {
        return TensuraRaids.rosterType(rosterTier, 0);
    }

    @Override
    public EntityType<?> getArcherRaiderType() {
        return TensuraRaids.rosterType(rosterTier, 1);
    }

    @Override
    public EntityType<?> getBossRaiderType() {
        return TensuraRaids.rosterType(rosterTier, 2);
    }

    @Override
    public void addSpawner(BlockPos pos) {
        // No campfire/spawner structures in v1.
    }

    @Override
    public List<BlockPos> getWayPoints() {
        return colony == null ? List.of() : List.of(colony.getCenter());
    }

    // ------------------------------------------------------------------
    // Persistence — rehydrated through the colonyeventtypes registry entry.
    // ------------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putByte("status", (byte) status.ordinal());
        tag.put("spawnPos", NbtUtils.writeBlockPos(spawnPoint));
        tag.putLong("endTick", endTick);
        tag.putInt("totalSpawned", totalSpawned);
        tag.putInt("rosterTier", rosterTier);
        ListTag uuids = new ListTag();
        for (UUID uuid : raiderUuids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", uuid);
            uuids.add(entry);
        }
        tag.put("raiders", uuids);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        this.id = tag.getInt("id");
        int statusOrdinal = tag.getByte("status") & 0xFF;
        EventStatus[] statuses = EventStatus.values();
        this.status = statusOrdinal < statuses.length ? statuses[statusOrdinal] : EventStatus.PROGRESSING;
        this.spawnPoint = NbtUtils.readBlockPos(tag, "spawnPos").orElse(BlockPos.ZERO);
        this.endTick = tag.getLong("endTick");
        this.totalSpawned = tag.getInt("totalSpawned");
        this.rosterTier = tag.getInt("rosterTier");
        this.raiderUuids.clear();
        if (tag.contains("raiders", Tag.TAG_LIST)) {
            ListTag uuids = tag.getList("raiders", Tag.TAG_COMPOUND);
            for (int i = 0; i < uuids.size(); i++) {
                CompoundTag entry = uuids.getCompound(i);
                if (entry.hasUUID("uuid")) raiderUuids.add(entry.getUUID("uuid"));
            }
        }
    }
}
