package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.util.RandomSource;

/**
 * Per-colony composition storage.
 *
 * One global instance per server (lives on the overworld's data storage
 * like {@link RaceIdentitySavedData}), keyed by MineColonies' colony ID.
 * Maps {@code colonyId → Set<ColonyMember>} — a colony can have multiple
 * member kinds (e.g. {@code {GOBLIN, ORC}} for a mixed race colony or
 * {@code {COLONIST, GOBLIN}} for the upcoming envoy-style diplomacy).
 *
 * Absent key (no entry) means the colony has no composition configured
 * and falls back to vanilla MineColonies citizens — legacy colonies and
 * any colony predating the race picker all just read as absent. A
 * single-element {@code {COLONIST}} set behaves identically at spawn
 * time but is an EXPLICIT "default citizens" choice the player made via
 * the picker, distinguishing it from legacy "no entry" for future
 * envoy-system semantics.
 *
 * NBT format (current):
 * <pre>
 * entries: [
 *   { colonyId: int, members: byte[] /&#42; ColonyMember ids &#42;/ }
 * ]
 * </pre>
 *
 * Legacy single-race format (pre-multi-race), still loaded:
 * <pre>
 * entries: [
 *   { colonyId: int, race: byte /&#42; Race id &#42;/ }
 * ]
 * </pre>
 * On load, a legacy {@code race} byte is wrapped into a one-element
 * {@code Set<ColonyMember>} via {@link ColonyMember#fromRace}.
 *
 * Saved to: {@code world/data/tensura_minecolonies_colony_race_config.dat}
 * (data-key unchanged for save-file continuity).
 */
public class ColonyRaceConfigSavedData extends SavedData {

    public static final String DATA_KEY = "tensura_minecolonies_colony_race_config";

    private final Map<Integer, EnumSet<ColonyMember>> membersByColony = new HashMap<>();

    /**
     * Colonies awaiting the player's first race-pick choice. Unchanged by
     * the multi-race migration — pending is purely about the initial-menu
     * UX. Lifecycle:
     *
     * - {@code onColonyCreated} → add (and send the picker open packet).
     * - Player picks → remove (and write the chosen members into
     *   {@link #membersByColony}).
     */
    private final Set<Integer> pendingChoice = new HashSet<>();

    /**
     * Per-colony "this envoy already came, no need to send another" locks.
     * When an envoy of race X is accepted (or — in Stage 3 — accepted /
     * lethally rejected), X is added here so the scheduler never proposes
     * another X-envoy for this colony. Survives independently of
     * {@link #membersByColony}: removing X from the spawn set later does
     * NOT re-enable the envoy.
     */
    private final Map<Integer, EnumSet<ColonyMember>> acceptedEnvoys = new HashMap<>();

    // -----------------------------------------------------------------
    // Stage 3a — envoy scheduling state
    // -----------------------------------------------------------------

    /** Game-tick at which we first saw this colony (set on ColonyCreatedModEvent
     *  or on first scheduler observation). Powers the "colony age >= N days"
     *  unlock condition for the COLONIST envoy. */
    private final Map<Integer, Long> colonyCreationTick = new HashMap<>();

    /** Game-tick at which the last envoy at this colony resolved (accept,
     *  decline, or removal). Used by the scheduler to enforce a 3-day gap
     *  between envoy spawns. Absent = no envoy has resolved yet. */
    private final Map<Integer, Long> lastEnvoyResolveTick = new HashMap<>();

    /** UUID of the currently-spawned envoy at this colony, if any. At most
     *  one envoy alive per colony at a time; the scheduler skips spawning
     *  while this is set. Cleared on resolve. */
    private final Map<Integer, UUID> activeEnvoyUuid = new HashMap<>();

    /** Per-player set of distinct non-COLONIST races whose envoys this player
     *  has ever seen spawn (regardless of accept/decline outcome). Drives the
     *  {@code tensuraMaxNonColonistEnvoys} gamerule cap — once the player
     *  has seen the cap-many distinct non-colonist races, the scheduler
     *  blocks further non-colonist envoys for that player. */
    private final Map<UUID, EnumSet<ColonyMember>> playerNonColonistEnvoysSeen = new HashMap<>();

