package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenSkillHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harvest Festival — the persistent, prestige-resettable colony buff.
 *
 * <p>This class owns the INDIRECT-buff track (the spec's #3): a one-time
 * (per colony) tiered MineColonies-skill bonus, ranked by Tensura EP, baked into
 * the citizens' skill levels so it drives productivity even past the 99 cap
 * (the {@code 0.85^(primary/2)} formula reads the raw {@code getLevel}; see
 * {@code docs/decisions.md}). Every bonus is recorded in {@link FestivalSavedData}
 * so a prestige reset can subtract it back out cleanly.
 *
 * <p>The Tensura swap / base-festival / stat-sync track (spec #1/#2/#4) is wired
 * separately; for unloaded colonies it's queued via
 * {@link FestivalSavedData#queueSwap}. Skill buffs here run on {@code CitizenData}
 * and therefore work whether or not the colony is loaded.
 */
public final class HarvestFestival {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tier bonuses to the top-3 skills: T1=+4, T2=+3, T3=+2, T4=+1. */
    private static final int[] TIER_BONUS = {4, 3, 2, 1};
    /** How many top skills each tiered citizen gets the bonus on. */
    private static final int TIER_TOP_SKILLS = 3;
    /** The "very minimal" baseline for untiered citizens (incl. vanilla, EP 0): +1 to the single top skill. */
    private static final int MINIMAL_BONUS = 1;
    private static final int MINIMAL_TOP_SKILLS = 1;

    private HarvestFestival() {}

    /**
     * Full festival: the once-per-colony skill prestige AND the Tensura EP gift to
     * every IN_COLONY subordinate. Called on the awakening COMPLETION
     * ({@code AWAKENING_EVENT}) and by {@code /festival run}.
     *
     * <p><b>Why the EP gift runs every time but the skill prestige only once:</b>
     * base Tensura re-gifts a demon lord's subordinates on every festival (each
     * one multiplies their EP), so the EP gift to our colony-form subordinates
     * mirrors that and is NOT gated by the per-colony flag. The colony-wide skill
     * bonus is the persistent, prestige-resettable buff — it applies once per
     * colony ({@code isDone} guard) and is reverted by a reset scroll.
     */
    public static void onEnterFestival(ServerPlayer host) {
        runFestival(host, true);
    }

    /**
     * The festival START hook ({@code ENTER_HARVEST_FESTIVAL_EVENT}) applies only
     * the once-per-colony skill prestige — NOT the EP gift. The EP gift waits for
     * the awakening completion so a single festival (which fires both the start
     * and the completion event) doesn't multiply EP twice.
     */
    public static void applyPrestigeOnly(ServerPlayer host) {
        runFestival(host, false);
    }

    private static void runFestival(ServerPlayer host, boolean withEpGift) {
        ServerLevel level = host.serverLevel();
        FestivalSavedData fest = FestivalSavedData.get(level);
        UUID hostId = host.getUUID();
        double playerEP = ExampleMod.playerEP(host);

        int owned = 0, prestige = 0, gifted = 0;
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            if (!hostId.equals(colony.getPermissions().getOwner())) continue;
            owned++;
            int colonyId = colony.getID();
            try {
                // Skill prestige: once per colony.
                if (!fest.isDone(colonyId)) {
                    applyIndirectBuffs(level, colony, playerEP, fest);
                    fest.markDone(colonyId);
                    prestige++;
                }
                // Tensura EP gift: every festival (mirrors base Tensura re-gifting
                // the demon lord's subordinates each time). Not gated by isDone.
                if (withEpGift) {
                    gifted += applyTensuraEPGifts(level, colonyId, fest);
                }
            } catch (Throwable t) {
                LOGGER.error("[TM] festival: failed on colony {}", colonyId, t);
            }
        }
        LOGGER.info("[TM] festival: {} owned colony(ies) in {} — skill-prestige applied to {}, EP-gifted {} citizen(s) (epGift={}, playerEP {})",
                owned, level.dimension().location(), prestige, gifted, withEpGift, playerEP);
        // Sync the (possibly new) skill bonuses to the owner's client for the "+X" UI.
        ExampleMod.sendFestivalBonus(host);
    }

    /**
     * Tensura EP track (option A): apply Tensura's festival stat gift directly to
     * each IN_COLONY subordinate's snapshot (EP multiply; no swap, no proximity,
     * no demon-lord flag). Works regardless of load state.
     *
     * <p><b>Once per subordinate</b> — like base Tensura, a subordinate upgrades
     * only once. We gate on the per-identity {@code isGifted} flag (NOT the
     * per-colony done flag), so a subordinate that joins the colony AFTER its
     * first festival still gets its single upgrade on the next one, and a
     * subordinate already gifted is never gifted again no matter how many
     * festivals fire. The flag is cleared by a prestige reset.
     */
    private static int applyTensuraEPGifts(ServerLevel level, int colonyId, FestivalSavedData fest) {
        RaceIdentitySavedData ids = RaceIdentitySavedData.get(level);
        int gifted = 0;
        for (RaceIdentitySavedData.RaceIdentity id : ids.all()) {
            if (id.colonyId != colonyId) continue;
            if (id.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
            if (fest.isGifted(id.identityId)) continue;   // already had its one upgrade
            try {
                if (ExampleMod.applyFestivalEPGift(level, ids, id)) {
                    fest.markGifted(id.identityId);
                    gifted++;
                }
            } catch (Throwable t) {
                LOGGER.warn("[TM] festival: EP gift failed for identity {}", id.identityId, t);
            }
        }
        return gifted;
    }

    /**
     * Apply the tiered indirect skill buffs to every citizen of the colony,
     * ranked by Tensura EP descending (strongest first → biggest tier).
     */
    private static void applyIndirectBuffs(ServerLevel level, IColony colony,
                                           double playerEP, FestivalSavedData fest) {
        int colonyId = colony.getID();
        int tier1 = tier1Count(playerEP);

        // Rank all citizens by EP descending (vanilla citizens have EP 0 → bottom).
        List<ICitizenData> citizens = new ArrayList<>(colony.getCitizenManager().getCitizens());
        Map<Integer, Double> epByCitizen = new java.util.HashMap<>();
        for (ICitizenData cd : citizens) {
            epByCitizen.put(cd.getId(), ExampleMod.citizenEP(level, cd.getId()));
        }
        citizens.sort(Comparator
                .comparingDouble((ICitizenData cd) -> epByCitizen.getOrDefault(cd.getId(), 0.0))
                .reversed()
                .thenComparingInt(ICitizenData::getId));

        // Cumulative tier boundaries: T1=[0,t), T2=[t,3t), T3=[3t,6t), T4=[6t,10t), rest=minimal.
        int t = tier1;
        int countT1 = 0, countT2 = 0, countT3 = 0, countT4 = 0, countMin = 0;
        for (int i = 0; i < citizens.size(); i++) {
            ICitizenData cd = citizens.get(i);
            int bonus;
            int topSkills;
            if (t > 0 && i < t)            { bonus = TIER_BONUS[0]; topSkills = TIER_TOP_SKILLS; countT1++; }
            else if (t > 0 && i < 3 * t)   { bonus = TIER_BONUS[1]; topSkills = TIER_TOP_SKILLS; countT2++; }
            else if (t > 0 && i < 6 * t)   { bonus = TIER_BONUS[2]; topSkills = TIER_TOP_SKILLS; countT3++; }
            else if (t > 0 && i < 10 * t)  { bonus = TIER_BONUS[3]; topSkills = TIER_TOP_SKILLS; countT4++; }
            else                            { bonus = MINIMAL_BONUS; topSkills = MINIMAL_TOP_SKILLS; countMin++; }
            applyCitizenSkillBonus(cd, colonyId, bonus, topSkills, fest);
        }
        LOGGER.info("[TM] festival colony {}: tier1Count={} -> T1={} T2={} T3={} T4={} minimal={} ({} citizens)",
                colonyId, tier1, countT1, countT2, countT3, countT4, countMin, citizens.size());
    }

    /**
     * Add {@code bonus} to a citizen's {@code topSkills} highest skills, baking it
     * into the raw level (can exceed 99 — that's intended; the productivity
     * formula reads the raw level) and recording the offset for prestige reset.
     */
    private static void applyCitizenSkillBonus(ICitizenData cd, int colonyId, int bonus,
                                               int topSkills, FestivalSavedData fest) {
        if (bonus <= 0) return;
        ICitizenSkillHandler handler = cd.getCitizenSkillHandler();
        Map<Skill, CitizenSkillHandler.SkillData> skills = handler.getSkills();

        List<Map.Entry<Skill, CitizenSkillHandler.SkillData>> ranked = new ArrayList<>(skills.entrySet());
        ranked.sort(Comparator
                .comparingInt((Map.Entry<Skill, CitizenSkillHandler.SkillData> e) -> e.getValue().getLevel())
                .reversed()
                .thenComparingInt(e -> e.getKey().ordinal()));

        int n = Math.min(topSkills, ranked.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<Skill, CitizenSkillHandler.SkillData> e = ranked.get(i);
            CitizenSkillHandler.SkillData sd = e.getValue();
            sd.setLevel(sd.getLevel() + bonus); // intentionally allowed past 99
            fest.addOffset(colonyId, cd.getId(), e.getKey().ordinal(), bonus);
        }
        cd.markDirty(10);
    }

    /**
     * Prestige reset for a colony: subtract every recorded festival offset from
     * its citizens' skills, then clear the colony's festival record. Works on
     * {@code CitizenData} so it doesn't require loaded entities.
     */
    public static void resetColony(ServerLevel level, IColony colony, FestivalSavedData fest) {
        int colonyId = colony.getID();
        Map<Integer, Map<Integer, Integer>> colonyOffsets = fest.offsetsForColony(colonyId);
        int reverted = 0;
        for (var citizenEntry : colonyOffsets.entrySet()) {
            ICitizenData cd = colony.getCitizenManager().getCivilian(citizenEntry.getKey());
            if (cd == null) continue;
            Map<Skill, CitizenSkillHandler.SkillData> skills = cd.getCitizenSkillHandler().getSkills();
            for (var skillEntry : citizenEntry.getValue().entrySet()) {
                Skill skill = Skill.values()[skillEntry.getKey()];
                CitizenSkillHandler.SkillData sd = skills.get(skill);
                if (sd == null) continue;
                sd.setLevel(Math.max(1, sd.getLevel() - skillEntry.getValue()));
                reverted++;
            }
            cd.markDirty(10);
        }
        // Clear the per-subordinate one-time gift flags for this colony so the
        // festival can be earned again after a prestige reset. (The EP/stat gift
        // itself is a Tensura-side buff and is not auto-reverted — see
        // docs/decisions.md; re-running after a reset re-applies it.)
        RaceIdentitySavedData ids = RaceIdentitySavedData.get(level);
        int clearedGifts = 0;
        for (RaceIdentitySavedData.RaceIdentity id : ids.all()) {
            if (id.colonyId == colonyId && fest.isGifted(id.identityId)) {
                fest.clearGifted(id.identityId);
                clearedGifts++;
            }
        }
        fest.clearColony(colonyId);
        LOGGER.info("[TM] festival reset: colony {} — reverted {} skill offsets, cleared {} subordinate gift flags",
                colonyId, reverted, clearedGifts);
    }

    /**
     * Tier-1 citizen count from the player's EP (the spec's count function).
     * T2/T3/T4 counts are 2×/3×/4× this in {@link #applyIndirectBuffs}.
     */
    static int tier1Count(double playerEP) {
        if (playerEP < 1_000_000.0) {
            return (int) Math.round(playerEP / 100_000.0);
        }
        return 10 + (int) Math.round((playerEP - 1_000_000.0) / 500_000.0);
    }
}
