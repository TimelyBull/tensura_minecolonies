package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
        public final UUID ownerPlayerUUID;  // player who named the goblin; matches
                                            // IExistence.permanentOwner. Used for
                                            // the roster filter. Null on legacy records.

        public GoblinIdentity(UUID identityId, int citizenId, int colonyId,
                              UUID goblinEntityUUID, Mode mode,
                              CompoundTag entitySnapshot, UUID ownerPlayerUUID) {
            this.identityId        = identityId;
            this.citizenId         = citizenId;
            this.colonyId          = colonyId;
            this.goblinEntityUUID  = goblinEntityUUID;
            this.mode              = mode;
            this.entitySnapshot    = entitySnapshot;
            this.ownerPlayerUUID   = ownerPlayerUUID;
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
            if (ownerPlayerUUID != null) {
                tag.putUUID("ownerPlayerUUID", ownerPlayerUUID);
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
            UUID ownerPlayerUUID  = tag.hasUUID("ownerPlayerUUID")
                                    ? tag.getUUID("ownerPlayerUUID") : null;
            return new GoblinIdentity(identityId, citizenId, colonyId,
                                      goblinEntityUUID, mode, entity, ownerPlayerUUID);
        }
    }

    // -----------------------------------------------------------------
    // Pending pool — goblins named before any colony exists.
    // Drained by ColonyCreatedModEvent into the newly-created colony.
    // Single-colony assumption: see docs/decisions.md.
    // -----------------------------------------------------------------

    public static class PendingGoblin {
        public final UUID identityId;        // becomes GoblinIdentity.identityId on promotion
        public final String name;            // citizen name once promoted
        public final UUID goblinEntityUUID;  // for stale-check + identity link on promotion
        public final UUID ownerPlayerUUID;   // namer's UUID — propagated to GoblinIdentity on promotion

        public PendingGoblin(UUID identityId, String name, UUID goblinEntityUUID,
                             UUID ownerPlayerUUID) {
            this.identityId       = identityId;
            this.name             = name;
            this.goblinEntityUUID = goblinEntityUUID;
            this.ownerPlayerUUID  = ownerPlayerUUID;
        }

        CompoundTag toNBT() {
            CompoundTag t = new CompoundTag();
            t.putUUID("identityId", identityId);
            t.putString("name", name);
            t.putUUID("goblinEntityUUID", goblinEntityUUID);
            if (ownerPlayerUUID != null) t.putUUID("ownerPlayerUUID", ownerPlayerUUID);
            return t;
        }

        static PendingGoblin fromNBT(CompoundTag t) {
            UUID ownerPlayerUUID = t.hasUUID("ownerPlayerUUID")
                                   ? t.getUUID("ownerPlayerUUID") : null;
            return new PendingGoblin(
                    t.getUUID("identityId"),
                    t.getString("name"),
                    t.getUUID("goblinEntityUUID"),
                    ownerPlayerUUID
            );
        }
    }

    // -----------------------------------------------------------------
    // Internal maps
    // -----------------------------------------------------------------

    /** Primary map: identityId → identity */
    private final Map<UUID, GoblinIdentity> byIdentityId = new HashMap<>();

    /** Reverse map for quick lookup when a goblin entity is interacted with */
    private final Map<UUID, UUID> goblinUUIDToIdentityId = new HashMap<>();

    /** Pending goblins waiting for a colony to exist */
    private final List<PendingGoblin> pending = new java.util.ArrayList<>();

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

    /** Remove an identity entirely — called by the death hooks. Permanent. */
    public void removeIdentity(GoblinIdentity identity) {
        byIdentityId.remove(identity.identityId);
        if (identity.goblinEntityUUID != null) {
            goblinUUIDToIdentityId.remove(identity.goblinEntityUUID);
        }
        setDirty();
    }

    public void updateEntitySnapshot(GoblinIdentity identity, CompoundTag snapshot) {
        identity.entitySnapshot = snapshot;
        setDirty();
    }

    // -----------------------------------------------------------------
    // Pending pool — add / remove / list
    // -----------------------------------------------------------------

    public void addPending(PendingGoblin p) {
        pending.add(p);
        setDirty();
    }

    public void removePending(PendingGoblin p) {
        pending.remove(p);
        setDirty();
    }

    /** Defensive cleanup: drop a pending entry whose goblin entity died before
     *  any colony existed. Called from the goblin-death hook. No-op if no match. */
    public void removePendingByGoblinUUID(UUID goblinEntityUUID) {
        if (pending.removeIf(p -> p.goblinEntityUUID.equals(goblinEntityUUID))) {
            setDirty();
        }
    }

    public List<PendingGoblin> getPending() {
        return pending;
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
        ListTag identitiesList = new ListTag();
        for (GoblinIdentity identity : byIdentityId.values()) {
            identitiesList.add(identity.toNBT());
        }
        tag.put("identities", identitiesList);

        ListTag pendingList = new ListTag();
        for (PendingGoblin p : pending) {
            pendingList.add(p.toNBT());
        }
        tag.put("pending", pendingList);

        return tag;
    }

    public static GoblinIdentitySavedData load(CompoundTag tag,
                                               HolderLookup.Provider registries) {
        GoblinIdentitySavedData data = new GoblinIdentitySavedData();

        ListTag identitiesList = tag.getList("identities", Tag.TAG_COMPOUND);
        for (int i = 0; i < identitiesList.size(); i++) {
            GoblinIdentity identity = GoblinIdentity.fromNBT(identitiesList.getCompound(i));
            data.byIdentityId.put(identity.identityId, identity);
            if (identity.goblinEntityUUID != null) {
                data.goblinUUIDToIdentityId.put(identity.goblinEntityUUID, identity.identityId);
            }
        }

        ListTag pendingList = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            data.pending.add(PendingGoblin.fromNBT(pendingList.getCompound(i)));
        }

        return data;
    }
}
