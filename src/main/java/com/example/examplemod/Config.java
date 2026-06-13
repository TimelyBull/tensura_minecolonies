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

    /** Master switch for the whole FACTION LAYER (world reputation /
     *  faction standings / marked-boss movers / faction events / future
     *  diplomacy). When false the layer is dormant: standings read as
     *  flat NEUTRAL, no mover writes, marked-boss kills carry no faction
     *  consequences, /worldrep reports the layer disabled. Everything
     *  BELOW the layer (colony reputation, generic raids, the barrier,
     *  assassins, envoys, festivals) is untouched — the gates sit at the
     *  faction layer's entry points only (WorldReputationManager + the
     *  two ExampleMod mover hooks). */
    public static final ModConfigSpec.BooleanValue FACTION_SYSTEM_ENABLED = BUILDER
            .comment("Enable the faction layer (world reputation, faction standings,",
                     "marked-boss consequences, faction events, future diplomacy).",
                     "false = the layer is dormant; boss kills behave pre-faction-system",
                     "(colony +10 and envoy unlocks still apply); colony-level systems",
                     "(colony reputation, raids, barrier, assassins, envoys) unaffected.")
            .define("factionSystemEnabled", true);

    /** Master switch for the assassin system. When false: no
     *  determination buildup, existing LURKING/ARMED plots are defused
     *  on the next daily pass, and ARMED strikes never fire. An
     *  already-ACTIVE boss stays in the world (kill it to reclaim). */
    public static final ModConfigSpec.BooleanValue ENABLE_ASSASSINS = BUILDER
            .comment("Enable the assassin system (mistreated colonies breeding assassins).",
                     "false = no new plots; existing lurking/armed plots defuse;",
                     "an already-active assassin boss remains until slain.")
            .define("enableAssassins", true);

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