    // -----------------------------------------------------------------
    // Stage 3b — kill-gate condition resets
    // -----------------------------------------------------------------
    //
    // When the player kills a race they haven't accepted, that race's
    // unlock condition is reset for that colony. The three condition
    // shapes need different reset semantics:
    //
    //   - TIMER (COLONIST: 3 in-game days since some anchor):
    //     Store the kill tick; eligibility re-anchors from there.
    //   - CUMULATIVE COUNT (GOBLIN: 3 named goblins total):
    //     Snapshot the current count; eligibility requires
    //     (currentCount - snapshot) >= 3, i.e. 3 named AFTER the kill.
    //   - CURRENT VALUE (ORC: ≥25 citizens right now):
    //     Snapshot the current count; eligibility requires the colony
    //     to GROW past that snapshot AND still meet the threshold.
    //     This "crosses the threshold" interpretation handles both
    //     "still at 25" and "way above 25" cases cleanly — the colony
    //     must grow from the kill-time level.
    //
    // All three reset states are persistent and per-colony.

    /** Tick at which COLONIST's 3-day timer was last reset by a kill.
     *  Eligibility uses {@code max(colonyCreationTick, this)} as the anchor. */
    private final Map<Integer, Long> colonistKillResetTick = new HashMap<>();

    /** Named-goblin count at the moment a goblin kill reset GOBLIN eligibility.
     *  Need 3 more named goblins above this baseline before another envoy. */
    private final Map<Integer, Integer> goblinNamedBaseline = new HashMap<>();

    /** Citizen count at the moment an orc kill reset ORC eligibility.
     *  Need {@code currentCount > snapshot} (colony must grow) AND the
     *  standard {@code >= 25} threshold. */
    private final Map<Integer, Integer> orcCitizenSnapshot = new HashMap<>();

    /** Citizen count at the moment a lizardman kill reset LIZARDMAN eligibility.
     *  Same shape as the orc snapshot — current-value condition with
     *  grow-past-snapshot reset semantics. */
    private final Map<Integer, Integer> lizardmanCitizenSnapshot = new HashMap<>();

    /** Citizen count at the moment a dwarf kill reset DWARF eligibility.
     *  Placeholder branch only — the placeholder condition (≥30 citizens +
     *  Miner's Hut) keeps the same current-value snapshot shape it always
     *  had. The new "real" dwarf conditions (20-days-no-death, dwarven
     *  village, demon-lord, hero) have their own kill-gate semantics
     *  tracked in the per-colony / per-player fields below. */
    private final Map<Integer, Integer> dwarfCitizenSnapshot = new HashMap<>();

    // -----------------------------------------------------------------
    // Deferred-content envoy conditions — per-colony + per-player state
    // -----------------------------------------------------------------
    //
    // The dwarf envoy got three new alternative conditions (any one
    // qualifies): 20-days-no-death (per-colony), dwarven village found
    // (per-player), true demon lord (live IExistence read, gated by a
    // per-player disable flag), true hero (same shape as demon lord).
    //
    // Orc / lizardman gained one alternative each: orc disaster killed
    // (per-player, immune to all resets) and ifrit defeated (per-player,
    // cleared on any lizardman kill — Ifrit is repeatable).
    //
    // Per-colony storage lives in this map; per-player storage lives in
    // the Set<UUID> fields below. All are NBT-persistent.

    /** Anchor tick for the dwarf "20 in-game days, no owner death" streak.
     *  Eligibility query: {@code (now − tick) / 24000L ≥ 20}. Re-based to
     *  {@code now} (streak restarts) by:
     *    - the owner's {@code LivingDeathEvent} (they died), and
     *    - the owner's login AND logout (so the streak counts only CONTINUOUS
     *      ONLINE presence — offline time never accrues even if the colony
     *      chunk stays loaded and the server keeps ticking).
     *  The dwarf kill-gate applies a penalty by advancing this tick forward
     *  by 10 days (capped to {@code now}) — "10 days of progress lost."
     *  Absent key means "no anchor recorded yet" → eligibility uses the
     *  colony creation tick as the anchor instead. */
    private final Map<Integer, Long> lastOwnerDeathTick = new HashMap<>();

    /** Per-player flag: this player has been physically inside the
     *  bounding box of a {@code tensura:dwarf_village} jigsaw structure
     *  at least once. Set by the per-tick structure poll on
     *  {@code ServerPlayer}s. Cleared by the dwarf kill-gate. Survives
     *  log-out / log-in. */
    private final Set<UUID> dwarvenVillageEntered = new HashSet<>();

