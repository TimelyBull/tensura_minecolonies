package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.EnumMap;

import static com.example.examplemod.RaceSkillProfile.SkillBias;
import static com.example.examplemod.RaceSkillProfile.SkillBias.NONE;

/**
 * Per-race skill-profile registry.
 *
 * <p>Profiles are STARTING BIASES, not absolute overrides — see
 * {@link RaceSkillProfile} for the layering semantics. Each race entry
 * defines biases for the skills it wants to shift; skills omitted (or
 * set to {@link SkillBias#NONE}) keep the MineColonies-randomised
 * baseline.
 *
 * <p>Numbers are deliberately moderate so an orc joining a fresh colony
 * lands within the home-hut skill cap {@code (hutLevel + 1) * 10}
 * (verified by tracing {@code CitizenSkillHandler.addXpToSkill}):
 * a vanilla level-1 hut caps each skill at 20, so an orc with MC's
 * baseline 1-4 + bias 6-10 lands at most ~14 in Strength — still room
 * to grow through normal progression.
 *
 * <p>Designed values (presets defined in {@link SkillBias}):
 * <ul>
 *   <li>{@code HIGH = +8 ± 2} → effective +6 to +10
 *   <li>{@code LOW  = -3 ± 2} → effective -5 to -1 (clamped at 1)
 *   <li>{@code MILD = +3 ± 1} → effective +2 to +4
 *   <li>{@code NONE = 0 ± 0}  → unchanged
 * </ul>
 */
public final class RaceSkillProfiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final RaceSkillProfile ORC = new RaceSkillProfile(orcBiases());
    public static final RaceSkillProfile GOBLIN = new RaceSkillProfile(goblinBiases());
    public static final RaceSkillProfile COLONIST = new RaceSkillProfile(new EnumMap<>(Skill.class));
    public static final RaceSkillProfile LIZARDMAN = new RaceSkillProfile(lizardmanBiases());
    public static final RaceSkillProfile DWARF = new RaceSkillProfile(dwarfBiases());

    /**
     * ORC — tanky warrior. Strong in physical skills, weak in academic
     * ones, baseline in everything else.
     */
    private static EnumMap<Skill, SkillBias> orcBiases() {
        EnumMap<Skill, SkillBias> map = new EnumMap<>(Skill.class);
        map.put(Skill.Strength,     SkillBias.high());
        map.put(Skill.Athletics,    SkillBias.high());
        map.put(Skill.Stamina,      SkillBias.high());
        map.put(Skill.Intelligence, SkillBias.low());
        map.put(Skill.Knowledge,    SkillBias.low());
        map.put(Skill.Creativity,   SkillBias.low());
        // Dexterity, Agility, Mana, Adaptability, Focus: NONE (baseline).
        return map;
    }

    /**
     * GOBLIN — flat generalist. Mild positive on Adaptability and
     * Stamina, no strong peaks or weaknesses elsewhere.
     */
    private static EnumMap<Skill, SkillBias> goblinBiases() {
        EnumMap<Skill, SkillBias> map = new EnumMap<>(Skill.class);
        map.put(Skill.Adaptability, SkillBias.mild());
        map.put(Skill.Stamina,      SkillBias.mild());
        return map;
    }

    /**
     * LIZARDMAN — precision / mystic carrier archetype. Strong in
     * agility-dexterity-focus AND mana — the only earned race that
     * covers Mana, which gates Healer / Alchemist / Enchanter
     * (otherwise no race-bias parity for those jobs). Weak in raw
     * strength and stamina. Baseline everything else.
     *
     * <p>Asymmetry by design: 4 HIGH / 2 LOW (vs orc/dwarf 3 HIGH /
     * 3 LOW). The extra HIGH (Mana) is a narrow gate — only 3 jobs
     * use Mana primary/secondary — so the lizardman doesn't dominate
     * the broader physical / crafting lanes. Peer-equal in practice.
     */
    private static EnumMap<Skill, SkillBias> lizardmanBiases() {
        EnumMap<Skill, SkillBias> map = new EnumMap<>(Skill.class);
        map.put(Skill.Agility,   SkillBias.high());
        map.put(Skill.Dexterity, SkillBias.high());
        map.put(Skill.Focus,     SkillBias.high());
        map.put(Skill.Mana,      SkillBias.high());   // niche expansion: mystic
        map.put(Skill.Strength,  SkillBias.low());
        map.put(Skill.Stamina,   SkillBias.low());
        // Athletics, Intelligence, Knowledge, Creativity, Adaptability: NONE.
        return map;
    }

    /**
     * DWARF — mental / craft archetype. Strong in
     * knowledge-creativity-intelligence — owns the academic and
     * craft-design domain cleanly. Weak in raw physical labour and
     * mobility. Baseline everything else.
     *
     * <p>Shifted from the earlier dexterity-heavy build: Dexterity
     * is now LIZARDMAN's lane (precision), and dwarves take
     * Intelligence in its place so they own the MENTAL domain
     * (Knowledge + Intelligence + Creativity) unambiguously.
     * Strength was added to the LOWs so the dwarf can't double as a
     * heavy-labour race and remains squarely in the head-not-hands
     * lane.
     */
    private static EnumMap<Skill, SkillBias> dwarfBiases() {
        EnumMap<Skill, SkillBias> map = new EnumMap<>(Skill.class);
        map.put(Skill.Knowledge,    SkillBias.high());
        map.put(Skill.Creativity,   SkillBias.high());
        map.put(Skill.Intelligence, SkillBias.high());
        map.put(Skill.Athletics,    SkillBias.low());
        map.put(Skill.Strength,     SkillBias.low());
        map.put(Skill.Agility,      SkillBias.low());
        // Stamina, Dexterity, Focus, Mana, Adaptability: NONE.
        return map;
    }

    /**
     * Look up the profile for {@code race} and apply it to
     * {@code citizenData}. Logs the before/after summary at INFO.
     * Returns silently if no profile is registered for the race.
     */
    public static void applyForRace(ICitizenData citizenData, Race race, RandomSource random) {
        if (race == null) return;
        ColonyMember member = ColonyMember.fromRace(race);
        RaceSkillProfile profile = profileFor(member);
        if (profile == null) {
            LOGGER.info("[TM] skills: no profile registered for race {} — leaving MC baseline untouched", race);
            return;
        }
        String summary = profile.apply(citizenData, random);
        if (summary.isEmpty()) {
            LOGGER.info("[TM] skills: {} profile applied to citizen {} (no changes)", race, citizenData.getId());
        } else {
            LOGGER.info("[TM] skills: {} profile applied to citizen {} — {}", race, citizenData.getId(), summary);
        }
    }

    /** Lookup by {@link ColonyMember} — extension point for the
     *  upcoming LIZARDMAN / DWARF races. */
    public static RaceSkillProfile profileFor(ColonyMember member) {
        return switch (member) {
            case ORC -> ORC;
            case GOBLIN -> GOBLIN;
            case COLONIST -> COLONIST;
            case LIZARDMAN -> LIZARDMAN;
            case DWARF -> DWARF;
        };
    }

    private RaceSkillProfiles() {}
}
