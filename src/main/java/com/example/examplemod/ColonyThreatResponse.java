package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.tslat.smartbrainlib.util.BrainUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Colony threat-response — the per-second evaluator that decides how each
 * citizen reacts when the colony is under attack.
 *
 * <p><b>The rule:</b>
 * <ul>
 *   <li><b>Guards</b> — never touched. They fight as MineColonies intends.</li>
 *   <li><b>Regular / low-EP citizens</b> — flee. We do NOT steer them; we
 *       lean on MineColonies' native non-combatant raid behaviour (citizens
 *       flee / hide automatically while {@code getRaiderManager().isRaided()}
 *       is true). Tensura-bodied citizens below {@link #FORM_SWAP_EP} stay in
 *       colonist form and flee with everyone else.</li>
 *   <li><b>Tensura citizens with EP ≥ {@link #FORM_SWAP_EP}</b> — place-swap
 *       to their Tensura subordinate body (via
 *       {@link ExampleMod#defenseSwapToSubordinate}) and FIGHT with skills.
 *       Skill use is driven by the Nightmare's Tensura Utils "Sentient" skill
 *       granted to the swapped-in body; targeting is the same nearest-raider
 *       steer the ally-support system uses.</li>
 * </ul>
 *
 * <p>When the threat ends ({@code isRaided()} false) every defender swaps
 * back to colonist form.
 *
 * <p>Skill-fighting only ever happens in the Tensura subordinate form — the
 * only skill-bearing body. The colonist form holds no skills, so the
 * autocaster does nothing to it (and to plain MineColonies citizens, which
 * have no ManasCore skill storage at all).
 */
public final class ColonyThreatResponse {

    private static final Logger LOGGER = LoggerFactory.getLogger(ColonyThreatResponse.class);

    /** EP at or above which a Tensura citizen switches to its subordinate
     *  body to fight rather than fleeing. ⚠ BALANCE GUESS — tunable. */
    static final double FORM_SWAP_EP = 10_000.0;

    /** Cap on place-swaps performed in a single tick. A big raid can push
     *  many citizens over the threshold at once; spreading the swaps over a
     *  few seconds keeps any single tick cheap. */
    static final int MAX_SWAPS_PER_TICK = 3;

    /** Radius (blocks) around the town hall to scan for raiders to target. */
    static final double RAIDER_SCAN_RADIUS = 80.0;

    private ColonyThreatResponse() {}

    // Defender spell use is now driven by the Sentient skill, granted to the
    // place-swapped subordinate body in ExampleMod.defenseSwapToSubordinate and
    // removed on swap-back. The old registerReflectiveManascoreAutocaster
    // registration was removed (Sentient replaces it).

    /** Per-second evaluator. Called from the realtime scheduler pass. */
    static void tick(MinecraftServer server) {
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(server.overworld());
        int swapsThisTick = 0;

        for (ServerLevel level : server.getAllLevels()) {
            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                // A town hall is required for both swap directions (the body
                // materialises near it on swap-back). No town hall → skip.
                if (!colony.getServerBuildingManager().hasTownHall()) continue;

                // Identities belonging to THIS colony.
                List<RaceIdentitySavedData.RaceIdentity> ids = new ArrayList<>();
                for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
                    if (id.colonyId == colony.getID()) ids.add(id);
                }
                if (ids.isEmpty()) continue;

                boolean raided;
                try {
                    raided = colony.getRaiderManager().isRaided();
                } catch (Throwable t) {
                    continue; // defensive — a malformed colony shouldn't break the pass
                }

                if (raided) {
                    List<Mob> raiders = scanRaiders(level, colony);
                    for (RaceIdentitySavedData.RaceIdentity id : ids) {
                        if (id.defendingColony
                                && id.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
                            // Already fighting — keep it tagged + on a raider.
                            steerDefender(level, id, raiders);
                        } else if (!id.defendingColony
                                && id.mode == RaceIdentitySavedData.Mode.IN_COLONY) {
                            if (swapsThisTick >= MAX_SWAPS_PER_TICK) continue;
                            if (!shouldDefend(level, colony, id)) continue;
                            if (ExampleMod.defenseSwapToSubordinate(level, saved, id)) {
                                swapsThisTick++;
                                steerDefender(level, id, raiders);
                            }
                        }
                    }
                } else {
                    // Threat over — bring every defender home.
                    for (RaceIdentitySavedData.RaceIdentity id : ids) {
                        if (id.defendingColony) {
                            ExampleMod.defenseSwapToColony(level, saved, id);
                        }
                    }
                }
            }
        }
    }

    /**
     * Whether this IN_COLONY identity qualifies to swap and fight:
     * a non-guard citizen with a loaded body and EP ≥ {@link #FORM_SWAP_EP}.
     * Guards and low-EP citizens fall through to MineColonies' native flee.
     */
    private static boolean shouldDefend(ServerLevel level, IColony colony,
                                        RaceIdentitySavedData.RaceIdentity id) {
        ICitizenData cd = colony.getCitizenManager().getCivilian(id.citizenId);
        if (cd == null) return false;
        // Guards keep MineColonies behaviour — never swapped.
        if (cd.getJob() != null && cd.getJob().isGuard()) return false;
        var bodyOpt = cd.getEntity();
        if (bodyOpt.isEmpty()) return false; // chunk not loaded
        ExistenceStorage exist = ExampleMod.readExistence(bodyOpt.get());
        double ep = exist == null ? 0.0 : exist.getEP();
        return ep >= FORM_SWAP_EP;
    }

    /** Raiders (RAID_TAG mobs) currently loaded near the colony's town hall. */
    private static List<Mob> scanRaiders(ServerLevel level, IColony colony) {
        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        AABB box = new AABB(th).inflate(RAIDER_SCAN_RADIUS);
        return level.getEntitiesOfClass(Mob.class, box,
                m -> m.isAlive() && m.hasData(Attachments.RAID_TAG.get()));
    }

    /**
     * Keep a defending subordinate tagged for the autocaster and locked onto
     * the nearest living raider — the same dual-write idiom the ally-support
     * steer uses, so the autocaster (which reads {@code mob.getTarget()})
     * always has a combat target. Re-attaches the COLONY_DEFENDER tag if a
     * reload dropped it.
     */
    private static void steerDefender(ServerLevel level,
                                      RaceIdentitySavedData.RaceIdentity id,
                                      List<Mob> raiders) {
        if (id.mobEntityUUID == null) return;
        Entity e = level.getEntity(id.mobEntityUUID);
        if (!(e instanceof Mob defender) || !defender.isAlive()) return;

        if (!defender.hasData(Attachments.COLONY_DEFENDER.get())) {
            defender.setData(Attachments.COLONY_DEFENDER.get(),
                    new ColonyDefenderTag(id.colonyId));
        }

        // Keep the current target if it's still a live raider.
        LivingEntity cur = BrainUtils.getTargetOfEntity(defender);
        if (cur != null && cur.isAlive() && cur.hasData(Attachments.RAID_TAG.get())) {
            if (defender.getTarget() != cur) defender.setTarget(cur);
            return;
        }
        // Otherwise lock onto the nearest raider.
        Mob nearest = null;
        double best = Double.MAX_VALUE;
        for (Mob raider : raiders) {
            double d = raider.distanceToSqr(defender);
            if (d < best) { best = d; nearest = raider; }
        }
        if (nearest != null) {
            BrainUtils.setTargetOfEntity(defender, nearest);
            defender.setTarget(nearest);
        }
    }
}
