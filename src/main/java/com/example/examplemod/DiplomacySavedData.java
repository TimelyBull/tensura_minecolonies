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

    long getLastInbound(UUID player) {
        Long tick = lastInbound.get(player);
        return tick == null ? Long.MIN_VALUE / 2 : tick;
    }

    void setLastInbound(UUID player, long tick) {
        lastInbound.put(player, tick);
        setDirty();
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
            players.add(player);
        }
        tag.put("players", players);
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
        }
        return data;
    }
}
