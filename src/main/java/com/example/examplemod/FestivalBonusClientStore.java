package com.example.examplemod;

import com.minecolonies.api.entity.citizen.Skill;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the Harvest Festival per-citizen skill bonuses, keyed by
 * MineColonies citizen id. Populated by {@link Networking.FestivalBonusPayload}
 * (sent to the colony owner on festival apply + login). Read by
 * {@link CitizenSkillBonusHandler} to draw the blue "+X" in the citizen window.
 */
@OnlyIn(Dist.CLIENT)
public final class FestivalBonusClientStore {

    /** key(colonyId, citizenId) -> (Skill -> bonus). */
    private static final Map<Long, Map<Skill, Integer>> BONUSES = new ConcurrentHashMap<>();

    private FestivalBonusClientStore() {}

    private static long key(int colonyId, int citizenId) {
        return ((long) colonyId << 32) | (citizenId & 0xFFFFFFFFL);
    }

    /** Replace the whole set from a payload. */
    public static void onPayload(Networking.FestivalBonusPayload p) {
        BONUSES.clear();
        Skill[] all = Skill.values();
        for (Networking.FestivalBonusEntry e : p.entries()) {
            if (e.skillOrdinal() < 0 || e.skillOrdinal() >= all.length) continue;
            BONUSES.computeIfAbsent(key(e.colonyId(), e.citizenId()), c -> new HashMap<>())
                   .put(all[e.skillOrdinal()], e.bonus());
        }
    }

    /** The bonuses for a citizen (empty map if none). */
    public static Map<Skill, Integer> get(int colonyId, int citizenId) {
        return BONUSES.getOrDefault(key(colonyId, citizenId), Map.of());
    }

    public static void clear() {
        BONUSES.clear();
    }
}
