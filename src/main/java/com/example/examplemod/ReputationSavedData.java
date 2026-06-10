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
 * Reputation storage — per-colony standing plus a per-player (ruler)
 * standing reserved for future features.
 *
 * <p><b>ACCESS RULE: nothing outside {@link ReputationManager} may touch
 * this class.</b> The manager is the sole door — it owns clamping, the
 * default, dirty-marking, and any future side-effects (sync, logging,
 * throttling). The class and its accessors are deliberately
 * package-private and undecorated; if you're about to call this from a
 * feature, call {@link ReputationManager} instead.
 *
 * <p>One global instance per server, on the overworld's data storage —
 * the same pattern as {@link RaceIdentitySavedData} and
 * {@link ColonyRaceConfigSavedData}.
 *
 * <p>Missing key → {@link ReputationManager#DEFAULT_REPUTATION} (50.0,
 * NEUTRAL). Legacy worlds and freshly-created colonies need no
 * migration — absent simply reads as neutral.
 *
 * NBT format:
 * <pre>
 * colonies: [ { colonyId: int, value: double } ]
 * players:  [ { uuid: UUID,   value: double } ]
 * </pre>
 *
 * Saved to: {@code world/data/tensura_minecolonies_reputation.dat}
 */
class ReputationSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_reputation";

    /** Per-colony standing — the v1 core. */
    private final Map<Integer, Double> reputationByColony = new HashMap<>();

    /** Per-player (ruler) standing — plumbed now, driven by NO v1 mover.
     *  Reserved for future features (assassins, reclaim, ruler dialogue)
     *  that need a standing independent of any single colony. */
    private final Map<UUID, Double> reputationByPlayer = new HashMap<>();

    private ReputationSavedData() {}

    static ReputationSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ReputationSavedData::new,
                                        ReputationSavedData::load),
                DATA_KEY
        );
    }

    // -----------------------------------------------------------------
    // Raw accessors — ReputationManager only. No clamping here; the
    // manager clamps before writing and defaults on read.
    // -----------------------------------------------------------------

    Double getColonyValue(int colonyId) {
        return reputationByColony.get(colonyId);
    }

    void setColonyValue(int colonyId, double value) {
        reputationByColony.put(colonyId, value);
        setDirty();
    }

    void clearColony(int colonyId) {
        if (reputationByColony.remove(colonyId) != null) {
            setDirty();
        }
    }

    Double getPlayerValue(UUID playerUuid) {
        return reputationByPlayer.get(playerUuid);
    }

    void setPlayerValue(UUID playerUuid, double value) {
        reputationByPlayer.put(playerUuid, value);
        setDirty();
    }

    // -----------------------------------------------------------------
    // Save / load
    // -----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag colonies = new ListTag();
        for (Map.Entry<Integer, Double> e : reputationByColony.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            entry.putDouble("value", e.getValue());
            colonies.add(entry);
        }
        tag.put("colonies", colonies);

        ListTag players = new ListTag();
        for (Map.Entry<UUID, Double> e : reputationByPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", e.getKey());
            entry.putDouble("value", e.getValue());
            players.add(entry);
        }
        tag.put("players", players);

        return tag;
    }

    static ReputationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReputationSavedData data = new ReputationSavedData();
        if (tag.contains("colonies", Tag.TAG_LIST)) {
            ListTag colonies = tag.getList("colonies", Tag.TAG_COMPOUND);
            for (int i = 0; i < colonies.size(); i++) {
                CompoundTag entry = colonies.getCompound(i);
                data.reputationByColony.put(entry.getInt("colonyId"), entry.getDouble("value"));
            }
        }
        if (tag.contains("players", Tag.TAG_LIST)) {
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag entry = players.getCompound(i);
                if (entry.hasUUID("uuid")) {
                    data.reputationByPlayer.put(entry.getUUID("uuid"), entry.getDouble("value"));
                }
            }
        }
        return data;
    }
}
