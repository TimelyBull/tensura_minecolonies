package com.example.examplemod;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;

/**
 * Beast-guard AI — PATROL-locked subclass of {@link AbstractEntityAIGuard}.
 *
 * <p>Two behaviour overrides versus the standard guard:
 * <ol>
 *   <li>{@link #decide()} short-circuits the GuardTaskSetting dispatch
 *       and routes directly to {@link #patrol()}. Beasts never enter
 *       GUARD (return-to-tower), FOLLOW (escort player), or PATROL_MINE
 *       modes. This is the make-or-break "constant patrol, never return
 *       to building" semantics.</li>
 *   <li>{@link #guardMovement()} is a no-op. The default guard randomly
 *       drifts back toward the assigned guard pos when idle; that pulls
 *       the beast back to the tower it's bound to (the colony anchor),
 *       which conflicts with the constant-patrol design.</li>
 * </ol>
 *
 * <p>Combat (`inCombat`), sleep / regen / flee, and the
 * `randomPatrolPoint` (random-building selection) are inherited
 * unchanged. The beast attacks hostile mobs like any guard, picks
 * patrol targets from the colony's building list, and uses the
 * standard guard equipment / regen pipeline.
 *
 * <p>Type parameter binding: {@code <JobBeastGuard, AbstractBuildingGuards>}.
 * Beasts bind to a regular {@link com.minecolonies.core.colony.buildings.workerbuildings.BuildingGuardTower}
 * (a subclass of AbstractBuildingGuards) — same as a Knight. The tower
 * is required structurally (the {@code buildingGuards} field is
 * dereferenced unguarded all over AbstractEntityAIGuard) but the beast
 * never returns there because PATROL doesn't.
 */
