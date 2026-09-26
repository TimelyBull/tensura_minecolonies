package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodic repair pass that keeps the identity store in step with
 * MineColonies' own citizen list.
 *
 * <h2>Why this exists</h2>
 *
 * Every race citizen is TWO records: a MineColonies {@code CitizenData}
 * (the colony's own bookkeeping) and our {@code RaceIdentity} (race,
 * appearance, monster snapshot, owner). Nothing used to check that the two
 * still agreed. When they drifted apart the player saw a "ghost" — a citizen
 * with no body that could not be recalled — or a race citizen that quietly
 * turned human. Both drifts came from the same root: an identity record
 * outliving, or doubling up on, the citizen it described, while MineColonies
 * hands a dead citizen's number straight to the next one it creates.
 *
 * <h2>What it repairs</h2>
 * <ol>
 *   <li><b>Dangling identities.</b> The identity's colony exists (it is loaded
 *       in some level) but has no citizen with that number any more. The
 *       identity is deleted. Without this, the next citizen MineColonies gives
 *       that number inherits a dead citizen's race and appearance.</li>
 *   <li><b>Duplicate pairs.</b> Two identities claim the same (colony,
 *       citizen). Only one can be right. The pass keeps the one whose monster
 *       is actually alive, else the one that is IN_COLONY (its body is the
 *       citizen itself, which exists), else the first it saw; the rest are
 *       deleted.</li>
 *   <li><b>Inherited "travelling forever" entries.</b> MineColonies keeps its
 *       travelling list in the colony file keyed by citizen NUMBER and never
 *       clears an entry when the citizen is removed. We mark every away
 *       subordinate travelling with a practically infinite time. When such a
 *       citizen was removed without clearing the entry (a monster dying, an
 *       old purge, a hand-edited save, the mod being uninstalled for a while),
 *       the next citizen given that number inherited it: no body, no recall,
 *       no kill — a plain MineColonies citizen turned ghost. The pass clears
 *       the entry for any travelling citizen that is NOT one of our away
 *       subordinates and is NOT a Nether worker (the one MineColonies job that
 *       legitimately travels), so MineColonies respawns it on its next pass.</li>
 * </ol>
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li>It never touches a SUBORDINATE identity whose monster cannot be found.
 *       A monster in an unloaded chunk looks exactly the same as a monster
 *       that is gone, and an earlier "ghost sweep" built on that mistake had
 *       to be deleted (see decisions.md, CORRECTION 2). Those records are the
 *       player's to recover through the roster or {@code /recoverorphans}.</li>
 *   <li>It never deletes an identity whose colony cannot be found at all —
 *       the colony may simply live in a dimension that is not loaded.</li>
 * </ul>
 *
 * <p>Runs every {@link #PERIOD_TICKS} from the server tick pass, and on demand
 * through {@code /identityaudit}.</p>
 */
public final class IdentityAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityAudit.class);

    /** Once a minute. The pass is a single walk over the identity list plus one
     *  colony lookup per distinct colony, so it is cheap even on big servers. */
    static final long PERIOD_TICKS = 1200L;

    private IdentityAudit() {}

    /** What one run found and did. */
    public record Report(int checked, int danglingRemoved, int duplicatesRemoved,
                         int coloniesNotFound, int travellingCleared) {
        public String summary() {
            return "Identity audit: " + checked + " record(s) checked — "
                    + danglingRemoved + " dangling removed (citizen no longer exists), "
                    + duplicatesRemoved + " duplicate(s) removed (two records on one citizen), "
                    + coloniesNotFound + " skipped (colony not loaded); "
                    + travellingCleared + " stuck citizen(s) released (inherited travelling entry).";
        }
    }

    /**
     * Run the audit over every identity on the server.
     *
     * @param verbose log every record that was skipped as well as every repair
     */
    public static Report run(MinecraftServer server, boolean verbose) {
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(server.overworld());
        List<RaceIdentitySavedData.RaceIdentity> all = new ArrayList<>(saved.all());

        int dangling = 0;
        int duplicates = 0;
        int notFound = 0;

        // One colony lookup per colony id, across every level, cached for the run.
        Map<Integer, IColony> colonies = new HashMap<>();

        // Pass 1 — dangling: colony is loaded, citizen number is gone.
        for (RaceIdentitySavedData.RaceIdentity id : all) {
            IColony colony = colonyFor(server, colonies, id.colonyId);
            if (colony == null) {
                notFound++;
                if (verbose) {
                    LOGGER.info("[TM] audit: identity {} — colony {} not loaded, skipped",
                            id.identityId, id.colonyId);
                }
                continue;
            }
            ICitizenData cd = colony.getCitizenManager().getCivilian(id.citizenId);
            if (cd == null) {
                LOGGER.warn("[TM] audit: identity {} (citizen {} colony {} race {} mode {}) has no "
                        + "citizen record any more — removing the dangling identity",
                        id.identityId, id.citizenId, id.colonyId, id.race, id.mode);
                saved.removeIdentity(id);
                dangling++;
            }
        }

        // Pass 2 — duplicates: two surviving identities on one (colony, citizen).
        Map<String, List<RaceIdentitySavedData.RaceIdentity>> byPair = new HashMap<>();
        for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
            byPair.computeIfAbsent(id.colonyId + ":" + id.citizenId, k -> new ArrayList<>()).add(id);
        }
        for (Map.Entry<String, List<RaceIdentitySavedData.RaceIdentity>> e : byPair.entrySet()) {
            List<RaceIdentitySavedData.RaceIdentity> group = e.getValue();
            if (group.size() < 2) continue;
            RaceIdentitySavedData.RaceIdentity keep = chooseKeeper(server, group);
            for (RaceIdentitySavedData.RaceIdentity id : group) {
                if (id == keep) continue;
                LOGGER.warn("[TM] audit: citizen {} of colony {} had two identities — keeping {} ({}, {}), "
                        + "removing {} ({}, {})",
                        keep.citizenId, keep.colonyId, keep.identityId, keep.race, keep.mode,
                        id.identityId, id.race, id.mode);
                saved.removeIdentity(id);
                duplicates++;
            }
        }

        // Pass 3 — inherited travelling entries on citizens that are not ours.
        int travellingCleared = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                try {
                    travellingCleared += releaseStuckTravellers(saved, colony, verbose);
                } catch (Throwable t) {
                    LOGGER.warn("[TM] audit: travelling check threw for colony {}", colony.getID(), t);
                }
            }
        }

        Report report = new Report(all.size(), dangling, duplicates, notFound, travellingCleared);
        if (verbose || dangling > 0 || duplicates > 0 || travellingCleared > 0) {
            LOGGER.info("[TM] {}", report.summary());
        }
        return report;
    }

    /**
     * Clear travelling entries that no longer belong to anyone. A citizen is
     * left alone when it is one of our away subordinates (SUBORDINATE-mode
     * identity — travelling is exactly its state) or a Nether worker (the only
     * MineColonies job that travels on its own). Everything else that is
     * travelling has inherited a dead citizen's entry and can never come back
     * on its own.
     *
     * <p>Iterates the colony's citizens rather than the travelling list itself
     * (MineColonies exposes no accessor for it). An entry for a number no
     * citizen currently holds is harmless until the number is reused — and the
     * moment it is, that citizen exists and this pass catches it.</p>
     */
    private static int releaseStuckTravellers(RaceIdentitySavedData saved, IColony colony, boolean verbose) {
        int cleared = 0;
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null) continue;
            if (!colony.getTravellingManager().isTravelling(cd)) continue;
            RaceIdentitySavedData.RaceIdentity id = saved.getByColonyAndCitizen(colony.getID(), cd.getId());
            if (id != null && id.mode == RaceIdentitySavedData.Mode.SUBORDINATE) continue; // ours, away
            if (cd.getJob() instanceof com.minecolonies.core.colony.jobs.JobNetherWorker) continue; // real trip
            colony.getTravellingManager().finishTravellingFor(cd);
            cleared++;
            LOGGER.warn("[TM] audit: citizen {} ('{}') of colony {} was stuck travelling with no reason "
                    + "(inherited a removed citizen's entry) — released; MineColonies will respawn it",
                    cd.getId(), cd.getName(), colony.getID());
        }
        if (verbose && cleared == 0) {
            LOGGER.info("[TM] audit: colony {} — no stuck travellers", colony.getID());
        }
        return cleared;
    }

    /** Among several identities on one citizen, the one most likely to be real:
     *  a live monster beats an IN_COLONY record, which beats anything else. */
    private static RaceIdentitySavedData.RaceIdentity chooseKeeper(
            MinecraftServer server, List<RaceIdentitySavedData.RaceIdentity> group) {
        for (RaceIdentitySavedData.RaceIdentity id : group) {
            if (id.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                    && id.mobEntityUUID != null
                    && ExampleMod.findLivingEntityAcrossLevels(server, id.mobEntityUUID) != null) {
                return id;
            }
        }
        for (RaceIdentitySavedData.RaceIdentity id : group) {
            if (id.mode == RaceIdentitySavedData.Mode.IN_COLONY) return id;
        }
        return group.get(0);
    }

    /** The colony with this id, searched across every level; null if it is not
     *  loaded anywhere. Cached per run. */
    private static IColony colonyFor(MinecraftServer server, Map<Integer, IColony> cache, int colonyId) {
        if (cache.containsKey(colonyId)) return cache.get(colonyId);
        IColony found = null;
        for (ServerLevel level : server.getAllLevels()) {
            found = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (found != null) break;
        }
        cache.put(colonyId, found);
        return found;
    }
}
