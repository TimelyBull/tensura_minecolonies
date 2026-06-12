package com.example.examplemod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The expanded per-faction model (docs/faction-model.md #1/#2/#4) —
 * everything a faction IS beyond its bare standing number:
 *
 * <ul>
 *   <li><b>Disposition bases</b> — the starting posture toward a HUMAN
 *       vs a MAJIN player. Computed LIVE into the standing
 *       ({@code effective = clamp(base + earned delta)}), never stored,
 *       so a mid-game race change shifts the world's posture by itself.</li>
 *   <li><b>The relationship web</b> — ally/enemy faction-id sets the
 *       two-sided movers fan out across. Symmetry is enforced by
 *       {@link #validateWeb()} (a log warning, not a storage rule, so
 *       addon factions can merge one edge at a time).</li>
 *   <li><b>Swing multiplier</b> — scales every mover hitting this
 *       faction ("swingable" Milim/Carrion 1.5×, "aloof"
 *       Leon/Otherworlders 0.5×).</li>
 *   <li><b>Provocation threshold</b> — offense points before the
 *       faction counts as PROVOKED (derived, never stored:
 *       {@code offense >= threshold}). Only violence writes offense,
 *       so "hostile-to-violence" patient factions fall out
 *       structurally.</li>
 * </ul>
 *
 * <p><b>Addon door:</b> string-keyed like the storage. The CLOWNS are
 * folded into CLAYMAN for v1 (user-confirmed); a future {@code clowns}
 * faction is one map entry plus edges.
 */
public record FactionProfile(
        String factionId,
        double baseHuman,
        double baseMajin,
        Set<String> allies,
        Set<String> enemies,
        double swingMultiplier,
        double provocationThreshold,
        boolean sendsEnvoysToHuman,
        boolean sendsEnvoysToMajin) {

    private static final Logger LOGGER = LoggerFactory.getLogger(FactionProfile.class);

    /** The faction's disposition base for the player's race side. */
    public double base(boolean majinSide) {
        return majinSide ? baseMajin : baseHuman;
    }

    /** The INBOUND envoy race-gate (docs/diplomacy.md #1): does this
     *  faction SEND diplomatic envoys to a player of this race side?
     *  False both ways = outbound-only (the player must send theirs —
     *  the aloof factions, and the Holy bloc toward majin). */
    public boolean sendsEnvoysTo(boolean majinSide) {
        return majinSide ? sendsEnvoysToMajin : sendsEnvoysToHuman;
    }

    // ------------------------------------------------------------------
    // The v1 profile table — user-confirmed numbers
    // (docs/faction-model.md, disposition + web + threshold tables).
    // ------------------------------------------------------------------

    public static final Map<String, FactionProfile> PROFILES = buildProfiles();

    public static FactionProfile byId(String factionId) {
        return PROFILES.get(factionId);
    }

    private static Map<String, FactionProfile> buildProfiles() {
        Map<String, FactionProfile> map = new LinkedHashMap<>();
        // Holy bloc — wary of humans, HOSTILE-band to majin. Sends
        // envoys to HUMAN players only — a majin must send their own.
        put(map, new FactionProfile("luminous", 30, 10,
                Set.of("falmuth"), Set.of("clayman"), 1.0, 5, true, false));
        put(map, new FactionProfile("falmuth", 35, 15,
                Set.of("luminous"), Set.of("clayman"), 1.0, 5, true, false));
        // The schemers (Clowns folded in) — neutral-but-scheming,
        // provoked on the first real slight. Never SENDS envoys (he
        // schemes, he doesn't court) — outbound only.
        put(map, new FactionProfile("clayman", 45, 45,
                Set.of(),
                Set.of("luminous", "falmuth", "tempest", "jura_alliance", "milim", "carrion"),
                1.0, 3, false, false));
        // The diplomats — patient; only sustained violence provokes.
        // Diplomacy-open: they send to anyone.
        put(map, new FactionProfile("dwargon", 50, 50,
                Set.of("tempest", "jura_alliance"), Set.of(), 1.0, 10, true, true));
        put(map, new FactionProfile("tempest", 50, 55,
                Set.of("jura_alliance", "dwargon"), Set.of("clayman"), 1.0, 10, true, true));
        put(map, new FactionProfile("jura_alliance", 50, 55,
                Set.of("tempest", "dwargon"), Set.of("clayman"), 1.0, 10, true, true));
        // The swingables — neutral but every mover lands 1.5×.
        put(map, new FactionProfile("milim", 50, 50,
                Set.of(), Set.of("clayman"), 1.5, 8, true, true));
        put(map, new FactionProfile("carrion", 50, 50,
                Set.of(), Set.of("clayman"), 1.5, 8, true, true));
        // The aloof — movers dampened to 0.5×; never send (outbound only).
        put(map, new FactionProfile("leon", 50, 50,
                Set.of(), Set.of(), 0.5, 15, false, false));
        put(map, new FactionProfile("otherworlders", 50, 50,
                Set.of(), Set.of(), 0.5, 15, false, false));
        put(map, new FactionProfile("shizu", 50, 50,
                Set.of(), Set.of(), 1.0, 15, false, false));
        return Map.copyOf(map);
    }

    private static void put(Map<String, FactionProfile> map, FactionProfile profile) {
        map.put(profile.factionId(), profile);
    }

    /**
     * Sanity check: every ally/enemy edge should appear on BOTH ends and
     * point at a known profile. A failure is a LOG WARNING (an addon
     * adding one side of an edge still works — its faction just won't
     * receive the mirrored ripple until the other end lists it too).
     * Called once from the mod constructor.
     */
    public static void validateWeb() {
        for (FactionProfile profile : PROFILES.values()) {
            for (String ally : profile.allies()) {
                FactionProfile other = PROFILES.get(ally);
                if (other == null) {
                    LOGGER.warn("[TM] faction web: {} lists unknown ally '{}'",
                            profile.factionId(), ally);
                } else if (!other.allies().contains(profile.factionId())) {
                    LOGGER.warn("[TM] faction web: {} → ally '{}' is not mirrored",
                            profile.factionId(), ally);
                }
            }
            for (String enemy : profile.enemies()) {
                FactionProfile other = PROFILES.get(enemy);
                if (other == null) {
                    LOGGER.warn("[TM] faction web: {} lists unknown enemy '{}'",
                            profile.factionId(), enemy);
                } else if (!other.enemies().contains(profile.factionId())) {
                    LOGGER.warn("[TM] faction web: {} → enemy '{}' is not mirrored",
                            profile.factionId(), enemy);
                }
            }
        }
    }
}
