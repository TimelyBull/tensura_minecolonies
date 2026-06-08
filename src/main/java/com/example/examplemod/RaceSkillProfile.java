package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-race STARTING-BIAS profile, layered on MineColonies' normal
 * skill-init randomisation.
 *
 * <p>NOT a replacement for MC's progression. {@link #apply} reads the
 * skill levels MC's {@code init(levelCap)} already produced (called inside
 * {@code createAndRegisterCivilianData} from
 * {@code CitizenData.initForNewCivilian}) and adds a bias on top:
 *
 * <pre>
 *   newLevel = clamp(currentLevel + meanBias + randInt[-spread, +spread], 1, 99)
 * </pre>
 *
 * <p>An orc joining a happiness-5 colony (MC rolls 1-4) starts a few
 * levels stronger than a vanilla colonist would. An orc joining a
 * happiness-10 colony (MC rolls 1-9) starts proportionally higher — the
 * bias is RELATIVE to MC's colony-scaled baseline, not an absolute
 * override.
 *
 * <p>Skills absent from {@link #biases} get NO change — they keep the
 * value MC randomly assigned.
 *
 * <p>Apply ONCE at naming-to-citizen time. The bias persists on
 * {@code CitizenData} through every save / send / summon cycle. Normal
 * MineColonies progression (XP from working → level-up) continues
 * untouched after application: confirmed by reading
 * {@code CitizenSkillHandler.addXpToSkill} — no internal "initialized"
 * flag, no reset on setLevel, just `skillData.level + skillData.experience`
 * growth gated by the home-hut cap {@code (hutLevel + 1) * 10} and the
 * absolute 99 max.
 */
public record RaceSkillProfile(EnumMap<Skill, SkillBias> biases) {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 99;

    /**
     * Per-skill bias. {@code meanBias} shifts the average; {@code spread}
     * is the symmetric range of per-citizen variance around the mean. A
     * profile of {@code (8, 2)} produces +6 to +10 on top of MC's baseline.
     */
    public record SkillBias(int meanBias, int spread) {
        /** No bias — unchanged. */
        public static final SkillBias NONE = new SkillBias(0, 0);

        /** Quick presets used by {@link RaceSkillProfiles} for readability. */
        public static SkillBias high()  { return new SkillBias(+8, 2); }
        public static SkillBias low()   { return new SkillBias(-3, 2); }
        public static SkillBias mild()  { return new SkillBias(+3, 1); }
    }

    /**
     * Apply this profile's bias to {@code data}'s skill handler. Reads
     * each biased skill's current level (set by MC's random init), adds
     * the bias, clamps, and writes back via {@code SkillData.setLevel}.
     * Skills absent from {@link #biases} are untouched.
     *
     * @return a brief summary string for logging.
     */
    public String apply(ICitizenData data, RandomSource random) {
        ICitizenSkillHandler handler = data.getCitizenSkillHandler();
        Map<Skill, com.minecolonies.core.entity.citizen.citizenhandlers.CitizenSkillHandler.SkillData> skills
                = handler.getSkills(); // unmodifiable map; values mutable.
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<Skill, SkillBias> entry : biases.entrySet()) {
            Skill skill = entry.getKey();
            SkillBias bias = entry.getValue();
            if (bias.meanBias == 0 && bias.spread == 0) continue;

            var skillData = skills.get(skill);
            if (skillData == null) continue;
            int current = skillData.getLevel();
            int roll = bias.spread == 0 ? 0
                    : random.nextInt(bias.spread * 2 + 1) - bias.spread;
            int updated = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, current + bias.meanBias + roll));
            skillData.setLevel(updated);
            summary.append(skill).append(":").append(current).append("->").append(updated).append(" ");
        }
        // Mark the data dirty so the new levels persist on next save.
        data.markDirty(10);
        return summary.toString().trim();
    }
}
