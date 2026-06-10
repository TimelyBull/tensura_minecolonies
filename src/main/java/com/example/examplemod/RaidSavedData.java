package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Raid-scheduler state — per-colony cooldown anchors.
 *
 * One global instance per server on the overworld's data storage, same
 * pattern as {@link ColonyRaceConfigSavedData} / {@link ReputationSavedData}.
 *
 * Only the cooldown lives here; the active raid itself is an
 * {@link TensuraRaidEvent} persisted by MineColonies' own event manager.
 *
 * NBT: {@code raids: [{colonyId:int, lastResolveTick:long}]}.
 * Missing key → never raided (cooldown passes).
 */
class RaidSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_raids";

    /** Game-tick at which the last raid at this colony RESOLVED (victory,
     *  timeout, or cleanup). The trigger requires a configured number of
     *  in-game days past this anchor before another raid can roll. */
    private final Map<Integer, Long> lastRaidResolveTick = new HashMap<>();

    private RaidSavedData() {}

    static RaidSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaidSavedData::new, RaidSavedData::load),
                DATA_KEY
        );
    }

    long getLastRaidResolveTick(int colonyId, long fallback) {
        return lastRaidResolveTick.getOrDefault(colonyId, fallback);
    }

    void setLastRaidResolveTick(int colonyId, long tick) {
        lastRaidResolveTick.put(colonyId, tick);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Long> e : lastRaidResolveTick.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            entry.putLong("lastResolveTick", e.getValue());
            list.add(entry);
        }
        tag.put("raids", list);
        return tag;
    }

    static RaidSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidSavedData data = new RaidSavedData();
        if (tag.contains("raids", Tag.TAG_LIST)) {
            ListTag list = tag.getList("raids", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                data.lastRaidResolveTick.put(entry.getInt("colonyId"),
                        entry.getLong("lastResolveTick"));
            }
        }
        return data;
    }
}
