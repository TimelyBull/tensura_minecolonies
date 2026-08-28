package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps MineColonies from handing a JOB to a race citizen that has no body to do
 * it with, and clears the job off the one case that already went wrong.
 *
 * <h2>Two different things, deliberately treated differently</h2>
 *
 * A named subordinate is, by design, a citizen with no {@code EntityCitizen}:
 * naming creates the {@code CitizenData} and then suppresses the body-spawn loop
 * via {@code startTravellingTo(..., Integer.MAX_VALUE)}. That covers two states
 * that look identical to MineColonies but are not the same at all:
 *
 * <dl>
 *   <dt><b>An away subordinate — NORMAL, left alone.</b></dt>
 *   <dd>The player summoned it out and is walking around with it. Its real body
 *       is the Tensura mob at their side. It should keep any job it holds; the
 *       job simply goes undone until it is sent home, which is the mod working
 *       as intended. Nothing here fires it, and the player never has to think
 *       about job assignments before taking a subordinate out.</dd>
 *
 *   <dt><b>A ghost — BROKEN, repaired by {@link #tickReconcile}.</b></dt>
 *   <dd>An identity stuck in {@code SUBORDINATE} whose mob resolves to nothing
 *       anywhere: removed by a third-party mob-storage item, lost to a rolled-back
 *       summon, or displaced by the pre-0.2.1 double-registration bug. There is no
 *       body in EITHER form. This is what players reported — a worker that
 *       "isn't real": it can't be recalled ({@code updateEntityIfNecessary}
 *       returns early while travelling), can't be killed or targeted by commands
 *       (no entity exists), and can't be fired (firing works, but auto-hire
 *       re-grabs it on the next colony tick). The hut it occupies is stuck for
 *       good. Same population {@code /recoverorphans} reports.</dd>
 * </dl>
 *
 * <h2>The guards</h2>
 *
 * <ol>
 *   <li><b>Never hire a bodiless citizen in the first place.</b> The mixins call
 *       {@link #isAwaySubordinate}: {@code CitizenManagerJoblessMixin} skips them
 *       during auto-hire, and the two {@code ...AssignJobMixin}es refuse a manual
 *       hire. This applies to away subordinates AND ghosts, because neither can
 *       work — an away subordinate keeps a job it already had, but must not be
 *       given a new one while it is gone.</li>
 *   <li><b>Free the hut a ghost is squatting.</b> {@link #tickReconcile} strips
 *       the job from ghosts only, so worlds that already softlocked repair
 *       themselves shortly after load.</li>
 * </ol>
 *
 * <p>Housing is untouched throughout — an away subordinate keeps its bed and its
 * housing slot, which is existing, intended behaviour.</p>
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
     * <p>Covers both an ordinary away subordinate and a ghost — for the purpose
     * of HIRING they are the same, since neither can do the work. The difference
     * only matters to {@link #tickReconcile}, which strips jobs off ghosts alone.</p>
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
    // Ghost detection
    // ------------------------------------------------------------------

    /**
     * True when this identity has NO body in either form — the "not real"
     * citizen players reported.
     *
     * <p>Deliberately conservative, because the obvious test — "the mob UUID
     * doesn't resolve" — is also what a perfectly healthy subordinate standing in
     * an UNLOADED CHUNK looks like. Getting that wrong would strip jobs off
     * players' real subordinates, which is exactly what must not happen. So only
     * these three count, each of which is impossible for a healthy subordinate:</p>
     *
     * <ul>
     *   <li><b>no mob UUID at all</b> — a SUBORDINATE record always has one;
     *       null means the link was lost;</li>
     *   <li><b>displaced</b> — some OTHER identity now owns this mob UUID, so
     *       whatever is standing in the world is not this citizen's body (the
     *       pre-0.2.1 double-registration bug);</li>
     *   <li><b>owner online and the mob is nowhere</b> — the owner is loaded and
     *       present, so a still-valid subordinate would be in a loaded chunk.
     *       Same reasoning {@code /recoverorphans} uses to decide it is safe to
     *       act. With the owner offline we simply wait.</li>
     * </ul>
     *
     * <p>A colony DEFENDER is never a ghost here: it is mid-swap by design and
     * its {@code defendingColony} flag says so.</p>
     */
    private static boolean isGhost(MinecraftServer server,
                                   RaceIdentitySavedData saved,
                                   RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE) return false;
        if (identity.defendingColony) return false;

        if (identity.mobEntityUUID == null) return true;
        if (saved.getByMobUUID(identity.mobEntityUUID) != identity) return true;   // displaced

        LivingEntity mob = ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID);
        if (mob != null && mob.isAlive()) return false;

        boolean ownerOnline = identity.ownerPlayerUUID != null
                && server.getPlayerList().getPlayer(identity.ownerPlayerUUID) != null;
        return ownerOnline;
    }

    // ------------------------------------------------------------------
    // Repair pass
    // ------------------------------------------------------------------

    /**
     * Strip the colony job off any GHOST that is holding one, freeing the hut to
     * hire a real worker.
     *
     * <p>This is what repairs an already-broken world: a colony whose Builder's
     * Hut is stuck on a citizen that isn't real releases it within a few seconds
     * of loading, with nothing for the player to do. Ordinary away subordinates
     * are not touched — see the class javadoc for why the two are treated
     * differently.</p>
     *
     * <p>The citizen record itself is left alone. Emptying the hut is what
     * un-sticks the colony; deciding whether that citizen should be restored as a
     * colonist or deleted outright is what {@code /recoverorphans} is for, and
     * that is the player's call, not something to do behind their back.</p>
     */
    public static void tickReconcile(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            RaceIdentitySavedData saved;
            try {
                saved = RaceIdentitySavedData.get(level);
            } catch (Throwable t) {
                LOGGER.error("[TM] job guard: could not read identities on level {}",
                        level.dimension().location(), t);
                continue;
            }

            // Copy first — MineColonies' removeCitizen touches colony state.
            for (RaceIdentitySavedData.RaceIdentity identity
                    : new java.util.ArrayList<>(saved.all())) {
                try {
                    if (!isGhost(server, saved, identity)) continue;

                    IColony colony = IColonyManager.getInstance()
                            .getColonyByWorld(identity.colonyId, level);
                    if (colony == null) continue;
                    ICitizenData citizen =
                            colony.getCitizenManager().getCivilian(identity.citizenId);
                    if (citizen == null || citizen.getWorkBuilding() == null) continue;

                    releaseGhostJob(citizen);
                } catch (Throwable t) {
                    LOGGER.error("[TM] job guard: reconcile threw for identity {} — skipped",
                            identity.identityId, t);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Client sync — so the hiring window can gray these citizens out
    // ------------------------------------------------------------------

    /** Last id set broadcast per colony, so an unchanged colony sends nothing. */
    private static final java.util.Map<Integer, java.util.Set<Integer>> LAST_SENT =
            new java.util.HashMap<>();
    /** Who was online at the last broadcast — a new arrival needs a full resend. */
    private static int lastPlayerCount = -1;

    /**
     * Tell clients which citizens are away as subordinates, so
     * {@code WindowHireWorkerGrayOutMixin} can gray those rows out.
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
                continue;   // already logged by the reconcile pass
            }
            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                try {
                    java.util.Set<Integer> away = new java.util.HashSet<>();
                    for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
                        if (id.colonyId == colony.getID()
                                && id.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
                            away.add(id.citizenId);
                        }
                    }
                    java.util.Set<Integer> previous = LAST_SENT.get(colony.getID());
                    if (!forceAll && away.equals(previous)) continue;
                    LAST_SENT.put(colony.getID(), away);

                    net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                            new Networking.SyncSubordinateCitizensPayload(
                                    colony.getID(), new java.util.ArrayList<>(away)));
                } catch (Throwable t) {
                    LOGGER.error("[TM] job guard: subordinate sync threw for colony {} — skipped",
                            colony.getID(), t);
                }
            }
        }
    }

    /** Unassign a ghost from whatever job module is holding it. */
    private static void releaseGhostJob(ICitizenData citizen) {
        IBuilding work = citizen.getWorkBuilding();
        BlockPos pos = work.getPosition();

        boolean removed = false;
        for (IAssignsJob module : work.getModules(IAssignsJob.class)) {
            if (module.hasAssignedCitizen(citizen)) {
                module.removeCitizen(citizen);
                removed = true;
            }
        }
        if (!removed) {
            // getWorkBuilding() said yes but no job module owned them — a desync.
            // Normally the two move together (AbstractJob.assignTo sets the job's
            // building, onRemoval clears it via setJob(null)). Clear the job
            // directly, or this pass would see the same stale building every few
            // seconds forever.
            LOGGER.warn("[TM] job guard: ghost citizen {} reported work building {} but no job module held it — clearing the stale job",
                    citizen.getId(), pos);
            citizen.setJob(null);
            return;
        }

        LOGGER.warn("[TM] job guard: citizen {} has no body in either form (a ghost) but held a job at {} — released it so the building can hire a real worker. Run /recoverorphans to deal with the citizen record itself.",
                citizen.getId(), pos);
    }
}
