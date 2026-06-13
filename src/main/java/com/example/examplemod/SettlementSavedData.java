package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Storage for generated rival-faction {@link Settlement}s (rival-colony
 * arc, Stage A) — the foundation record Stages B–E read and extend.
 *
 * <p><b>ACCESS RULE:</b> nothing outside {@link RivalColonies} touches
 * this — the sole-door discipline used by every other system spine.
 * Overworld-global SavedData (settlements carry their own dimension);
 * saved to {@code world/data/tensura_minecolonies_settlements.dat}.
 */
class SettlementSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_settlements";

    private final Map<Integer, Settlement> settlements = new HashMap<>();
    private int nextId = 1;
    /** Dwarven-village anchors already evaluated for the wild/colony
     *  roll (as packed longs), so a village isn't re-rolled each visit.
     *  Villages that became settlements are also in {@link #settlements};
     *  this set additionally remembers the ones that rolled "normal". */
    private final java.util.Set<Long> evaluatedVillages = new java.util.HashSet<>();

    private SettlementSavedData() {}

    static SettlementSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SettlementSavedData::new, SettlementSavedData::load),
                DATA_KEY);
    }

    int allocateId() {
        return nextId++;
    }

    void put(Settlement s) {
        settlements.put(s.id, s);
        setDirty();
    }

    Settlement get(int id) {
        return settlements.get(id);
    }

    void remove(int id) {
        if (settlements.remove(id) != null) setDirty();
    }

    Collection<Settlement> all() {
        return settlements.values();
    }

    /** Mark dirty after a caller mutates a Settlement in place. */
    void markChanged() {
        setDirty();
    }

    boolean isVillageEvaluated(net.minecraft.core.BlockPos center) {
        return evaluatedVillages.contains(center.asLong());
    }

    void markVillageEvaluated(net.minecraft.core.BlockPos center) {
        if (evaluatedVillages.add(center.asLong())) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        ListTag list = new ListTag();
        for (Settlement s : settlements.values()) {
            list.add(s.save());
        }
        tag.put("settlements", list);
        long[] evaluated = new long[evaluatedVillages.size()];
        int i = 0;
        for (Long v : evaluatedVillages) evaluated[i++] = v;
        tag.putLongArray("evaluatedVillages", evaluated);
        return tag;
    }

    static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementSavedData data = new SettlementSavedData();
        data.nextId = tag.getInt("nextId");
        if (data.nextId < 1) data.nextId = 1;
        ListTag list = tag.getList("settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Settlement s = Settlement.load(list.getCompound(i));
            data.settlements.put(s.id, s);
        }
        for (long v : tag.getLongArray("evaluatedVillages")) {
            data.evaluatedVillages.add(v);
        }
        return data;
    }
}