public class EntityAIBeastGuard
        extends AbstractEntityAIGuard<JobBeastGuard, AbstractBuildingGuards> {

    /**
     * Extra clearance, in blocks, kept between a patrol point and any
     * building's estimated footprint. Effective rejection half-extent
     * becomes {@code (6 + 2 * level) + BUILDING_AVOID_BUFFER}, so e.g.
     * an L3 hut (footprint ~12 wide) gets a 6-block buffer ring on
     * every side. Tunable: lower (e.g. 3) if the spider can't find
     * valid patrol points in cramped colonies; higher if it still
     * brushes walls.
     */
    private static final int BUILDING_AVOID_BUFFER = 6;

    public EntityAIBeastGuard(JobBeastGuard job) {
        super(job);
        // Stage L3a — instantiate the beast-specific combat AI. Stage 1
        // mistakenly relied on parent behaviour for combat, but the
        // parent {@code AbstractEntityAIGuard} doesn't create a combat
        // AI; the concrete guard subclass does ({@code EntityAIKnight}
        // constructs a {@link com.minecolonies.core.entity.ai.workers.guard.KnightCombatAI}
        // in its own ctor). Without one, the beast had no `inCombat`
        // action. This line both FIXES that gap AND boosts the attack
        // reach to match the spider's visual footprint via the
        // {@link BeastGuardCombatAI#getAttackDistance} override.
        //
        // The combat AI registers itself with our state machine via its
        // superclass constructor; the returned instance is intentionally
        // discarded (same pattern as EntityAIKnight).
        if (worker instanceof com.minecolonies.core.entity.citizen.EntityCitizen ec) {
            new BeastGuardCombatAI(ec, getStateAI(), this);
        }
    }

    /**
     * Stage L3 polish — never route the spider to the tower's anchor
     * block.
     *
     * <p>The parent {@code AbstractEntityAIGuard.startWorkingAtOwnBuilding}
     * sets the building's temp-next-patrol-point to {@code buildingGuards.getPosition()}
     * — i.e. the tower's anchor, which is typically the floor block
     * INSIDE the tower structure. The patrol cycle then sends the
     * spider INTO the tower for the first move after work-start.
     *
     * <p>Redirect by computing a nearby exterior block (same logic
     * the materialise pipeline uses — ring scan around the tower
     * anchor at radius 3–6 with standing-clearance check) and using
     * that as the temp patrol point instead.
     *
     * <p>Falls back to the parent behaviour if the world isn't a
     * ServerLevel or no safe spot is found (rare — the materialise
     * path uses the same scan and almost always succeeds).
     */
    @Override
    protected com.minecolonies.api.entity.ai.statemachine.states.IAIState startWorkingAtOwnBuilding() {
        try {
            if (building != null
                    && world instanceof net.minecraft.server.level.ServerLevel) {
                // Use our patrol-point picker — same logic that drives
                // every subsequent patrol target — so the FIRST move
                // also respects the building-avoid buffer. The earlier
                // ring-3 materialise spot sits INSIDE the buffer
                // around the tower itself; this lands ≥22 blocks out.
                net.minecraft.core.BlockPos exterior = randomPatrolPoint();
                if (exterior != null) {
                    com.minecolonies.api.colony.buildings.IGuardBuilding gb =
                            (com.minecolonies.api.colony.buildings.IGuardBuilding) building;
                    gb.setTempNextPatrolPoint(exterior);
                    return com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.DECIDE;
                }
            }
        } catch (Throwable t) {
            // Defensive — guard AI swallows exceptions silently
            // (see AbstractAISkeleton.onException), so log loudly
            // before falling back to parent. Otherwise a redirect
            // failure would manifest as "spider keeps walking into
            // tower" with no log trail.
            org.slf4j.LoggerFactory.getLogger(EntityAIBeastGuard.class)
                    .warn("[TM] beast-guard startWorking redirect failed — using parent fallback",
                            t);
        }
        return super.startWorkingAtOwnBuilding();
    }

    @Override
    protected IAIState decide() {
        // Lock to PATROL. Skips the standard GuardTaskSetting dispatch
        // (which can route to GUARD / FOLLOW / PATROL_MINE depending on
        // the building's setting). Beasts only patrol.
        //
        // The standard decide() also handles combat-leave actions
        // (equipInventoryArmor, stopUsingItem, regularActionTimer
        // bookkeeping) and the rally-banner branch. We skip all of
        // that for Stage 1 to keep the override minimal; if Stage 2+
        // wants rally-banner support for beasts, this override should
        // delegate to super() for the non-task-mode pieces.
        return patrol();
    }

    /**
     * Stage L3 polish — keep patrol on the EXTERIOR. The parent
     * {@code randomPatrolPoint()} returns
     * {@code building.getColony().getServerBuildingManager().getRandomBuilding(predicate)}
     * — i.e. a BUILDING'S anchor block, which is typically INSIDE the
     * structure. The spider then paths into the interior and gets
     * stuck (5-wide visual + narrow doorways).
     *
     * <p>Replace with a position OUTSIDE the picked building: take
     * the building anchor + a random horizontal offset of 6 blocks
     * (just past a typical 5×5 building footprint), then walk the Y
     * up to the world-surface heightmap so we don't path inside the
     * floor.
     *
     * <p>Result: spider patrols the colony PERIMETER, visiting open
     * ground near each building rather than the building interiors.
     */
    @Override
    protected net.minecraft.core.BlockPos randomPatrolPoint() {
        // Stage L3 redesign — patrol the TOWER's exterior, not building
        // anchors anywhere in the colony.
        //
        // Old approach (super.randomPatrolPoint() returns a random
        // building's anchor; we offset by 6) failed because:
        //  1. A 6-block offset is INSIDE the footprint of larger
        //     buildings (a level-4 house can be 9+ wide).
        //  2. Even when the patrol POINT is outside, MC's A* pathfinder
        //     happily routes through other buildings to reach it — so
        //     the spider visibly walks through interiors mid-route.
        //
        // New approach: ignore other buildings entirely. Pick a random
        // point in a ring 8–14 blocks from the guard's TOWER, snapped
        // to world surface, and rejected if it lands inside any
        // building's bounding box. Up to 6 retries.
        //
        // Result: patrol points always land on open ground around the
        // tower. The spider circles its own tower instead of touring
        // the colony — narrower beat, but visually correct (it never
        // walks into structures).
        if (!(world instanceof net.minecraft.server.level.ServerLevel sl)) {
            return super.randomPatrolPoint();
        }
        net.minecraft.core.BlockPos towerAnchor = building.getPosition();

        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = worker.getRandom().nextDouble() * Math.PI * 2.0;
            // Radius 22–32: comfortably outside the buffered avoid
            // ring of any L1–L5 building (worst-case half-extent ~16
            // + BUILDING_AVOID_BUFFER = ~22), still close enough to
            // the tower that the spider's beat stays defensive.
            double radius = 22.0 + worker.getRandom().nextDouble() * 10.0;
            int x = towerAnchor.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = towerAnchor.getZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = sl.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    new net.minecraft.core.BlockPos(x, 0, z)).getY();
            net.minecraft.core.BlockPos candidate = new net.minecraft.core.BlockPos(x, y, z);

            if (!isTooCloseToBuilding(candidate)) {
                return candidate;
            }
        }
        // All 6 candidates landed inside building buffer rings. Fall
        // back: pick the angle whose candidate is FURTHEST from the
        // nearest building anchor, so we at least move toward the most
        // open direction available. Last resort before returning the
        // tower anchor.
        net.minecraft.core.BlockPos bestFallback = null;
        double bestDist = -1.0;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = (Math.PI * 2.0 / 8.0) * attempt;
            double radius = 22.0;
            int x = towerAnchor.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = towerAnchor.getZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = sl.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    new net.minecraft.core.BlockPos(x, 0, z)).getY();
            net.minecraft.core.BlockPos candidate = new net.minecraft.core.BlockPos(x, y, z);
            double d = distanceToNearestBuildingSqr(candidate);
            if (d > bestDist) {
                bestDist = d;
                bestFallback = candidate;
            }
        }
        return bestFallback != null ? bestFallback : towerAnchor;
    }

    /**
     * Conservative "is this point too close to a building" check.
     *
     * <p>MineColonies' {@code IBuilding} API in this version doesn't
     * expose corners publicly (just {@code calculateCorners()} as
     * package internals). Without the precise BB, we approximate: a
     * building's footprint scales roughly with its building level —
     * an L1 hut is ~5×5, an L5 manor can be ~13×13. Use a
     * {@code 6 + 2 * level} block half-extent square centred on each
     * building's anchor as a worst-case footprint estimate, then add
     * {@link #BUILDING_AVOID_BUFFER} as an extra keep-away ring so
     * the spider never PATHS along a building wall.
     *
     * <p>Iteration cost is fine — colonies have at most ~50 buildings
     * and we check at most 6 + 8 candidates per patrol pick.
     */
    private boolean isTooCloseToBuilding(net.minecraft.core.BlockPos pos) {
        try {
            for (Object obj
                    : building.getColony().getServerBuildingManager().getBuildings().values()) {
                if (!(obj instanceof com.minecolonies.api.colony.buildings.IBuilding b)) continue;
                net.minecraft.core.BlockPos anchor = b.getPosition();
                int half = 6 + 2 * Math.max(1, b.getBuildingLevel()) + BUILDING_AVOID_BUFFER;
                if (Math.abs(pos.getX() - anchor.getX()) <= half
                        && Math.abs(pos.getZ() - anchor.getZ()) <= half) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // Defensive — if the colony/building accessors change in a
            // future MC version, fail open (treat as outside). Worst
            // case is occasional interior patrol, not a crash.
        }
        return false;
    }

    /**
     * Chebyshev-distance² to the closest building's anchor.
     * Used by the patrol-point fallback to score "which random angle
     * lands furthest from any building" — squared form avoids the
     * sqrt while preserving ordering.
     */
    private double distanceToNearestBuildingSqr(net.minecraft.core.BlockPos pos) {
        double best = Double.MAX_VALUE;
        try {
            for (Object obj
                    : building.getColony().getServerBuildingManager().getBuildings().values()) {
                if (!(obj instanceof com.minecolonies.api.colony.buildings.IBuilding b)) continue;
                net.minecraft.core.BlockPos anchor = b.getPosition();
                double dx = pos.getX() - anchor.getX();
                double dz = pos.getZ() - anchor.getZ();
                double d = dx * dx + dz * dz;
                if (d < best) best = d;
            }
        } catch (Throwable ignored) {
            // Defensive — return 0 so the candidate scores lowest
            // (treat as "near a building", picker will choose
            // alternatives if any).
            return 0.0;
        }
        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    @Override
    public void guardMovement() {
        // No-op. The default implementation drifts back toward the
        // guard pos (buildingGuards.getGuardPos(worker)) — i.e. the
        // tower the beast is bound to. We want constant patrol, not
        // drift-back. Skipping this entirely keeps the beast on its
        // patrol targets.
    }

    @Override
    public void equipInventoryArmor() {
        // No-op. Stage L3 hotfix #3 (companion to the atBuildingActions
        // fix below) — the parent
        // {@link com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIFight#equipInventoryArmor}
        // reads {@code itemsNeeded.get(buildingLevel - 1)} for the
        // armor-equip loop. Same empty-list trap as atBuildingActions:
        // {@code EntityAIKnight} populates itemsNeeded; we don't (beasts
        // have no inventory). Calling get(0) on the empty list throws
        // IndexOutOfBoundsException, silently swallowed by
        // {@link com.minecolonies.core.entity.ai.workers.AbstractAISkeleton#onException}
        // (literal {@code { return; }}). State machine stuck in
        // PREPARING → no patrol, no combat.
        //
        // {@code prepare()} calls equipInventoryArmor BEFORE
        // atBuildingActions, so this is actually the FIRST throw point
        // — the L3-hotfix-2 atBuildingActions override never got a
        // chance to run because we crashed earlier in the prepare flow.
        // Both methods now need the override.
    }

    @Override
    protected void atBuildingActions() {
        // No-op. Stage L3 hotfix #2 — the parent
        // {@link com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIFight#atBuildingActions}
        // reads {@code itemsNeeded.get(buildingLevel - 1)} to equip gear
        // from the tower's chest. {@code EntityAIKnight} populates
        // {@code itemsNeeded} in its constructor with sword / armor
        // requirements; we do NOT (beasts have no inventory, no gear
        // to equip from the tower's chest). Inheriting the parent
        // behaviour means the parent's {@code List.get(level - 1)} on
        // the empty list throws IndexOutOfBoundsException.
        //
        // {@link com.minecolonies.core.entity.ai.workers.AbstractAISkeleton#onException}
        // is a no-op — exceptions are SILENTLY SWALLOWED. The state
        // machine stays in PREPARING and never advances to DECIDE.
        // Result: beast never patrols and never enters combat. The
        // bug surfaces post-construction (so the L3 crash-fix didn't
        // catch it — that fixed AI construction; this fixes the first
        // tick AFTER construction).
        //
        // Beasts have no legitimate work for this method — no items to
        // equip, no chest to dump into. No-op is correct, not a stub.
    }
}
