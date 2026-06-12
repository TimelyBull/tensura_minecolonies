package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-reputation storage — per-PLAYER, per-FACTION standings.
 *
 * <p><b>ACCESS RULE: nothing outside {@link WorldReputationManager} may
 * touch this class</b> — the manager owns clamping, defaults,
 * dirty-marking, and future side-effects (the colony
 * {@link ReputationSavedData} discipline repeated).
 *
 * <p><b>Addon door (user-confirmed):</b> standings are keyed by faction
 * ID STRING, not enum ordinal — and ids that don't match any
 * {@link BossFaction} (e.g. a future data-driven addon faction) are
 * PRESERVED through load/save round-trips rather than dropped. v1 only
 * ever reads/writes enum ids; the spine just doesn't foreclose more.
 *
 * <p>Player-level lifecycle: no cleanup hook — standings for departed
 * players are a few bytes and standing SHOULD survive a long absence.
 * Missing player/faction key → the manager's default (50, NEUTRAL).
 *
 * NBT: {@code players: [ { uuid, standings: [ { faction: string, value: double } ] } ]}
 * Saved to {@code world/data/tensura_minecolonies_world_reputation.dat}.
 */
class WorldReputationSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_world_reputation";

    /** player → (faction id string → standing). String-keyed for the
     *  addon door; unknown ids ride along untouched.
     *  SEMANTICS (faction-model v1): the stored number is the EARNED
     *  DELTA (default 0) — the manager layers the live race-computed
     *  disposition base under it on read. */
    private final Map<UUID, Map<String, Double>> standings = new HashMap<>();

    /** player → (faction id string → offense score). The no-decay
     *  ledger of ACTS (docs/lore-events.md #3 / faction-model.md #4) —
     *  written by the marked-boss movers, consumed/reset by faction
     *  events. Same addon-door string keying as standings. */
    private final Map<UUID, Map<String, Double>> offenses = new HashMap<>();

    private WorldReputationSavedData() {}

    static WorldReputationSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorldReputationSavedData::new,
                                        WorldReputationSavedData::load),
                DATA_KEY
        );
    }

    /** Raw read — null when never set (manager applies the default). */
    Double getStanding(UUID player, String factionId) {
        Map<String, Double> byFaction = standings.get(player);
        return byFaction == null ? null : byFaction.get(factionId);
    }

    void setStanding(UUID player, String factionId, double value) {
        standings.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, value);
        setDirty();
    }

    /** Raw offense read — 0 when never written. */
    double getOffense(UUID player, String factionId) {
        Map<String, Double> byFaction = offenses.get(player);
        Double stored = byFaction == null ? null : byFaction.get(factionId);
        return stored == null ? 0.0 : stored;
    }

    void setOffense(UUID player, String factionId, double value) {
        offenses.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, value);
        setDirty();
    }

    void clearOffense(UUID player, String factionId) {
        Map<String, Double> byFaction = offenses.get(player);
        if (byFaction != null && byFaction.remove(factionId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Map<String, Double>> e : standings.entrySet()) {
            CompoundTag player = new CompoundTag();
            player.putUUID("uuid", e.getKey());
            ListTag entries = new ListTag();
            for (Map.Entry<String, Double> s : e.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", s.getKey());
                entry.putDouble("value", s.getValue());
                entries.add(entry);
            }
            player.put("standings", entries);
            ListTag offenseEntries = new ListTag();
            Map<String, Double> byFaction = offenses.get(e.getKey());
            if (byFaction != null) {
                for (Map.Entry<String, Double> o : byFaction.entrySet()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("faction", o.getKey());
                    entry.putDouble("value", o.getValue());
                    offenseEntries.add(entry);
                }
            }
            player.put("offenses", offenseEntries);
            players.add(player);
        }
        // Players with offenses but no standings still need persisting.
        for (Map.Entry<UUID, Map<String, Double>> e : offenses.entrySet()) {
            if (standings.containsKey(e.getKey()) || e.getValue().isEmpty()) continue;
            CompoundTag player = new CompoundTag();
            player.putUUID("uuid", e.getKey());
            player.put("standings", new ListTag());
            ListTag offenseEntries = new ListTag();
            for (Map.Entry<String, Double> o : e.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", o.getKey());
                entry.putDouble("value", o.getValue());
                offenseEntries.add(entry);
            }
            player.put("offenses", offenseEntries);
            players.add(player);
        }
        tag.put("players", players);
        return tag;
    }

    static WorldReputationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldReputationSavedData data = new WorldReputationSavedData();
        if (tag.contains("players", Tag.TAG_LIST)) {
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag player = players.getCompound(i);
                if (!player.hasUUID("uuid")) continue;
                Map<String, Double> byFaction = new HashMap<>();
                ListTag entries = player.getList("standings", Tag.TAG_COMPOUND);
                for (int j = 0; j < entries.size(); j++) {
                    CompoundTag entry = entries.getCompound(j);
                    // Unknown faction ids are kept — the addon door.
                    byFaction.put(entry.getString("faction"), entry.getDouble("value"));
                }
                if (!byFaction.isEmpty()) {
                    data.standings.put(player.getUUID("uuid"), byFaction);
                }
                Map<String, Double> offByFaction = new HashMap<>();
                ListTag offenseEntries = player.getList("offenses", Tag.TAG_COMPOUND);
                for (int j = 0; j < offenseEntries.size(); j++) {
                    CompoundTag entry = offenseEntries.getCompound(j);
                    offByFaction.put(entry.getString("faction"), entry.getDouble("value"));
                }
                if (!offByFaction.isEmpty()) {
                    data.offenses.put(player.getUUID("uuid"), offByFaction);
                }
            }
        }
        return data;
    }
}
