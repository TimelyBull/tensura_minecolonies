package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.util.SubordinateHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * "Patrol Colony Outskirts" — a fourth right-click command added to the
 * native Tensura subordinate command cycle.
 *
 * <h2>Design (see docs/decisions.md → "Patrol Colony Outskirts command")</h2>
 *
 * The mob STAYS a Tensura subordinate the whole time — its own entity, its
 * own SmartBrainLib AI, its native pathfinding and combat. It does NOT become
 * a MineColonies citizen or guard. We only:
 * <ol>
 *   <li>insert a fourth state into the right-click command cycle
 *       (FOLLOW → WANDER → STAY → <b>PATROL</b> → FOLLOW), and</li>
 *   <li>while PATROL is active, feed the brain's vanilla {@code WALK_TARGET}
 *       memory a stream of points in the outer ring of the target colony,
 *       skipping water.</li>
 * </ol>
 *
 * <h3>Command cycle (no mixin)</h3>
 * The cycle is driven from {@link ExampleMod#onEntityInteract} on a
 * <b>sneak + right-click + empty main hand</b> of a named, owned, tame
 * {@code ISubordinate} (see {@link #handleCommandCycle}). Sneak is used
 * universally so we never hijack the plain right-click (which opens the
 * inventory screen for humanoids, mounts a mount, etc.). The cycle reads the
 * entity's real flags ({@code isWandering}/{@code isOrderedToSit}) plus our
 * {@link Attachments#PATROL_ORDER} attachment, so it stays in sync even if
 * the player also uses Tensura's native plain-click cycle on a beast.
 *
 * <h3>Patrol movement (brain-native, no mixin)</h3>
 * Every Tensura subordinate has the {@code MoveToWalkTarget} core brain task,
 * which paths the mob to whatever is in {@code WALK_TARGET}. We keep that
 * memory populated from {@link #onEntityTick}. Tensura's idle wander
 * ({@code SetRandomWalkTarget}) only fires when {@code WALK_TARGET} is
 * <i>absent</i>, and follow is disabled while {@code isWandering} is true, so
 * a continuously-populated memory cleanly suppresses native idle movement
 * without patching Tensura.
 *
 * <h3>Combat coexistence</h3>
 * Entering PATROL sets the combat stance to aggressive so the brain's target
 * sensors pick up hostiles; while the mob has an attack target the driver
 * yields entirely (native fight behaviours drive movement), then resumes
 * patrolling once the target is gone. The existing colony-citizen targeting
 * veto keeps it from attacking friendly citizens.
 *
 * <h3>Persistence</h3>
 * {@link PatrolOrder} is a serialized NeoForge attachment, so the standing
 * order survives the mob unloading/reloading and relog; {@code EntityTickEvent}
 * resumes patrol automatically on reload.
 */
public final class SubordinatePatrol {

    /** Public no-arg constructor — an instance is registered on the NeoForge
     *  game event bus for the {@link #onEntityTick} handler. */
    public SubordinatePatrol() {}

    /** Walk speed multiplier handed to the brain. 1.0 = the mob's normal
     *  movement speed; patrolling is a relaxed activity so we keep it at 1. */
    private static final float PATROL_SPEED = 1.0f;
    /** How close (blocks) counts as "reached this patrol point". */
    private static final int CLOSE_ENOUGH = 2;
    /** Outer search cap for the colony boundary march, in chunks. The march
     *  self-adapts to the real claimed boundary below this; the cap only
     *  bounds the search for unusually large colonies. 16 chunks = 256 blocks. */
    private static final int MAX_SEARCH_CHUNKS = 16;
    /** Re-pick the patrol point at most this often (ticks) to bound path
     *  recomputation; between picks the mob just walks to its current point. */
    private static final int REPICK_INTERVAL = 10;
    /** Inner edge of the outskirts band, as a fraction of the boundary radius
     *  in the chosen direction. Below this is "the middle of the colony" and
     *  is intentionally avoided — we want the OUTER area. */
    private static final double BAND_INNER = 0.70;
    /** Outer edge of the band, as a fraction of the boundary radius. Kept
     *  below 1.0 so the point stays a few blocks inside the claim. */
    private static final double BAND_OUTER = 0.95;

    // =================================================================
    // Command cycle — called from ExampleMod.onEntityInteract
    // =================================================================

    /** @return true if this entity is a named subordinate owned by the
     *  player — the population the patrol command is offered on. */
    public static boolean isNamedSubordinateOf(net.minecraft.world.entity.Entity entity, Player player) {
        if (!(entity instanceof Mob mob)) return false;
        if (!(entity instanceof ISubordinate sub)) return false;
        return sub.isTame() && sub.isOwnedBy(player) && mob.hasCustomName();
    }

    /**
     * Advance the named subordinate one step along the extended command
     * cycle FOLLOW → WANDER → STAY → PATROL → FOLLOW.
     *
     * The first three edges reuse Tensura's own state setters and message
     * keys, so they behave exactly like the native cycle. The two edges that
     * touch PATROL are ours. State is derived from the entity's real flags,
     * never a separate counter, so it can't drift out of sync.
     */
    public static void handleCommandCycle(Mob mob, ServerPlayer player) {
        ISubordinate sub = (ISubordinate) mob;

        if (mob.hasData(Attachments.PATROL_ORDER.get())) {
            // PATROL → FOLLOW
            stopPatrol(mob);
            SubordinateHelper.setFollow(mob);
            sendPetMessage(player, "tensura.message.pet.follow", mob);
            return;
        }
        if (sub.isOrderedToSit()) {
            // STAY → PATROL
            beginPatrol(mob, player);
            return;
        }
        if (sub.isWandering()) {
            // WANDER → STAY
            SubordinateHelper.setStay(mob);
            sendPetMessage(player, "tensura.message.pet.stay", mob);
            return;
        }
        // FOLLOW → WANDER
        SubordinateHelper.setWander(mob);
        sendPetMessage(player, "tensura.message.pet.wander", mob);
    }

    /**
     * Enter the PATROL state: pin the order to the colony nearest the PLAYER
     * (so it keeps patrolling that colony after the player leaves), put the
     * mob in a "wandering, aggressive" stance so follow is off and hostiles
     * are engaged, and attach the standing order.
     */
    private static void beginPatrol(Mob mob, ServerPlayer player) {
        Level level = player.level();
        IColony colony = IColonyManager.getInstance()
                .getClosestColony(level, player.blockPosition());
        if (colony == null) {
            // Nothing to patrol — advise and fall through to FOLLOW so the
            // cycle still moves and the player isn't stuck on STAY.
            ExampleMod.sendAdvisoryNotice(player, "No colony nearby to patrol — set to follow instead.");
            SubordinateHelper.setFollow(mob);
            sendPetMessage(player, "tensura.message.pet.follow", mob);
            return;
        }

        // WANDER stance: disables the follow-owner behaviour and clears any
        // current target; our tick driver overrides the wander target.
        SubordinateHelper.setWander(mob);
        // Aggressive combat stance so the brain engages hostiles while
        // patrolling (the friendly-citizen veto still protects colonists).
        SubordinateHelper.setAggressive(mob);

        mob.setData(Attachments.PATROL_ORDER.get(),
                new PatrolOrder(colony.getID(), colony.getDimension().location()));
        // Seed the first patrol point immediately so there's no idle frame.
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        player.displayClientMessage(
                Component.translatable("tensura_minecolonies.command.patrol",
                        mob.getDisplayName(), colony.getName()),
                true);
        ExampleMod.LOGGER.info("[TM] '{}' now patrolling outskirts of colony '{}' (id={})",
                mob.getName().getString(), colony.getName(), colony.getID());
    }

    /** Leave the PATROL state: drop the standing order and clear the patrol
     *  walk target so the mob doesn't keep walking to the last point. */
    private static void stopPatrol(Mob mob) {
        mob.removeData(Attachments.PATROL_ORDER.get());
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    // =================================================================
    // Patrol driver — per-entity server tick
    // =================================================================

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(mob instanceof ISubordinate sub)) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!mob.hasData(Attachments.PATROL_ORDER.get())) return;

        // Auto-cancel if the player changed the command outside our cycle
        // (e.g. Tensura's native plain-click cycle on a beast moved it to
        // STAY/FOLLOW). beginPatrol leaves the mob wandering-and-not-sitting;
        // any deviation means the order is no longer in force.
        if (sub.isOrderedToSit() || !sub.isWandering()) {
            mob.removeData(Attachments.PATROL_ORDER.get());
            return;
        }

        // In combat: yield completely. Native fight behaviours own movement
        // and the WALK_TARGET memory; we resume patrolling once it clears.
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return;

        PatrolOrder order = mob.getData(Attachments.PATROL_ORDER.get());
        // The order is pinned to a specific colony in a specific dimension.
        if (!level.dimension().equals(order.dimensionKey())) return;
        IColony colony = IColonyManager.getInstance().getColonyByWorld(order.colonyId(), level);
        if (colony == null) return; // colony deleted / not loaded — idle, keep order

        boolean hasTarget = mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
        if (hasTarget) {
            if (!navigationStalled(mob)) return;              // happily walking — leave it
            if (mob.tickCount % REPICK_INTERVAL != 0) return; // target unreachable — throttle retries
        }
        // Either the mob just reached its point (memory cleared by the brain
        // this same tick) or its target is unreachable and a retry is due.
        // Refilling immediately on arrival closes the window in which
        // Tensura's idle wander could grab the empty WALK_TARGET memory.
        BlockPos target = computeOutskirtsTarget(colony, level, mob);
        if (target == null) return; // no dry in-colony point found this pass — retry next tick
        mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, PATROL_SPEED, CLOSE_ENOUGH));
    }

    /** True when the mob has stopped pathing but isn't actually at its walk
     *  target — i.e. the target is unreachable and we should pick another. */
    private static boolean navigationStalled(Mob mob) {
        if (!mob.getNavigation().isDone()) return false; // still moving
        var mem = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (mem.isEmpty()) return true;
        BlockPos t = mem.get().getTarget().currentBlockPosition();
        return mob.blockPosition().distSqr(t) > (double) (CLOSE_ENOUGH + 1) * (CLOSE_ENOUGH + 1);
    }

    // =================================================================
    // Outskirts geometry
    // =================================================================

    /**
     * Pick a point in the OUTER ring of the colony's claimed area, avoiding
     * water.
     *
     * Colonies claim whole chunks, so we don't need a hardcoded radius: from
     * the colony centre we march outward along a random bearing in 16-block
     * (one-chunk) steps while {@code isCoordInColony} stays true, which finds
     * the real claimed boundary in that direction. The patrol point is placed
     * in the outer band of that distance (see {@link #BAND_INNER}/
     * {@link #BAND_OUTER}). A handful of bearings are tried so a water edge in
     * one direction doesn't strand the mob.
     *
     * @return a dry, in-colony surface position in the outer ring, or null if
     *         none of the sampled bearings produced one.
     */
    private static BlockPos computeOutskirtsTarget(IColony colony, ServerLevel level, Mob mob) {
        BlockPos center = colony.getCenter();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);

            // March outward to find the claimed boundary in this direction.
            int boundary = 0;
            for (int step = 1; step <= MAX_SEARCH_CHUNKS; step++) {
                int r = step * 16;
                BlockPos probe = new BlockPos(
                        center.getX() + (int) Math.round(dx * r),
                        center.getY(),
                        center.getZ() + (int) Math.round(dz * r));
                if (colony.isCoordInColony(level, probe)) {
                    boundary = r;
                } else {
                    break;
                }
            }
            if (boundary < 16) continue; // colony barely extends this way

            // Place the point in the outer band of the boundary distance.
            double frac = BAND_INNER + mob.getRandom().nextDouble() * (BAND_OUTER - BAND_INNER);
            int radius = Math.max(8, (int) Math.round(boundary * frac));
            int x = center.getX() + (int) Math.round(dx * radius);
            int z = center.getZ() + (int) Math.round(dz * radius);

            // Snap to the surface and reject water.
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (isWater(level, surface)) continue;
            if (!colony.isCoordInColony(level, surface)) continue;
            return surface;
        }
        return null;
    }

    /** True if the surface position (or the block it stands on) is water —
     *  the patrol skips these so it never wades into ponds / oceans. */
    private static boolean isWater(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER)
                || level.getFluidState(pos.below()).is(FluidTags.WATER);
    }

    // =================================================================
    // Helpers
    // =================================================================

    /** Send a Tensura "pet" command message (above the hotbar), matching the
     *  native follow/wander/stay feedback. */
    private static void sendPetMessage(ServerPlayer player, String key, Mob mob) {
        player.displayClientMessage(Component.translatable(key, mob.getDisplayName()), true);
    }
}
