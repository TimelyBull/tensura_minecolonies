package com.example.examplemod;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Master switch for the ENTIRE faction + diplomacy system. This is the
     *  single source of truth — there is no gamerule or command for it.
     *  When false (the DEFAULT), the whole layer is dormant and inaccessible:
     *  no rival-colony / settlement generation, no diplomacy (inbound or
     *  player-sent envoys, deals, trades), no warfare / conquest, no lore
     *  raids (Orc Disaster etc.), no marked-boss world-reputation
     *  consequences, and the Diplomacy / Wars buttons are hidden from the
     *  roster menu. Reads return flat NEUTRAL and writes no-op.
     *  <p>Everything BELOW the faction layer is untouched and stays ON:
     *  naming Tensura mobs as colony citizens, the race-envoy system that
     *  adds races to a colony's spawn set, colony reputation, generic
     *  reputation raids, the barrier, assassins (own toggle), the threat-
     *  response defenders, and festivals. The gates sit at the faction
     *  layer's entry points only (read via
     *  {@link WorldReputationManager#isFactionSystemEnabled()}). */
    public static final ModConfigSpec.BooleanValue ENABLE_FACTION_SYSTEM = BUILDER
            .comment("Enable the ENTIRE faction + diplomacy system (rival colonies,",
                     "settlement generation, diplomacy envoys/deals/trades, warfare and",
                     "conquest, lore raids like the Orc Disaster, and marked-boss world",
                     "reputation). This is the only switch — there is no gamerule/command.",
                     "DEFAULT false = the whole faction layer is off and inaccessible;",
                     "the core mod (Tensura mobs as citizens, race envoys, colony",
                     "reputation, generic raids, barrier, assassins) is unaffected.")
            .define("enableFactionSystem", false);

    /** Master switch for the assassin system. When false: no
     *  determination buildup, existing LURKING/ARMED plots are defused
     *  on the next daily pass, and ARMED strikes never fire. An
     *  already-ACTIVE boss stays in the world (kill it to reclaim). */
    public static final ModConfigSpec.BooleanValue ENABLE_ASSASSINS = BUILDER
            .comment("Enable the assassin system (mistreated colonies breeding assassins).",
                     "false = no new plots; existing lurking/armed plots defuse;",
                     "an already-active assassin boss remains until slain.")
            .define("enableAssassins", true);

    /** How aggressive innately-hostile Tensura mobs are toward colony
     *  citizens — the extra targeting this compat mod adds on top of vanilla
     *  Tensura (which, by itself, does NOT target citizens). OFF (default) =
     *  no added aggression; MEDIUM = about half; HIGH = the old "prey on
     *  sight" behaviour. Read via {@link #mobAggression()}. */
    public enum AggressionLevel { OFF, MEDIUM, HIGH }

    public static final ModConfigSpec.EnumValue<AggressionLevel> MOB_AGGRESSION = BUILDER
            .comment("How aggressive innately-hostile Tensura mobs are toward colony citizens.",
                     "OFF (default) = this mod adds NO extra aggression — citizens are invisible to",
                     "Tensura's hostile-prey targeting, as in vanilla Tensura. MEDIUM = about half:",
                     "only roughly half of mob/citizen encounters treat the citizen as prey, so mobs",
                     "lock on about half as often. HIGH = citizens are unconditional prey on sight",
                     "(the previous behaviour).")
            .defineEnum("mobAggression", AggressionLevel.OFF);

    /** Safe read of the mob-aggression level. Returns OFF if the config
     *  isn't loaded yet (very early startup) — matches the default. */
    public static AggressionLevel mobAggression() {
        try {
            return MOB_AGGRESSION.get();
        } catch (IllegalStateException e) {
            return AggressionLevel.OFF;
        }
    }

    /** How many physical-faction bosses generate as the COLONY version
     *  (a settlement around them) vs. WILD (boss alone). ALL = every
     *  physical boss gets a settlement; SOME = a fraction (see chance);
     *  NONE = the physical-settlement layer is disabled entirely
     *  (factions stay abstract, no settlements generate). */
    public enum SettlementMode { ALL, SOME, NONE }

    public static final ModConfigSpec.EnumValue<SettlementMode> RIVAL_SETTLEMENT_MODE = BUILDER
            .comment("Rival-colony settlements: ALL (every physical faction boss gets a town),",
                     "SOME (a fraction — see rivalSettlementSomeChance), NONE (disabled).")
            .defineEnum("rivalSettlementMode", SettlementMode.SOME);

    /** Under SOME: the fraction of physical-boss generations that become
     *  the COLONY version (the rest are WILD). */
    public static final ModConfigSpec.DoubleValue RIVAL_SETTLEMENT_SOME_CHANCE = BUILDER
            .comment("Under SOME mode: chance (0..1) a physical faction boss generates as a settlement.")
            .defineInRange("rivalSettlementSomeChance", 0.5, 0.0, 1.0);

    /** Allow rare NATURAL settlement generation on the scheduler. When
     *  false, settlements only appear via the debug command (testing). */
    public static final ModConfigSpec.BooleanValue RIVAL_NATURAL_GEN = BUILDER
            .comment("Allow rival settlements to generate naturally over time (false = debug-command only).")
            .define("rivalNaturalGeneration", true);

    /** Drago Nova: does the blast harm allies/citizens/subordinates? */
    public static final ModConfigSpec.BooleanValue DRAGO_NOVA_HARM_ALLIES = BUILDER
            .comment("Drago Nova: the blast also harms allies, citizens and named subordinates.")
            .define("dragoNovaHarmAllies", false);

    /** Drago Nova: does the blast break terrain? */
    public static final ModConfigSpec.BooleanValue DRAGO_NOVA_BREAK_BLOCKS = BUILDER
            .comment("Drago Nova: the blast damages the ground (a TNT-style crater).")
            .define("dragoNovaBreakBlocks", false);

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
