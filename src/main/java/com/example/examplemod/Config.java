package com.example.examplemod;

import net.neoforged.neoforge.common.ModConfigSpec;

/** This mod's config. A COMMON spec for the launch-time options, plus a
 *  per-world SERVER spec for the gameplay master switches that must be
 *  changeable from the in-game config menu. */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** SERVER-side config spec (per-world). Lives in
     *  {@code saves/<world>/serverconfig/tensura_minecolonies-server.toml} and
     *  is (re)loaded on every world load, so the in-game config menu can
     *  actually change it — unlike the COMMON spec, which loads once per game
     *  launch. Holds the per-world gameplay master switches (faction system,
     *  defense form-swap) — see below. */
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

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
     *  {@link WorldReputationManager#isFactionSystemEnabled()}).
     *  <p>This is a per-world SERVER config marked {@code worldRestart()}:
     *  changing it in the in-game config menu prompts the player to reload the
     *  world, and the new value takes effect on world (re)entry. (It used to be
     *  a COMMON config, where the in-game menu silently did nothing because the
     *  running session kept the value cached — players had to edit the file by
     *  hand. See docs/user-bug-reports.md, 2026-07-04.) Because it isn't loaded
     *  at the main menu, {@link WorldReputationManager#isFactionSystemEnabled()}
     *  catches the not-loaded case and returns false. */
    public static final ModConfigSpec.BooleanValue ENABLE_FACTION_SYSTEM = SERVER_BUILDER
            .comment("Enable the ENTIRE faction + diplomacy system (rival colonies,",
                     "settlement generation, diplomacy envoys/deals/trades, warfare and",
                     "conquest, lore raids like the Orc Disaster, and marked-boss world",
                     "reputation). This is the only switch — there is no gamerule/command.",
                     "DEFAULT false = the whole faction layer is off and inaccessible;",
                     "the core mod (Tensura mobs as citizens, race envoys, colony",
                     "reputation, generic raids, barrier, assassins) is unaffected.",
                     "This is a per-world setting: after changing it, reload the world to apply.")
            .worldRestart()
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

    /** Master switch for the colony threat-response defense form-swap. When a
     *  colony is raided, strong (EP ≥ 10k) non-guard Tensura-race citizens
     *  place-swap into their full Tensura monster body and fight with skills,
     *  then swap back when the raid ends ({@link ColonyThreatResponse} /
     *  {@link ExampleMod#defenseSwapToSubordinate}). Some players don't want
     *  their citizens transforming into rampaging monsters mid-raid. When
     *  false: no citizen ever swaps to fight (guards still guard, everyone else
     *  uses MineColonies' native flee), and any citizen currently swapped-in is
     *  swapped back on the next tick. DEFAULT true = current behaviour. Read via
     *  {@link #enableDefenseSwap()}.
     *  <p>Per-world SERVER config marked {@code worldRestart()} (same reasoning
     *  as {@link #ENABLE_FACTION_SYSTEM}): the in-game config menu applies it on
     *  world reload, instead of the COMMON-config cache trap where the menu
     *  silently does nothing until the file is hand-edited. */
    public static final ModConfigSpec.BooleanValue ENABLE_DEFENSE_SWAP = SERVER_BUILDER
            .comment("Enable the colony defense form-swap: during a raid, strong non-guard",
                     "Tensura-race citizens transform into their monster body and fight with",
                     "skills, then change back when the raid ends. false = citizens never",
                     "transform to fight (guards still guard; others flee as MineColonies",
                     "normally handles it); any citizen already transformed reverts next tick.",
                     "This is a per-world setting: after changing it, reload the world to apply.")
            .worldRestart()
            .define("enableDefenseSwap", true);

    /** Safe read of the defense-swap toggle. Returns true (the default) if the
     *  config isn't loaded yet (e.g. at the main menu — SERVER configs load per
     *  world). The scheduler only reads this while a world is loaded, so the
     *  fallback effectively only matters very early in startup. */
    public static boolean enableDefenseSwap() {
        try {
            return ENABLE_DEFENSE_SWAP.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Master switch for the generic (reputation-triggered) Tensura raids. When
     *  false, the nightfall trigger never fires — a colony whose reputation has
     *  fallen below NEUTRAL is no longer raided ({@link TensuraRaids}). A raid
     *  already in progress when this is turned off still resolves normally (the
     *  drive/resolution pass is not gated), and the {@code /tensuraraid} debug
     *  command still force-starts one for testing. This does NOT affect the
     *  faction-system's lore raids (the Orc Disaster etc.) — those are gated by
     *  {@link #ENABLE_FACTION_SYSTEM}. DEFAULT true = current behaviour. Read via
     *  {@link #enableRaids()}.
     *  <p>Per-world SERVER config marked {@code worldRestart()} (same reasoning
     *  as {@link #ENABLE_FACTION_SYSTEM}): the in-game config menu applies it on
     *  world reload. */
    public static final ModConfigSpec.BooleanValue ENABLE_RAIDS = SERVER_BUILDER
            .comment("Enable generic reputation-triggered raids: when a colony's standing falls",
                     "below Neutral, hostile Tensura mobs may raid it at nightfall. false = these",
                     "raids never trigger (a raid already underway still finishes; the debug",
                     "command still works). Does not affect the faction system's lore raids like",
                     "the Orc Disaster (those follow the faction-system switch).",
                     "This is a per-world setting: after changing it, reload the world to apply.")
            .worldRestart()
            .define("enableRaids", true);

    /** Safe read of the raids toggle. Returns true (the default) when the config
     *  isn't loaded yet (main menu — SERVER configs load per world). The
     *  scheduler only reads this while a world is loaded. */
    public static boolean enableRaids() {
        try {
            return ENABLE_RAIDS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Protect colony builds from Tensura giant-mob block breaking. Tensura's
     *  {@code IGiantMob} mobs (Orc Lord, Charybdis, Megalodon, Knight Spider,
     *  Giant Ant, …) smash blocks by walking into them, and Tensura only checks
     *  the vanilla {@code mobGriefing} gamerule — it is NOT colony-aware, so a
     *  colony gets wrecked even though MineColonies would normally protect it.
     *  When true (DEFAULT) these mobs cannot break blocks inside any colony's
     *  claimed area. When false, this mod adds no protection (vanilla Tensura
     *  behaviour). Handled by {@code IGiantMobMixin} via
     *  {@link GriefProtection#blockMobGrief}. Read via
     *  {@link #protectColoniesFromMobGriefing()}.
     *  <p>Per-world SERVER config: changeable from the in-game config menu and
     *  read live on every break attempt, so it applies immediately. */
    public static final ModConfigSpec.BooleanValue PROTECT_FROM_MOB_GRIEF = SERVER_BUILDER
            .comment("Protect colony blocks from Tensura giant mobs (Orc Lord, Charybdis, etc.)",
                     "that break blocks by walking into them. true (default) = these mobs cannot",
                     "break blocks inside a colony's claimed area. false = no protection added",
                     "(they break blocks as vanilla Tensura allows). Applies immediately.")
            .define("protectColoniesFromMobGriefing", true);

    /** Safe read of the mob-grief protection toggle. Returns true (the default)
     *  if the config isn't loaded yet (very early startup). */
    public static boolean protectColoniesFromMobGriefing() {
        try {
            return PROTECT_FROM_MOB_GRIEF.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Protect colony builds from Tensura terraforming-SKILL block breaking.
     *  Skills like Earth Manipulation / Domination, Molecular Manipulation,
     *  Fusionist and Degenerate reshape terrain and, like the mobs above, only
     *  respect the vanilla {@code mobGriefing} gamerule. When true (DEFAULT)
     *  these skills cannot break blocks inside any colony's claimed area (the
     *  cast still fires elsewhere; only the colony-interior blocks are spared).
     *  When false, this mod adds no protection. Handled by the
     *  {@code SKILL_GRIEF_PRE} listener via {@link GriefProtection#blockSkillGrief}.
     *  Read via {@link #protectColoniesFromSkillGriefing()}.
     *  <p>Per-world SERVER config: changeable from the in-game config menu and
     *  read live on every cast, so it applies immediately. */
    public static final ModConfigSpec.BooleanValue PROTECT_FROM_SKILL_GRIEF = SERVER_BUILDER
            .comment("Protect colony blocks from Tensura terraforming skills (Earth Manipulation,",
                     "Molecular Manipulation, Fusionist, Degenerate, etc.). true (default) = these",
                     "skills cannot break blocks inside a colony's claimed area. false = no",
                     "protection added (they grief as vanilla Tensura allows). Applies immediately.")
            .define("protectColoniesFromSkillGriefing", true);

    /** Safe read of the skill-grief protection toggle. Returns true (the default)
     *  if the config isn't loaded yet (very early startup). */
    public static boolean protectColoniesFromSkillGriefing() {
        try {
            return PROTECT_FROM_SKILL_GRIEF.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** How aggressive innately-hostile Tensura mobs are toward colony
     *  citizens — the extra targeting this compat mod adds on top of vanilla
     *  Tensura (which, by itself, does NOT target citizens). OFF (default) =
     *  no added aggression; MEDIUM = about half; HIGH = the old "prey on
     *  sight" behaviour. Read via {@link #citizenAggression()}. */
    public enum AggressionLevel { OFF, MEDIUM, HIGH }

    public static final ModConfigSpec.EnumValue<AggressionLevel> CITIZEN_AGGRESSION = BUILDER
            .comment("How aggressive innately-hostile Tensura mobs are toward colony citizens.",
                     "OFF (default) = this mod adds NO extra aggression — citizens are invisible to",
                     "Tensura's hostile-prey targeting, as in vanilla Tensura. MEDIUM = about half:",
                     "only roughly half of mob/citizen encounters treat the citizen as prey, so mobs",
                     "lock on about half as often. HIGH = citizens are unconditional prey on sight",
                     "(the previous behaviour).")
            .defineEnum("citizenAggression", AggressionLevel.OFF);

    /** Safe read of the citizen-aggression level. Returns OFF if the config
     *  isn't loaded yet (very early startup) — matches the default. */
    public static AggressionLevel citizenAggression() {
        try {
            return CITIZEN_AGGRESSION.get();
        } catch (IllegalStateException e) {
            return AggressionLevel.OFF;
        }
    }

    /** Allow NATURAL settlement generation as players explore — both the
     *  worldgen faction towns and the adoption of Tensura dwarf villages as
     *  Dwargon settlements. When false, neither happens on its own and
     *  settlements only appear via the debug commands.
     *  <p>(0.2.0) This absorbed the old {@code rivalSettlementMode}: its ALL and
     *  SOME values had become indistinguishable once worldgen anchors always
     *  populated as colonies, and its NONE value did the same job as setting
     *  this to false. */
    public static final ModConfigSpec.BooleanValue RIVAL_NATURAL_GEN = BUILDER
            .comment("Allow rival faction settlements to generate naturally as you explore.",
                     "false = no settlement ever appears on its own (debug commands only).")
            .define("rivalNaturalGeneration", true);

    /** Drago Nova: does the blast harm allies/citizens/subordinates? */
    public static final ModConfigSpec.BooleanValue DRAGO_NOVA_HARM_ALLIES = BUILDER
            .comment("Drago Nova: the blast also harms allies, citizens and named subordinates.")
            .define("dragoNovaHarmAllies", false);

    /** Drago Nova: does the blast break terrain? */
    public static final ModConfigSpec.BooleanValue DRAGO_NOVA_BREAK_BLOCKS = BUILDER
            .comment("Drago Nova: the blast damages the ground (a TNT-style crater).")
            .define("dragoNovaBreakBlocks", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Built from {@link #SERVER_BUILDER} — the per-world SERVER spec holding the
     *  world-gameplay toggles that must be changeable from the in-game config
     *  menu ({@link #ENABLE_FACTION_SYSTEM}, {@link #ENABLE_DEFENSE_SWAP}).
     *  Registered as {@code ModConfig.Type.SERVER} in {@code ExampleMod}. */
    static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
