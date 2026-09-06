package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops MineColonies from handing a JOB to a race citizen that is away from the
 * colony as a Tensura subordinate, and therefore has no body to do it with.
 *
 * <h2>The bug</h2>
 *
 * A named subordinate is, by design, a citizen with no {@code EntityCitizen}:
 * naming creates the {@code CitizenData} and then suppresses the body-spawn loop
 * via {@code startTravellingTo(..., Integer.MAX_VALUE)}. Its real body is the
 * Tensura mob out in the world.
 *
 * <p>MineColonies knows none of that. Automatic hiring asks
 * {@code CitizenManager.getJoblessCitizen()}, which filters on exactly
 * {@code getWorkBuilding() == null && !isChild()} — no entity check, no
 * travelling check — so a bodiless subordinate is a valid hire, and auto-hiring
 * is on by default. The player names a goblin, walks away, and a hut quietly
 * hires it. Nobody ever turns up for work; recall reports {@code recallfail}
 * (because {@code updateEntityIfNecessary} returns early while travelling); there
 * is no {@code EntityCitizen} for commands to target; and firing appeared not to
 * work because auto-hire re-grabbed the same citizen on the next colony tick.
 * That last part is what made it feel permanent.</p>
 *
 * <h2>The fix, and what it deliberately is NOT</h2>
 *
 * <p>One idea, applied at every door: <b>a citizen with no body is never GIVEN a
 * job.</b> {@code CitizenManagerJoblessMixin} skips them during auto-hire and the
 * two {@code ...AssignJobMixin}es refuse a manual hire — all three ask
 * {@link #isAwaySubordinate}. {@code HireWorkerRowTagMixin} then grays those rows
 * in the hiring window and labels them "Subordinate Mode", fed by
 * {@link #tickSyncToClients}. It also RE-ENABLES the hut's Fire button for them:
 * MineColonies hides Fire for any citizen its travelling manager reports as
 * travelling, and our subordinates are permanently marked travelling (that flag
 * is what stops a body spawning), so without this a worker who is out with the
 * player cannot be dismissed at all.</p>
 *
 * <p>It does NOT take jobs away. An away subordinate KEEPS whatever job it
 * already held; the work simply waits until it is sent home. Taking your own
 * subordinate out is normal play and must not reshuffle your huts. An earlier
 * build both released jobs on summon and ran a periodic sweep that stripped jobs
 * from "ghost" citizens; both were removed as unnecessary and, in the sweep's
 * case, resting on a false assumption — see decisions.md. Because auto-hire no
 * longer re-grabs these citizens, a colony that was already jammed is fixed by
 * firing the worker once, which now sticks.</p>
 *
 * <p>Housing is untouched — an away subordinate keeps its bed and housing slot,
 * which is existing, intended behaviour.</p>
 */
public final class SubordinateJobGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubordinateJobGuard.class);

    private SubordinateJobGuard() {}

    // ------------------------------------------------------------------
    // The predicate the mixins ask
    // ------------------------------------------------------------------

    /**
     * True when this citizen is currently out of the colony as a Tensura
     * subordinate, and so has no {@code EntityCitizen} to work a job with.
     *
     * <p>Answers only the hiring question. It says nothing about whether that
     * citizen is healthy — a subordinate standing in an unloaded chunk is a
     * perfectly ordinary one, and nothing here treats it as broken.</p>
     *
     * <p>FAILS OPEN. This runs inside MineColonies' own hiring code; if anything
     * goes wrong (a colony mid-load with no world yet, a missing saved-data
     * store) we answer "no, ordinary citizen" and let MineColonies proceed.
     * Wrongly blocking a hire would break colonies that have nothing to do with
     * us; wrongly allowing one is recoverable.</p>
     */
    public static boolean isAwaySubordinate(ICitizenData citizen) {
        try {
            if (citizen == null) return false;
            IColony colony = citizen.getColony();
            if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) return false;
            RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
            RaceIdentitySavedData.RaceIdentity identity =
                    saved.getByColonyAndCitizen(colony.getID(), citizen.getId());
            return identity != null
                    && identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE;
        } catch (Throwable t) {
            LOGGER.error("[TM] job guard: away-subordinate check threw for citizen {} — treating as ordinary",
                    citizen == null ? "null" : String.valueOf(citizen.getId()), t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Client sync — so the hiring window can gray these citizens out
    // ------------------------------------------------------------------

    /** Last [away, race] id sets broadcast per colony, so an unchanged colony
     *  sends nothing. */
    private static final java.util.Map<Integer, java.util.List<java.util.Set<Integer>>> LAST_SENT =
            new java.util.HashMap<>();
    /** Who was online at the last broadcast — a new arrival needs a full resend. */
    private static int lastPlayerCount = -1;

    /**
     * Tell clients which citizens are away as subordinates, and which are race
     * citizens at all, so {@code HireWorkerRowTagMixin} can gray away rows and
     * label each of ours "Subordinate Mode" / "Colonist Mode". Ordinary
     * MineColonies colonists appear in neither set and are left untouched.
     *
     * <p>Broadcast rather than request/response: the set is a handful of ints, and
     * pushing it means the hiring window already has the answer when it opens
     * instead of drawing a wrong list and correcting itself a moment later.</p>
     *
     * <p>Sends only when a colony's set actually changed, so an idle server is
     * silent. A player joining resets the change detection (their client starts
     * empty and needs everything), which is what {@code lastPlayerCount} is
     * watching for.</p>
     */
    public static void tickSyncToClients(MinecraftServer server) {
        int online = server.getPlayerList().getPlayerCount();
        if (online == 0) return;
        boolean forceAll = online != lastPlayerCount;
        lastPlayerCount = online;

        for (ServerLevel level : server.getAllLevels()) {
            RaceIdentitySavedData saved;
            try {
                saved = RaceIdentitySavedData.get(level);
            } catch (Throwable t) {
                continue;   // nothing to sync for this level
            }
            // One walk for the whole level, instead of a full saved.all() scan
            // per colony. See RaceIdentitySavedData.allByColony.
            java.util.Map<Integer, java.util.List<RaceIdentitySavedData.RaceIdentity>> idsByColony =
                    saved.allByColony();

            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                try {
                    java.util.Set<Integer> away = new java.util.HashSet<>();
                    java.util.Set<Integer> race = new java.util.HashSet<>();
                    // NOTE: deliberately NOT an early `continue` when a colony
                    // has no identities. A colony that just lost its last race
                    // citizen must still reach the change detection below so the
                    // now-empty sets are broadcast — otherwise clients keep the
                    // stale ids forever and the hiring window mislabels rows.
                    for (RaceIdentitySavedData.RaceIdentity id
                            : idsByColony.getOrDefault(colony.getID(), java.util.List.of())) {
                        race.add(id.citizenId);
                        if (id.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
                            away.add(id.citizenId);
                        }
                    }
                    // Change detection keys on BOTH sets — a citizen switching
                    // mode changes `away` only, while naming a new one changes
                    // `race` only, and each must reach the client.
                    java.util.List<java.util.Set<Integer>> current = java.util.List.of(away, race);
                    if (!forceAll && current.equals(LAST_SENT.get(colony.getID()))) continue;
                    LAST_SENT.put(colony.getID(), current);

                    net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                            new Networking.SyncSubordinateCitizensPayload(
                                    colony.getID(),
                                    new java.util.ArrayList<>(away),
                                    new java.util.ArrayList<>(race)));
                } catch (Throwable t) {
                    LOGGER.error("[TM] job guard: subordinate sync threw for colony {} — skipped",
                            colony.getID(), t);
                }
            }
        }
    }
}
