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

    /** Worldgen-settlement origins already populated into a real settlement —
     *  the double-populate guard (packed-long structure-start centers). Written
     *  via {@link #markPopulated}; read by {@code RivalColonies.populateSettlementAt}.
     *  NBT key kept as "spikePopulated" for backward-compat with existing saves. */
    private final java.util.Set<Long> populatedStarts = new java.util.HashSet<>();

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

    // ------------------------------------------------------------------
    // Espionage: scout missions (2026-09-05). One record per subordinate
    // away scouting a settlement; drives the away-gate + the return tick.
    // ------------------------------------------------------------------

    static class ScoutMission {
        java.util.UUID identityId;   // the scout's RaceIdentity
        int settlementId;            // the target
        java.util.UUID requester;    // who sent them (owner OR controller)
        long returnTick;             // gameTime the mission resolves
        // Captured at DEPART — the scout's relevant abilities + power.
        double scoutEp;
        boolean hasAppraisal;        // can it read EP? gates the EP line
        boolean hasStealth;          // formhide/concealment — halves detection
        String scoutName;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("identity", identityId);
            t.putInt("settlement", settlementId);
            t.putUUID("requester", requester);
            t.putLong("returnTick", returnTick);
            t.putDouble("scoutEp", scoutEp);
            t.putBoolean("appraisal", hasAppraisal);
            t.putBoolean("stealth", hasStealth);
            t.putString("name", scoutName);
            return t;
        }

        static ScoutMission load(CompoundTag t) {
            ScoutMission m = new ScoutMission();
            m.identityId = t.getUUID("identity");
            m.settlementId = t.getInt("settlement");
            m.requester = t.getUUID("requester");
            m.returnTick = t.getLong("returnTick");
            m.scoutEp = t.getDouble("scoutEp");
            m.hasAppraisal = t.getBoolean("appraisal");
            m.hasStealth = t.getBoolean("stealth");
            m.scoutName = t.getString("name");
            return m;
        }
    }

    private final java.util.List<ScoutMission> scoutMissions = new ArrayList<>();

    void addScoutMission(ScoutMission mission) {
        scoutMissions.add(mission);
        setDirty();
    }

    void removeScoutMission(ScoutMission mission) {
        if (scoutMissions.remove(mission)) setDirty();
    }

    java.util.List<ScoutMission> scoutMissions() {
        return scoutMissions;
    }

    boolean isScoutAway(java.util.UUID identityId) {
        for (ScoutMission m : scoutMissions) {
            if (m.identityId.equals(identityId)) return true;
        }
        return false;
    }

    boolean hasScoutMission(int settlementId, java.util.UUID requester) {
        for (ScoutMission m : scoutMissions) {
            if (m.settlementId == settlementId && m.requester.equals(requester)) return true;
        }
        return false;
    }

    boolean isVillageEvaluated(net.minecraft.core.BlockPos center) {
        return evaluatedVillages.contains(center.asLong());
    }

    void markVillageEvaluated(net.minecraft.core.BlockPos center) {
        if (evaluatedVillages.add(center.asLong())) setDirty();
    }

    // --- worldgen-settlement double-populate guard ---
    boolean isPopulated(long centerLong) {
        return populatedStarts.contains(centerLong);
    }

    void markPopulated(long centerLong) {
        if (populatedStarts.add(centerLong)) setDirty();
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
        // Worldgen-settlement double-populate guard (NBT key kept for save compat).
        long[] populated = new long[populatedStarts.size()];
        int j = 0;
        for (Long v : populatedStarts) populated[j++] = v;
        tag.putLongArray("spikePopulated", populated);
        ListTag missions = new ListTag();
        for (ScoutMission m : scoutMissions) missions.add(m.save());
        tag.put("scoutMissions", missions);
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
        // Worldgen-settlement double-populate guard. ("spikePending" from older
        // saves — the removed Stage-0 scaffolding — is simply ignored.)
        for (long v : tag.getLongArray("spikePopulated")) {
            data.populatedStarts.add(v);
        }
        ListTag missions = tag.getList("scoutMissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < missions.size(); i++) {
            data.scoutMissions.add(ScoutMission.load(missions.getCompound(i)));
        }
        return data;
    }
}