    /** Per-player flag: this player has landed the killing blow on a
     *  {@code tensura:orc_disaster} ({@code OrcDisasterEntity}). ONE-TIME
     *  ACHIEVEMENT: once set, NOTHING (kill-gate, scroll, character
     *  reset, admin command) flips it back. This is the only envoy
     *  condition with no removal path. */
    private final Set<UUID> orcDisasterDefeated = new HashSet<>();

    /** Per-player flag: this player has landed the killing blow on an
     *  {@code IfritEntity}. Cleared by the lizardman kill-gate
     *  (Ifrit is repeatable; the player must defeat another). */
    private final Set<UUID> ifritDefeated = new HashSet<>();

    /** Per-player flag: this player has killed a dwarf while being a
     *  true demon lord. While set, even {@code isTrueDemonLord() == true}
     *  does NOT qualify for the dwarf envoy. Cleared by character reset
     *  ({@code ResetScrollItem RESET_ALL}) and by the
     *  status-currently-false fallback in the scheduler. Note: this flag
     *  is set ONLY when the player IS currently a demon lord at the
     *  moment of the kill — non-demon-lord dwarf kills don't poison the
     *  path. Same shape for hero. */
    private final Set<UUID> demonLordPathDisabled = new HashSet<>();

    /** Per-player flag: hero analog of {@link #demonLordPathDisabled}. */
    private final Set<UUID> heroPathDisabled = new HashSet<>();

    private ColonyRaceConfigSavedData() {}

