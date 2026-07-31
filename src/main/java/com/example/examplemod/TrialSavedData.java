package com.example.examplemod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-player progress for Luminous's Covenant, "The Trial of Light &amp; Dark".
 *
 * <p>One global instance per server on the overworld's data storage (same pattern
 * as {@link RaidSavedData} / {@link ReputationSavedData}). State is stored as one
 * {@link CompoundTag} per player UUID; {@link TrialManager} owns the schema and
 * reads/writes fields through it. Presence of an entry = the player has an active
 * trial. Cleared when the trial is turned in (or abandoned).
 *
 * <p>NBT: {@code trials: [{player:UUID, ...TrialManager fields...}]}.
 */
class TrialSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_trials";

    private final Map<UUID, CompoundTag> progress = new HashMap<>();

    private TrialSavedData() {}

    static TrialSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TrialSavedData::new, TrialSavedData::load),
                DATA_KEY);
    }

    boolean has(UUID player) {
        return progress.containsKey(player);
    }

    /** The player's trial tag, or null if they have no active trial. */
    CompoundTag get(UUID player) {
        return progress.get(player);
    }

    void put(UUID player, CompoundTag tag) {
        progress.put(player, tag);
        setDirty();
    }

    void remove(UUID player) {
        if (progress.remove(player) != null) setDirty();
    }

    /** Call after mutating a tag returned by {@link #get} in place. */
    void markDirty() {
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : progress.entrySet()) {
            CompoundTag entry = e.getValue().copy();
            entry.putUUID("player", e.getKey());
            list.add(entry);
        }
        tag.put("trials", list);
        return tag;
    }

    static TrialSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrialSavedData data = new TrialSavedData();
        ListTag list = tag.getList("trials", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i).copy();
            if (!entry.hasUUID("player")) continue;
            UUID player = entry.getUUID("player");
            entry.remove("player");
            data.progress.put(player, entry);
        }
        return data;
    }
}
