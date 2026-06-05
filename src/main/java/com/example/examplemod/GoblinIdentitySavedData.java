package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side persistent store for named-goblin identities.
 *
 * Keyed by an identity UUID assigned at naming time, independent of any live
 * entity. Each record links a Tensura goblin (by entity UUID) to a MineColonies
 * CitizenData (by integer ID) and stores the last-known IExistence NBT snapshot
 * so the goblin can be re-materialized with identical stats.
 *
 * Saved to: world/data/tensura_minecolonies_identities.dat
 */
public class GoblinIdentitySavedData extends SavedData {

    public static final String DATA_KEY = "tensura_minecolonies_identities";

    // -----------------------------------------------------------------
    // Identity record
    // -----------------------------------------------------------------

    public enum Mode { SUBORDINATE, IN_COLONY }

    public static class GoblinIdentity {
        public final UUID identityId;       // stable key, assigned at naming time
        public final int  citizenId;        // MineColonies CitizenData integer ID
        public final int  colonyId;         // which colony this identity belongs to
        public UUID       goblinEntityUUID; // current goblin entity UUID (null while IN_COLONY)
        public Mode       mode;
        public CompoundTag entitySnapshot; // full Entity.save(tag) — captures
                                           // type, position, attributes, inventory,
                                           // appearance, EvoState, and ManasCoreStorage
                                           // (all Tensura storages). Null until first send.

        public GoblinIdentity(UUID identityId, int citizenId, int colonyId,
                              UUID goblinEntityUUID, Mode mode,
                              CompoundTag entitySnapshot) {
            this.identityId        = identityId;
            this.citizenId         = citizenId;
            this.colonyId          = colonyId;
            this.goblinEntityUUID  = goblinEntityUUID;
            this.mode              = mode;
            this.entitySnapshot = entitySnapshot;
        }

        CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("identityId", identityId);
            tag.putInt("citizenId", citizenId);
            tag.putInt("colonyId", colonyId);
            tag.putString("mode", mode.name());
            if (goblinEntityUUID != null) {
                tag.putUUID("goblinEntityUUID", goblinEntityUUID);
            }
            if (entitySnapshot != null) {
                tag.put("entity", entitySnapshot.copy());
            }
            return tag;
        }

        static GoblinIdentity fromNBT(CompoundTag tag) {
            UUID identityId       = tag.getUUID("identityId");
            int  citizenId        = tag.getInt("citizenId");
            int  colonyId         = tag.getInt("colonyId");
            Mode mode             = Mode.valueOf(tag.getString("mode"));
            UUID goblinEntityUUID = tag.hasUUID("goblinEntityUUID")
                                    ? tag.getUUID("goblinEntityUUID") : null;
            CompoundTag entity = tag.contains("entity", Tag.TAG_COMPOUND)
                                 ? tag.getCompound("entity") : null;
            return new GoblinIdentity(identityId, citizenId, colonyId,
                                      goblinEntityUUID, mode, entity);
        }
    }

    // -----------------------------------------------------------------
    // Internal maps
    // -----------------------------------------------------------------

    /** Primary map: identityId → identity */
    private final Map<UUID, GoblinIdentity> byIdentityId = new HashMap<>();

    /** Reverse map for quick lookup when a goblin entity is interacted with */
    private final Map<UUID, UUID> goblinUUIDToIdentityId = new HashMap<>();

    // -----------------------------------------------------------------
    // Factory / access
    // -----------------------------------------------------------------

    private GoblinIdentitySavedData() {}

    /**
     * Get (or create) the identity store for this server.
     * Always use the overworld's data storage so identities are server-global,
     * not per-dimension.
     */
    public static GoblinIdentitySavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GoblinIdentitySavedData::new,
                                        GoblinIdentitySavedData::load),
                DATA_KEY
        );
    }

    // -----------------------------------------------------------------
    // Mutators
    // -----------------------------------------------------------------

    public void addIdentity(GoblinIdentity identity) {
        byIdentityId.put(identity.identityId, identity);
        if (identity.goblinEntityUUID != null) {
            goblinUUIDToIdentityId.put(identity.goblinEntityUUID, identity.identityId);
        }
        setDirty();
    }

    /**
     * Call whenever goblinEntityUUID changes (e.g. a freshly-summoned goblin
     * has a new entity UUID). Keeps the reverse map consistent.
     */
    public void updateGoblinUUID(GoblinIdentity identity, UUID newGoblinUUID) {
        if (identity.goblinEntityUUID != null) {
            goblinUUIDToIdentityId.remove(identity.goblinEntityUUID);
        }
        identity.goblinEntityUUID = newGoblinUUID;
        if (newGoblinUUID != null) {
            goblinUUIDToIdentityId.put(newGoblinUUID, identity.identityId);
        }
        setDirty();
    }

    public void updateMode(GoblinIdentity identity, Mode mode) {
        identity.mode = mode;
        setDirty();
    }

    public void updateEntitySnapshot(GoblinIdentity identity, CompoundTag snapshot) {
        identity.entitySnapshot = snapshot;
        setDirty();
    }

    // -----------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------

    /** Look up by the goblin entity's UUID — used in the send/death handlers. */
    public GoblinIdentity getByGoblinUUID(UUID goblinEntityUUID) {
        UUID identityId = goblinUUIDToIdentityId.get(goblinEntityUUID);
        return identityId != null ? byIdentityId.get(identityId) : null;
    }

    /** Look up by MineColonies citizen integer ID — used in the summon/death handlers. */
    public GoblinIdentity getByCitizenId(int citizenId) {
        for (GoblinIdentity identity : byIdentityId.values()) {
            if (identity.citizenId == citizenId) return identity;
        }
        return null;
    }

    public Collection<GoblinIdentity> all() {
        return byIdentityId.values();
    }

    // -----------------------------------------------------------------
    // Save / load
    // -----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (GoblinIdentity identity : byIdentityId.values()) {
            list.add(identity.toNBT());
        }
        tag.put("identities", list);
        return tag;
    }

    public static GoblinIdentitySavedData load(CompoundTag tag,
                                               HolderLookup.Provider registries) {
        GoblinIdentitySavedData data = new GoblinIdentitySavedData();
        ListTag list = tag.getList("identities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            GoblinIdentity identity = GoblinIdentity.fromNBT(list.getCompound(i));
            data.byIdentityId.put(identity.identityId, identity);
            if (identity.goblinEntityUUID != null) {
                data.goblinUUIDToIdentityId.put(identity.goblinEntityUUID, identity.identityId);
            }
        }
        return data;
    }
}