    public static ColonyRaceConfigSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ColonyRaceConfigSavedData::new,
                                        ColonyRaceConfigSavedData::load),
                DATA_KEY
        );
    }

    // -----------------------------------------------------------------
    // Composition API
    // -----------------------------------------------------------------

    /**
     * @return the colony's member set, or an empty set if no entry exists.
     *     The returned set is a defensive copy — mutate it freely without
     *     affecting storage; persist changes via {@link #setMembers}.
     */
    public EnumSet<ColonyMember> getMembers(int colonyId) {
        EnumSet<ColonyMember> stored = membersByColony.get(colonyId);
        return stored == null ? EnumSet.noneOf(ColonyMember.class) : EnumSet.copyOf(stored);
    }

    /** True iff the colony has at least one member configured. False for
     *  legacy / no-entry colonies — the spawn hook reads this and falls
     *  through to vanilla behaviour. */
    public boolean hasAnyMember(int colonyId) {
        EnumSet<ColonyMember> stored = membersByColony.get(colonyId);
        return stored != null && !stored.isEmpty();
    }

    /**
     * Replace the colony's member set wholesale. Passing an empty set
     * deletes the entry (so {@link #hasAnyMember} returns false again,
     * matching legacy / no-entry semantics).
     */
    public void setMembers(int colonyId, Set<ColonyMember> members) {
        if (members == null || members.isEmpty()) {
            clearMembers(colonyId);
            return;
        }
        membersByColony.put(colonyId, EnumSet.copyOf(members));
        setDirty();
    }

    /** Convenience for the picker / single-race tests: replace the set with
     *  a single member. */
    public void setSingleMember(int colonyId, ColonyMember member) {
        setMembers(colonyId, EnumSet.of(member));
    }

    /** Add a member; returns true if the set actually changed. */
    public boolean addMember(int colonyId, ColonyMember member) {
        EnumSet<ColonyMember> stored = membersByColony.computeIfAbsent(
                colonyId, k -> EnumSet.noneOf(ColonyMember.class));
        boolean changed = stored.add(member);
        if (changed) setDirty();
        return changed;
    }

    /** Remove a member; returns true if the set actually changed. When
     *  removing the last member, the entry is fully deleted so the colony
     *  reverts to legacy / no-entry semantics. */
    public boolean removeMember(int colonyId, ColonyMember member) {
        EnumSet<ColonyMember> stored = membersByColony.get(colonyId);
        if (stored == null) return false;
        boolean changed = stored.remove(member);
        if (changed) {
            if (stored.isEmpty()) membersByColony.remove(colonyId);
            setDirty();
        }
        return changed;
    }

    /** Drop the entry entirely. Called from the colony-deleted cleanup
     *  hook so a future re-creation under the same id starts fresh. */
    public void clearMembers(int colonyId) {
        if (membersByColony.remove(colonyId) != null) {
            setDirty();
        }
    }

    /**
     * Random selection from the colony's member set for one population
     * spawn. Returns null if the set is empty (legacy / no entry) —
     * callers fall through to vanilla MC behaviour.
     */
    public ColonyMember pickRandomMember(int colonyId, RandomSource random) {
        EnumSet<ColonyMember> stored = membersByColony.get(colonyId);
        if (stored == null || stored.isEmpty()) return null;
        List<ColonyMember> options = new ArrayList<>(stored);
        return options.get(random.nextInt(options.size()));
    }

    public int size() {
        return membersByColony.size();
    }

    // -----------------------------------------------------------------
    // Pending-choice state — unchanged
    // -----------------------------------------------------------------

    public boolean isPending(int colonyId) {
        return pendingChoice.contains(colonyId);
    }

    public void markPending(int colonyId) {
        if (pendingChoice.add(colonyId)) {
            setDirty();
        }
    }

    public void clearPending(int colonyId) {
        if (pendingChoice.remove(colonyId)) {
            setDirty();
        }
    }

    public Set<Integer> pendingColonies() {
        return java.util.Collections.unmodifiableSet(pendingChoice);
    }

    // -----------------------------------------------------------------
    // Envoy diplomacy locks
    // -----------------------------------------------------------------

    public boolean hasAcceptedEnvoy(int colonyId, ColonyMember member) {
        EnumSet<ColonyMember> set = acceptedEnvoys.get(colonyId);
        return set != null && set.contains(member);
    }

    public boolean markEnvoyAccepted(int colonyId, ColonyMember member) {
        EnumSet<ColonyMember> set = acceptedEnvoys.computeIfAbsent(
                colonyId, k -> EnumSet.noneOf(ColonyMember.class));
        boolean changed = set.add(member);
        if (changed) setDirty();
        return changed;
    }

    public EnumSet<ColonyMember> acceptedEnvoys(int colonyId) {
        EnumSet<ColonyMember> set = acceptedEnvoys.get(colonyId);
        return set == null ? EnumSet.noneOf(ColonyMember.class) : EnumSet.copyOf(set);
    }

    // -----------------------------------------------------------------
    // Stage 3a — envoy scheduling
    // -----------------------------------------------------------------

    /** Record the colony's first-observation tick if not already set. */
    public void recordColonyCreation(int colonyId, long tick) {
        if (!colonyCreationTick.containsKey(colonyId)) {
            colonyCreationTick.put(colonyId, tick);
            setDirty();
        }
    }

    public long getColonyCreationTick(int colonyId, long fallback) {
        return colonyCreationTick.getOrDefault(colonyId, fallback);
    }

    public long getLastEnvoyResolveTick(int colonyId, long fallback) {
        return lastEnvoyResolveTick.getOrDefault(colonyId, fallback);
    }

    public void setLastEnvoyResolveTick(int colonyId, long tick) {
        lastEnvoyResolveTick.put(colonyId, tick);
        setDirty();
    }

    public UUID getActiveEnvoyUuid(int colonyId) {
        return activeEnvoyUuid.get(colonyId);
    }

    public void setActiveEnvoyUuid(int colonyId, UUID envoyUuid) {
        if (envoyUuid == null) activeEnvoyUuid.remove(colonyId);
        else activeEnvoyUuid.put(colonyId, envoyUuid);
        setDirty();
    }

    public EnumSet<ColonyMember> playerNonColonistEnvoysSeen(UUID playerUuid) {
        EnumSet<ColonyMember> set = playerNonColonistEnvoysSeen.get(playerUuid);
        return set == null ? EnumSet.noneOf(ColonyMember.class) : EnumSet.copyOf(set);
    }

    /** Record that this player has seen an envoy of {@code member}. No-op
     *  for COLONIST (the cap targets non-colonist races only). */
    public void recordPlayerEnvoySeen(UUID playerUuid, ColonyMember member) {
        if (member == ColonyMember.COLONIST) return;
        EnumSet<ColonyMember> set = playerNonColonistEnvoysSeen.computeIfAbsent(
                playerUuid, k -> EnumSet.noneOf(ColonyMember.class));
        if (set.add(member)) setDirty();
    }

    // -----------------------------------------------------------------
    // Stage 3b — kill-gate reset API
    // -----------------------------------------------------------------

    public long getColonistKillResetTick(int colonyId, long fallback) {
        return colonistKillResetTick.getOrDefault(colonyId, fallback);
    }

    public void recordColonistKillReset(int colonyId, long tick) {
        colonistKillResetTick.put(colonyId, tick);
        setDirty();
    }

    public int getGoblinNamedBaseline(int colonyId) {
        return goblinNamedBaseline.getOrDefault(colonyId, 0);
    }

    public void recordGoblinNamedBaseline(int colonyId, int snapshot) {
        goblinNamedBaseline.put(colonyId, snapshot);
        setDirty();
    }

    /** {@code -1} sentinel = no snapshot pending. */
    public int getOrcCitizenSnapshot(int colonyId) {
        return orcCitizenSnapshot.getOrDefault(colonyId, -1);
    }

    public void recordOrcCitizenSnapshot(int colonyId, int snapshot) {
        orcCitizenSnapshot.put(colonyId, snapshot);
        setDirty();
    }

    public void clearOrcCitizenSnapshot(int colonyId) {
        if (orcCitizenSnapshot.remove(colonyId) != null) setDirty();
    }

    public int getLizardmanCitizenSnapshot(int colonyId) {
        return lizardmanCitizenSnapshot.getOrDefault(colonyId, -1);
    }

    public void recordLizardmanCitizenSnapshot(int colonyId, int snapshot) {
        lizardmanCitizenSnapshot.put(colonyId, snapshot);
        setDirty();
    }

    public void clearLizardmanCitizenSnapshot(int colonyId) {
        if (lizardmanCitizenSnapshot.remove(colonyId) != null) setDirty();
    }

    public int getDwarfCitizenSnapshot(int colonyId) {
        return dwarfCitizenSnapshot.getOrDefault(colonyId, -1);
    }

    public void recordDwarfCitizenSnapshot(int colonyId, int snapshot) {
        dwarfCitizenSnapshot.put(colonyId, snapshot);
        setDirty();
    }

    public void clearDwarfCitizenSnapshot(int colonyId) {
        if (dwarfCitizenSnapshot.remove(colonyId) != null) setDirty();
    }

    // -----------------------------------------------------------------
    // Deferred-content condition API
    // -----------------------------------------------------------------

    /** Returns the last-owner-death tick, or {@code fallback} when no
     *  death is recorded yet. Eligibility callers typically pass the
     *  colony creation tick as the fallback so a colony with no recorded
     *  death qualifies for the 20-day check as soon as it's 20 days old. */
    public long getLastOwnerDeathTick(int colonyId, long fallback) {
        return lastOwnerDeathTick.getOrDefault(colonyId, fallback);
    }

    public boolean hasOwnerDeathRecord(int colonyId) {
        return lastOwnerDeathTick.containsKey(colonyId);
    }

    public void setLastOwnerDeathTick(int colonyId, long tick) {
        lastOwnerDeathTick.put(colonyId, tick);
        setDirty();
    }

    public boolean hasEnteredDwarvenVillage(UUID playerUuid) {
        return dwarvenVillageEntered.contains(playerUuid);
    }

    /** @return true if the flag actually changed. */
    public boolean markDwarvenVillageEntered(UUID playerUuid) {
        boolean changed = dwarvenVillageEntered.add(playerUuid);
        if (changed) setDirty();
        return changed;
    }

    public void clearDwarvenVillageEntered(UUID playerUuid) {
        if (dwarvenVillageEntered.remove(playerUuid)) setDirty();
    }

    public boolean hasDefeatedOrcDisaster(UUID playerUuid) {
        return orcDisasterDefeated.contains(playerUuid);
    }

    /** Set the permanent orc-disaster flag. Truly permanent — there's no
     *  matching {@code clearOrcDisasterDefeated}. */
    public boolean markOrcDisasterDefeated(UUID playerUuid) {
        boolean changed = orcDisasterDefeated.add(playerUuid);
        if (changed) setDirty();
        return changed;
    }

    public boolean hasDefeatedIfrit(UUID playerUuid) {
        return ifritDefeated.contains(playerUuid);
    }

    public boolean markIfritDefeated(UUID playerUuid) {
        boolean changed = ifritDefeated.add(playerUuid);
        if (changed) setDirty();
        return changed;
    }

    public void clearIfritDefeated(UUID playerUuid) {
        if (ifritDefeated.remove(playerUuid)) setDirty();
    }

    public boolean isDemonLordPathDisabled(UUID playerUuid) {
        return demonLordPathDisabled.contains(playerUuid);
    }

    public boolean setDemonLordPathDisabled(UUID playerUuid) {
        boolean changed = demonLordPathDisabled.add(playerUuid);
        if (changed) setDirty();
        return changed;
    }

    public void clearDemonLordPathDisabled(UUID playerUuid) {
        if (demonLordPathDisabled.remove(playerUuid)) setDirty();
    }

    public boolean isHeroPathDisabled(UUID playerUuid) {
        return heroPathDisabled.contains(playerUuid);
    }

    public boolean setHeroPathDisabled(UUID playerUuid) {
        boolean changed = heroPathDisabled.add(playerUuid);
        if (changed) setDirty();
        return changed;
    }

    public void clearHeroPathDisabled(UUID playerUuid) {
        if (heroPathDisabled.remove(playerUuid)) setDirty();
    }

    /** Iterate every known colonyId (member entry, accepted-envoy entry, or
     *  scheduling-state entry). Used by the scheduler to find candidate
     *  colonies to consider. */
    public Set<Integer> allKnownColonyIds() {
        Set<Integer> out = new HashSet<>();
        out.addAll(membersByColony.keySet());
        out.addAll(acceptedEnvoys.keySet());
        out.addAll(colonyCreationTick.keySet());
        out.addAll(lastEnvoyResolveTick.keySet());
        out.addAll(activeEnvoyUuid.keySet());
        return out;
    }

    // -----------------------------------------------------------------
    // Save / load
    // -----------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, EnumSet<ColonyMember>> e : membersByColony.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            EnumSet<ColonyMember> members = e.getValue();
            byte[] ids = new byte[members.size()];
            int i = 0;
            for (ColonyMember m : members) {
                ids[i++] = (byte) m.getId();
            }
            entry.putByteArray("members", ids);
            list.add(entry);
        }
        tag.put("entries", list);

        ListTag pendingList = new ListTag();
        for (Integer id : pendingChoice) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", id);
            pendingList.add(entry);
        }
        tag.put("pending", pendingList);

        ListTag acceptedList = new ListTag();
        for (Map.Entry<Integer, EnumSet<ColonyMember>> e : acceptedEnvoys.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            byte[] ids = new byte[e.getValue().size()];
            int i = 0;
            for (ColonyMember m : e.getValue()) ids[i++] = (byte) m.getId();
            entry.putByteArray("members", ids);
            acceptedList.add(entry);
        }
        tag.put("acceptedEnvoys", acceptedList);

        // Stage 3a scheduling state
        ListTag colonyTimers = new ListTag();
        for (Map.Entry<Integer, Long> e : colonyCreationTick.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            entry.putLong("createdTick", e.getValue());
            Long lastResolve = lastEnvoyResolveTick.get(e.getKey());
            if (lastResolve != null) entry.putLong("lastResolveTick", lastResolve);
            UUID activeUuid = activeEnvoyUuid.get(e.getKey());
            if (activeUuid != null) entry.putUUID("activeEnvoyUuid", activeUuid);
            colonyTimers.add(entry);
        }
        // Colonies that have only lastResolve / activeUuid without a creation tick
        // (shouldn't normally happen but be defensive) — emit them too.
        for (Integer cid : lastEnvoyResolveTick.keySet()) {
            if (colonyCreationTick.containsKey(cid)) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", cid);
            entry.putLong("lastResolveTick", lastEnvoyResolveTick.get(cid));
            UUID activeUuid = activeEnvoyUuid.get(cid);
            if (activeUuid != null) entry.putUUID("activeEnvoyUuid", activeUuid);
            colonyTimers.add(entry);
        }
        tag.put("envoyColonyState", colonyTimers);

        ListTag playerSeen = new ListTag();
        for (Map.Entry<UUID, EnumSet<ColonyMember>> e : playerNonColonistEnvoysSeen.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("playerUuid", e.getKey());
            byte[] ids = new byte[e.getValue().size()];
            int i = 0;
            for (ColonyMember m : e.getValue()) ids[i++] = (byte) m.getId();
            entry.putByteArray("members", ids);
            playerSeen.add(entry);
        }
        tag.put("playerEnvoysSeen", playerSeen);

        // Stage 3b — kill-gate reset state. Union of all per-colony keys so
        // a colony with only one of the reset entries still writes.
        Set<Integer> killResetColonies = new HashSet<>();
        killResetColonies.addAll(colonistKillResetTick.keySet());
        killResetColonies.addAll(goblinNamedBaseline.keySet());
        killResetColonies.addAll(orcCitizenSnapshot.keySet());
        killResetColonies.addAll(lizardmanCitizenSnapshot.keySet());
        killResetColonies.addAll(dwarfCitizenSnapshot.keySet());
        ListTag killResetList = new ListTag();
        for (Integer cid : killResetColonies) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", cid);
            if (colonistKillResetTick.containsKey(cid)) {
                entry.putLong("colonistResetTick", colonistKillResetTick.get(cid));
            }
            if (goblinNamedBaseline.containsKey(cid)) {
                entry.putInt("goblinBaseline", goblinNamedBaseline.get(cid));
            }
            if (orcCitizenSnapshot.containsKey(cid)) {
                entry.putInt("orcSnapshot", orcCitizenSnapshot.get(cid));
            }
            if (lizardmanCitizenSnapshot.containsKey(cid)) {
                entry.putInt("lizardmanSnapshot", lizardmanCitizenSnapshot.get(cid));
            }
            if (dwarfCitizenSnapshot.containsKey(cid)) {
                entry.putInt("dwarfSnapshot", dwarfCitizenSnapshot.get(cid));
            }
            killResetList.add(entry);
        }
        tag.put("envoyKillResets", killResetList);

        // Deferred-content envoy conditions — per-colony death tick + four
        // per-player flag sets. Independent keys so a partial save (e.g.
        // only the death-tick map populated) round-trips cleanly.
        ListTag deathTicks = new ListTag();
        for (Map.Entry<Integer, Long> e : lastOwnerDeathTick.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("colonyId", e.getKey());
            entry.putLong("tick", e.getValue());
            deathTicks.add(entry);
        }
        tag.put("ownerDeathTicks", deathTicks);

        tag.put("dwarvenVillageEntered", encodeUuidSet(dwarvenVillageEntered));
        tag.put("orcDisasterDefeated", encodeUuidSet(orcDisasterDefeated));
        tag.put("ifritDefeated", encodeUuidSet(ifritDefeated));
        tag.put("demonLordPathDisabled", encodeUuidSet(demonLordPathDisabled));
        tag.put("heroPathDisabled", encodeUuidSet(heroPathDisabled));

        return tag;
    }

    /** Per-player flag set serialisation: list of {@code uuid} compounds.
     *  Cheap, readable, and round-trips cleanly through {@link #decodeUuidSet}. */
    private static ListTag encodeUuidSet(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID id : set) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", id);
            list.add(entry);
        }
        return list;
    }

    private static void decodeUuidSet(CompoundTag tag, String key, Set<UUID> into) {
        if (!tag.contains(key, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("uuid")) into.add(entry.getUUID("uuid"));
        }
    }

    public static ColonyRaceConfigSavedData load(CompoundTag tag,
                                                 HolderLookup.Provider registries) {
        ColonyRaceConfigSavedData data = new ColonyRaceConfigSavedData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int colonyId = entry.getInt("colonyId");
            EnumSet<ColonyMember> members = EnumSet.noneOf(ColonyMember.class);
            if (entry.contains("members", Tag.TAG_BYTE_ARRAY)) {
                // Current format — multi-member byte array.
                for (byte b : entry.getByteArray("members")) {
                    members.add(ColonyMember.byId(b & 0xFF));
                }
            } else if (entry.contains("race", Tag.TAG_BYTE)) {
                // Legacy single-race format — wrap the one race in a set.
                Race race = Race.byId(entry.getByte("race") & 0xFF);
                members.add(ColonyMember.fromRace(race));
            }
            if (!members.isEmpty()) {
                data.membersByColony.put(colonyId, members);
            }
        }

        if (tag.contains("pending", Tag.TAG_LIST)) {
            ListTag pendingList = tag.getList("pending", Tag.TAG_COMPOUND);
            for (int i = 0; i < pendingList.size(); i++) {
                data.pendingChoice.add(pendingList.getCompound(i).getInt("colonyId"));
            }
        }

        if (tag.contains("acceptedEnvoys", Tag.TAG_LIST)) {
            ListTag acceptedList = tag.getList("acceptedEnvoys", Tag.TAG_COMPOUND);
            for (int i = 0; i < acceptedList.size(); i++) {
                CompoundTag entry = acceptedList.getCompound(i);
                int colonyId = entry.getInt("colonyId");
                EnumSet<ColonyMember> members = EnumSet.noneOf(ColonyMember.class);
                for (byte b : entry.getByteArray("members")) {
                    members.add(ColonyMember.byId(b & 0xFF));
                }
                if (!members.isEmpty()) {
                    data.acceptedEnvoys.put(colonyId, members);
                }
            }
        }

        if (tag.contains("envoyColonyState", Tag.TAG_LIST)) {
            ListTag colonyTimers = tag.getList("envoyColonyState", Tag.TAG_COMPOUND);
            for (int i = 0; i < colonyTimers.size(); i++) {
                CompoundTag entry = colonyTimers.getCompound(i);
                int colonyId = entry.getInt("colonyId");
                if (entry.contains("createdTick", Tag.TAG_LONG)) {
                    data.colonyCreationTick.put(colonyId, entry.getLong("createdTick"));
                }
                if (entry.contains("lastResolveTick", Tag.TAG_LONG)) {
                    data.lastEnvoyResolveTick.put(colonyId, entry.getLong("lastResolveTick"));
                }
                if (entry.hasUUID("activeEnvoyUuid")) {
                    data.activeEnvoyUuid.put(colonyId, entry.getUUID("activeEnvoyUuid"));
                }
            }
        }

        if (tag.contains("playerEnvoysSeen", Tag.TAG_LIST)) {
            ListTag playerSeen = tag.getList("playerEnvoysSeen", Tag.TAG_COMPOUND);
            for (int i = 0; i < playerSeen.size(); i++) {
                CompoundTag entry = playerSeen.getCompound(i);
                if (!entry.hasUUID("playerUuid")) continue;
                UUID playerUuid = entry.getUUID("playerUuid");
                EnumSet<ColonyMember> members = EnumSet.noneOf(ColonyMember.class);
                for (byte b : entry.getByteArray("members")) {
                    members.add(ColonyMember.byId(b & 0xFF));
                }
                if (!members.isEmpty()) {
                    data.playerNonColonistEnvoysSeen.put(playerUuid, members);
                }
            }
        }

        if (tag.contains("envoyKillResets", Tag.TAG_LIST)) {
            ListTag killResetList = tag.getList("envoyKillResets", Tag.TAG_COMPOUND);
            for (int i = 0; i < killResetList.size(); i++) {
                CompoundTag entry = killResetList.getCompound(i);
                int colonyId = entry.getInt("colonyId");
                if (entry.contains("colonistResetTick", Tag.TAG_LONG)) {
                    data.colonistKillResetTick.put(colonyId, entry.getLong("colonistResetTick"));
                }
                if (entry.contains("goblinBaseline", Tag.TAG_INT)) {
                    data.goblinNamedBaseline.put(colonyId, entry.getInt("goblinBaseline"));
                }
                if (entry.contains("orcSnapshot", Tag.TAG_INT)) {
                    data.orcCitizenSnapshot.put(colonyId, entry.getInt("orcSnapshot"));
                }
                if (entry.contains("lizardmanSnapshot", Tag.TAG_INT)) {
                    data.lizardmanCitizenSnapshot.put(colonyId, entry.getInt("lizardmanSnapshot"));
                }
                if (entry.contains("dwarfSnapshot", Tag.TAG_INT)) {
                    data.dwarfCitizenSnapshot.put(colonyId, entry.getInt("dwarfSnapshot"));
                }
            }
        }

        // Deferred-content envoy conditions. All four flag sets and the
        // death-tick map are optional — missing keys load as empty (legacy
        // saves predating this stage all read as "unset / not-disabled /
        // not-defeated"), matching the spec's backward-compat requirement.
        if (tag.contains("ownerDeathTicks", Tag.TAG_LIST)) {
            ListTag deathTicks = tag.getList("ownerDeathTicks", Tag.TAG_COMPOUND);
            for (int i = 0; i < deathTicks.size(); i++) {
                CompoundTag entry = deathTicks.getCompound(i);
                data.lastOwnerDeathTick.put(
                        entry.getInt("colonyId"),
                        entry.getLong("tick"));
            }
        }
        decodeUuidSet(tag, "dwarvenVillageEntered", data.dwarvenVillageEntered);
        decodeUuidSet(tag, "orcDisasterDefeated", data.orcDisasterDefeated);
        decodeUuidSet(tag, "ifritDefeated", data.ifritDefeated);
        decodeUuidSet(tag, "demonLordPathDisabled", data.demonLordPathDisabled);
        decodeUuidSet(tag, "heroPathDisabled", data.heroPathDisabled);

        return data;
    }
}
