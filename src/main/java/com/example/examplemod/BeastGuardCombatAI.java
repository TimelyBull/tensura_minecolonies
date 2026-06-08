package com.example.examplemod;

import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import com.minecolonies.core.entity.ai.workers.guard.KnightCombatAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;

/**
 * Stage L3a — beast-guard combat AI. Subclass of MineColonies'
 * {@link KnightCombatAI} that overrides the hardcoded attack distance
 * so the citizen-guard's functional reach matches the spider's visual
 * footprint.
 *
 * <p><b>Why this exists</b>: a knight-spider citizen has a humanoid
 * hitbox (0.6 wide — pinned by {@code Attributes.SCALE = 1.0} so MC
 * pathfinding stays tractable). MC pathfinding doesn't know the spider
 * is visually 5 wide. The vanilla {@link KnightCombatAI} uses
 * {@code getAttackDistance() = 2.0} which is tuned for a humanoid
 * citizen's swing reach. With a spider visual sitting on top of a 0.6
 * hitbox, the player sees the spider's legs reach a target it
 * "logically" cannot hit. Boosting the attack distance to 4.0 closes
 * that gap — the spider hits things its legs visually touch.
 *
 * <p>4.0 = half the spider's 5-wide footprint + a small margin for
 * partial coverage of its limbs. The combat AI also drives target
 * acquisition (via {@code isWithinPersecutionDistance} which composes
 * with the attack distance), so target-engagement range scales the
 * same way.
 *
 * <p>This is ALSO the fix for the Stage 1 bug where
 * {@link EntityAIBeastGuard} never instantiated a combat AI at all —
 * beasts inherited only the parent {@code AbstractEntityAIGuard}'s
 * machinery, which has no combat behaviour of its own. Without
 * constructing a combat AI subclass in the EntityAI constructor, the
 * beast had no `inCombat` action and effectively didn't fight. This
 * class is what {@link EntityAIBeastGuard} now constructs at init time.
 */
public class BeastGuardCombatAI extends KnightCombatAI {

    /** Reach in blocks. Tracks the VISUAL size, not the hitbox.
     *
     *  <p>The spider's visual and hitbox are now decoupled (see
     *  {@code ExampleMod.BEAST_RENDER_SCALE} vs
     *  {@code ExampleMod.BEAST_BODY_SCALE}). Render half-width is
     *  ~1.75 blocks (native 5/2 × {@code BEAST_RENDER_SCALE = 0.7}).
     *  Hitbox half-width is ~0.45 (humanoid 0.6/2 ×
     *  {@code BEAST_BODY_SCALE = 1.5}).
     *
     *  <p>{@code AttackMoveAI.isInDistanceForAttack} uses center-to-
     *  center distance against {@link #getAttackDistance()} — it
     *  ignores hitbox half-extents. To hit at the visual edge:
     *  {@code visualHalfWidth + targetHalfWidth + margin}
     *  ≈ 1.75 + 0.3 + 0.45 ≈ 2.5 blocks.
     *
     *  <p>2.75 gives a slight reach edge for the spider's mandible /
     *  leg arc. Tune with {@code BEAST_RENDER_SCALE}, not
     *  {@code BEAST_BODY_SCALE} — the hitbox size doesn't affect this
     *  math, only what the spider looks like it should reach.
     */
    public static final double BEAST_GUARD_ATTACK_DISTANCE = 2.75;

    public BeastGuardCombatAI(EntityCitizen worker,
                              ITickRateStateMachine<?> stateMachine,
                              AbstractEntityAIGuard parentAI) {
        super(worker, stateMachine, parentAI);

        // Stage L3 polish — register an AGGRESSIVE target scan.
        //
        // Disassembly of {@code TargetAI.<init>} shows two registered
        // transitions from NO_TARGET:
        //  - tickRate 5: {@code this::checkForTarget} (reactive — only
        //    fires when something already hit us and added itself to
        //    the threat table).
        //  - tickRate 80: {@code this::searchNearbyTarget} (proactive
        //    AABB scan — what actually finds passing mobs).
        //
        // 80 ticks = 4 seconds between proactive scans. With our
        // symmetric search-area override below, each scan covers the
        // full 360° — but the SPIDER still has up to 4 seconds of
        // "blindness" per cycle during which a mob can walk past
        // unmolested. User said: "guard walks past hostile mobs" even
        // after the symmetric-AABB fix.
        //
        // Add a THIRD transition: from NO_TARGET, every 10 ticks
        // (~0.5s), run {@code searchNearbyTarget}; if it found
        // something, transition to ATTACKING. 10-tick rate gives the
        // spider ~8× faster detection than a vanilla knight without
        // burning CPU on per-tick AABB queries.
        //
        // Using AITarget (= TickingTransition) with the same
        // {@code (state, condition, stateSupplier, tickRate)} signature
        // TargetAI's own scan transitions use.
        @SuppressWarnings({"unchecked", "rawtypes"})
        com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine sm
                = stateMachine;
        sm.addTransition(new com.minecolonies.api.entity.ai.statemachine.AITarget<>(
                com.minecolonies.api.entity.ai.combat.CombatAIStates.NO_TARGET,
                this::searchNearbyTarget,
                () -> com.minecolonies.api.entity.ai.combat.CombatAIStates.ATTACKING,
                10));
    }

