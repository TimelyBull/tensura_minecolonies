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

    /** player → faction ids whose diplomacy is CLOSED (lore-events.md
     *  #4). RECOVERABLE by design — the future diplomacy arc's mending
     *  ritual clears entries at a steep price; until then the flag just
     *  exists (+ a dark flavor line at set time). */
    private final Map<UUID, Set<String>> diplomacyClosed = new HashMap<>();

    /** player → lore event ids whose boss was SLAIN — those events never
     *  recur for that player (the Disaster is dead). */
    private final Map<UUID, Set<String>> loreDefeated = new HashMap<>();

    /** player → (lore event id → game tick before which it cannot recur)
     *  — the timed-out-march regroup cooldown. */
    private final Map<UUID, Map<String, Long>> loreCooldowns = new HashMap<>();

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

    boolean isDiplomacyClosed(UUID player, String factionId) {
        Set<String> closed = diplomacyClosed.get(player);
        return closed != null && closed.contains(factionId);
    }

    void closeDiplomacy(UUID player, String factionId) {
        if (diplomacyClosed.computeIfAbsent(player, k -> new HashSet<>()).add(factionId)) {
            setDirty();
        }
    }

    /** The future mending ritual's door — recoverable by design. */
    void reopenDiplomacy(UUID player, String factionId) {
        Set<String> closed = diplomacyClosed.get(player);
        if (closed != null && closed.remove(factionId)) setDirty();
    }

    boolean isLoreEventDefeated(UUID player, String eventId) {
        Set<String> defeated = loreDefeated.get(player);
        return defeated != null && defeated.contains(eventId);
    }

    void markLoreEventDefeated(UUID player, String eventId) {
        if (loreDefeated.computeIfAbsent(player, k -> new HashSet<>()).add(eventId)) {
            setDirty();
        }
    }

    long getLoreEventCooldownUntil(UUID player, String eventId) {
        Map<String, Long> byEvent = loreCooldowns.get(player);
        Long until = byEvent == null ? null : byEvent.get(eventId);
        return until == null ? Long.MIN_VALUE : until;
    }

    void setLoreEventCooldownUntil(UUID player, String eventId, long untilTick) {
        loreCooldowns.computeIfAbsent(player, k -> new HashMap<>()).put(eventId, untilTick);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // One compound per player holding every section — union of all
        // map keys so a player with (say) only a lore cooldown persists.
        Set<UUID> allPlayers = new HashSet<>();
        allPlayers.addAll(standings.keySet());
        allPlayers.addAll(offenses.keySet());
        allPlayers.addAll(diplomacyClosed.keySet());
        allPlayers.addAll(loreDefeated.keySet());
        allPlayers.addAll(loreCooldowns.keySet());

        ListTag players = new ListTag();
        for (UUID uuid : allPlayers) {
            CompoundTag player = new CompoundTag();
            player.putUUID("uuid", uuid);
            player.put("standings", writeFactionDoubles(standings.get(uuid)));
            player.put("offenses", writeFactionDoubles(offenses.get(uuid)));
            player.put("diplomacyClosed", writeStringSet(diplomacyClosed.get(uuid)));
            player.put("loreDefeated", writeStringSet(loreDefeated.get(uuid)));
            ListTag cooldowns = new ListTag();
            Map<String, Long> byEvent = loreCooldowns.get(uuid);
            if (byEvent != null) {
                for (Map.Entry<String, Long> c : byEvent.entrySet()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("event", c.getKey());
                    entry.putLong("until", c.getValue());
                    cooldowns.add(entry);
                }
            }
            player.put("loreCooldowns", cooldowns);
            players.add(player);
        }
        tag.put("players", players);
        return tag;
    }

    private static ListTag writeFactionDoubles(Map<String, Double> byFaction) {
        ListTag entries = new ListTag();
        if (byFaction != null) {
            for (Map.Entry<String, Double> e : byFaction.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("faction", e.getKey());
                entry.putDouble("value", e.getValue());
                entries.add(entry);
            }
        }
        return entries;
    }

    private static ListTag writeStringSet(Set<String> values) {
        ListTag entries = new ListTag();
        if (values != null) {
            for (String value : values) {
                entries.add(net.minecraft.nbt.StringTag.valueOf(value));
            }
        }
        return entries;
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
                Set<String> closed = readStringSet(player, "diplomacyClosed");
                if (!closed.isEmpty()) data.diplomacyClosed.put(player.getUUID("uuid"), closed);
                Set<String> defeated = readStringSet(player, "loreDefeated");
                if (!defeated.isEmpty()) data.loreDefeated.put(player.getUUID("uuid"), defeated);
                Map<String, Long> cooldowns = new HashMap<>();
                ListTag cooldownEntries = player.getList("loreCooldowns", Tag.TAG_COMPOUND);
                for (int j = 0; j < cooldownEntries.size(); j++) {
                    CompoundTag entry = cooldownEntries.getCompound(j);
                    cooldowns.put(entry.getString("event"), entry.getLong("until"));
                }
                if (!cooldowns.isEmpty()) {
                    data.loreCooldowns.put(player.getUUID("uuid"), cooldowns);
                }
            }
        }
        migrateRenamedFactionKeys(data);
        return data;
    }

    /**
     * Faction-key renames applied on load so old saves don't orphan data:
     * <ul>
     *   <li>{@code jura_alliance} → {@code tempest} (the step-1 merge into
     *       the Jura-Tempest Federation).</li>
     *   <li>{@code carrion} → {@code eurazania} (the step-2 Beast Kingdom
     *       rename).</li>
     * </ul>
     * For each, the old key's standing/offense/diplomacy-closed entry moves
     * to the new key. Combine rules when BOTH keys exist for the same player
     * (only possible for the merge, never the pure rename — the new key
     * cannot pre-exist in an old save):
     * <ul>
     *   <li><b>standing</b> (earned delta) — keep the larger MAGNITUDE.</li>
     *   <li><b>offense</b> — keep the MAX (the stronger provocation).</li>
     *   <li><b>diplomacy-closed</b> — set UNION.</li>
     * </ul>
     */
    private static void migrateRenamedFactionKeys(WorldReputationSavedData data) {
        foldFactionKey(data, "jura_alliance", "tempest");
        foldFactionKey(data, "carrion", "eurazania");
    }

    private static void foldFactionKey(WorldReputationSavedData data, String OLD, String NEW) {
        for (Map<String, Double> byFaction : data.standings.values()) {
            Double old = byFaction.remove(OLD);
            if (old == null) continue;
            Double cur = byFaction.get(NEW);
            byFaction.put(NEW, cur == null ? old
                    : (Math.abs(old) > Math.abs(cur) ? old : cur));
        }
        for (Map<String, Double> byFaction : data.offenses.values()) {
            Double old = byFaction.remove(OLD);
            if (old == null) continue;
            Double cur = byFaction.get(NEW);
            byFaction.put(NEW, cur == null ? old : Math.max(old, cur));
        }
        for (Set<String> closed : data.diplomacyClosed.values()) {
            if (closed.remove(OLD)) closed.add(NEW);
        }
    }

    private static Set<String> readStringSet(CompoundTag player, String key) {
        Set<String> out = new HashSet<>();
        ListTag entries = player.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < entries.size(); i++) {
            out.add(entries.getString(i));
        }
        return out;
    }
}
