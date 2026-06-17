package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.github.manasmods.manascore.race.api.ManasRace;
import io.github.manasmods.manascore.race.api.ManasRaceInstance;
import io.github.manasmods.manascore.race.api.RaceAPI;
import io.github.manasmods.tensura.data.TensuraRaceTags;
import io.github.manasmods.tensura.race.TensuraRace;
import io.github.manasmods.tensura.registry.entity.HumanEntityTypes;
import io.github.manasmods.tensura.registry.entity.MonsterEntityTypes;
import io.github.manasmods.tensura.storage.Alignment;
import io.github.manasmods.tensura.storage.ep.IExistence;
import io.github.manasmods.tensura.util.EnergyHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE world-reputation API — the faction-layer spine (player ×
 * boss-faction standing) and the SOLE door to
 * {@link WorldReputationSavedData}. Mirror of {@link ReputationManager}'s
 * discipline: every current and future feature (lore events, faction
 * raids, diplomacy) reads via {@code getStanding/getTier/isAtLeast/
 * isBelow} and writes via {@code modifyStanding} or the marked-boss
 * fan-outs ONLY.
 *
 * <p><b>Expanded model (docs/faction-model.md, v1 built):</b>
 * <ul>
 *   <li><b>Live base + earned delta:</b> {@code effective standing =
 *       clamp(dispositionBase + storedDelta)}. The base comes from the
 *       faction's {@link FactionProfile} and the player's CURRENT race
 *       side ({@link #isMajinSide}) — computed on read, never stored,
 *       so a mid-game race change (human → majin demon-lord path)
 *       shifts every faction's posture automatically.</li>
 *   <li><b>Two-sided weighted movers:</b> a MARKED boss kill moves the
 *       boss's faction down AND fans out across its relationship web
 *       (allies mourn, enemies celebrate), all scaled by the boss's
 *       {@link BossProfile.Importance} and each target's swing
 *       multiplier. {@link #applyMarkedBossKill} /
 *       {@link #applyMarkedBossAttack} are the only entry points.</li>
 *   <li><b>Marked-only:</b> the movers fire ONLY for entities carrying
 *       {@link FactionMarkTag} — wild/self-summoned boss kills carry no
 *       faction consequences.</li>
 *   <li><b>Offense ledger + provocation:</b> marked acts also write a
 *       no-decay offense score; {@link #isProvoked} derives
 *       per-faction provocation from it (never stored).</li>
 *   <li><b>Config gate:</b> {@code factionSystemEnabled=false} makes
 *       the whole layer dormant — standings read flat NEUTRAL, every
 *       write no-ops. Colony-level systems below are untouched.</li>
 * </ul>
 *
 * <p><b>Scale:</b> effective standing clamped 0–100;
 * {@link FactionTier} derives the disposition tier. The STORED value is
 * the earned delta (default 0), clamped on write so the effective stays
 * in range against the CURRENT base.
 *
 * <p><b>Notoriety:</b> {@link #getOverallNotoriety} is a PURE DERIVED
 * function over EFFECTIVE standings — a majin player carries some base
 * notoriety from the Holy bloc's disposition (lore-correct). No
 * consumer in v1; shown in /worldrep.
 */
public final class WorldReputationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReputationManager.class);

    public static final double MIN_STANDING = 0.0;
    public static final double MAX_STANDING = 100.0;
    /** Effective standing when the faction layer is disabled, the
     *  faction has no profile, or the player can't be resolved. */
    public static final double DEFAULT_STANDING = 50.0;

    // --- Two-sided mover constants (user-confirmed; faction-model.md #2) ---
    /** Direct standing hit on the victim faction per marked KILL, ×importance. */
    public static final double KILL_BASE = 30.0;
    /** Direct standing hit per marked ATTACK (deduped), ×importance. No ripple. */
    public static final double ATTACK_BASE = 3.0;
    /** Allies of the victim faction lose this fraction of the direct hit. */
    public static final double ALLY_LOSS_FACTOR = 0.5;
    /** Enemies of the victim faction GAIN this fraction of the direct hit. */
    public static final double ENEMY_GAIN_FACTOR = 0.4;
    /** Offense ledger points per marked kill, ×importance. */
    public static final double OFFENSE_KILL = 10.0;
    /** Offense ledger points per marked (deduped) attack, ×importance. */
    public static final double OFFENSE_ATTACK = 1.0;

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

    /** Our shipped race tag patching Tensura's incomplete HUMAN_LIKE
     *  (which lists only the 5 BASE human-side races) — the human-family
     *  EVOLUTIONS live in data/tensura_minecolonies/tags/manascore_race/
     *  races/human_side.json. Datapack-extensible: addon races join the
     *  human side by joining the tag. */
    public static final TagKey<ManasRace> HUMAN_SIDE_RACES = TagKey.create(
            RaceAPI.getRaceRegistryKey(),
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "human_side"));

    private WorldReputationManager() {}

    // ------------------------------------------------------------------
    // The master gate — the faction layer's single on/off switch
    // ------------------------------------------------------------------

    /** False → the whole faction layer is dormant: reads return flat
     *  NEUTRAL, writes no-op. Checked at every entry point here plus
     *  the two mover hooks in ExampleMod and future event triggers. */
    public static boolean isFactionSystemEnabled() {
        try {
            return Config.FACTION_SYSTEM_ENABLED.get();
        } catch (IllegalStateException e) {
            return true; // config not loaded yet (early startup)
        }
    }

    // ------------------------------------------------------------------
    // Race-side classifier (faction-model.md #1 — the 5-step composite)
    // ------------------------------------------------------------------

    /**
     * Is the player on the MAJIN/monster side of the world's eyes?
     * First match wins:
     * <ol>
     *   <li>no race instance → human-side (unawakened = plain human)</li>
     *   <li>alignment MAJIN or CHAOS → majin; HOLY → human</li>
     *   <li>Tensura's HUMAN_LIKE race tag → human (base races only)</li>
     *   <li>our {@link #HUMAN_SIDE_RACES} tag → human (the evolutions
     *       Tensura's tag misses, e.g. Human Saint)</li>
     *   <li>otherwise → majin (goblin/ogre/lizardman/harpy/... report
     *       DEFAULT alignment and aren't HUMAN_LIKE — monsters default
     *       to the monster side)</li>
     * </ol>
     */
    public static boolean isMajinSide(ServerPlayer player) {
        try {
            var tracker = RaceAPI.getRaceFrom(player);
            if (tracker == null) return false;
            Optional<ManasRaceInstance> instanceOpt = tracker.getRace();
            if (instanceOpt.isEmpty()) return false;
            ManasRaceInstance instance = instanceOpt.get();
            if (instance.getRace() instanceof TensuraRace tensuraRace) {
                Alignment alignment = tensuraRace.getAlignment();
                if (alignment == Alignment.MAJIN || alignment == Alignment.CHAOS) return true;
                if (alignment == Alignment.HOLY) return false;
            }
            if (instance.is(TensuraRaceTags.HUMAN_LIKE)) return false;
            if (instance.is(HUMAN_SIDE_RACES)) return false;
            return true;
        } catch (Throwable t) {
            // A race-API hiccup must never break a standing read.
            LOGGER.warn("[TM] worldrep: race-side read failed for {} — assuming human-side",
                    player.getGameProfile().getName(), t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Boss profiles (entity → faction + importance). Lazy map over the
    // registry suppliers — built on first use, after registries exist.
    // User-confirmed mappings + importance tiers.
    // ------------------------------------------------------------------

    private static Map<EntityType<?>, BossProfile> bossProfiles;

    @Nullable
    public static BossProfile bossProfileOf(EntityType<?> type) {
        if (bossProfiles == null) {
            Map<EntityType<?>, BossProfile> map = new HashMap<>();
            // KEYSTONE — the faction's anchor figure.
            map.put(HumanEntityTypes.HINATA_SAKAGUCHI.get(),
                    new BossProfile(BossFaction.LUMINOUS, BossProfile.Importance.KEYSTONE));
            map.put(HumanEntityTypes.GAZEL_DWARGO.get(),
                    new BossProfile(BossFaction.DWARGON, BossProfile.Importance.KEYSTONE));
            map.put(HumanEntityTypes.SHIZU.get(),
                    new BossProfile(BossFaction.SHIZU, BossProfile.Importance.KEYSTONE));
            // MAJOR — faction schemes/calamities.
            map.put(MonsterEntityTypes.CHARYBDIS.get(),
                    new BossProfile(BossFaction.CLAYMAN, BossProfile.Importance.MAJOR));
            map.put(MonsterEntityTypes.ORC_DISASTER.get(),
                    new BossProfile(BossFaction.CLAYMAN, BossProfile.Importance.MAJOR));
            map.put(MonsterEntityTypes.IFRIT.get(),
                    new BossProfile(BossFaction.LEON, BossProfile.Importance.MAJOR));
            // NOTABLE — named lieutenants.
            map.put(HumanEntityTypes.KIRARA_MIZUTANI.get(),
                    new BossProfile(BossFaction.FALMUTH, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.KYOYA_TACHIBANA.get(),
                    new BossProfile(BossFaction.FALMUTH, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.SHOGO_TAGUCHI.get(),
                    new BossProfile(BossFaction.FALMUTH, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.SHIN_RYUSEI.get(),
                    new BossProfile(BossFaction.JURA_ALLIANCE, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.SHINJI_TANIMURA.get(),
                    new BossProfile(BossFaction.JURA_ALLIANCE, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.MARK_LAUREN.get(),
                    new BossProfile(BossFaction.JURA_ALLIANCE, BossProfile.Importance.NOTABLE));
            map.put(HumanEntityTypes.MAI_FURUKI.get(),
                    new BossProfile(BossFaction.OTHERWORLDERS, BossProfile.Importance.NOTABLE));
            // MINOR — fodder; kept small (killing mobs is core Minecraft).
            map.put(HumanEntityTypes.FALMUTH_KNIGHT.get(),
                    new BossProfile(BossFaction.FALMUTH, BossProfile.Importance.MINOR));
            map.put(HumanEntityTypes.FOLGEN.get(),
                    new BossProfile(BossFaction.FALMUTH, BossProfile.Importance.MINOR));
            map.put(MonsterEntityTypes.ORC_LORD.get(),
                    new BossProfile(BossFaction.CLAYMAN, BossProfile.Importance.MINOR));
            bossProfiles = map;
        }
        return bossProfiles.get(type);
    }

    // ------------------------------------------------------------------
    // Marked bosses (faction-model.md #3) — the mark is the authority
    // ------------------------------------------------------------------

    /** The mark on this entity, or null (= faction-insignificant). */
    @Nullable
    public static FactionMarkTag getMark(LivingEntity entity) {
        return entity.hasData(Attachments.FACTION_MARK.get())
                ? entity.getData(Attachments.FACTION_MARK.get()) : null;
    }

    /**
     * Mark an entity as faction-significant and TITLE it in the
     * faction's color ("Clayman's Orc Disaster") — consequence must be
     * visible before the swing. Callers with a bespoke name (lore-event
     * lead bosses) pass {@code title=false} and set their own.
     */
    public static void markBoss(LivingEntity boss, String factionId,
                                String sourceEventId, boolean title) {
        boss.setData(Attachments.FACTION_MARK.get(),
                new FactionMarkTag(factionId, sourceEventId));
        if (title) {
            BossFaction faction = BossFaction.byId(factionId);
            // The nameplate is the entity's own name in the faction colour —
            // NO faction-possessive prefix. EXCEPTION: the Orc Disaster
            // (Geld) keeps its "<faction>'s …" form so its lore identity is
            // unchanged.
            boolean orcDisaster = boss.getType()
                    == io.github.manasmods.tensura.registry.entity.MonsterEntityTypes.ORC_DISASTER.get();
            net.minecraft.network.chat.MutableComponent name;
            if (orcDisaster) {
                String owner = faction != null ? faction.displayName() : factionId;
                name = Component.literal(owner + "'s ").append(boss.getType().getDescription());
            } else {
                name = Component.empty().append(boss.getType().getDescription());
            }
            name.withStyle(faction != null ? faction.color() : net.minecraft.ChatFormatting.WHITE);
            boss.setCustomName(name);
            boss.setCustomNameVisible(true);
        }
        LOGGER.info("[TM] worldrep: marked {} for faction '{}' (source {})",
                boss.getType(), factionId, sourceEventId);
    }

    // ------------------------------------------------------------------
    // Per-faction standing — live base + earned delta
    // ------------------------------------------------------------------

    /**
     * The faction's disposition base toward this player — computed LIVE
     * from the player's current race side. Offline player (or addon
     * faction without a profile) → {@link #DEFAULT_STANDING}; v1
     * consumers all act on online players.
     */
    public static double getBase(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return DEFAULT_STANDING;
        FactionProfile profile = FactionProfile.byId(faction.id());
        if (profile == null) return DEFAULT_STANDING;
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online == null) return DEFAULT_STANDING;
        return profile.base(isMajinSide(online));
    }

    /** The stored EARNED component (what the player's acts added up to);
     *  0 when no mover has ever fired. */
    public static double getEarned(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return 0.0;
        Double stored = WorldReputationSavedData.get(level).getStanding(player, faction.id());
        return stored == null ? 0.0 : stored;
    }

    /** The EFFECTIVE standing every consumer reads:
     *  clamp(liveBase + earnedDelta). Flat NEUTRAL while the faction
     *  layer is disabled. */
    public static double getStanding(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return DEFAULT_STANDING;
        return clamp(getBase(level, player, faction) + getEarned(level, player, faction));
    }

    public static FactionTier getTier(ServerLevel level, UUID player, BossFaction faction) {
        return FactionTier.forValue(getStanding(level, player, faction));
    }

    /**
     * THE mutator — every standing write lands here. Adjusts the EARNED
     * delta, clamped against the CURRENT base so the effective standing
     * stays in [0, 100] without dead accumulation. No-op while the
     * faction layer is disabled.
     */
    public static double modifyStanding(ServerLevel level, UUID player, BossFaction faction,
                                        double amount, WorldRepReason reason) {
        return modifyStandingById(level, player, faction.id(), faction.displayName(),
                amount, reason);
    }

    /** String-id twin for addon faction ids carried by a mark — same
     *  clamp/log path; PRIVATE to keep the enum API the public door. */
    private static double modifyStandingById(ServerLevel level, UUID player, String factionId,
                                             String displayName, double amount,
                                             WorldRepReason reason) {
        if (!isFactionSystemEnabled()) return DEFAULT_STANDING;
        WorldReputationSavedData data = WorldReputationSavedData.get(level);

        FactionProfile profile = FactionProfile.byId(factionId);
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        double base = (profile != null && online != null)
                ? profile.base(isMajinSide(online)) : DEFAULT_STANDING;

        Double stored = data.getStanding(player, factionId);
        double oldDelta = stored == null ? 0.0 : stored;
        double newDelta = Math.max(MIN_STANDING - base,
                Math.min(MAX_STANDING - base, oldDelta + amount));
        data.setStanding(player, factionId, newDelta);

        double before = clamp(base + oldDelta);
        double after = clamp(base + newDelta);
        LOGGER.info("[TM] worldrep: player {} × {} {} {} → {} (base {} + earned {}; {}, tier {})",
                player, displayName,
                String.format("%.1f", before), String.format("%+.1f", amount),
                String.format("%.1f", after),
                String.format("%.1f", base), String.format("%+.1f", newDelta),
                reason, FactionTier.forValue(after));
        return after;
    }

    /** Admin/debug absolute set (the /worldrep set command) — sets the
     *  EFFECTIVE standing by storing {@code value − base} as the delta. */
    public static double setStanding(ServerLevel level, UUID player, BossFaction faction,
                                     double value, WorldRepReason reason) {
        if (!isFactionSystemEnabled()) return DEFAULT_STANDING;
        double clamped = clamp(value);
        double base = getBase(level, player, faction);
        WorldReputationSavedData.get(level).setStanding(player, faction.id(), clamped - base);
        LOGGER.info("[TM] worldrep: player {} × {} SET to {} (base {} + earned {}; {}, tier {})",
                player, faction.displayName(), String.format("%.1f", clamped),
                String.format("%.1f", base), String.format("%+.1f", clamped - base),
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
    // The two-sided weighted movers (faction-model.md #2) — the ONLY
    // faction-consequence entry points for boss violence. Marked only.
    // ------------------------------------------------------------------

    /**
     * A marked boss was KILLED: the victim faction takes the direct hit,
     * its allies lose half of it, its enemies gain 40% of it — each leg
     * scaled by the boss's importance and the TARGET faction's swing
     * multiplier. Also writes the offense ledger. No-op if the entity
     * is unmarked or the layer is disabled.
     */
    public static void applyMarkedBossKill(ServerLevel level, UUID killer, LivingEntity victim) {
        if (!isFactionSystemEnabled()) return;
        FactionMarkTag mark = getMark(victim);
        if (mark == null) return;

        double w = importanceOf(victim);
        String factionId = mark.factionId();
        applyLeg(level, killer, factionId, -KILL_BASE * w, WorldRepReason.MARKED_BOSS_KILLED);
        addOffense(level, killer, factionId, OFFENSE_KILL * w);

        FactionProfile profile = FactionProfile.byId(factionId);
        if (profile == null) return; // addon faction without a web — direct leg only
        for (String ally : profile.allies()) {
            applyLeg(level, killer, ally, -KILL_BASE * w * ALLY_LOSS_FACTOR,
                    WorldRepReason.MARKED_BOSS_RIPPLE);
        }
        for (String enemy : profile.enemies()) {
            applyLeg(level, killer, enemy, KILL_BASE * w * ENEMY_GAIN_FACTOR,
                    WorldRepReason.MARKED_BOSS_RIPPLE);
        }
    }

    /**
     * A marked boss was ATTACKED (caller dedupes rapid combos): direct
     * hit on its own faction only — attacks don't ripple; only kills
     * are statements. Still writes offense.
     */
    public static void applyMarkedBossAttack(ServerLevel level, UUID attacker, LivingEntity victim) {
        if (!isFactionSystemEnabled()) return;
        FactionMarkTag mark = getMark(victim);
        if (mark == null) return;

        double w = importanceOf(victim);
        applyLeg(level, attacker, mark.factionId(), -ATTACK_BASE * w,
                WorldRepReason.MARKED_BOSS_ATTACKED);
        addOffense(level, attacker, mark.factionId(), OFFENSE_ATTACK * w);
    }

    /** One fan-out leg: amount × the target faction's swing multiplier,
     *  through the single mutator. */
    private static void applyLeg(ServerLevel level, UUID player, String factionId,
                                 double amount, WorldRepReason reason) {
        FactionProfile profile = FactionProfile.byId(factionId);
        double swing = profile != null ? profile.swingMultiplier() : 1.0;
        BossFaction faction = BossFaction.byId(factionId);
        String display = faction != null ? faction.displayName() : factionId;
        modifyStandingById(level, player, factionId, display, amount * swing, reason);
    }

    /** The boss's lore-importance weight; entities marked by an addon
     *  without a BOSS_PROFILES entry default to MINOR (conservative). */
    private static double importanceOf(LivingEntity victim) {
        BossProfile profile = bossProfileOf(victim.getType());
        return (profile != null ? profile.importance() : BossProfile.Importance.MINOR).weight();
    }

    // ------------------------------------------------------------------
    // Offense ledger + provocation (faction-model.md #4)
    // ------------------------------------------------------------------

    public static double getOffense(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return 0.0;
        return WorldReputationSavedData.get(level).getOffense(player, faction.id());
    }

    private static void addOffense(ServerLevel level, UUID player, String factionId,
                                   double amount) {
        WorldReputationSavedData data = WorldReputationSavedData.get(level);
        double after = data.getOffense(player, factionId) + amount;
        data.setOffense(player, factionId, after);
        LOGGER.info("[TM] worldrep: player {} offense vs '{}' {} → {}",
                player, factionId, String.format("%+.1f", amount),
                String.format("%.1f", after));
    }

    /** Faction events consumed their retribution — reset the ledger
     *  (lore-events.md #3: offense is spent when a march resolves). */
    public static void clearOffense(ServerLevel level, UUID player, BossFaction faction) {
        WorldReputationSavedData.get(level).clearOffense(player, faction.id());
        LOGGER.info("[TM] worldrep: player {} offense vs '{}' cleared", player, faction.id());
    }

    /** Derived, never stored: offense has crossed THIS faction's
     *  threshold. The arm condition for faction events — standing only
     *  SCALES them (soft influence, no hard rep gate). */
    public static boolean isProvoked(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return false;
        FactionProfile profile = FactionProfile.byId(faction.id());
        if (profile == null) return false;
        return getOffense(level, player, faction) >= profile.provocationThreshold();
    }

    // ------------------------------------------------------------------
    // Diplomacy-closed flags + lore-event recurrence state (consumed by
    // LoreEvents; stored here because they're per-(player, faction/event)
    // world-layer facts — the sole-door discipline holds)
    // ------------------------------------------------------------------

    /** The future diplomacy system's FIRST check. False while the
     *  faction layer is disabled. */
    public static boolean isDiplomacyClosed(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return false;
        return WorldReputationSavedData.get(level).isDiplomacyClosed(player, faction.id());
    }

    /** RECOVERABLE by design (user decision): set on the lead-boss kill,
     *  clearable later via the diplomacy arc's steep mending ritual
     *  ({@link #reopenDiplomacy}). */
    public static void closeDiplomacy(ServerLevel level, UUID player, BossFaction faction) {
        if (!isFactionSystemEnabled()) return;
        WorldReputationSavedData.get(level).closeDiplomacy(player, faction.id());
        LOGGER.info("[TM] worldrep: player {} diplomacy with {} CLOSED (recoverable)",
                player, faction.displayName());
    }

    /** The mending ritual's door — to be wired by the diplomacy build. */
    public static void reopenDiplomacy(ServerLevel level, UUID player, BossFaction faction) {
        WorldReputationSavedData.get(level).reopenDiplomacy(player, faction.id());
        LOGGER.info("[TM] worldrep: player {} diplomacy with {} REOPENED",
                player, faction.displayName());
    }

    /** A slain lore event never recurs for this player. */
    public static boolean isLoreEventDefeated(ServerLevel level, UUID player, String eventId) {
        return WorldReputationSavedData.get(level).isLoreEventDefeated(player, eventId);
    }

    public static void markLoreEventDefeated(ServerLevel level, UUID player, String eventId) {
        WorldReputationSavedData.get(level).markLoreEventDefeated(player, eventId);
        LOGGER.info("[TM] worldrep: player {} lore event '{}' DEFEATED — never recurs",
                player, eventId);
    }

    /** Game tick before which a timed-out lore march cannot recur. */
    public static long getLoreEventCooldownUntil(ServerLevel level, UUID player, String eventId) {
        return WorldReputationSavedData.get(level).getLoreEventCooldownUntil(player, eventId);
    }

    public static void setLoreEventCooldownUntil(ServerLevel level, UUID player,
                                                 String eventId, long untilTick) {
        WorldReputationSavedData.get(level).setLoreEventCooldownUntil(player, eventId, untilTick);
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
        // wants you gone. EFFECTIVE standings — disposition included
        // (the world fears what you ARE). Average (not sum) keeps the
        // scale stable as the cast grows.
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
