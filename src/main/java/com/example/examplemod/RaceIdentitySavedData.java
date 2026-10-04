package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(RaceIdentitySavedData.class);

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
                                            // Captured at naming time, refreshed
                                            // periodically while the subordinate
                                            // is loaded and on each send. Null
                                            // only for legacy pre-2026-07-13
                                            // records that never sent.
        public final UUID ownerPlayerUUID;  // player who named the mob; matches
                                            // IExistence.permanentOwner.
        public Race race;                   // which worker race this identity is —
                                            // determines renderer + variant
                                            // capture.
        public boolean defendingColony = false; // ColonyThreatResponse — true
                                            // while this citizen has been
                                            // place-swapped to its Tensura
                                            // body to fight off a colony
                                            // threat. The source-of-truth flag
                                            // distinguishing a defense-swap
                                            // from a player-menu summon, so a
                                            // reload mid-defense reconciles
                                            // correctly (swap back when the
                                            // threat ends). Persisted; default
                                            // false for legacy records.
        public CompoundTag raceTagSnapshot; // FIX 2 (Bug 1a — the revert) —
                                            // the last-known RaceTag, serialized
                                            // via RaceTag.SERIALIZER. The race
                                            // appearance lives on the live
                                            // citizen ENTITY as an attachment,
                                            // which is lost whenever MineColonies
                                            // rebuilds the body from its own
                                            // CitizenData (respawn loop) instead
                                            // of from chunk NBT — the citizen
                                            // then renders as a plain colonist.
                                            // This durable copy lets the
                                            // EntityJoinLevelEvent handler
                                            // re-stamp the attachment so the
                                            // Tensura form self-heals. Null for
                                            // identities never sent (no tag built
                                            // yet) and legacy records.
        public UUID controlledByUUID = null; // Mind-control (TR:Nightmare /
                                            // Tensura charm skills) — the player
                                            // currently holding this mob's
                                            // Tensura temporaryOwner, when that
                                            // differs from ownerPlayerUUID.
                                            // Maintained by MindControlTracker's
                                            // per-second reconcile from the LIVE
                                            // mob (source of truth is Tensura's
                                            // existence storage; this is the
                                            // persisted mirror so a reload
                                            // mid-control reconciles). null =
                                            // not controlled.
        public boolean controlParked = false; // true while the CITIZEN half was
                                            // moved into the CONTROLLER's colony
                                            // by a send-while-controlled. The
                                            // revert gate: when control ends and
                                            // this is set, the tracker moves the
                                            // citizen back to the owner's colony
                                            // (silently) and clears it. Persisted.
        public long controlBudgetTicks = -1; // COLONIST-mode control budget —
                                            // how long a controlled citizen may
                                            // keep serving the controller's
                                            // colony. Set at CONTROL START from
                                            // the mind-control effect's duration
                                            // (strength proxy), capped at 1 h;
                                            // FROZEN while the wild body is out
                                            // (its own effect timer runs then);
                                            // decremented per second only while
                                            // parked IN_COLONY; at 0 control is
                                            // force-ended and the citizen goes
                                            // home. -1 = unset. Persisted.
        public boolean planted = false;     // deceit — the CONTROLLER chose
                                            // "Send to Original Colony": the
                                            // citizen serves in the OWNER's
                                            // colony while secretly controlled.
                                            // Burns the colonist budget like a
                                            // park; cleared whenever control
                                            // ends. Persisted.
        public UUID previousOwnerUUID = null; // set when a PERMANENT steal
                                            // transfers this identity away —
                                            // the player it was taken FROM.
                                            // Grants the ex-owner: the
                                            // "(stolen)" roster row, the
                                            // ask-permission summon/send flow,
                                            // and the Release attempt. Swapped
                                            // on each transfer (a released
                                            // subordinate records the thief).
                                            // Persisted.
        public byte controlRank = 0;        // rank of the CONTROL skill that
                                            // took this mob (read from the
                                            // MIND_CONTROL effect's source
                                            // ability at control start): 0
                                            // none, 1 unique-tier, 2
                                            // ultimate-tier, 3 god-tier, 4
                                            // SUPREME (effect-less/indefinite
                                            // controls — Lemegeton seal,
                                            // King's Authority, permanent
                                            // Charisma). Drives unmasking +
                                            // interrogation. Persisted.
        public boolean stealing = false;    // deceit — [Steal] toggled on a
                                            // planted sleeper: it skims from
                                            // the victim colony's warehouses
                                            // into stolenLoot. Cleared when
                                            // control ends. Persisted.
        public net.minecraft.nbt.ListTag stolenLoot = null; // skimmed item
                                            // stacks (ItemStack NBT), handed
                                            // to the controller when they next
                                            // summon the sleeper out. Dropped
                                            // (lost) if control ends first.
                                            // Persisted.
        public boolean strikeArmed = false; // deceit — the controller ordered
                                            // the planted sleeper to strike;
                                            // it waits for the owner's next
                                            // vulnerability window (Assassins
                                            // detector). Cleared at launch or
                                            // when control ends. Persisted.
        public boolean scrubControl = false; // set when the colonist budget
                                            // expires while the control NBT
                                            // (temp owner + effect) is still
                                            // baked into the entity snapshot —
                                            // the next live body gets the stale
                                            // control stripped, then this
                                            // clears. Persisted.
        public net.minecraft.core.BlockPos jobSitePos; // Feature C — the
                                            // villager job-site block this
                                            // citizen merchant claimed its
                                            // profession from. While set, the
                                            // citizen keeps its profession /
                                            // trades as long as THIS block
                                            // stays a job site (independent of
                                            // the citizen wandering); cleared
                                            // when the block is broken. null =
                                            // jobless / not anchored.

        public RaceIdentity(UUID identityId, int citizenId, int colonyId,
                            UUID mobEntityUUID, Mode mode,
                            CompoundTag entitySnapshot, UUID ownerPlayerUUID,
                            Race race) {
            this.identityId       = identityId;
            this.citizenId        = citizenId;
            this.colonyId         = colonyId;
            this.mobEntityUUID    = mobEntityUUID;
            this.mode             = mode;
            this.entitySnapshot   = cleanSnapshot(entitySnapshot);
            this.ownerPlayerUUID  = ownerPlayerUUID;
            this.race             = race;
        }

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
            if (race != null) {
                tag.putByte("race", (byte) race.getId());
            }
            if (jobSitePos != null) {
                tag.putLong("jobSitePos", jobSitePos.asLong());
            }
            if (raceTagSnapshot != null) {
                tag.put("raceTagSnapshot", raceTagSnapshot.copy());
            }
            if (defendingColony) {
                tag.putBoolean("defendingColony", true);
            }
            if (controlledByUUID != null) {
                tag.putUUID("controlledBy", controlledByUUID);
            }
            if (controlParked) {
                tag.putBoolean("controlParked", true);
            }
            if (controlBudgetTicks >= 0) {
                tag.putLong("controlBudget", controlBudgetTicks);
            }
            if (scrubControl) {
                tag.putBoolean("scrubControl", true);
            }
            if (planted) {
                tag.putBoolean("planted", true);
            }
            if (strikeArmed) {
                tag.putBoolean("strikeArmed", true);
            }
            if (controlRank != 0) {
                tag.putByte("controlRank", controlRank);
            }
            if (previousOwnerUUID != null) {
                tag.putUUID("previousOwner", previousOwnerUUID);
            }
            if (stealing) {
                tag.putBoolean("stealing", true);
            }
            if (stolenLoot != null && !stolenLoot.isEmpty()) {
                tag.put("stolenLoot", stolenLoot.copy());
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
            Race race = tag.contains("race")
                   ? Race.byId(tag.getByte("race") & 0xFF)
                   : Race.GOBLIN; // legacy records (pre-multi-race-format)
            RaceIdentity id = new RaceIdentity(identityId, citizenId, colonyId,
                                    mobEntityUUID, mode, entity,
                                    ownerPlayerUUID, race);
            if (tag.contains("jobSitePos")) {
                id.jobSitePos = net.minecraft.core.BlockPos.of(tag.getLong("jobSitePos"));
            }
            if (tag.contains("raceTagSnapshot", Tag.TAG_COMPOUND)) {
                id.raceTagSnapshot = tag.getCompound("raceTagSnapshot");
            }
            id.defendingColony = tag.getBoolean("defendingColony"); // false if absent
            if (tag.hasUUID("controlledBy")) {
                id.controlledByUUID = tag.getUUID("controlledBy");
            }
            id.controlParked = tag.getBoolean("controlParked"); // false if absent
            id.controlBudgetTicks = tag.contains("controlBudget")
                    ? tag.getLong("controlBudget") : -1L;
            id.scrubControl = tag.getBoolean("scrubControl"); // false if absent
            id.planted = tag.getBoolean("planted");           // false if absent
            id.strikeArmed = tag.getBoolean("strikeArmed");   // false if absent
            id.controlRank = tag.getByte("controlRank");      // 0 if absent
            if (tag.hasUUID("previousOwner")) {
                id.previousOwnerUUID = tag.getUUID("previousOwner");
            }
            id.stealing = tag.getBoolean("stealing");         // false if absent
            if (tag.contains("stolenLoot", Tag.TAG_LIST)) {
                id.stolenLoot = tag.getList("stolenLoot", Tag.TAG_COMPOUND);
            }
            return id;
        }
    }

    // -----------------------------------------------------------------
    // Pending pool — race-mobs named before any colony exists.
    // Drained by ColonyCreatedModEvent into the newly-created colony.
    // -----------------------------------------------------------------

    public static class PendingRaceMob {
        public final UUID identityId;        // becomes RaceIdentity.identityId on promotion
        public String name;                  // citizen name once promoted — a
                                             // re-name before any colony exists
                                             // updates this in place rather than
                                             // queueing the same mob twice
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

    /**
     * Register an identity, enforcing the two invariants the store lives by:
     *
     * <ol>
     *   <li><b>One identity per mob.</b> If a DIFFERENT identity already owns
     *       {@code identity.mobEntityUUID}, the new record is REFUSED (nothing is
     *       added, {@code false} is returned) and an error with a stack trace is
     *       logged. Silently displacing the older record is exactly what produced
     *       the 0.2.0 phantom citizen, so it is no longer possible. Every caller
     *       must check {@link #getByMobUUID} BEFORE creating a CitizenData.</li>
     *   <li><b>One identity per (colonyId, citizenId).</b> MineColonies reuses a
     *       dead citizen's number for the very next citizen it creates, so an old
     *       record still claiming that pair is by definition STALE (its citizen is
     *       gone). It is removed here, with a warning, before the new one goes in.
     *       Without this, two records fight over one citizen and the lookup
     *       returns whichever the map iterates first.</li>
     * </ol>
     *
     * @return true if the identity was added.
     */
    public boolean addIdentity(RaceIdentity identity) {
        if (identity.mobEntityUUID != null) {
            RaceIdentity mobOwner = getByMobUUID(identity.mobEntityUUID);
            if (mobOwner != null && !mobOwner.identityId.equals(identity.identityId)) {
                LOGGER.error("[TM] identity: REFUSED to register identity {} (citizen {} colony {} race {}) — "
                        + "mob {} already belongs to identity {} (citizen {} colony {}). "
                        + "A caller skipped the getByMobUUID check.",
                        identity.identityId, identity.citizenId, identity.colonyId, identity.race,
                        identity.mobEntityUUID, mobOwner.identityId, mobOwner.citizenId, mobOwner.colonyId,
                        new IllegalStateException("duplicate mob registration"));
                return false;
            }
        }
        RaceIdentity stale = getByColonyAndCitizen(identity.colonyId, identity.citizenId);
        if (stale != null && !stale.identityId.equals(identity.identityId)) {
            LOGGER.warn("[TM] identity: citizen {} in colony {} already had identity {} ({}, {}) — "
                    + "that record is stale (MineColonies reused the citizen number) and is removed "
                    + "in favour of new identity {} ({})",
                    identity.citizenId, identity.colonyId, stale.identityId, stale.race, stale.mode,
                    identity.identityId, identity.race);
            removeIdentity(stale);
        }
        byIdentityId.put(identity.identityId, identity);
        if (identity.mobEntityUUID != null) {
            mobUUIDToIdentityId.put(identity.mobEntityUUID, identity.identityId);
        }
        setDirty();
        return true;
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

    /** Flip the colony-defender flag (ColonyThreatResponse). Persisted so a
     *  reload mid-defense reconciles correctly. */
    public void setDefendingColony(RaceIdentity identity, boolean defending) {
        identity.defendingColony = defending;
        setDirty();
    }

    /** Mind-control mirror — set/clear the controlling player. Persisted. */
    public void setControlledBy(RaceIdentity identity, UUID controller) {
        identity.controlledByUUID = controller;
        setDirty();
    }

    /** Flag/clear the "citizen parked in the controller's colony" state. */
    public void setControlParked(RaceIdentity identity, boolean parked) {
        identity.controlParked = parked;
        setDirty();
    }

    /** Set the colonist-mode control budget (ticks; -1 = unset). */
    public void setControlBudget(RaceIdentity identity, long ticks) {
        identity.controlBudgetTicks = ticks;
        setDirty();
    }

    /** Flag/clear the "strip stale control off the next live body" marker. */
    public void setScrubControl(RaceIdentity identity, boolean scrub) {
        identity.scrubControl = scrub;
        setDirty();
    }

    /** Deceit — flag/clear the "planted in the owner's colony" state. */
    public void setPlanted(RaceIdentity identity, boolean planted) {
        identity.planted = planted;
        setDirty();
    }

    /** Deceit — arm/clear the sleeper strike order. */
    public void setStrikeArmed(RaceIdentity identity, boolean armed) {
        identity.strikeArmed = armed;
        setDirty();
    }

    /** Record the rank of the skill controlling this mob (0 = none). */
    public void setControlRank(RaceIdentity identity, byte rank) {
        identity.controlRank = rank;
        setDirty();
    }

    /** Record who this identity was permanently taken from (null clears). */
    public void setPreviousOwner(RaceIdentity identity, UUID previousOwner) {
        identity.previousOwnerUUID = previousOwner;
        setDirty();
    }

    /** Deceit — toggle the sleeper's warehouse skimming. */
    public void setStealing(RaceIdentity identity, boolean stealing) {
        identity.stealing = stealing;
        setDirty();
    }

    /** Deceit — replace the skimmed-loot list (null clears). */
    public void setStolenLoot(RaceIdentity identity, net.minecraft.nbt.ListTag loot) {
        identity.stolenLoot = loot;
        setDirty();
    }

    /**
     * Swap an identity record for a rebuilt one under the SAME identityId —
     * the only way to change the final citizenId / colonyId / ownerPlayerUUID
     * fields (ownership transfer, citizen moved between colonies). Keeps the
     * mob-UUID reverse index consistent. The caller must have copied every
     * mutable field it wants to keep onto {@code replacement} first.
     */
    public void replaceIdentity(RaceIdentity old, RaceIdentity replacement) {
        if (!old.identityId.equals(replacement.identityId)) {
            throw new IllegalArgumentException("replaceIdentity: identityId mismatch");
        }
        removeIdentity(old);
        addIdentity(replacement);
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
        identity.entitySnapshot = cleanSnapshot(snapshot);
        setDirty();
    }

    /** Persistent-data key the body-swap sink animation writes on a body
     *  while it is being lowered (see ExampleMod.markSinking). */
    public static final String SINK_RESTORE_KEY = "tm_sink_restore";

    /**
     * Strip the swap animation's TEMPORARY state out of a body snapshot.
     *
     * <p>A body is made invulnerable while it sinks into its magic circle, and
     * the send path snapshots it in exactly that state. Minecraft saves the
     * invulnerable flag with the entity, so every body later rebuilt from that
     * snapshot came back unkillable unless the rise animation happened to clear
     * it (raid defenders are rebuilt without the animation). No identity body
     * is ever meant to be invulnerable, so the flag is simply never stored.
     * Every snapshot write goes through here, and so does loading an old save,
     * which repairs snapshots that already carry the flag.</p>
     */
    static CompoundTag cleanSnapshot(CompoundTag snapshot) {
        if (snapshot == null) return null;
        snapshot.remove("Invulnerable");
        if (snapshot.contains("NeoForgeData", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            snapshot.getCompound("NeoForgeData").remove(SINK_RESTORE_KEY);
        }
        return snapshot;
    }

    /** FIX 2 — store the last-known serialized RaceTag so a body MineColonies
     *  rebuilds from CitizenData (losing the attachment) can be re-stamped. */
    public void setRaceTagSnapshot(RaceIdentity identity, CompoundTag raceTagNbt) {
        identity.raceTagSnapshot = raceTagNbt;
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

    /** Re-name a queued mob in place — naming the same mob again must not add a
     *  second pending entry (it would promote to two citizens). */
    public void renamePending(PendingRaceMob p, String newName) {
        p.name = newName;
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

    /** Lookup by (colony, citizen). Citizen IDs are only unique WITHIN a
     *  colony (every colony numbers its own citizens from 1), so this is the
     *  ONLY citizen-keyed lookup the store offers — the old single-key
     *  variant deleted a wrong colony's identity on multi-colony servers. */
    public RaceIdentity getByColonyAndCitizen(int colonyId, int citizenId) {
        for (RaceIdentity identity : byIdentityId.values()) {
            if (identity.colonyId == colonyId && identity.citizenId == citizenId) {
                return identity;
            }
        }
        return null;
    }

    public Collection<RaceIdentity> all() {
        return byIdentityId.values();
    }

    /** Every identity, grouped by {@code colonyId}, built in ONE walk.
     *
     *  <p>Why this exists: several per-second passes used to do
     *  {@code for (colony) { for (identity : all()) if (id.colonyId == colony.getID()) ... }}
     *  — a full walk of every identity in the world, once per colony, every
     *  pass, allocating a fresh list each time. That is O(colonies × identities),
     *  and BOTH of those numbers only ever grow as a save ages (births and
     *  immigration keep adding citizens), so the per-tick cost climbs forever.
     *  That is the shape behind the 2026-09-05 "gets worse the longer you play"
     *  report.
     *
     *  <p>Callers now build this map once and look their colony up by key,
     *  which is O(identities + colonies) for the whole pass.
     *
     *  <p>Semantics are deliberately IDENTICAL to the loops it replaces: the
     *  key is {@code colonyId} alone, with no dimension component, exactly as
     *  those {@code id.colonyId == colony.getID()} comparisons did. Do not add
     *  a dimension to the key without checking every caller — that would be a
     *  behaviour change, not a speed-up.
     *
     *  <p>The returned map and its lists are fresh and caller-owned, but the
     *  {@code RaceIdentity} values are the LIVE records — mutating one still
     *  mutates the stored identity (callers rely on this). Rebuild it each
     *  pass; never cache it across ticks, or you will miss new identities. */
    public java.util.Map<Integer, java.util.List<RaceIdentity>> allByColony() {
        java.util.Map<Integer, java.util.List<RaceIdentity>> byColony = new java.util.HashMap<>();
        for (RaceIdentity identity : byIdentityId.values()) {
            byColony.computeIfAbsent(identity.colonyId, k -> new java.util.ArrayList<>())
                    .add(identity);
        }
        return byColony;
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
