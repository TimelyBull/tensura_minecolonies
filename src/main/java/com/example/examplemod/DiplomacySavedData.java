package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Diplomacy Stage-1 storage (docs/diplomacy.md) — relations state,
 * active/offered deals, envoy entry bookkeeping. Kept SEPARATE from
 * {@link WorldReputationSavedData} on purpose: the Layer-1 file stays
 * pure standing/offense/flags; diplomacy's churnier deal state lives
 * here.
 *
 * <p><b>ACCESS RULE: nothing outside {@link DiplomacyManager} may touch
 * this class</b> — the established sole-door discipline.
 *
 * <p>All faction keys are STRING ids (the addon door). Saved to
 * {@code world/data/tensura_minecolonies_diplomacy.dat}.
 */
class DiplomacySavedData extends SavedData {

    static final String DATA_KEY = "tensura_minecolonies_diplomacy";

    /** One offered (not yet accepted) deal + its offer expiry. */
    record Offer(String dealId, long expiresTick) {}

    /** player → (faction id → relations state id). Absent = NONE. */
    private final Map<UUID, Map<String, Byte>> states = new HashMap<>();
    /** player → (faction id → the ONE active deal). */
    private final Map<UUID, Map<String, ActiveDeal>> deals = new HashMap<>();
    /** player → (faction id → current offers). */
    private final Map<UUID, Map<String, List<Offer>>> offers = new HashMap<>();
    /** player → (faction id → game tick the envoy reply arrives). */
    private final Map<UUID, Map<String, Long>> pendingReplies = new HashMap<>();
    /** player → (faction id → last outbound-envoy send tick) — resend throttle. */
    private final Map<UUID, Map<String, Long>> lastSend = new HashMap<>();
    /** player → (faction id → last deal-activity tick) — the decay idle check. */
    private final Map<UUID, Map<String, Long>> lastActivity = new HashMap<>();
    /** player → last inbound faction-envoy spawn tick (one at a time + cooldown). */
    private final Map<UUID, Long> lastInbound = new HashMap<>();
    /** player → (faction id → offer deal ids the player has SEEN) — the
     *  "!" new-offer badge clears when the faction tab is clicked. */
    private final Map<UUID, Map<String, Set<String>>> seenOffers = new HashMap<>();
    /** Stage 3 — player → (faction id → last caravan-claim tick). */
    private final Map<UUID, Map<String, Long>> lastCaravan = new HashMap<>();
    /** Stage 3 — player → last caravan-home travel tick. */
    private final Map<UUID, Long> lastTravel = new HashMap<>();
    /** Stage 3 — player → claimed once-ever standing-gift ids. */
    private final Map<UUID, Set<String>> claimedGifts = new HashMap<>();
    /** Stage 3 — player → last known race side (0 human / 1 majin) for
     *  the majin-downgrade watch. Absent = not yet observed. */
    private final Map<UUID, Byte> lastSide = new HashMap<>();
    /** Covenant — player → last Drago Nova claim (REAL-LIFE millis, not
     *  game time: the brief specifies one per real-world hour). */
    private final Map<UUID, Long> dragoNovaClaimMillis = new HashMap<>();
    /** Catalog — player → deal ids whose capstone SKILL reward is owed
     *  (granted on next login if the deal completed offline). */
    private final Map<UUID, Set<String>> pendingSkillDeals = new HashMap<>();
    /** Envoy dispatch (model A) — subordinate identityId → the faction id
     *  it is AWAY serving as an envoy to. While present, the subordinate
     *  is unavailable (its body is despawned); the entry clears when the
     *  envoy mission resolves. Server-global (not per-player). */
    private final Map<UUID, String> envoyAway = new HashMap<>();
    /** Envoy dispatch — identityIds whose mission RESOLVED while the owner
     *  was offline; the subordinate's body re-materializes on next login. */
    private final Set<UUID> envoyReturnPending = new HashSet<>();
    /** Daily-pass rollover anchor (overworld day number). */
    private long lastProcessedDay = -1;

    private DiplomacySavedData() {}