    @Override
    protected double getAttackDistance() {
        return BEAST_GUARD_ATTACK_DISTANCE;
    }

    /**
     * Stage L3 polish — boost detection range. Parent default is 16
     * blocks. The spider is supposed to be a formidable guard;
     * 24-block scan gives it ~50% more reach than a vanilla knight.
     */
    @Override
    protected int getSearchRange() {
        return 24;
    }

    /**
     * Stage L3 polish — replace the parent's ASYMMETRIC search area
     * with a symmetric 360° AABB.
     *
     * <p>The parent {@code TargetAI.getSearchArea()} builds an AABB
     * biased in one of 4 random {@code Direction.from3DDataValue}
     * faces per scan. Each scan only covers ~one quadrant — full 360°
     * coverage takes ~4 scans = ~16 seconds. Mobs walking through
     * unscanned quadrants are invisible to the spider until a lucky
     * direction roll. User complaint: "guard walks past hostile mobs."
     *
     * <p>Symmetric AABB scans every direction simultaneously, so each
     * proactive scan (every 80 ticks via the searchTickRate passed in
     * {@code AttackMoveAI.<init>}) covers everything within range.
     * Combined with the bumped {@link #getSearchRange()}, the spider
     * spots hostiles much more reliably.
     *
     * <p>Vertical search is +/- {@link #getYSearchRange()} (parent
     * default; ~4 blocks). Adequate — the citizen-hitbox is humanoid,
     * mobs at the same Y will be caught.
     */
    @Override
    protected net.minecraft.world.phys.AABB getSearchArea() {
        net.minecraft.core.BlockPos pos = user.blockPosition();
        int xz = getSearchRange();
        int y = getYSearchRange();
        return new net.minecraft.world.phys.AABB(
                pos.getX() - xz, pos.getY() - y, pos.getZ() - xz,
                pos.getX() + xz, pos.getY() + y, pos.getZ() + xz);
    }

    /**
     * Stage L3 hotfix — beasts don't need a sword.
     *
     * <p>The parent {@link KnightCombatAI#canAttack()} searches the
     * citizen's {@code InventoryCitizen} for a sword via
     * {@code InventoryUtils.getFirstSlotOfItemHandlerContainingEquipment(...,
     * SWORD, ...)} and returns false if none is found. The beast's body
     * IS the weapon — there's no sword in the spider's (empty)
     * inventory, so the parent returns false, doAttack never fires,
     * and the spider ignores hostiles even when they're adjacent.
     *
     * <p>Always return true for beasts. The damage value comes from
     * {@link #getAttackDamage()} below which reads the entity's
     * {@code Attributes.ATTACK_DAMAGE} (scaled by Stage L3a's EP
     * multiplier on the spider). No held item required.
     */
    @Override
    public boolean canAttack() {
        return true;
    }

    /**
     * Stage L3 hotfix — beast damage comes from the entity attribute,
     * not from a held weapon.
     *
     * <p>The parent {@link KnightCombatAI#getAttackDamage()} starts at
     * 0.0 and only increases if the main-hand item is a recognised
     * weapon (sword/axe). With an empty hand the beast would deal 0
     * damage even if {@code canAttack} returned true.
     *
     * <p>Read directly from {@code Attributes.ATTACK_DAMAGE} on the
     * worker — that's the slot Stage L3a's {@code applyBeastLevelScaling}
     * writes the spider-baseline × EP-multiplier value into. So a
     * baseline knight spider (22 ATK × ~2× EP scale) hits for ~44 per
     * swing without needing any held weapon.
     */
    @Override
    protected double getAttackDamage() {
        return user.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
    }

