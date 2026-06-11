package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.github.manasmods.tensura.registry.entity.HumanEntityTypes;
import io.github.manasmods.tensura.registry.entity.MonsterEntityTypes;
import io.github.manasmods.tensura.storage.ep.IExistence;
import io.github.manasmods.tensura.util.EnergyHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * THE world-reputation API — the second foundational spine
 * (player-level, per-boss-faction standing) and the SOLE door to
 * {@link WorldReputationSavedData}. Mirror of {@link ReputationManager}'s
 * discipline: every current and future feature (lore raids, rival
 * colonies, diplomacy, reclaim) reads via
 * {@code getStanding/getTier/isAtLeast/isBelow} and writes via
 * {@code modifyStanding(player, faction, amount, reason)} ONLY.
 *
 * <p><b>Scale:</b> clamped 0–100 per faction, default
 * {@link #DEFAULT_STANDING} (50 — NEUTRAL). {@link FactionTier} derives
 * the disposition; thresholds live there.
 *
 * <p><b>Notoriety:</b> {@link #getOverallNotoriety} is a PURE DERIVED
 * function — computed on read from live inputs (faction hostility,
 * EP, demon-lord status, colony rule), never stored, so it can never
 * desync. It has NO consumer in v1 by design (shown in /worldrep; the
 * escalating-threat/angel-raid layer hooks it later).
 */
public final class WorldReputationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReputationManager.class);

    public static final double MIN_STANDING = 0.0;
    public static final double MAX_STANDING = 100.0;
    /** Absent key reads as this — every faction starts NEUTRAL. */
    public static final double DEFAULT_STANDING = 50.0;

    // --- Notoriety blend (all tunable; see docs/world-reputation.md) ---
    /** Weight of average faction hostility in the blend. */
    public static final double NOTORIETY_HOSTILITY_WEIGHT = 0.5;
    /** Weight of raw power (EP) in the blend. */
    public static final double NOTORIETY_POWER_WEIGHT = 0.3;
    /** Base max EP at which the power component saturates (endgame-ish). */
    public static final double NOTORIETY_EP_REFERENCE = 200_000.0;
    /** Flat bonus while the player is a true demon lord. */
    public static final double NOTORIETY_DEMON_LORD_BONUS = 20.0;
    /** Colony-rule penalty: (50 − avg owned-colony rep) × this, floor 0
     *  — i.e. at most +20 when the player's colonies sit at rep 0. */
    public static final double NOTORIETY_COLONY_PENALTY_FACTOR = 0.4;

    private WorldReputationManager() {}

    // ------------------------------------------------------------------
    // Entity → faction (the mover helper). Lazy map over the registry
    // suppliers — built on first use, after all registries exist.
    // User-confirmed mappings; SHIZU anchors LEON (canonically Leon's
    // summon-child carrying Ifrit).
    // ------------------------------------------------------------------

    private static Map<EntityType<?>, BossFaction> factionByEntity;

    @Nullable
    public static BossFaction factionOf(EntityType<?> type) {
        if (factionByEntity == null) {
            Map<EntityType<?>, BossFaction> map = new HashMap<>();
            map.put(HumanEntityTypes.HINATA_SAKAGUCHI.get(), BossFaction.LUMINOUS);
            map.put(HumanEntityTypes.GAZEL_DWARGO.get(), BossFaction.DWARGON);
            map.put(HumanEntityTypes.FALMUTH_KNIGHT.get(), BossFaction.FALMUTH);
            map.put(HumanEntityTypes.FOLGEN.get(), BossFaction.FALMUTH);
            // Falmuth's three otherworlders fight under Falmuth's banner.
            map.put(HumanEntityTypes.KIRARA_MIZUTANI.get(), BossFaction.FALMUTH);
            map.put(HumanEntityTypes.KYOYA_TACHIBANA.get(), BossFaction.FALMUTH);
            map.put(HumanEntityTypes.SHOGO_TAGUCHI.get(), BossFaction.FALMUTH);
            // Jura Alliance otherworlders.
            map.put(HumanEntityTypes.SHIN_RYUSEI.get(), BossFaction.JURA_ALLIANCE);
            map.put(HumanEntityTypes.SHINJI_TANIMURA.get(), BossFaction.JURA_ALLIANCE);
            map.put(HumanEntityTypes.MARK_LAUREN.get(), BossFaction.JURA_ALLIANCE);
            // Unaffiliated otherworlders.
            map.put(HumanEntityTypes.MAI_FURUKI.get(), BossFaction.OTHERWORLDERS);
            // Shizu stands alone.
            map.put(HumanEntityTypes.SHIZU.get(), BossFaction.SHIZU);
            map.put(MonsterEntityTypes.CHARYBDIS.get(), BossFaction.CLAYMAN);
            map.put(MonsterEntityTypes.ORC_LORD.get(), BossFaction.CLAYMAN);
            map.put(MonsterEntityTypes.ORC_DISASTER.get(), BossFaction.CLAYMAN);
            map.put(MonsterEntityTypes.IFRIT.get(), BossFaction.LEON);
            factionByEntity = map;
        }
        return factionByEntity.get(type);
    }

    // ------------------------------------------------------------------
    // Per-faction standing — the core
    // ------------------------------------------------------------------

    public static double getStanding(ServerLevel level, UUID player, BossFaction faction) {
        Double stored = WorldReputationSavedData.get(level).getStanding(player, faction.id());
        return stored == null ? DEFAULT_STANDING : clamp(stored);
    }

    public static FactionTier getTier(ServerLevel level, UUID player, BossFaction faction) {
        return FactionTier.forValue(getStanding(level, player, faction));
    }

    /**
     * THE mutator — clamps to [0, 100], persists, logs. The single place
     * future side-effects (sync, per-reason policy) get added.
     */
    public static double modifyStanding(ServerLevel level, UUID player, BossFaction faction,
                                        double amount, WorldRepReason reason) {
        WorldReputationSavedData data = WorldReputationSavedData.get(level);
        Double stored = data.getStanding(player, faction.id());
        double before = stored == null ? DEFAULT_STANDING : clamp(stored);
        double after = clamp(before + amount);
        data.setStanding(player, faction.id(), after);
        LOGGER.info("[TM] worldrep: player {} × {} {} {} → {} ({}, tier {})",
                player, faction.displayName(),
                String.format("%.1f", before), String.format("%+.1f", amount),
                String.format("%.1f", after), reason, FactionTier.forValue(after));
        return after;
    }

    /** Admin/debug absolute set (the /worldrep set command). */
    public static double setStanding(ServerLevel level, UUID player, BossFaction faction,
                                     double value, WorldRepReason reason) {
        double clamped = clamp(value);
        WorldReputationSavedData.get(level).setStanding(player, faction.id(), clamped);
        LOGGER.info("[TM] worldrep: player {} × {} SET to {} ({}, tier {})",
                player, faction.displayName(), String.format("%.1f", clamped),
                reason, FactionTier.forValue(clamped));
        return clamped;
    }

    public static boolean isAtLeast(ServerLevel level, UUID player, BossFaction faction,
                                    FactionTier tier) {
        return getTier(level, player, faction).compareTo(tier) >= 0;
    }

    public static boolean isBelow(ServerLevel level, UUID player, BossFaction faction,
                                  FactionTier tier) {
        return getTier(level, player, faction).compareTo(tier) < 0;
    }

    // ------------------------------------------------------------------
    // Notoriety — pure derived aggregate (never stored)
    // ------------------------------------------------------------------

    /** Component breakdown for the /worldrep readout (tuning aid). */
    public record Notoriety(double hostility, double power, double demonLordBonus,
                            double colonyPenalty, double total) {}

    public static Notoriety computeNotoriety(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // Average hostility across ALL factions: how much of the world
        // wants you gone. Average (not sum) keeps the scale stable as
        // the cast grows.
        double hostilitySum = 0;
        for (BossFaction faction : BossFaction.values()) {
            double standing = getStanding(level, player.getUUID(), faction);
            hostilitySum += Math.max(0, DEFAULT_STANDING - standing) * 2.0; // 0..100 per faction
        }
        double hostility = hostilitySum / BossFaction.values().length;

        // Raw power — the world fears what you are.
        double power = 0;
        try {
            power = Math.min(1.0,
                    EnergyHelper.getBaseMaxEP(player) / NOTORIETY_EP_REFERENCE) * 100.0;
        } catch (Throwable ignored) { }

        // True demon lord — a fixed mark on the world stage.
        double demonLord = 0;
        try {
            IExistence ex = ExampleMod.readExistenceSafe(player);
            if (ex != null && ex.isTrueDemonLord()) demonLord = NOTORIETY_DEMON_LORD_BONUS;
        } catch (Throwable ignored) { }

        // Colony rule — the world fears how you govern. Average owned-
        // colony reputation below neutral adds up to +20.
        double colonyPenalty = 0;
        double repSum = 0;
        int colonies = 0;
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            UUID owner = colony.getPermissions().getOwner();
            if (owner != null && owner.equals(player.getUUID())) {
                repSum += ReputationManager.getReputation(colony);
                colonies++;
            }
        }
        if (colonies > 0) {
            double avgRep = repSum / colonies;
            colonyPenalty = Math.max(0, 50.0 - avgRep) * NOTORIETY_COLONY_PENALTY_FACTOR;
        }

        double total = Math.max(0, Math.min(100,
                NOTORIETY_HOSTILITY_WEIGHT * hostility
                + NOTORIETY_POWER_WEIGHT * power
                + demonLord
                + colonyPenalty));
        return new Notoriety(hostility, power, demonLord, colonyPenalty, total);
    }

    /** The headline number (0–100). */
    public static double getOverallNotoriety(ServerPlayer player) {
        return computeNotoriety(player).total();
    }

    private static double clamp(double v) {
        return Math.max(MIN_STANDING, Math.min(MAX_STANDING, v));
    }
}
