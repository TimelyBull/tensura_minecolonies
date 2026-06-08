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
 * Server-side persistent store for named race-mob identities.
 *
 * Keyed by an identity UUID assigned at naming time, independent of any
 * live entity. Each record links a Tensura race-mob (by entity UUID) to
 * a MineColonies CitizenData (by integer ID), the race itself, and the
 * last-known full-entity NBT snapshot so the mob can be re-materialized
 * with identical stats and appearance.
 *
 * Saved to: {@code world/data/tensura_minecolonies_identities.dat}
 * (data key unchanged from the goblin-only version of this class —
 * world saves are backward-compatible).
 *
 * Stage G1 rename: was {@code GoblinIdentitySavedData}, with goblin-only
 * inner records. The class is now race-general; legacy goblin-only
 * records (no {@code race} NBT key) decode as {@link Race#GOBLIN}.
 */
public class RaceIdentitySavedData extends SavedData {

    public static final String DATA_KEY = "tensura_minecolonies_identities";

    // -----------------------------------------------------------------
    // Identity record
    // -----------------------------------------------------------------

    public enum Mode { SUBORDINATE, IN_COLONY }

    public static class RaceIdentity {
        public final UUID identityId;       // stable key, assigned at naming time
        public final int  citizenId;        // MineColonies CitizenData integer ID
        public final int  colonyId;         // which colony this identity belongs to
        public UUID       mobEntityUUID;    // current race-mob entity UUID
                                            // (null while IN_COLONY)
        public Mode       mode;
        public CompoundTag entitySnapshot;  // full Entity.save(tag) — captures
                                            // type, position, attributes,
                                            // inventory, appearance, evolution
                                            // state, and ManasCoreStorage.
                                            // Null until first send.
        public final UUID ownerPlayerUUID;  // player who named the mob; matches
                                            // IExistence.permanentOwner.
        public Race race;                   // which worker race this identity is —
                                            // determines renderer + variant
                                            // capture. NULL when this identity
                                            // is a beast (see {@link #beast}).
        public Beast beast;                 // Stage L2: which beast this identity
                                            // is. NULL when {@link #race} is set.
                                            // Exactly one of race/beast is non-null
                                            // for any well-formed identity.

        public RaceIdentity(UUID identityId, int citizenId, int colonyId,
                            UUID mobEntityUUID, Mode mode,
                            CompoundTag entitySnapshot, UUID ownerPlayerUUID,
                            Race race) {
            this(identityId, citizenId, colonyId, mobEntityUUID, mode,
                 entitySnapshot, ownerPlayerUUID, race, null);
        }

        /** Full constructor accepting both race and beast — exactly one
         *  must be non-null. The race-only convenience ctor above passes
         *  beast=null and is the path every legacy call site uses. */
        public RaceIdentity(UUID identityId, int citizenId, int colonyId,
                            UUID mobEntityUUID, Mode mode,
                            CompoundTag entitySnapshot, UUID ownerPlayerUUID,
                            Race race, Beast beast) {
            this.identityId       = identityId;
            this.citizenId        = citizenId;
            this.colonyId         = colonyId;
            this.mobEntityUUID    = mobEntityUUID;
            this.mode             = mode;
            this.entitySnapshot   = entitySnapshot;
            this.ownerPlayerUUID  = ownerPlayerUUID;
            this.race             = race;
            this.beast            = beast;
        }

        /** True iff this identity is a beast (not a worker race). */
        public boolean isBeast() { return beast != null; }

        CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("identityId", identityId);
            tag.putInt("citizenId", citizenId);
            tag.putInt("colonyId", colonyId);
            tag.putString("mode", mode.name());
            // NBT key kept as "goblinEntityUUID" for backward compat with
            // existing saves — the field is now a generic mob UUID.
            if (mobEntityUUID != null) {
                tag.putUUID("goblinEntityUUID", mobEntityUUID);
            }
            if (entitySnapshot != null) {
                tag.put("entity", entitySnapshot.copy());
            }
            if (ownerPlayerUUID != null) {
                tag.putUUID("ownerPlayerUUID", ownerPlayerUUID);
            }
            // Exactly one of race / beast is non-null. Race write skipped
            // for beast identities so legacy "race=GOBLIN default on
            // missing" logic in {@link #fromNBT} cleanly distinguishes
            // race-only old saves from beast-only new ones.
            if (race != null) {
                tag.putByte("race", (byte) race.getId());
            }
            if (beast != null) {
                tag.putByte("beast", (byte) beast.getId());
            }
            return tag;
        }

        static RaceIdentity fromNBT(CompoundTag tag) {
            UUID identityId       = tag.getUUID("identityId");
            int  citizenId        = tag.getInt("citizenId");
            int  colonyId         = tag.getInt("colonyId");
            Mode mode             = Mode.valueOf(tag.getString("mode"));
            UUID mobEntityUUID    = tag.hasUUID("goblinEntityUUID")
                                    ? tag.getUUID("goblinEntityUUID") : null;
            CompoundTag entity    = tag.contains("entity", Tag.TAG_COMPOUND)
                                    ? tag.getCompound("entity") : null;
            UUID ownerPlayerUUID  = tag.hasUUID("ownerPlayerUUID")
                                    ? tag.getUUID("ownerPlayerUUID") : null;
            // Decode order: beast tag wins if present (Stage L2 records),
            // else race tag (worker race) with legacy fallback to GOBLIN.
            Beast beast = tag.contains("beast")
                          ? Beast.byId(tag.getByte("beast") & 0xFF)
                          : null;
            Race race;
            if (beast != null) {
                // Beast identity — race intentionally null.
                race = null;
            } else {
                race = tag.contains("race")
                       ? Race.byId(tag.getByte("race") & 0xFF)
                       : Race.GOBLIN; // legacy records (pre-multi-race-format)
            }
            return new RaceIdentity(identityId, citizenId, colonyId,
                                    mobEntityUUID, mode, entity,
                                    ownerPlayerUUID, race, beast);
        }
    }

    // -----------------------------------------------------------------
    // Pending pool — race-mobs named before any colony exists.
    // Drained by ColonyCreatedModEvent into the newly-created colony.
    // -----------------------------------------------------------------

    public static class PendingRaceMob {
        public final UUID identityId;        // becomes RaceIdentity.identityId on promotion
        public final String name;            // citizen name once promoted
        public final UUID mobEntityUUID;     // for stale-check + identity link
        public final UUID ownerPlayerUUID;   // namer's UUID
        public final Race race;              // which race this pending mob is

        public PendingRaceMob(UUID identityId, String name, UUID mobEntityUUID,
                              UUID ownerPlayerUUID, Race race) {
            this.identityId       = identityId;
            this.name             = name;
            this.mobEntityUUID    = mobEntityUUID;
            this.ownerPlayerUUID  = ownerPlayerUUID;
            this.race             = race;
        }

        CompoundTag toNBT() {
            CompoundTag t = new CompoundTag();
            t.putUUID("identityId", identityId);
            t.putString("name", name);
            // Same NBT-key compat as RaceIdentity — key stays "goblinEntityUUID".
            t.putUUID("goblinEntityUUID", mobEntityUUID);
            if (ownerPlayerUUID != null) t.putUUID("ownerPlayerUUID", ownerPlayerUUID);
            t.putByte("race", (byte) race.getId());
            return t;
        }

        static PendingRaceMob fromNBT(CompoundTag t) {
            UUID ownerPlayerUUID = t.hasUUID("ownerPlayerUUID")
                                   ? t.getUUID("ownerPlayerUUID") : null;
            Race race = t.contains("race")
                        ? Race.byId(t.getByte("race") & 0xFF)
                        : Race.GOBLIN; // legacy records
            return new PendingRaceMob(
                    t.getUUID("identityId"),
                    t.getString("name"),
                    t.getUUID("goblinEntityUUID"),
                    ownerPlayerUUID,
                    race
            );
        }
    }

    // -----------------------------------------------------------------
    // Internal maps
    // -----------------------------------------------------------------

    /** Primary map: identityId → identity */
    private final Map<UUID, RaceIdentity> byIdentityId = new HashMap<>();

    /** Reverse map for quick lookup when a mob entity is interacted with */
    private final Map<UUID, UUID> mobUUIDToIdentityId = new HashMap<>();

    /** Pending race-mobs waiting for a colony to exist */
    private final List<PendingRaceMob> pending = new java.util.ArrayList<>();

    // -----------------------------------------------------------------
    // Factory / access
    // -----------------------------------------------------------------

    private RaceIdentitySavedData() {}

    /**
     * Get (or create) the identity store for this server. Always uses
     * the overworld's data storage so identities are server-global, not
     * per-dimension.
     */
    public static RaceIdentitySavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaceIdentitySavedData::new,
                                        RaceIdentitySavedData::load),
                DATA_KEY
        );
    }

    // -----------------------------------------------------------------
    // Mutators
    // -----------------------------------------------------------------

    public void addIdentity(RaceIdentity identity) {
        byIdentityId.put(identity.identityId, identity);
        if (identity.mobEntityUUID != null) {
            mobUUIDToIdentityId.put(identity.mobEntityUUID, identity.identityId);
        }
        setDirty();
    }

    /**
     * Call whenever mobEntityUUID changes (e.g. a freshly-summoned mob
     * has a new entity UUID). Keeps the reverse map consistent.
     */
    public void updateMobUUID(RaceIdentity identity, UUID newMobUUID) {
        if (identity.mobEntityUUID != null) {
            mobUUIDToIdentityId.remove(identity.mobEntityUUID);
        }
        identity.mobEntityUUID = newMobUUID;
        if (newMobUUID != null) {
            mobUUIDToIdentityId.put(newMobUUID, identity.identityId);
        }
        setDirty();
    }

    public void updateMode(RaceIdentity identity, Mode mode) {
        identity.mode = mode;
        setDirty();
    }

    /** Remove an identity entirely — called by the death hooks. Permanent. */
    public void removeIdentity(RaceIdentity identity) {
        byIdentityId.remove(identity.identityId);
        if (identity.mobEntityUUID != null) {
            mobUUIDToIdentityId.remove(identity.mobEntityUUID);
        }
        setDirty();
    }

    public void updateEntitySnapshot(RaceIdentity identity, CompoundTag snapshot) {
        identity.entitySnapshot = snapshot;
        setDirty();
    }

    // -----------------------------------------------------------------
    // Pending pool — add / remove / list
    // -----------------------------------------------------------------

    public void addPending(PendingRaceMob p) {
        pending.add(p);
        setDirty();
    }

    public void removePending(PendingRaceMob p) {
        pending.remove(p);
        setDirty();
    }

    /** Defensive cleanup: drop a pending entry whose mob entity died before
     *  any colony existed. Called from the mob-death hook. No-op if no match. */
    public void removePendingByMobUUID(UUID mobEntityUUID) {
        if (pending.removeIf(p -> p.mobEntityUUID.equals(mobEntityUUID))) {
            setDirty();
        }
    }

    public List<PendingRaceMob> getPending() {
        return pending;
    }

    // -----------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------

    /** Look up by the stable identity UUID — used by the menu action handler. */
    public RaceIdentity getById(UUID identityId) {
        return byIdentityId.get(identityId);
    }

    /** Look up by the mob entity's UUID — used in the send/death handlers. */
    public RaceIdentity getByMobUUID(UUID mobEntityUUID) {
        UUID identityId = mobUUIDToIdentityId.get(mobEntityUUID);
        return identityId != null ? byIdentityId.get(identityId) : null;
    }

    /** Look up by MineColonies citizen integer ID — used in the summon/death handlers. */
    public RaceIdentity getByCitizenId(int citizenId) {
        for (RaceIdentity identity : byIdentityId.values()) {
            if (identity.citizenId == citizenId) return identity;
        }
        return null;
    }

    public Collection<RaceIdentity> all() {
        return byIdentityId.values();
    }

    // -----------------------------------------------------------------
    // Save / load
    // -----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag identitiesList = new ListTag();
        for (RaceIdentity identity : byIdentityId.values()) {
            identitiesList.add(identity.toNBT());
        }
        tag.put("identities", identitiesList);

        ListTag pendingList = new ListTag();
        for (PendingRaceMob p : pending) {
            pendingList.add(p.toNBT());
        }
        tag.put("pending", pendingList);

        return tag;
    }

    public static RaceIdentitySavedData load(CompoundTag tag,
                                             HolderLookup.Provider registries) {
        RaceIdentitySavedData data = new RaceIdentitySavedData();

        ListTag identitiesList = tag.getList("identities", Tag.TAG_COMPOUND);
        for (int i = 0; i < identitiesList.size(); i++) {
            RaceIdentity identity = RaceIdentity.fromNBT(identitiesList.getCompound(i));
            data.byIdentityId.put(identity.identityId, identity);
            if (identity.mobEntityUUID != null) {
                data.mobUUIDToIdentityId.put(identity.mobEntityUUID, identity.identityId);
            }
        }

        ListTag pendingList = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            data.pending.add(PendingRaceMob.fromNBT(pendingList.getCompound(i)));
        }

        return data;
    }
}
