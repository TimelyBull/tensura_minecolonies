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
    private static final Map<Integer, Set<Integer>> BY_COLONY = new HashMap<>();

    private SubordinateClientStore() {}

    /** Replace one colony's set wholesale — the payload is always a full list. */
    public static void accept(int colonyId, Set<Integer> citizenIds) {
        if (citizenIds.isEmpty()) {
            BY_COLONY.remove(colonyId);
        } else {
            BY_COLONY.put(colonyId, citizenIds);
        }
    }

    /** True if this citizen is away from the colony as a subordinate. */
    public static boolean isAway(int colonyId, int citizenId) {
        return BY_COLONY.getOrDefault(colonyId, Collections.emptySet()).contains(citizenId);
    }

    /** Drop everything — on disconnect, so a later world doesn't inherit stale ids. */
    public static void clear() {
        BY_COLONY.clear();
    }

    /** Defensive copy helper for the payload handler. */
    public static Set<Integer> copyOf(Iterable<Integer> ids) {
        Set<Integer> out = new HashSet<>();
        for (Integer id : ids) out.add(id);
        return out;
    }
}