    static DiplomacySavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DiplomacySavedData::new, DiplomacySavedData::load),
                DATA_KEY
        );
    }

    // ------------------------------------------------------------------
    // Accessors (manager-only)
    // ------------------------------------------------------------------

    byte getState(UUID player, String factionId) {
        Map<String, Byte> byFaction = states.get(player);
        Byte state = byFaction == null ? null : byFaction.get(factionId);
        return state == null ? 0 : state;
    }

    void setState(UUID player, String factionId, byte state) {
        if (state == 0) {
            Map<String, Byte> byFaction = states.get(player);
            if (byFaction != null) byFaction.remove(factionId);
        } else {
            states.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, state);
        }
        setDirty();
    }

    ActiveDeal getDeal(UUID player, String factionId) {
        Map<String, ActiveDeal> byFaction = deals.get(player);
        return byFaction == null ? null : byFaction.get(factionId);
    }

    void setDeal(UUID player, String factionId, ActiveDeal deal) {
        deals.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, deal);
        setDirty();
    }

    void removeDeal(UUID player, String factionId) {
        Map<String, ActiveDeal> byFaction = deals.get(player);
        if (byFaction != null && byFaction.remove(factionId) != null) setDirty();
    }

    /** Snapshot of every (player, faction) pair holding an active deal. */
    Map<UUID, Map<String, ActiveDeal>> allDeals() {
        return deals;
    }

    /** Every (player, faction) pair with non-NONE relations. */
    Map<UUID, Map<String, Byte>> allStates() {
        return states;
    }

    List<Offer> getOffers(UUID player, String factionId) {
        Map<String, List<Offer>> byFaction = offers.get(player);
        List<Offer> list = byFaction == null ? null : byFaction.get(factionId);
        return list == null ? new ArrayList<>() : list;
    }

    void setOffers(UUID player, String factionId, List<Offer> list) {
        offers.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, list);
        setDirty();
    }

    Long getPendingReply(UUID player, String factionId) {
        Map<String, Long> byFaction = pendingReplies.get(player);
        return byFaction == null ? null : byFaction.get(factionId);
    }

    void setPendingReply(UUID player, String factionId, long replyTick) {
        pendingReplies.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, replyTick);
        setDirty();
    }

    void clearPendingReply(UUID player, String factionId) {
        Map<String, Long> byFaction = pendingReplies.get(player);
        if (byFaction != null && byFaction.remove(factionId) != null) setDirty();
    }

    Map<UUID, Map<String, Long>> allPendingReplies() {
        return pendingReplies;
    }

    /** Absent reads return MIN_VALUE/2 (not MIN_VALUE) so callers'
     *  {@code now - last} age math can't overflow negative — the
     *  RaidSavedData idiom. */
    long getLastSend(UUID player, String factionId) {
        Map<String, Long> byFaction = lastSend.get(player);
        Long tick = byFaction == null ? null : byFaction.get(factionId);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastSend(UUID player, String factionId, long tick) {
        lastSend.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, tick);
        setDirty();
    }

    long getLastActivity(UUID player, String factionId) {
        Map<String, Long> byFaction = lastActivity.get(player);
        Long tick = byFaction == null ? null : byFaction.get(factionId);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastActivity(UUID player, String factionId, long tick) {
        lastActivity.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, tick);
        setDirty();
    }

    Set<String> getSeenOffers(UUID player, String factionId) {
        Map<String, Set<String>> byFaction = seenOffers.get(player);
        Set<String> seen = byFaction == null ? null : byFaction.get(factionId);
        return seen == null ? new HashSet<>() : seen;
    }

    void markOffersSeen(UUID player, String factionId, java.util.Collection<String> dealIds) {
        if (dealIds.isEmpty()) return;
        seenOffers.computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(factionId, k -> new HashSet<>()).addAll(dealIds);
        setDirty();
    }

    long getLastCaravan(UUID player, String factionId) {
        Map<String, Long> byFaction = lastCaravan.get(player);
        Long tick = byFaction == null ? null : byFaction.get(factionId);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastCaravan(UUID player, String factionId, long tick) {
        lastCaravan.computeIfAbsent(player, k -> new HashMap<>()).put(factionId, tick);
        setDirty();
    }

    long getLastTravel(UUID player) {
        Long tick = lastTravel.get(player);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastTravel(UUID player, long tick) {
        lastTravel.put(player, tick);
        setDirty();
    }

    boolean hasClaimedGift(UUID player, String giftId) {
        Set<String> claimed = claimedGifts.get(player);
        return claimed != null && claimed.contains(giftId);
    }

    void markGiftClaimed(UUID player, String giftId) {
        if (claimedGifts.computeIfAbsent(player, k -> new HashSet<>()).add(giftId)) {
            setDirty();
        }
    }

    long getDragoNovaClaimMillis(UUID player) {
        Long ms = dragoNovaClaimMillis.get(player);
        return ms == null ? 0L : ms;
    }

    void setDragoNovaClaimMillis(UUID player, long ms) {
        dragoNovaClaimMillis.put(player, ms);
        setDirty();
    }

    Set<String> getPendingSkillDeals(UUID player) {
        Set<String> s = pendingSkillDeals.get(player);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    void addPendingSkillDeal(UUID player, String dealId) {
        if (pendingSkillDeals.computeIfAbsent(player, k -> new HashSet<>()).add(dealId)) {
            setDirty();
        }
    }

    void clearPendingSkillDeal(UUID player, String dealId) {
        Set<String> s = pendingSkillDeals.get(player);
        if (s != null && s.remove(dealId)) setDirty();
    }

    /** -1 = never observed; else 0 human / 1 majin. */
    int getLastSide(UUID player) {
        Byte side = lastSide.get(player);
        return side == null ? -1 : side;
    }

    void setLastSide(UUID player, byte side) {
        lastSide.put(player, side);
        setDirty();
    }

    long getLastInbound(UUID player) {
        Long tick = lastInbound.get(player);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastInbound(UUID player, long tick) {
        lastInbound.put(player, tick);
        setDirty();
    }

    // --- envoy dispatch (away / return) ---
    void setEnvoyAway(UUID identityId, String factionId) {
        envoyAway.put(identityId, factionId);
        setDirty();
    }

    /** The faction this identity is away serving, or null if not away. */
    String envoyAwayFaction(UUID identityId) {
        return envoyAway.get(identityId);
    }

    boolean isEnvoyAway(UUID identityId) {
        return envoyAway.containsKey(identityId);
    }

    void clearEnvoyAway(UUID identityId) {
        if (envoyAway.remove(identityId) != null) setDirty();
    }

    /** identityId → factionId for every subordinate currently out on an envoy. */
    Map<UUID, String> allEnvoyAway() {
        return envoyAway;
    }

    void markEnvoyReturnPending(UUID identityId) {
        if (envoyReturnPending.add(identityId)) setDirty();
    }

    boolean isEnvoyReturnPending(UUID identityId) {
        return envoyReturnPending.contains(identityId);
    }

    Set<UUID> allEnvoyReturnPending() {
        return envoyReturnPending;
    }

    void clearEnvoyReturnPending(UUID identityId) {
        if (envoyReturnPending.remove(identityId)) setDirty();
    }

    long lastProcessedDay() {
        return lastProcessedDay;
    }

    void setLastProcessedDay(long day) {
        this.lastProcessedDay = day;
        setDirty();
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Set<UUID> allPlayers = new HashSet<>();
        allPlayers.addAll(states.keySet());
        allPlayers.addAll(deals.keySet());
        allPlayers.addAll(offers.keySet());
        allPlayers.addAll(pendingReplies.keySet());
        allPlayers.addAll(lastSend.keySet());
        allPlayers.addAll(lastActivity.keySet());
        allPlayers.addAll(lastInbound.keySet());
        allPlayers.addAll(seenOffers.keySet());
        allPlayers.addAll(lastCaravan.keySet());
        allPlayers.addAll(lastTravel.keySet());
        allPlayers.addAll(claimedGifts.keySet());
        allPlayers.addAll(lastSide.keySet());
        allPlayers.addAll(dragoNovaClaimMillis.keySet());
        allPlayers.addAll(pendingSkillDeals.keySet());

        ListTag players = new ListTag();
        for (UUID uuid : allPlayers) {
            CompoundTag player = new CompoundTag();
            player.putUUID("uuid", uuid);

            ListTag stateEntries = new ListTag();
            for (Map.Entry<String, Byte> e : states.getOrDefault(uuid, Map.of()).entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", e.getKey());
                entry.putByte("state", e.getValue());
                stateEntries.add(entry);
            }
            player.put("states", stateEntries);

            ListTag dealEntries = new ListTag();
            for (Map.Entry<String, ActiveDeal> e : deals.getOrDefault(uuid, Map.of()).entrySet()) {
                CompoundTag entry = e.getValue().save();
                entry.putString("faction", e.getKey());
                dealEntries.add(entry);
            }
            player.put("deals", dealEntries);

            ListTag offerEntries = new ListTag();
            for (Map.Entry<String, List<Offer>> e : offers.getOrDefault(uuid, Map.of()).entrySet()) {
                for (Offer offer : e.getValue()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("faction", e.getKey());
                    entry.putString("dealId", offer.dealId());
                    entry.putLong("expires", offer.expiresTick());
                    offerEntries.add(entry);
                }
            }
            player.put("offers", offerEntries);

            player.put("pendingReplies", writeFactionLongs(pendingReplies.get(uuid)));
            player.put("lastSend", writeFactionLongs(lastSend.get(uuid)));
            player.put("lastActivity", writeFactionLongs(lastActivity.get(uuid)));
            Long inbound = lastInbound.get(uuid);
            if (inbound != null) player.putLong("lastInbound", inbound);
            ListTag seenEntries = new ListTag();
            for (Map.Entry<String, Set<String>> e : seenOffers.getOrDefault(uuid, Map.of()).entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", e.getKey());
                ListTag ids = new ListTag();
                for (String id : e.getValue()) ids.add(net.minecraft.nbt.StringTag.valueOf(id));
                entry.put("ids", ids);
                seenEntries.add(entry);
            }
            player.put("seenOffers", seenEntries);
            player.put("lastCaravan", writeFactionLongs(lastCaravan.get(uuid)));
            Long travel = lastTravel.get(uuid);
            if (travel != null) player.putLong("lastTravel", travel);
            ListTag giftEntries = new ListTag();
            for (String gift : claimedGifts.getOrDefault(uuid, Set.of())) {
                giftEntries.add(net.minecraft.nbt.StringTag.valueOf(gift));
            }
            player.put("claimedGifts", giftEntries);
            Byte side = lastSide.get(uuid);
            if (side != null) player.putByte("lastSide", side);
            Long novaMs = dragoNovaClaimMillis.get(uuid);
            if (novaMs != null) player.putLong("dragoNovaClaimMillis", novaMs);
            ListTag pendingSkills = new ListTag();
            for (String dealId : pendingSkillDeals.getOrDefault(uuid, Set.of())) {
                pendingSkills.add(net.minecraft.nbt.StringTag.valueOf(dealId));
            }
            player.put("pendingSkillDeals", pendingSkills);
            players.add(player);
        }
        tag.put("players", players);

        // Envoy dispatch — server-global away/return bookkeeping.
        ListTag awayList = new ListTag();
        for (Map.Entry<UUID, String> e : envoyAway.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putString("faction", e.getValue());
            awayList.add(entry);
        }
        tag.put("envoyAway", awayList);
        ListTag returnList = new ListTag();
        for (UUID id : envoyReturnPending) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            returnList.add(entry);
        }
        tag.put("envoyReturnPending", returnList);

        tag.putLong("lastProcessedDay", lastProcessedDay);
        return tag;
    }

    private static ListTag writeFactionLongs(Map<String, Long> byFaction) {
        ListTag entries = new ListTag();
        if (byFaction != null) {
            for (Map.Entry<String, Long> e : byFaction.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", e.getKey());
                entry.putLong("value", e.getValue());
                entries.add(entry);
            }
        }
        return entries;
    }

    private static Map<String, Long> readFactionLongs(CompoundTag player, String key) {
        Map<String, Long> out = new HashMap<>();
        ListTag entries = player.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            out.put(entry.getString("faction"), entry.getLong("value"));
        }
        return out;
    }

    static DiplomacySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        DiplomacySavedData data = new DiplomacySavedData();
        data.lastProcessedDay = tag.getLong("lastProcessedDay");
        ListTag awayList = tag.getList("envoyAway", Tag.TAG_COMPOUND);
        for (int i = 0; i < awayList.size(); i++) {
            CompoundTag entry = awayList.getCompound(i);
            if (entry.hasUUID("id")) data.envoyAway.put(entry.getUUID("id"), entry.getString("faction"));
        }
        ListTag returnList = tag.getList("envoyReturnPending", Tag.TAG_COMPOUND);
        for (int i = 0; i < returnList.size(); i++) {
            CompoundTag entry = returnList.getCompound(i);
            if (entry.hasUUID("id")) data.envoyReturnPending.add(entry.getUUID("id"));
        }
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            if (!player.hasUUID("uuid")) continue;
            UUID uuid = player.getUUID("uuid");

            ListTag stateEntries = player.getList("states", Tag.TAG_COMPOUND);
            Map<String, Byte> stateMap = new HashMap<>();
            for (int j = 0; j < stateEntries.size(); j++) {
                CompoundTag entry = stateEntries.getCompound(j);
                stateMap.put(entry.getString("faction"), entry.getByte("state"));
            }
            if (!stateMap.isEmpty()) data.states.put(uuid, stateMap);

            ListTag dealEntries = player.getList("deals", Tag.TAG_COMPOUND);
            Map<String, ActiveDeal> dealMap = new HashMap<>();
            for (int j = 0; j < dealEntries.size(); j++) {
                CompoundTag entry = dealEntries.getCompound(j);
                dealMap.put(entry.getString("faction"), ActiveDeal.load(entry));
            }
            if (!dealMap.isEmpty()) data.deals.put(uuid, dealMap);

            ListTag offerEntries = player.getList("offers", Tag.TAG_COMPOUND);
            Map<String, List<Offer>> offerMap = new HashMap<>();
            for (int j = 0; j < offerEntries.size(); j++) {
                CompoundTag entry = offerEntries.getCompound(j);
                offerMap.computeIfAbsent(entry.getString("faction"), k -> new ArrayList<>())
                        .add(new Offer(entry.getString("dealId"), entry.getLong("expires")));
            }
            if (!offerMap.isEmpty()) data.offers.put(uuid, offerMap);

            Map<String, Long> replies = readFactionLongs(player, "pendingReplies");
            if (!replies.isEmpty()) data.pendingReplies.put(uuid, replies);
            Map<String, Long> sends = readFactionLongs(player, "lastSend");
            if (!sends.isEmpty()) data.lastSend.put(uuid, sends);
            Map<String, Long> activity = readFactionLongs(player, "lastActivity");
            if (!activity.isEmpty()) data.lastActivity.put(uuid, activity);
            if (player.contains("lastInbound")) {
                data.lastInbound.put(uuid, player.getLong("lastInbound"));
            }
            ListTag seenEntries = player.getList("seenOffers", Tag.TAG_COMPOUND);
            Map<String, Set<String>> seenMap = new HashMap<>();
            for (int j = 0; j < seenEntries.size(); j++) {
                CompoundTag entry = seenEntries.getCompound(j);
                Set<String> ids = new HashSet<>();
                ListTag idList = entry.getList("ids", Tag.TAG_STRING);
                for (int k = 0; k < idList.size(); k++) ids.add(idList.getString(k));
                if (!ids.isEmpty()) seenMap.put(entry.getString("faction"), ids);
            }
            if (!seenMap.isEmpty()) data.seenOffers.put(uuid, seenMap);
            Map<String, Long> caravans = readFactionLongs(player, "lastCaravan");
            if (!caravans.isEmpty()) data.lastCaravan.put(uuid, caravans);
            if (player.contains("lastTravel")) {
                data.lastTravel.put(uuid, player.getLong("lastTravel"));
            }
            ListTag giftEntries = player.getList("claimedGifts", Tag.TAG_STRING);
            Set<String> gifts = new HashSet<>();
            for (int j = 0; j < giftEntries.size(); j++) gifts.add(giftEntries.getString(j));
            if (!gifts.isEmpty()) data.claimedGifts.put(uuid, gifts);
            if (player.contains("lastSide")) {
                data.lastSide.put(uuid, player.getByte("lastSide"));
            }
            if (player.contains("dragoNovaClaimMillis")) {
                data.dragoNovaClaimMillis.put(uuid, player.getLong("dragoNovaClaimMillis"));
            }
            ListTag pendingSkills = player.getList("pendingSkillDeals", Tag.TAG_STRING);
            Set<String> skillDeals = new HashSet<>();
            for (int j = 0; j < pendingSkills.size(); j++) skillDeals.add(pendingSkills.getString(j));
            if (!skillDeals.isEmpty()) data.pendingSkillDeals.put(uuid, skillDeals);
        }
        data.migrateRenamedFactionKeys();
        return data;
    }

    /**
     * Faction-key renames applied on load so old saves don't orphan
     * diplomacy state:
     * <ul>
     *   <li>{@code jura_alliance} → {@code tempest} (the step-1 merge into
     *       the Jura-Tempest Federation).</li>
     *   <li>{@code carrion} → {@code eurazania} (the step-2 Beast Kingdom
     *       rename).</li>
     *   <li>{@code otherworlders} → {@code eastern_empire} (the step-4
     *       re-theme to the Eastern Empire).</li>
     * </ul>
     * Every per-faction record keyed under the old id folds into the new id.
     *
     * <p>Combine rules when both keys exist for a player (only possible for
     * the merge, never the pure rename — the new key cannot pre-exist):
     * <ul>
     *   <li><b>relations state</b> — keep the HIGHER tier.</li>
     *   <li><b>active deal</b> — keep the existing new-key deal; else move
     *       the old one (its deal ids stay valid; any dropped id self-heals
     *       via the {@code byId==null} cleanup).</li>
     *   <li><b>offers / seen-offers</b> — UNION.</li>
     *   <li><b>timer ticks</b> (replies/send/activity/caravan) — keep MAX.</li>
     * </ul>
     * The envoy-away faction value is also renamed if a subordinate is out
     * serving the old faction.
     */
    private void migrateRenamedFactionKeys() {
        foldRename("jura_alliance", "tempest");
        foldRename("carrion", "eurazania");
        foldRename("otherworlders", "eastern_empire");
    }

    private void foldRename(String OLD, String NEW) {
        foldFaction(states, OLD, NEW, (cur, old) -> (byte) Math.max(cur, old));
        foldFaction(deals, OLD, NEW, (cur, old) -> cur);                 // keep tempest's
        foldFaction(offers, OLD, NEW, (cur, old) -> {
            List<Offer> merged = new ArrayList<>(cur);
            merged.addAll(old);
            return merged;
        });
        foldFaction(pendingReplies, OLD, NEW, Math::max);
        foldFaction(lastSend, OLD, NEW, Math::max);
        foldFaction(lastActivity, OLD, NEW, Math::max);
        foldFaction(lastCaravan, OLD, NEW, Math::max);
        foldFaction(seenOffers, OLD, NEW, (cur, old) -> {
            Set<String> merged = new HashSet<>(cur);
            merged.addAll(old);
            return merged;
        });
        // Envoy-away records the faction id as the VALUE, not the key.
        for (Map.Entry<UUID, String> e : envoyAway.entrySet()) {
            if (OLD.equals(e.getValue())) e.setValue(NEW);
        }
    }

    /** Move the {@code oldKey} entry of every player's inner map into
     *  {@code newKey}, combining with any existing entry. */
    private static <V> void foldFaction(Map<UUID, Map<String, V>> outer,
                                        String oldKey, String newKey,
                                        java.util.function.BinaryOperator<V> combine) {
        for (Map<String, V> inner : outer.values()) {
            V old = inner.remove(oldKey);
            if (old == null) continue;
            V cur = inner.get(newKey);
            inner.put(newKey, cur == null ? old : combine.apply(cur, old));
        }
    }
}
