package com.example.examplemod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side mirror of "which citizens in this colony are currently out of the
 * colony as Tensura subordinates".
 *
 * <p>The hiring window needs this to gray those citizens out, and nothing in
 * MineColonies' {@code ICitizenDataView} carries it — the view has no concept of
 * a citizen with no body. So the server broadcasts the id set per colony (see
 * {@code SubordinateJobGuard.tickSyncToClients}) and this holds the latest copy.</p>
 *
 * <p>Purely advisory. It drives whether a row looks hireable; the server refuses
 * the hire regardless (the assign-job mixins), so a stale or empty store can
 * never let a bad hire through — it would just briefly show a row as clickable
 * that then declines.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SubordinateClientStore {

    /** colonyId → citizen ids currently away as subordinates. */
    private static final Map<Integer, Set<Integer>> AWAY_BY_COLONY = new HashMap<>();
    /** colonyId → EVERY race citizen, away or not. Lets the hiring row tell a
     *  race citizen serving in the colony ("Colonist Mode") from an ordinary
     *  MineColonies colonist, whose row we leave completely alone. */
    private static final Map<Integer, Set<Integer>> RACE_BY_COLONY = new HashMap<>();

    private SubordinateClientStore() {}

    /** Replace one colony's sets wholesale — the payload is always full lists. */
    public static void accept(int colonyId, Set<Integer> awayIds, Set<Integer> raceIds) {
        if (awayIds.isEmpty()) AWAY_BY_COLONY.remove(colonyId);
        else AWAY_BY_COLONY.put(colonyId, awayIds);
        if (raceIds.isEmpty()) RACE_BY_COLONY.remove(colonyId);
        else RACE_BY_COLONY.put(colonyId, raceIds);
    }

    /** True if this citizen is away from the colony as a subordinate. */
    public static boolean isAway(int colonyId, int citizenId) {
        return AWAY_BY_COLONY.getOrDefault(colonyId, Collections.emptySet()).contains(citizenId);
    }

    /** True if this citizen is one of ours at all (any race, either mode). */
    public static boolean isRaceCitizen(int colonyId, int citizenId) {
        return RACE_BY_COLONY.getOrDefault(colonyId, Collections.emptySet()).contains(citizenId);
    }

    /** Drop everything — on disconnect, so a later world doesn't inherit stale ids. */
    public static void clear() {
        AWAY_BY_COLONY.clear();
        RACE_BY_COLONY.clear();
    }

    /** Defensive copy helper for the payload handler. */
    public static Set<Integer> copyOf(Iterable<Integer> ids) {
        Set<Integer> out = new HashSet<>();
        for (Integer id : ids) out.add(id);
        return out;
    }
}
