package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * THE reputation API — the spine of the reputation system and the SOLE
 * door to {@link ReputationSavedData}.
 *
 * <p>Every current and future feature (crime, raids, assassins, reclaim,
 * settlement systems, dialogue, trades, …) reads and writes reputation
 * exclusively through this class. Nothing else touches the SavedData.
 * That makes {@link #modifyReputation} the single place to later hang
 * side-effects: HUD sync, per-reason policy (multipliers / diminishing
 * returns / throttles), chat messaging, analytics.
 *
 * <p><b>Representation.</b> A clamped double in
 * [{@link #MIN_REPUTATION}, {@link #MAX_REPUTATION}] (0–100), default
 * {@link #DEFAULT_REPUTATION} (50 — NEUTRAL). {@link ReputationTier}
 * derives the queryable tier; all band thresholds live there.
 *
 * <p><b>Scope.</b> Per-COLONY standing is the v1 core — every v1 mover
 * writes it. The per-PLAYER (ruler) standing is fully plumbed (storage +
 * API) but driven by no v1 mover; it's reserved for future features that
 * need a standing independent of any single colony.
 *
 * <p>Server-side only, static — same shape as the rest of the mod's
 * server logic.
 */
public final class ReputationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReputationManager.class);

    public static final double MIN_REPUTATION     = 0.0;
    public static final double MAX_REPUTATION     = 100.0;
    /** Absent storage key reads as this — every colony starts NEUTRAL. */
    public static final double DEFAULT_REPUTATION = 50.0;

    private ReputationManager() {}

    // -----------------------------------------------------------------
    // Per-colony — v1 core
    // -----------------------------------------------------------------

    /** Current reputation of {@code colony}, default 50.0 if never touched.
     *  Returns the default if the colony's world isn't a server level
     *  (client-side colony views have no storage). */
    public static double getReputation(IColony colony) {
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return DEFAULT_REPUTATION;
        }
        return getReputation(level, colony.getID());
    }

    /** Current reputation of colony {@code colonyId}, default 50.0. */
    public static double getReputation(ServerLevel level, int colonyId) {
        Double stored = ReputationSavedData.get(level).getColonyValue(colonyId);
        return stored == null ? DEFAULT_REPUTATION : clamp(stored);
    }

    /** Derived tier of {@code colony} — what downstream features gate on. */
    public static ReputationTier getTier(IColony colony) {
        return ReputationTier.forValue(getReputation(colony));
    }

    public static ReputationTier getTier(ServerLevel level, int colonyId) {
        return ReputationTier.forValue(getReputation(level, colonyId));
    }

    /**
     * THE mutator. Applies {@code amount} (positive or negative) to the
     * colony's reputation, clamped to [0, 100], and persists.
     *
     * <p>This is the only write path features may use — and therefore the
     * single place future policy (throttling, multipliers, HUD sync)
     * gets added without touching any call site.
     *
     * @return the new (clamped) reputation value.
     */
    public static double modifyReputation(IColony colony, double amount, ReputationReason reason) {
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return DEFAULT_REPUTATION;
        }
        ReputationSavedData data = ReputationSavedData.get(level);
        double before = valueOrDefault(data.getColonyValue(colony.getID()));
        double after = clamp(before + amount);
        data.setColonyValue(colony.getID(), after);
        LOGGER.info("[TM] reputation: colony {} ('{}') {} {} → {} ({}, tier {})",
                colony.getID(), colony.getName(),
                String.format("%.1f", before),
                String.format("%+.1f", amount),
                String.format("%.1f", after),
                reason, ReputationTier.forValue(after));
        return after;
    }

    /** Admin/debug absolute set (the /reputation command). Clamped. */
    public static double setReputation(IColony colony, double value, ReputationReason reason) {
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return DEFAULT_REPUTATION;
        }
        double clamped = clamp(value);
        ReputationSavedData.get(level).setColonyValue(colony.getID(), clamped);
        LOGGER.info("[TM] reputation: colony {} ('{}') SET to {} ({}, tier {})",
                colony.getID(), colony.getName(), String.format("%.1f", clamped),
                reason, ReputationTier.forValue(clamped));
        return clamped;
    }

    // -----------------------------------------------------------------
    // Tier gates — the declarative checks downstream features use
    // ("crime when isBelow(colony, WARY)").
    // -----------------------------------------------------------------

    public static boolean isAtLeast(IColony colony, ReputationTier tier) {
        return getTier(colony).compareTo(tier) >= 0;
    }

    public static boolean isBelow(IColony colony, ReputationTier tier) {
        return getTier(colony).compareTo(tier) < 0;
    }

    // -----------------------------------------------------------------
    // Per-player (ruler) — plumbed for future features, NO v1 mover
    // writes it.
    // -----------------------------------------------------------------

    public static double getPlayerReputation(ServerLevel level, UUID playerUuid) {
        Double stored = ReputationSavedData.get(level).getPlayerValue(playerUuid);
        return stored == null ? DEFAULT_REPUTATION : clamp(stored);
    }

    public static ReputationTier getPlayerTier(ServerLevel level, UUID playerUuid) {
        return ReputationTier.forValue(getPlayerReputation(level, playerUuid));
    }

    /** Player-standing twin of {@link #modifyReputation}. Unused by v1
     *  movers — reserved for future ruler-level features. */
    public static double modifyPlayerReputation(ServerLevel level, UUID playerUuid,
                                                double amount, ReputationReason reason) {
        ReputationSavedData data = ReputationSavedData.get(level);
        double before = valueOrDefault(data.getPlayerValue(playerUuid));
        double after = clamp(before + amount);
        data.setPlayerValue(playerUuid, after);
        LOGGER.info("[TM] reputation: player {} {} {} → {} ({}, tier {})",
                playerUuid,
                String.format("%.1f", before),
                String.format("%+.1f", amount),
                String.format("%.1f", after),
                reason, ReputationTier.forValue(after));
        return after;
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    /** Colony-deleted cleanup — called from the existing
     *  {@code ColonyDeletedModEvent} hook so a re-created colony under the
     *  same id starts back at the neutral default. */
    static void onColonyDeleted(ServerLevel level, int colonyId) {
        ReputationSavedData.get(level).clearColony(colonyId);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** Convenience for movers that only have a colonyId. */
    public static IColony resolveColony(ServerLevel level, int colonyId) {
        return IColonyManager.getInstance().getColonyByWorld(colonyId, level);
    }

    private static double clamp(double v) {
        return Math.max(MIN_REPUTATION, Math.min(MAX_REPUTATION, v));
    }

    private static double valueOrDefault(Double stored) {
        return stored == null ? DEFAULT_REPUTATION : clamp(stored);
    }
}
