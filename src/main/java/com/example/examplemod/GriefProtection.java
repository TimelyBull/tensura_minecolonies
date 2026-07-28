package com.example.examplemod;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.mojang.logging.LogUtils;
import dev.architectury.event.EventResult;
import io.github.manasmods.tensura.event.TensuraSkillEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Stops Tensura's block-breaking from chewing through colony builds.
 *
 * <p>Tensura has two independent ways to destroy terrain, and NEITHER is
 * colony-aware — both only ever consult the vanilla {@code mobGriefing}
 * gamerule:
 * <ul>
 *   <li><b>Giant mobs</b> ({@code IGiantMob}: Orc Lord, Charybdis, Megalodon,
 *       Knight Spider, Armorsaurus, Giant Ant, …) smash blocks by walking into
 *       them. Gated by {@code cantBreakBlock} / {@code breakableBlocks} /
 *       {@code diggableBlocks}, which are plain method returns with no
 *       cancellable event — so MineColonies' own boundary protection (the thing
 *       that stops creepers and zombies wrecking a colony) never gets a chance
 *       to run. Handled by {@link com.example.examplemod.mixin.IGiantMobMixin}.
 *   <li><b>Terraforming skills</b> (Earth Manipulation / Domination, Molecular
 *       Manipulation, Fusionist, Degenerate, …) funnel their block-breaking
 *       through one shared cancellable hook,
 *       {@code TensuraSkillEvents.SKILL_GRIEF_PRE}. Handled by
 *       {@link #registerSkillGriefListener()}.
 * </ul>
 *
 * <p>Both are relevant to THIS mod in particular because it spawns raids,
 * faction garrisons, and lore bosses made of exactly these mobs — an
 * unprotected colony can have its buildings demolished mid-raid.
 *
 * <p>Each of the two paths has its own on/off switch
 * ({@link Config#protectColoniesFromMobGriefing()} /
 * {@link Config#protectColoniesFromSkillGriefing()}), both default ON. The
 * checks are read live on every attempt, so toggling either config takes
 * effect immediately without a reload.
 *
 * <p>Ported from the sibling mod (Jinjer's tensura-minecolonies-compat) and
 * adapted to this mod's package + config system.
 */
public final class GriefProtection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GriefProtection() {}

    /**
     * True if {@code pos} lies inside any active colony's claimed area.
     * Fails OPEN (returns false = "not protected") if MineColonies' API throws
     * for any reason (colony manager not ready, etc.) rather than crashing the
     * mob-tick / skill-cast caller.
     */
    public static boolean isInColony(Level level, BlockPos pos) {
        try {
            IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                    .getColonyByPosFromWorld(level, pos);
            return colony != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Should a giant-mob block-break at {@code pos} be refused? */
    public static boolean blockMobGrief(Level level, BlockPos pos) {
        return Config.protectColoniesFromMobGriefing() && isInColony(level, pos);
    }

    /** Should a terraforming-skill block-break at {@code pos} be refused? */
    public static boolean blockSkillGrief(Level level, BlockPos pos) {
        return Config.protectColoniesFromSkillGriefing() && isInColony(level, pos);
    }

    /**
     * Wires the Tensura skill-grief hook. Called once during mod setup (from
     * {@code ExampleMod}, alongside the other Architectury/ManasCore event
     * registrations). The listener stays registered for the whole session; the
     * config toggle is read live inside it, so turning skill protection off/on
     * takes effect without re-registering.
     */
    public static void registerSkillGriefListener() {
        LOGGER.info("[TM] registering colony skill-grief protection (TensuraSkillEvents.SKILL_GRIEF_PRE)");
        TensuraSkillEvents.SKILL_GRIEF_PRE.register((skill, level, entity, x, y, z) -> {
            BlockPos pos = BlockPos.containing(x, y, z);
            if (blockSkillGrief(level, pos)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }
}