    /** Last tick on which the spider performed a leap. Throttles the
     *  leap to once per {@link #LEAP_COOLDOWN_TICKS} so it doesn't spam. */
    private long lastLeapTick = Long.MIN_VALUE;

    /** ~4 seconds between leap attempts. */
    private static final long LEAP_COOLDOWN_TICKS = 80L;

    /** Chance per doAttack to attempt a leap (when off-cooldown and out
     *  of melee range). 0.35 = ~1-in-3 swings. */
    private static final double LEAP_CHANCE = 0.35;

    /**
     * Stage L3 polish — apply the knight spider's signature
     * leap-toward-target movement as the beast-guard's flavour attack.
     *
     * <p>The wild knight spider uses
     * {@code KnightSpiderEntity.getFightTasks()} which wires three
     * SmartBrainLib behaviours: {@code AnimatableMeleeAttack},
     * {@code CustomRangeAttack} (a ranged spit), and
     * {@code LeapToTarget}. We can't run SmartBrainLib behaviours on
     * a MineColonies citizen — the AI runtimes are different — so the
     * port is manual: roll a chance on each combat tick, lunge the
     * spider toward the target with a vertical kick, and let the
     * normal melee swing follow up when the spider lands in range.
     *
     * <p>Ranged spit deliberately omitted — adding a ranged path
     * complicates the combat state machine (target-acquisition + path
     * + projectile-spawn-and-track) and is best treated as its own
     * stage. Slam-from-fall ({@code isSlammingFall}) also deferred —
     * it needs an apex/descend state-machine of its own.
     *
     * <p>The leap is a single {@code Entity.setDeltaMovement} push
     * toward the target with vertical lift. Damage still resolves
     * through the parent's normal melee path once the spider lands
     * close enough; this method is purely about MOVEMENT flavour.
     */
    @Override
    protected void doAttack(net.minecraft.world.entity.LivingEntity target) {
        long now = user.tickCount;
        // Only leap when off cooldown AND meaningfully out of melee
        // range — leaping while already touching the target would just
        // overshoot uselessly. Range threshold: 4 blocks (matches the
        // BeastGuardCombatAI attack-distance reach).
        boolean offCooldown = (now - lastLeapTick) >= LEAP_COOLDOWN_TICKS;
        boolean midRange = user.distanceToSqr(target) > 4.0 * 4.0;
        if (offCooldown && midRange && user.getRandom().nextDouble() < LEAP_CHANCE) {
            net.minecraft.world.phys.Vec3 toTarget = target.position().subtract(user.position());
            double dist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            if (dist > 0.01) {
                // Horizontal push proportional to distance (caps via the
                // physics step), with a fixed vertical lift so the spider
                // arcs visibly. Magic numbers tuned to feel like a
                // dramatic pounce — covers ~6 blocks in 1-2 seconds.
                double speed = 0.9;
                double dx = (toTarget.x / dist) * speed;
                double dz = (toTarget.z / dist) * speed;
                double dy = 0.45;
                user.setDeltaMovement(dx, dy, dz);
                user.hurtMarked = true; // tells MC to sync the velocity
                lastLeapTick = now;

                // Visual half — fire the spider's native leap animation
                // on the client renderer. The MOVEMENT is server-side
                // (the setDeltaMovement above), but GeckoLib's
                // triggerableAnim runs client-only and has no
                // observable state on the citizen that the renderer
                // could hook (unlike the bite, which we drive off the
                // citizen's swing-arm timer). So the server sends a
                // payload to nearby tracking clients telling them to
                // call shadow.triggerAnim("jumpController", "leap").
                //
                // Controller name "jumpController" + trigger name "leap"
                // confirmed by disassembly of
                // KnightSpiderEntity.registerControllers — the third
                // controller registers triggerableAnim("leap",
                // animation.knight_spider.leap).
                //
                // Defensive — if the network layer fails (shouldn't on
                // a normal send), the leap still happens, just without
                // the matching animation.
                try {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(
                            user,
                            new Networking.TriggerSpiderAnimPayload(
                                    user.getUUID(), "jumpController", "leap"));
                } catch (Throwable t) {
                    // Swallow — guard AI's silent-exception trap would
                    // hide this otherwise.
                    org.slf4j.LoggerFactory.getLogger(BeastGuardCombatAI.class)
                            .warn("[TM] beast-guard leap anim payload send failed",
                                    t);
                }
                // Don't call super.doAttack THIS tick — we're moving.
                // Damage applies on the next swing when the spider has
                // closed range.
                return;
            }
        }
        // Default — melee swing with attribute-driven damage.
        super.doAttack(target);
    }
}
