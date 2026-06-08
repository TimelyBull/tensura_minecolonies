package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.util.SubordinateHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
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
 * PATROL is added <i>into</i> Tensura's existing command set, activated the
 * same way: <b>sneak + right-click + empty main hand</b> (the gesture that
 * already reaches {@code cycleCommands} for humanoid subordinates — plain
 * right-click opens their inventory / mounts a mount). From
 * {@link ExampleMod#onEntityInteract} we intercept ONLY the two edges that
 * touch PATROL — STAY → PATROL ({@link #beginPatrol}) and PATROL → FOLLOW
 * ({@link #exitPatrolToFollow}). The other two edges (FOLLOW → WANDER,
 * WANDER → STAY) are left to pass through to Tensura's native
 * {@code cycleCommands}, which emits its own AQUA command message; our
 * PATROL / FOLLOW messages reuse that same AQUA style so the four commands
 * look identical as you cycle. State is derived from the entity's real flags
 * plus the {@link Attachments#PATROL_ORDER} attachment, so it never drifts
 * out of sync with Tensura's own cycle.
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

    /** Colour Tensura uses for its native pet command messages
     *  (follow/wander/stay) — matched so the PATROL message looks the same. */
    private static final net.minecraft.ChatFormatting PET_MESSAGE_COLOR =
            net.minecraft.ChatFormatting.AQUA;

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
    /** Re-anchor the targeting leash ({@code WANDER_POS}) to the mob only once
     *  it has drifted this far (blocks²) from the last anchor — WANDER_POS is
     *  synced entity data, so this avoids a packet every tick while walking.
     *  8 blocks² ≈ keeps the 20-block leash comfortably centred on the mob. */
    private static final double LEASH_REANCHOR_DIST_SQR = 64.0;
    /** Acquisition tether: a patrolling subordinate only takes targets within
     *  the colony claim + this many blocks, so it engages threats at the
     *  outskirts but doesn't lock onto mobs far outside the colony. */
    private static final int TARGET_AREA_BUFFER = 8;
    /** Recall tether: if a chase carries the mob beyond the colony claim + this
     *  many blocks, it abandons the target and heads back to the outskirts.
     *  Larger than the acquisition buffer so a brief edge-chase isn't cut short
     *  but a runaway chain-aggro is reined in. */
    private static final int STRAY_RECALL_BUFFER = 24;

    /** Tensura's "always hostile" entity-type tag (vanilla hostiles + Tensura
     *  beasts). Built by ResourceLocation rather than referencing Tensura's
     *  constant so we're decoupled from its field names and benefit from any
     *  datapack additions. */
    private static final net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> HOSTILE_MONSTER_TAG =
            net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ENTITY_TYPE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", "hostile_monster"));

    // =================================================================
    // Command cycle — driven from the cycleCommands mixin
    // =================================================================

    /**
     * Insert the PATROL command into Tensura's native command cycle. Called
     * from {@code ISubordinateCommandMixin} at the HEAD of
     * {@code ISubordinate.cycleCommands} — i.e. the exact point Tensura reaches
     * only after its own gating (sneak + right-click, item-priority for
     * food/potions, inventory-vs-command for humanoids). By hooking here PATROL
     * is activated identically to the native commands, with no empty-hand
     * requirement and no need to reproduce that gating ourselves.
     *
     * We handle only the two edges that touch PATROL and let the rest run
     * natively:
     * <ul>
     *   <li>PATROL → FOLLOW, and</li>
     *   <li>STAY → PATROL.</li>
     * </ul>
     *
     * @return true if we took an edge (caller cancels the native cycle); false
     *         to let Tensura's {@code cycleCommands} run FOLLOW → WANDER /
     *         WANDER → STAY unchanged.
     */
    public static boolean handlePatrolCycle(Mob mob, Player player) {
        if (mob.level().isClientSide()) return false;
        // Only NAMED subordinates get the patrol command; unnamed ones keep
        // the vanilla 3-state cycle.
        if (!mob.hasCustomName()) return false;
        if (!(mob instanceof ISubordinate sub)) return false;

        if (isPatrolling(mob)) {
            exitPatrolToFollow(mob, player);
            return true;
        }
        if (sub.isOrderedToSit()) {
            beginPatrol(mob, player);
            return true;
        }
        return false; // FOLLOW → WANDER / WANDER → STAY: let native handle it
    }

    /**
     * Is this subordinate currently following the PATROL command?
     */
    public static boolean isPatrolling(Mob mob) {
        return mob.hasData(Attachments.PATROL_ORDER.get());
    }

    /**
     * Enter the PATROL state (the STAY → PATROL edge of the command cycle):
     * pin the order to the colony nearest the PLAYER (so it keeps patrolling
     * that colony after the player leaves), put the mob in a "wandering,
     * aggressive" stance so follow is off and hostiles are engaged, and attach
     * the standing order.
     *
     * The message uses the same AQUA style as Tensura's native follow/wander/
     * stay command messages so the four commands look identical as you cycle.
     */
    public static void beginPatrol(Mob mob, Player player) {
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
        // patrolling (the friendly-race / citizen veto still protects allies).
        SubordinateHelper.setAggressive(mob);

        mob.setData(Attachments.PATROL_ORDER.get(),
                new PatrolOrder(colony.getID(), colony.getDimension().location()));
        // Seed the first patrol point immediately so there's no idle frame.
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        player.displayClientMessage(
                Component.translatable("tensura_minecolonies.command.patrol",
                                mob.getDisplayName(), colony.getName())
                        .withStyle(PET_MESSAGE_COLOR),
                true);
        ExampleMod.LOGGER.info("[TM] '{}' now patrolling outskirts of colony '{}' (id={})",
                mob.getName().getString(), colony.getName(), colony.getID());
    }

    /**
     * Leave the PATROL state via the PATROL → FOLLOW edge: drop the standing
     * order, clear the patrol walk target, and set the mob back to FOLLOW with
     * the native (AQUA) follow message — so this edge looks exactly like
     * Tensura's own STAY → FOLLOW step.
     */
    public static void exitPatrolToFollow(Mob mob, Player player) {
        mob.removeData(Attachments.PATROL_ORDER.get());
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        SubordinateHelper.setFollow(mob);
        sendPetMessage(player, "tensura.message.pet.follow", mob);
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

        // Keep the targeting leash centred on the PATROLLER, not the owner.
        // While a subordinate is wandering, Tensura's ISubordinate.shouldTarget
        // only allows targets within `tamedWanderRadius` (20) blocks of
        // getWanderPos(); SubordinateHelper.setWander left that at the owner's
        // position, so a patroller far from its owner could neither acquire
        // hostiles (aggressive proactive branch) NOR retaliate when hit —
        // it ignored nearby always-hostile mobs. Re-anchoring the leash to the
        // mob's own position makes it detect/engage anything within 20 blocks
        // of where it actually is. Throttled by distance because WANDER_POS is
        // synced entity data.
        BlockPos here = mob.blockPosition();
        BlockPos leash = sub.getWanderPos();
        if (leash == null || leash.distSqr(here) > LEASH_REANCHOR_DIST_SQR) {
            sub.setWanderPos(here);
        }

        // Resolve the pinned colony up front — the tether/recall below needs it
        // even while the mob is in combat.
        PatrolOrder order = mob.getData(Attachments.PATROL_ORDER.get());
        if (!level.dimension().equals(order.dimensionKey())) return;
        IColony colony = IColonyManager.getInstance().getColonyByWorld(order.colonyId(), level);
        if (colony == null) return; // colony deleted / not loaded — idle, keep order

        // Recall tether: if a chase (or a chain of chases) has carried the mob
        // well outside the colony, abandon the target and head back to the
        // outskirts so it keeps defending the colony instead of wandering off.
        // Runs even during combat, hence before the combat yield below.
        if (!isWithinColony(colony, level, here, STRAY_RECALL_BUFFER)) {
            SubordinateHelper.removeTarget(mob);
            BlockPos back = outskirtsReturnTarget(colony, level, mob);
            mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(back, PATROL_SPEED, CLOSE_ENOUGH));
            return;
        }

        // Combat handling. Yield to the native fight behaviour ONLY for a valid
        // patrol target — a genuine hostile that is still within the colony
        // area. Drop anything else: a peaceful mob (e.g. a pig that slipped
        // through, or a target set by a path that bypassed the acquisition
        // veto) or a hostile that has fled too far from the colony. Dropping it
        // here falls through to normal patrolling instead of chasing it.
        LivingEntity tgt = mob.getTarget();
        if (tgt != null && tgt.isAlive()) {
            boolean valid = isHostileThreat(mob, tgt)
                    && isWithinColony(colony, level, tgt.blockPosition(), STRAY_RECALL_BUFFER);
            if (valid) return;                  // legitimate fight — let the brain drive it
            SubordinateHelper.removeTarget(mob); // pig / runaway target — abandon it
        }

        // Peaceful patrol (no current target): clear any lingering persistent
        // anger. Tensura's run-vs-walk animation plays "run" while moving and
        // isAngry() (NeutralMob persistent-anger timer) is set; that timer
        // outlives a finished/lost fight, so a calm patroller moving at walk
        // speed would otherwise show the run animation. Clearing it here keeps
        // the animation consistent with the patrol (walk) speed; a fresh target
        // re-arms anger immediately and the native fight behaviour then drives
        // the run animation at chase speed.
        if (mob instanceof net.minecraft.world.entity.NeutralMob nm
                && nm.getRemainingPersistentAngerTime() > 0) {
            nm.stopBeingAngry();
        }

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
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
            BlockPos p = outskirtsPointAlong(colony, level, mob, angle);
            if (p != null) return p;
        }
        return null;
    }

    /**
     * An outskirts point on the bearing from the colony centre toward the mob —
     * i.e. the nearest stretch of outskirts to where the (strayed) mob is now.
     * Used by the recall path so a pulled-back patroller returns to the colony
     * edge it wandered off from rather than crossing to the far side or sitting
     * in the centre. Falls back to a random outskirts point, then the centre.
     */
    private static BlockPos outskirtsReturnTarget(IColony colony, ServerLevel level, Mob mob) {
        BlockPos center = colony.getCenter();
        double angle = Math.atan2(mob.getZ() - center.getZ(), mob.getX() - center.getX());
        BlockPos p = outskirtsPointAlong(colony, level, mob, angle);
        if (p != null) return p;
        p = computeOutskirtsTarget(colony, level, mob);
        return p != null ? p : center;
    }

    /**
     * Place a patrol point in the outer 70–95% band of the colony's claimed
     * radius along {@code angle}, snapped to the surface and rejected over
     * water. Colonies claim whole chunks, so the boundary is found by marching
     * outward in one-chunk steps while {@code isCoordInColony} holds (no
     * hardcoded radius). Returns null if the colony barely extends this way or
     * the point is water / out of claim.
     */
    private static BlockPos outskirtsPointAlong(IColony colony, ServerLevel level, Mob mob, double angle) {
        BlockPos center = colony.getCenter();
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);

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
        if (boundary < 16) return null; // colony barely extends this way

        double frac = BAND_INNER + mob.getRandom().nextDouble() * (BAND_OUTER - BAND_INNER);
        int radius = Math.max(8, (int) Math.round(boundary * frac));
        int x = center.getX() + (int) Math.round(dx * radius);
        int z = center.getZ() + (int) Math.round(dz * radius);

        BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        if (isWater(level, surface)) return null;
        if (!colony.isCoordInColony(level, surface)) return null;
        return surface;
    }

    // =================================================================
    // Targeting policy (colony tether + hostile-only) — called from the
    // ExampleMod.onSubordinateChangeTarget veto for patrolling subordinates
    // =================================================================

    /**
     * Whether a patrolling subordinate may target {@code candidate}. A target
     * is allowed only if it is a genuine hostile threat AND lies within the
     * patrolled colony's area. This keeps the patrol fighting actual enemies
     * (not peaceful animals like pigs) and stops it chasing mobs off into the
     * distance away from the colony it's meant to defend.
     */
    public static boolean isPatrolTargetAllowed(Mob mob, LivingEntity candidate) {
        PatrolOrder order = mob.getData(Attachments.PATROL_ORDER.get());
        if (order == null) return true;
        if (!(mob.level() instanceof ServerLevel level)) return true;
        if (!level.dimension().equals(order.dimensionKey())) return false;
        IColony colony = IColonyManager.getInstance().getColonyByWorld(order.colonyId(), level);
        if (colony == null) return true; // can't resolve — don't add a restriction

        if (!isHostileThreat(mob, candidate)) return false;                       // no pigs / neutrals
        return isWithinColony(colony, level, candidate.blockPosition(), TARGET_AREA_BUFFER); // tether
    }

    /**
     * A target counts as a hostile threat if it is an always-hostile mob
     * (Tensura's {@code tensura:hostile_monster} tag — covers vanilla zombies/
     * skeletons/etc. AND Tensura beasts), or if it is currently attacking the
     * patroller, one of its allies, or a colony citizen (e.g. a normally-neutral
     * wild orc that turned on the colony). Peaceful animals and idle neutral
     * mobs are NOT threats.
     */
    private static boolean isHostileThreat(Mob patroller, LivingEntity target) {
        if (target.getType().builtInRegistryHolder().is(HOSTILE_MONSTER_TAG)) return true;
        if (target instanceof Mob m) {
            LivingEntity victim = m.getTarget();
            if (victim != null && (victim == patroller
                    || victim instanceof AbstractEntityCitizen
                    || SubordinateHelper.isAlly(victim, patroller))) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code pos} is inside the colony's claimed chunks, or within
     *  {@code buffer} blocks of the claim edge (measured toward the centre). */
    private static boolean isWithinColony(IColony colony, ServerLevel level, BlockPos pos, int buffer) {
        if (colony.isCoordInColony(level, pos)) return true;
        if (buffer <= 0) return false;
        BlockPos center = colony.getCenter();
        double dx = center.getX() - pos.getX();
        double dz = center.getZ() - pos.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return true;
        BlockPos shifted = pos.offset(
                (int) Math.round(dx / len * buffer), 0, (int) Math.round(dz / len * buffer));
        return colony.isCoordInColony(level, shifted);
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

    /** Send a Tensura "pet" command message (above the hotbar) in the same
     *  AQUA colour Tensura uses for its native follow/wander/stay feedback
     *  ({@code ISubordinate.cycleCommands} applies {@code Style.withColor(AQUA)}). */
    private static void sendPetMessage(Player player, String key, Mob mob) {
        player.displayClientMessage(
                Component.translatable(key, mob.getDisplayName()).withStyle(PET_MESSAGE_COLOR),
                true);
    }
}
