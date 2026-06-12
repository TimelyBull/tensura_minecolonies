package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lore events — boss-specific faction hostility events (Layer 2,
 * consuming the Layer-1 faction model). See docs/lore-events.md +
 * docs/faction-model.md.
 *
 * <p><b>The pattern:</b> a SHARED SPINE (this class — the nightfall
 * trigger phase, provocation arming, soft-influence chance, recurrence
 * rules, resolution consequences) and a PLUGGABLE ENCOUNTER per event
 * (the {@link EncounterFactory} seam). The Orc Disaster happens to plug
 * the raid engine in ({@code TensuraRaids.startOrcDisaster}); Charybdis
 * (flying set-piece) and Ifrit (named-boss duel) plug their own
 * encounter classes later and inherit the spine unchanged — the spine
 * never assumes "encounter = wave".
 *
 * <p><b>Layer-1 integration (supersedes the old lore-events.md
 * trigger):</b>
 * <ul>
 *   <li><b>ARM via provocation</b> — {@code isProvoked(player, faction)}
 *       (offense ≥ the faction's profile threshold). NO hard standing
 *       gate.</li>
 *   <li><b>ROLL via soft influence</b> — chance/night =
 *       {@code baseChance + hostilityScale × hostility01(standing)};
 *       standing only SCALES, never gates.</li>
 *   <li><b>Consequences ride the marked-kill path</b> — the lead boss
 *       carries {@link FactionMarkTag}, so the two-sided weighted
 *       fan-out fires from the existing death hook with NO hand-coded
 *       numbers here. This class adds only the bespoke extras: the
 *       forced-HOSTILE clamp, the RECOVERABLE diplomacy-closed flag,
 *       offense reset, and the recurrence bookkeeping.</li>
 *   <li><b>Gated by {@code factionSystemEnabled}</b> — the trigger
 *       phase and the debug force-start both no-op when the faction
 *       layer is off (and a self-summoned boss is unmarked anyway).</li>
 * </ul>
 */
public final class LoreEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoreEvents.class);

    // ------------------------------------------------------------------
    // Tuning constants
    // ------------------------------------------------------------------

    /** Soft-influence roll: chance/night = BASE + SCALE × hostility01. */
    static final double EVENT_BASE_CHANCE = 0.10;
    static final double EVENT_HOSTILITY_CHANCE_SCALE = 0.30;
    /** Recurrence cooldown after a TIMED-OUT march — 8 in-game days
     *  (a slain Disaster never returns at all). */
    static final long ORC_DISASTER_TIMEOUT_COOLDOWN_TICKS = 192_000L;
    /** Forced-HOSTILE ceiling applied to the slayer's standing with the
     *  wronged faction (HOSTILE band tops out at 19). */
    static final double FORCED_HOSTILE_CEILING = 19.0;

    // ------------------------------------------------------------------
    // The descriptor + the encounter seam
    // ------------------------------------------------------------------

    /** The pluggable encounter: spawn the event's bespoke encounter at
     *  the chosen colony. Returns the started raid event, or null when
     *  nothing could spawn. NON-wave events plug a factory that runs
     *  their own encounter and returns null (the spine only uses the
     *  return for logging/one-per-night). */
    public interface EncounterFactory {
        TensuraRaidEvent start(ServerLevel level, ServerPlayer player,
                               IColony colony, LoreEvent event);
    }

    /**
     * One lore event's spine data: who is wronged, how the roll scales,
     * how it recurs, how its boss bar looks, and which encounter it
     * plugs in. Adding Charybdis/Ifrit later = one more entry in
     * {@link #EVENTS} with their own factory.
     */
    public record LoreEvent(
            String id,
            BossFaction faction,
            double baseChance,
            double hostilityChanceScale,
            long timeoutCooldownTicks,
            Component barTitle,
            BossEvent.BossBarColor barColor,
            EncounterFactory encounter) {}

    public static final Map<String, LoreEvent> EVENTS = Map.of(
            TensuraRaids.ORC_DISASTER_EVENT_ID, new LoreEvent(
                    TensuraRaids.ORC_DISASTER_EVENT_ID,
                    BossFaction.CLAYMAN,
                    EVENT_BASE_CHANCE,
                    EVENT_HOSTILITY_CHANCE_SCALE,
                    ORC_DISASTER_TIMEOUT_COOLDOWN_TICKS,
                    Component.literal("Geld, the Orc Disaster — Clayman's Calamity"),
                    BossEvent.BossBarColor.PURPLE,
                    TensuraRaids::startOrcDisaster));

    private LoreEvents() {}

    public static LoreEvent byId(String id) {
        return EVENTS.get(id);
    }

    /** How offended the faction FEELS (0 at NEUTRAL+, 1 at standing 0) —
     *  the soft-influence input for both the roll and the budget. */
    static double hostility01(double standing) {
        return Math.max(0.0, Math.min(1.0,
                (WorldReputationManager.DEFAULT_STANDING - standing)
                        / WorldReputationManager.DEFAULT_STANDING));
    }

    // ------------------------------------------------------------------
    // The trigger phase — per online player, on the nightfall edge
    // (called from TensuraRaids.tick after the colony-rep phase)
    // ------------------------------------------------------------------

    static void onNightfall(ServerLevel level) {
        // FACTION GATE — lore events are faction-layer; dormant when off.
        if (!WorldReputationManager.isFactionSystemEnabled()) return;

        for (ServerPlayer player : level.players()) {
            for (LoreEvent event : EVENTS.values()) {
                try {
                    if (maybeTrigger(level, player, event)) break; // one per player per night
                } catch (Throwable t) {
                    LOGGER.warn("[TM] lore: trigger failed for player {} event {}",
                            player.getGameProfile().getName(), event.id(), t);
                }
            }
        }
    }

    private static boolean maybeTrigger(ServerLevel level, ServerPlayer player, LoreEvent event) {
        UUID uuid = player.getUUID();

        // A slain Disaster never returns for this player.
        if (WorldReputationManager.isLoreEventDefeated(level, uuid, event.id())) return false;
        // ARM: provocation only — offense ≥ the faction's threshold.
        if (!WorldReputationManager.isProvoked(level, uuid, event.faction())) return false;
        // Timed-out marches regroup before coming again.
        if (level.getGameTime()
                < WorldReputationManager.getLoreEventCooldownUntil(level, uuid, event.id())) {
            return false;
        }
        // One march of this event per player at a time, across colonies.
        if (findActiveLoreRaid(level, uuid, event.id()) != null) return false;

        // ROLL: soft influence — standing scales the chance, never gates.
        double standing = WorldReputationManager.getStanding(level, uuid, event.faction());
        double chance = event.baseChance() + event.hostilityChanceScale() * hostility01(standing);
        if (level.getRandom().nextDouble() >= chance) {
            LOGGER.info("[TM] lore: {} armed for {} but rolled no march tonight (standing {}, chance {})",
                    event.id(), player.getGameProfile().getName(),
                    String.format("%.1f", standing), String.format("%.2f", chance));
            return false;
        }

        IColony colony = pickEligibleOwnedColony(level, uuid);
        if (colony == null) return false;

        TensuraRaidEvent started = event.encounter().start(level, player, colony, event);
        return started != null;
    }

    /** A RANDOM eligible colony OWNED by the player (faction wrath hits
     *  the player's holdings): no active raid, past the colony's
     *  existing raid-resolve cooldown. */
    private static IColony pickEligibleOwnedColony(ServerLevel level, UUID player) {
        RaidSavedData saved = RaidSavedData.get(level);
        List<IColony> eligible = new ArrayList<>();
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            UUID owner = colony.getPermissions().getOwner();
            if (owner == null || !owner.equals(player)) continue;
            if (TensuraRaids.findActiveRaid(colony) != null) continue;
            long lastResolve = saved.getLastRaidResolveTick(colony.getID(), Long.MIN_VALUE / 2);
            if (level.getGameTime() - lastResolve < TensuraRaids.RAID_COOLDOWN_TICKS) continue;
            eligible.add(colony);
        }
        if (eligible.isEmpty()) return null;
        return eligible.get(level.getRandom().nextInt(eligible.size()));
    }

    /** The player's active raid-engine lore event of this id, or null. */
    static TensuraRaidEvent findActiveLoreRaid(ServerLevel level, UUID player, String eventId) {
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            UUID owner = colony.getPermissions().getOwner();
            if (owner == null || !owner.equals(player)) continue;
            TensuraRaidEvent active = TensuraRaids.findActiveRaid(colony);
            if (active != null && eventId.equals(active.loreEventId())) return active;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Resolution — lead-boss death (the win) and timeout (the regroup)
    // ------------------------------------------------------------------

    /**
     * Called from the death hook AFTER the Layer-1 marked-kill fan-out
     * (so the forced-HOSTILE clamp lands on the post-ripple standing).
     * If the victim is an active lore raid's LEAD BOSS: the horde breaks
     * and flees (the Disaster is the horde's will), and the bespoke
     * consequences fire — defeated-forever, offense reset (retribution
     * spent), forced-HOSTILE clamp + RECOVERABLE diplomacy-closed flag
     * on the slayer.
     */
    static void onPotentialLeadBossDeath(ServerLevel level, LivingDeathEvent deathEvent) {
        LivingEntity victim = deathEvent.getEntity();
        if (!victim.hasData(Attachments.RAID_TAG.get())) return;
        RaidTag tag = victim.getData(Attachments.RAID_TAG.get());
        if (tag == null) return;
        IColony colony = IColonyManager.getInstance().getColonyByWorld(tag.colonyId(), level);
        if (colony == null) return;
        TensuraRaidEvent raid = TensuraRaids.findActiveRaid(colony);
        if (raid == null || raid.getID() != tag.eventId()) return;
        if (!victim.getUUID().equals(raid.leadBossUuid())) return;
        LoreEvent event = byId(raid.loreEventId());
        if (event == null) return;

        // The horde breaks — remaining raiders flee; colony is paid the
        // standard repelled reward through the raid engine.
        TensuraRaids.resolveLoreBreak(level, colony, raid);

        // Recurrence: a slain Disaster never returns for the TARGETED
        // player; the faction's retribution is spent either way.
        UUID owner = colony.getPermissions().getOwner();
        if (owner != null) {
            WorldReputationManager.markLoreEventDefeated(level, owner, event.id());
            WorldReputationManager.clearOffense(level, owner, event.faction());
        }

        // The slayer's consequences: the Layer-1 fan-out (−18 Clayman,
        // +Milim/Carrion/forest, ...) ALREADY fired from the marked-kill
        // hook. Here: clamp to HOSTILE + close diplomacy (recoverable).
        ServerPlayer killer = null;
        if (deathEvent.getSource().getEntity() instanceof ServerPlayer sp) killer = sp;
        if (killer == null && victim.getKillCredit() instanceof ServerPlayer sp) killer = sp;
        if (killer != null) {
            double current = WorldReputationManager.getStanding(level, killer.getUUID(), event.faction());
            if (current > FORCED_HOSTILE_CEILING) {
                WorldReputationManager.setStanding(level, killer.getUUID(), event.faction(),
                        FORCED_HOSTILE_CEILING, WorldRepReason.LORE_EVENT);
            }
            WorldReputationManager.closeDiplomacy(level, killer.getUUID(), event.faction());
            killer.sendSystemMessage(Component.literal(
                    event.faction().displayName() + " will not forget this — though even the"
                            + " deepest grudge might someday be bought back.")
                    .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        }
        LOGGER.info("[TM] lore: {} LEAD BOSS SLAIN at colony {} (killer {}) — event gone forever for owner {}",
                event.id(), colony.getID(),
                killer != null ? killer.getGameProfile().getName() : "non-player", owner);
    }

    /** A lore march TIMED OUT (called from the raid engine's timeout
     *  path): the faction regroups — recurrence cooldown + offense reset
     *  (the retribution was spent on the march, win or lose). */
    static void onTimeout(ServerLevel level, IColony colony, TensuraRaidEvent raid) {
        LoreEvent event = byId(raid.loreEventId());
        if (event == null) return;
        UUID owner = colony.getPermissions().getOwner();
        if (owner != null) {
            WorldReputationManager.setLoreEventCooldownUntil(level, owner, event.id(),
                    level.getGameTime() + event.timeoutCooldownTicks());
            WorldReputationManager.clearOffense(level, owner, event.faction());
        }
        LOGGER.info("[TM] lore: {} TIMED OUT at colony {} — cooldown {} ticks, offense reset",
                event.id(), colony.getID(), event.timeoutCooldownTicks());
    }

    /** The lead boss resolves live, for the HP-bound boss bar. */
    static LivingEntity resolveLeadBoss(ServerLevel level, UUID leadBossUuid) {
        if (leadBossUuid == null) return null;
        Entity e = level.getEntity(leadBossUuid);
        return e instanceof LivingEntity living && living.isAlive() ? living : null;
    }
}
