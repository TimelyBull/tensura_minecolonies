package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assassin-system state — per-identity determination/state plus the
 * per-colony cold-shoulder flag. Overworld-scoped, same pattern as the
 * other SavedData files. Kept SEPARATE from RaceIdentitySavedData on
 * purpose (no surgery on the identity NBT); entries are pruned when
 * their identity disappears.
 *
 * States: 0 NONE (only a determination value), 1 LURKING (potential
 * assassin — Great Sage can see it), 2 ARMED (guaranteed strike at the
 * next vulnerability window), 3 ACTIVE (the boss is in the world).
 */
class AssassinSavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_assassins";

    static final byte STATE_NONE = 0, STATE_LURKING = 1, STATE_ARMED = 2, STATE_ACTIVE = 3;

    /** identityId → accumulated determination (in-game days of misery). */
    private final Map<UUID, Double> determination = new HashMap<>();
    /** identityId → assassin state (see STATE_*). */
    private final Map<UUID, Byte> state = new HashMap<>();
    /** Colonies whose citizens cold-shoulder the player (active assassin). */
    private final Set<Integer> coldShoulder = new HashSet<>();
    /** Players owed an EP reclaim (boss died while they were offline) —
     *  applied on their next login. */
    private final Set<UUID> pendingReclaim = new HashSet<>();
    /** Colonies that have already CHOSEN their assassin — once marked,
     *  the colony never breeds another (defused, slain, or otherwise). */
    private final Set<Integer> assassinChosen = new HashSet<>();

    private AssassinSavedData() {}

    static AssassinSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AssassinSavedData::new, AssassinSavedData::load),
                DATA_KEY
        );
    }

    double getDetermination(UUID identityId) {
        return determination.getOrDefault(identityId, 0.0);
    }

    void setDetermination(UUID identityId, double value) {
        if (value <= 0) determination.remove(identityId);
        else determination.put(identityId, value);
        setDirty();
    }

    byte getState(UUID identityId) {
        return state.getOrDefault(identityId, STATE_NONE);
    }

    void setState(UUID identityId, byte newState) {
        if (newState == STATE_NONE) state.remove(identityId);
        else state.put(identityId, newState);
        setDirty();
    }

    /** Drop every trace of this identity (defused, died, or pruned). */
    void clearIdentity(UUID identityId) {
        boolean changed = determination.remove(identityId) != null
                | state.remove(identityId) != null;
        if (changed) setDirty();
    }

    /** All identityIds with any assassin state/determination. */
    Set<UUID> trackedIdentities() {
        Set<UUID> out = new HashSet<>(determination.keySet());
        out.addAll(state.keySet());
        return out;
    }

    boolean hasPendingReclaim(UUID playerUuid) {
        return pendingReclaim.contains(playerUuid);
    }

    void setPendingReclaim(UUID playerUuid, boolean pending) {
        if (pending ? pendingReclaim.add(playerUuid) : pendingReclaim.remove(playerUuid)) {
            setDirty();
        }
    }

    boolean hasChosenAssassin(int colonyId) {
        return assassinChosen.contains(colonyId);
    }

    void markAssassinChosen(int colonyId) {
        if (assassinChosen.add(colonyId)) setDirty();
    }

    boolean isColdShouldered(int colonyId) {
        return coldShoulder.contains(colonyId);
    }

    void setColdShoulder(int colonyId, boolean on) {
        if (on ? coldShoulder.add(colonyId) : coldShoulder.remove(colonyId)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (UUID id : trackedIdentities()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("identityId", id);
            e.putDouble("determination", determination.getOrDefault(id, 0.0));
            e.putByte("state", state.getOrDefault(id, STATE_NONE));
            entries.add(e);
        }
        tag.put("assassins", entries);
        ListTag cold = new ListTag();
        for (Integer cid : coldShoulder) {
            CompoundTag e = new CompoundTag();
            e.putInt("colonyId", cid);
            cold.add(e);
        }
        tag.put("coldShoulder", cold);
        ListTag reclaim = new ListTag();
        for (UUID id : pendingReclaim) {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", id);
            reclaim.add(e);
        }
        tag.put("pendingReclaim", reclaim);
        ListTag chosen = new ListTag();
        for (Integer cid : assassinChosen) {
            CompoundTag e = new CompoundTag();
            e.putInt("colonyId", cid);
            chosen.add(e);
        }
        tag.put("assassinChosen", chosen);
        return tag;
    }

    static AssassinSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        AssassinSavedData data = new AssassinSavedData();
        if (tag.contains("assassins", Tag.TAG_LIST)) {
            ListTag entries = tag.getList("assassins", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag e = entries.getCompound(i);
                if (!e.hasUUID("identityId")) continue;
                UUID id = e.getUUID("identityId");
                double det = e.getDouble("determination");
                byte st = e.getByte("state");
                if (det > 0) data.determination.put(id, det);
                if (st != STATE_NONE) data.state.put(id, st);
            }
        }
        if (tag.contains("coldShoulder", Tag.TAG_LIST)) {
            ListTag cold = tag.getList("coldShoulder", Tag.TAG_COMPOUND);
            for (int i = 0; i < cold.size(); i++) {
                data.coldShoulder.add(cold.getCompound(i).getInt("colonyId"));
            }
        }
        if (tag.contains("pendingReclaim", Tag.TAG_LIST)) {
            ListTag reclaim = tag.getList("pendingReclaim", Tag.TAG_COMPOUND);
            for (int i = 0; i < reclaim.size(); i++) {
                CompoundTag e = reclaim.getCompound(i);
                if (e.hasUUID("uuid")) data.pendingReclaim.add(e.getUUID("uuid"));
            }
        }
        if (tag.contains("assassinChosen", Tag.TAG_LIST)) {
            ListTag chosen = tag.getList("assassinChosen", Tag.TAG_COMPOUND);
            for (int i = 0; i < chosen.size(); i++) {
                data.assassinChosen.add(chosen.getCompound(i).getInt("colonyId"));
            }
        }
        return data;
    }
}
