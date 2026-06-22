package com.example.examplemod;

import com.minecolonies.api.entity.citizen.Skill;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One faction deal's STATIC definition (docs/diplomacy.md #3) — the
 * registry half of the deal framework. Persisted progress lives in
 * {@link ActiveDeal}; this record is pure data, string-keyed for the
 * addon door.
 *
 * <p><b>Stage 2:</b> deals are now authored per-faction
 * ({@link #FACTION_DEALS} — each faction offers what it VALUES;
 * {@link #DEALS} remains the global id → spec index), and the sealed
 * {@link Requirement} gained {@link LendCitizens} — the time-delayed
 * citizen-lending deal (VANILLA colonists only in Stage 2; race-citizen
 * lending is a documented follow-on).
 *
 * @param standingReward  paid on FULFILLMENT through the sole door
 *                        (WorldRepReason.DIPLOMACY)
 * @param standingPenalty charged on deadline FAILURE
 * @param deadlineTicks   accept → due window (lending deals fulfil at
 *                        accept, so it's unused there)
 * @param payoffDelayTicks 0 = reward lands instantly on fulfillment;
 *                        >0 = AWAITING_PAYOFF first (lending uses the
 *                        requirement's own duration instead)
 * @param minTier         offer gating — better deals at higher standing
 * @param milestone       reserved for Stage-4 milestone deals (the
 *                        mending ritual); the alliance pact is a PROMPT
 */
public record DealSpec(
        String id,
        String title,
        Requirement requirement,
        List<ItemStack> rewardItems,
        double standingReward,
        double standingPenalty,
        long deadlineTicks,
        long payoffDelayTicks,
        FactionTier minTier,
        boolean milestone) {

    /** What the faction asks for. Sealed — the extension seam. */
    public sealed interface Requirement
            permits SupplyItems, BuildingLevel, Population, Happiness, LendCitizens,
                    MendingRite, SupplyBundle, SlayEntities {
        /** Player-facing one-liner ("Supply 64 Iron Ingot"). */
        String summary();
    }

    /** Deliver a BUNDLE of different items in one act (all-or-nothing
     *  via the Deliver button) — the Covenant-commission shape. */
    public record SupplyBundle(List<ItemStack> items) implements Requirement {
        @Override public String summary() {
            StringBuilder sb = new StringBuilder("Deliver ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i).getCount()).append(" ")
                        .append(items.get(i).getHoverName().getString());
            }
            return sb.toString();
        }
    }

    /** Slay {@code count} of the listed entity types (tracked from the
     *  player's kills while the deal runs) — the hunt/combat shape. */
    public record SlayEntities(java.util.Set<String> entityTypeIds, int count,
                               String label) implements Requirement {
        @Override public String summary() {
            return "Slay " + count + " — " + label;
        }
    }

    /** Deliver N of an item via the tab's Deliver button (PUSH —
     *  consumed from the player's inventory; no deposit block). */
    public record SupplyItems(Item item, int count) implements Requirement {
        @Override public String summary() {
            return "Supply " + count + " " + new ItemStack(item).getHoverName().getString();
        }
    }

    /** A building of this schematic at ≥ this level in the deal's bound
     *  colony — detected via the existing BuildingConstructionModEvent
     *  hook; already-met requirements are filtered at OFFER time. */
    public record BuildingLevel(String schematicName, int level) implements Requirement {
        @Override public String summary() {
            return "Raise the " + schematicName + " to level " + level;
        }
    }

    /** Colony population milestone (polled on the 1 s scheduler). */
    public record Population(int citizens) implements Requirement {
        @Override public String summary() {
            return "Grow the colony to " + citizens + " citizens";
        }
    }

    /** Colony average-happiness milestone (polled). */
    public record Happiness(double average) implements Requirement {
        @Override public String summary() {
            return "Raise colony happiness to " + String.format("%.0f", average);
        }
    }

    /**
     * Stage 2 — LEND citizens: {@code count} VANILLA colonists with
     * {@code skill ≥ minLevel} leave the colony for
     * {@code durationTicks} (the workforce genuinely drops — snapshots
     * ride the ActiveDeal NBT), then return trained
     * (+{@code skillBoost} to the skill) alongside the item reward.
     * Race-citizens (with a RaceIdentity) are excluded from the picker
     * — the Stage-2 identity-collision guard.
     */
    public record LendCitizens(Skill skill, int minLevel, int count,
                               long durationTicks, int skillBoost,
                               List<Skill> secondarySkills) implements Requirement {
        /** The Stage-2 shape (no secondary skills). */
        public LendCitizens(Skill skill, int minLevel, int count,
                            long durationTicks, int skillBoost) {
            this(skill, minLevel, count, durationTicks, skillBoost, List.of());
        }

        @Override public String summary() {
            String extras = secondarySkills.isEmpty() ? "" : " +"
                    + secondarySkills.stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.joining("/"));
            return "Lend " + count + " citizens with " + skill.name() + " >= " + minLevel
                    + " for " + (durationTicks / DealSpec.DAY) + " days (return +"
                    + skillBoost + " " + skill.name() + extras + ")";
        }
    }

    /**
     * Stage 4 — the MENDING RITE: grave atonement for a foreclosed
     * faction (diplomacy-closed). The steep price: a large tribute AND
     * the SACRIFICE of the player's strongest named subordinate (EP ≥
     * {@code minSacrificeEP}; the body must be present for the rite).
     * Fulfilment clears the closed flag and reopens relations at a LOW
     * standing — forgiveness to REBUILD, not restoration.
     */
    public record MendingRite(Item tributeItem, int tributeCount,
                              double minSacrificeEP) implements Requirement {
        @Override public String summary() {
            return "Offer " + tributeCount + " "
                    + new ItemStack(tributeItem).getHoverName().getString()
                    + " and SACRIFICE your strongest named subordinate (EP >= "
                    + (long) minSacrificeEP + ")";
        }
    }

    /** Player-facing reward one-liner. */
    public String rewardSummary() {
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : rewardItems) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(stack.getCount()).append(" ").append(stack.getHoverName().getString());
        }
        return sb.length() == 0 ? "the faction's gratitude" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Stage-2 FACTION-FLAVORED deal tables — each faction offers what it
    // values; gating via minTier (better deals as standing rises).
    // Clayman / Leon / Otherworlders / Shizu offer NOTHING (hostility-
    // oriented or aloof — relations can open, but no deals). All
    // schematic names verified against ModBuildings constants; all
    // numbers are tuning values (lend skill bars kept LOW so fresh
    // colonies can actually staff them). Days = 24 000 ticks.
    // ------------------------------------------------------------------

    static final long DAY = 24_000L;

    /** faction id → that faction's deal table (offers drawn randomly
     *  from the eligible subset, so table order doesn't shadow). */
    public static final Map<String, List<DealSpec>> FACTION_DEALS = buildFactionDeals();

    // --- Stage 4: the mending ritual (tunables) ---
    static final Item MENDING_TRIBUTE_ITEM = Items.DIAMOND;
    static final int MENDING_TRIBUTE_COUNT = 32;
    /** The sacrificed subordinate must carry at least this much EP. */
    static final double MENDING_SACRIFICE_MIN_EP = 10_000.0;
    static final long MENDING_DEADLINE = 12 * DAY;

    /** faction id → its mending-rite deal ("mend_<faction>") — offered
     *  ONLY while that faction is diplomacy-closed. Generated for every
     *  faction so any future foreclosure source is covered. */
    public static final Map<String, DealSpec> MENDING_DEALS = buildMendingDeals();

    /** faction id → its UNIQUE Covenant milestone deal — offered only
     *  at PACT with standing ≥ the COVENANT threshold; completing it
     *  forges the COVENANT. A representative set (the 10+ quest catalog
     *  is the separate content pass). */
    public static final Map<String, DealSpec> COVENANT_DEALS = buildCovenantDeals();

    /** faction id → Covenant-only TRAINING deal. Tempest Jura Alliance
     *  fields the physical "Warrior Training"; the old Jura "Sage
     *  Training" was dropped in the merge (one training deal per faction). */
    public static final Map<String, DealSpec> COVENANT_TRAINING_DEALS = buildTrainingDeals();

    private static Map<String, DealSpec> buildCovenantDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        map.put("dwargon", new DealSpec("cov_dwargon", "The Masterwork Commission",
                new SupplyBundle(List.of(
                        new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                        "tensura", "hihiirokane_katana")), 1),
                        new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                        "tensura", "pure_magisteel_ingot")), 8),
                        new ItemStack(ExampleMod.MASTERWORK_FORGING_CORE.get(), 1))),
                List.of(new ItemStack(Items.EMERALD, 64)),
                10.0, 0.0, 20 * DAY, 0, FactionTier.ALLIED, true));
        map.put("tempest", new DealSpec("cov_tempest", "A Thriving Metropolis",
                new Population(25),
                List.of(new ItemStack(Items.EMERALD, 48)),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        // (Jura's old cov_jura "The Grand Academy" dropped — the merged
        // Tempest Jura Alliance keeps Tempest's cov_tempest as its one
        // Covenant deal.)
        map.put("milim", new DealSpec("cov_milim", "Apito's Jelly",
                new SupplyItems(ExampleMod.APITOS_JELLY.get(), 1),
                List.of(new ItemStack(Items.EMERALD, 48)),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        map.put("falmuth", new DealSpec("cov_falmuth", "Prove Your Might",
                new SlayEntities(java.util.Set.of("minecraft:wither"), 1, "the Wither"),
                List.of(new ItemStack(Items.EMERALD, 48)),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        map.put("carrion", new DealSpec("cov_carrion", "The Great Hunt",
                new SlayEntities(java.util.Set.of("minecraft:wither", "minecraft:warden",
                        "minecraft:elder_guardian", "tensura:charybdis", "tensura:ifrit"),
                        3, "great beasts (Wither / Warden / Elder Guardian / Charybdis / Ifrit)"),
                List.of(new ItemStack(Items.EMERALD, 48)),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        map.put("luminous", new DealSpec("cov_luminous", "The Grand Offering",
                new SupplyBundle(List.of(new ItemStack(Items.DIAMOND_BLOCK, 8),
                        new ItemStack(Items.GOLD_BLOCK, 16))),
                List.of(new ItemStack(Items.EMERALD, 64)),
                10.0, 0.0, 20 * DAY, 0, FactionTier.ALLIED, true));
        map.put("clayman", new DealSpec("cov_clayman", "Souls for the Core",
                new SlayEntities(java.util.Set.of("minecraft:villager"), 10,
                        "villagers (their souls feed the Charybdis core)"),
                List.of(),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        return Map.copyOf(map);
    }

    private static Map<String, DealSpec> buildTrainingDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        // Tempest Jura Alliance — PHYSICAL/community: Strength + Stamina +
        // Adaptability. (Jura's old cov_train_jura "Sage Training" dropped —
        // one training deal per faction; Tempest's Warrior Training is kept
        // per the merge spec.)
        map.put("tempest", new DealSpec("cov_train_tempest", "Warrior Training",
                new LendCitizens(Skill.Strength, 5, 2, 2 * DAY, 4,
                        List.of(Skill.Stamina, Skill.Adaptability)),
                List.of(new ItemStack(Items.EMERALD, 16)),
                4.0, 0.0, 2 * DAY, 0, FactionTier.ALLIED, false));
        return Map.copyOf(map);
    }

    /** Global id → spec index (accept/lookup path). */
    public static final Map<String, DealSpec> DEALS = buildIndex();

    public static boolean isMendingDeal(DealSpec spec) {
        return spec != null && spec.requirement() instanceof MendingRite;
    }

    private static Map<String, DealSpec> buildMendingDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        for (BossFaction faction : BossFaction.values()) {
            map.put(faction.id(), new DealSpec("mend_" + faction.id(), "Rite of Atonement",
                    new MendingRite(MENDING_TRIBUTE_ITEM, MENDING_TRIBUTE_COUNT,
                            MENDING_SACRIFICE_MIN_EP),
                    List.of(), // the reward IS the reopened door
                    0.0, 0.0, MENDING_DEADLINE, 0, FactionTier.HOSTILE, true));
        }
        return Map.copyOf(map);
    }

    public static DealSpec byId(String id) {
        return DEALS.get(id);
    }

    public static List<DealSpec> tableFor(String factionId) {
        return FACTION_DEALS.getOrDefault(factionId, List.of());
    }

    /** Tensura item by path (rewards/requirements that aren't vanilla). */
    private static net.minecraft.world.item.Item ten(String path) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", path));
    }

    /** MineColonies item by path (ancient tomes, scrolls, compost). */
    private static net.minecraft.world.item.Item mc(String path) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecolonies", path));
    }

    /**
     * Capstone SKILL rewards (user-approved): each faction's TOP ALLIED
     * catalog quest (the aloof three's highest quest) grants a
     * faction-themed Tensura skill on completion. Keyed by deal id →
     * the skill's lazy registry supplier (resolved at grant time, never
     * at class-load). The grant logic (lack→learn, have→master-upgrade,
     * resistance→no-op-if-owned, no-upgrade→pure-magisteel fallback)
     * lives in {@code DiplomacyManager.grantSkillReward}. Idempotent.
     */
    public static final Map<String, java.util.function.Supplier<
            ? extends io.github.manasmods.manascore.skill.api.ManasSkill>> SKILL_REWARDS =
            buildSkillRewards();

    /** The faction's capstone (Covenant) skill — the supplier behind that
     *  faction's top ALLIED catalog quest. Used by the rival-colony
     *  conquest payoff (Stage D) to grant the boss's faction skill by
     *  FORCE, the same idempotent reward the diplomatic route gives.
     *  Returns null for factions with no capstone skill. */
    public static java.util.function.Supplier<
            ? extends io.github.manasmods.manascore.skill.api.ManasSkill> covenantSkillFor(String factionId) {
        for (DealSpec d : FACTION_DEALS.getOrDefault(factionId, List.of())) {
            if (SKILL_REWARDS.containsKey(d.id())) return SKILL_REWARDS.get(d.id());
        }
        return null;
    }

    /** All distinct reward ItemStacks across a faction's catalog deals —
     *  the conquest loot pool (Stage D), so a warlord's spoils ≈ what the
     *  diplomat would earn. Returns fresh copies. */
    public static List<ItemStack> factionRewardPool(String factionId) {
        List<ItemStack> pool = new java.util.ArrayList<>();
        for (DealSpec d : FACTION_DEALS.getOrDefault(factionId, List.of())) {
            for (ItemStack stack : d.rewardItems()) {
                if (!stack.isEmpty()) pool.add(stack.copy());
            }
        }
        return pool;
    }

    private static Map<String, java.util.function.Supplier<
            ? extends io.github.manasmods.manascore.skill.api.ManasSkill>> buildSkillRewards() {
        Map<String, java.util.function.Supplier<
                ? extends io.github.manasmods.manascore.skill.api.ManasSkill>> m =
                new LinkedHashMap<>();
        m.put("dw_grand_forge", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.BODY_ARMOR);
        m.put("tp_joyful", io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
        m.put("ja_sages", io.github.manasmods.tensura.registry.skill.CommonSkills.THOUGHT_COMMUNICATION);
        m.put("lu_devout", io.github.manasmods.tensura.registry.skill.ResistanceSkills.HOLY_ATTACK_RESISTANCE);
        m.put("fa_fortress", io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
        m.put("mi_warriors", io.github.manasmods.tensura.registry.skill.CommonSkills.STRENGTH);
        m.put("ca_wild_haven", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.GIANTIFICATION);
        m.put("cl_enforcers", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.CHARM);
        m.put("le_flamebearers", io.github.manasmods.tensura.registry.skill.ResistanceSkills.FLAME_ATTACK_RESISTANCE);
        m.put("sh_pupils", io.github.manasmods.tensura.registry.skill.ResistanceSkills.HEAT_RESISTANCE);
        m.put("ow_specialists", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.EYE_OF_TRUTH);
        return Map.copyOf(m);
    }

    /**
     * The FACTION QUEST CATALOG — 10 faction-geared quests per
     * diplomable faction (+ small sets for the aloof three), all on the
     * five base Requirement types, tier-gated into a progression
     * (NEUTRAL → FRIENDLY → ALLIED; the Covenant milestone sits above).
     * Existing Stage-2 deal ids are preserved. Standing penalty 5
     * unless noted; supply deals fulfil instantly, lend deadlines equal
     * the lend duration.
     */
    private static Map<String, List<DealSpec>> buildFactionDeals() {
        Map<String, List<DealSpec>> map = new LinkedHashMap<>();

        // ⚒ DWARGON — craft mats, building blocks, earth tome.
        map.put("dwargon", List.of(
                new DealSpec("dw_iron_tribute", "Iron for the Forges",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_coal", "Fuel for the Forges",
                        new SupplyItems(Items.COAL, 64),
                        List.of(new ItemStack(Items.COAL, 32), new ItemStack(Items.IRON_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_silver", "Silver for the Smiths",
                        new SupplyItems(ten("silver_ingot"), 32),
                        List.of(new ItemStack(ten("low_magisteel_ingot"), 6),
                                new ItemStack(ten("magic_stone"), 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_magisteel_quota", "Magisteel Quota",
                        new SupplyItems(ten("low_magisteel_ingot"), 16),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 3),
                                new ItemStack(Items.IRON_BLOCK, 8)),
                        7.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_blacksmith", "A Proper Smithy",
                        new BuildingLevel("blacksmith", 3),
                        List.of(new ItemStack(Items.IRON_BLOCK, 8), new ItemStack(Items.COAL, 16),
                                new ItemStack(Items.STONE_BRICKS, 16)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_smeltery", "Fires of Industry",
                        new BuildingLevel("smeltery", 3),
                        List.of(new ItemStack(Items.IRON_BLOCK, 12),
                                new ItemStack(ten("low_magisteel_ingot"), 4)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_grand_forge", "The Grand Forge",
                        new BuildingLevel("blacksmith", 5),
                        List.of(new ItemStack(ten("pure_magisteel_ingot"), 1),
                                new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("dw_industrious", "An Industrious People",
                        new Population(18),
                        List.of(new ItemStack(Items.IRON_BLOCK, 8), new ItemStack(Items.COAL, 16),
                                new ItemStack(Items.STONE_BRICKS, 16)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_strong_backs", "Strong Backs for the Mines",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(ten("low_magisteel_ingot"), 4)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_artisans", "Master Artisans Abroad",
                        new LendCitizens(Skill.Creativity, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(ten("high_magisteel_ingot"), 4),
                                new ItemStack(ten("magic_tome_earth"), 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🌿 TEMPEST — building blocks, food, scrolls, water tome.
        map.put("tempest", List.of(
                new DealSpec("tp_provisions", "Provisions for Travellers",
                        new SupplyItems(Items.BREAD, 32),
                        List.of(new ItemStack(Items.BREAD, 16), new ItemStack(Items.IRON_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_market_meat", "Meat for the Market",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 8), new ItemStack(Items.EMERALD, 8)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_timber", "Timber for Expansion",
                        new SupplyItems(Items.OAK_LOG, 48),
                        List.of(new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.STONE_BRICKS, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_tavern", "A Place to Gather",
                        new BuildingLevel("tavern", 3),
                        List.of(new ItemStack(Items.GLASS, 16), new ItemStack(Items.BRICKS, 16)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_growing", "A Growing Town",
                        new Population(15),
                        List.of(new ItemStack(Items.BREAD, 16), new ItemStack(Items.STONE_BRICKS, 16)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_bustling", "A Bustling Town",
                        new Population(20),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.STONE_BRICKS, 32),
                                new ItemStack(Items.IRON_INGOT, 8)),
                        7.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_content", "Content People",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.GOLD_INGOT, 8), new ItemStack(Items.GLASS, 16),
                                new ItemStack(mc("scroll_tp"), 1)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_joyful", "A Joyful Haven",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.GLASS, 16),
                                new ItemStack(mc("scroll_area_tp"), 1),
                                new ItemStack(ten("magic_tome_water"), 1)),
                        7.0, 5.0, 16 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("tp_helping_hands", "Helping Hands",
                        new LendCitizens(Skill.Adaptability, 5, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("magic_stone"), 6)),
                        8.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_skilled_hands", "Skilled Hands Abroad",
                        new LendCitizens(Skill.Dexterity, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 16)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                // 📚 — the former JURA ALLIANCE catalog, merged in: books,
                // lapis, xp, ancient tomes, mental tome. (ja_enlightened —
                // Happiness 7.0 — dropped as a duplicate of tp_content.)
                new DealSpec("ja_harvest", "A Share of the Harvest",
                        new SupplyItems(Items.WHEAT, 64),
                        List.of(new ItemStack(Items.BOOK, 16), new ItemStack(Items.PAPER, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_paper", "Paper for the Scribes",
                        new SupplyItems(Items.PAPER, 64),
                        List.of(new ItemStack(Items.BOOKSHELF, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_books", "A Library's Worth",
                        new SupplyItems(Items.BOOK, 32),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 16),
                                new ItemStack(Items.EXPERIENCE_BOTTLE, 4)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_school", "Letters for the Young",
                        new BuildingLevel("school", 3),
                        List.of(new ItemStack(Items.BOOKSHELF, 8), new ItemStack(Items.BOOK, 16)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_library", "Halls of Knowledge",
                        new BuildingLevel("library", 3),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 16), new ItemStack(Items.BOOK, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_university", "Higher Learning",
                        new BuildingLevel("university", 4),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 16),
                                new ItemStack(Items.BOOKSHELF, 8), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(mc("ancienttome"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_scholars", "Scholars Abroad",
                        new LendCitizens(Skill.Knowledge, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 16),
                                new ItemStack(Items.EXPERIENCE_BOTTLE, 8),
                                new ItemStack(ten("magic_stone"), 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_focus", "Focused Minds Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 8), new ItemStack(Items.BOOK, 8)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_sages", "Sages for the Academy",
                        new LendCitizens(Skill.Intelligence, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 16),
                                new ItemStack(Items.LAPIS_LAZULI, 16), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(mc("ancienttome"), 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // ✦ LUMINOUS — premium: diamonds, gold, recovery tome, ancient tome.
        map.put("luminous", List.of(
                new DealSpec("lu_glowstone", "Light for the Cathedral",
                        new SupplyItems(Items.GLOWSTONE, 64),
                        List.of(new ItemStack(Items.GLOWSTONE, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_gold_tithe", "The Golden Tithe",
                        new SupplyItems(Items.GOLD_BLOCK, 32),
                        List.of(new ItemStack(Items.GOLD_BLOCK, 3), new ItemStack(Items.DIAMOND, 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_tribute", "Tribute to the Luminary",
                        new SupplyItems(Items.DIAMOND, 32),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.GOLD_INGOT, 16)),
                        8.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_diamond_offering", "The Diamond Offering",
                        new SupplyItems(Items.DIAMOND_BLOCK, 16),
                        List.of(new ItemStack(Items.DIAMOND, 16), new ItemStack(Items.DIAMOND_BLOCK, 2)),
                        9.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_grand_library", "A Light of Learning",
                        new BuildingLevel("library", 5),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(mc("ancienttome"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_university", "Sanctified Halls",
                        new BuildingLevel("university", 4),
                        List.of(new ItemStack(Items.DIAMOND, 12), new ItemStack(Items.GOLD_BLOCK, 2)),
                        10.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_cathedral", "A Cathedral of Light",
                        new BuildingLevel("mysticalsite", 3),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.GLOWSTONE, 16),
                                new ItemStack(ten("magic_tome_recovery"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_hospital", "Sanctuary of Healing",
                        new BuildingLevel("hospital", 4),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(ten("magic_stone"), 8)),
                        7.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_devout", "A Devout Congregation",
                        new Happiness(9.0),
                        List.of(new ItemStack(Items.DIAMOND, 16),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_faithful", "The Faithful Abroad",
                        new LendCitizens(Skill.Mana, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // ⚔ FALMUTH — war metal, magisteel, battlewill manual, enhancement tome.
        map.put("falmuth", List.of(
                new DealSpec("fa_iron_quota", "The Iron Quota",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_arrows", "Arrows for the Levy",
                        new SupplyItems(Items.ARROW, 64),
                        List.of(new ItemStack(Items.ARROW, 32), new ItemStack(Items.IRON_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_war_levy", "The War Levy",
                        new SupplyItems(Items.IRON_BLOCK, 32),
                        List.of(new ItemStack(Items.IRON_BLOCK, 8), new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(Items.DIAMOND_SWORD, 1)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_steel", "Blades of Magisteel",
                        new SupplyItems(ten("low_magisteel_ingot"), 16),
                        List.of(new ItemStack(ten("low_magisteel_ingot"), 6), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("magic_tome_enhancement"), 1)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_barracks", "Walls and Watchmen",
                        new BuildingLevel("barracks", 3),
                        List.of(new ItemStack(Items.IRON_BLOCK, 8), new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_archery", "Towers and Bowmen",
                        new BuildingLevel("archery", 3),
                        List.of(new ItemStack(Items.ARROW, 32), new ItemStack(Items.IRON_BLOCK, 8),
                                new ItemStack(mc("scroll_guard_help"), 1)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_fortress", "A Mighty Fortress",
                        new BuildingLevel("barracks", 5),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 3), new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("fa_garrison", "A Standing Garrison",
                        new Population(20),
                        List.of(new ItemStack(Items.IRON_BLOCK, 12), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(Items.SHIELD, 2)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_field_hands", "Hands for the Fields",
                        new LendCitizens(Skill.Stamina, 10, 3, 3 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_shock_troops", "Shock Troops Abroad",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 3), new ItemStack(Items.DIAMOND, 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🐉 MILIM — feast food, diamonds, gravity tome, battlewill manual.
        map.put("milim", List.of(
                new DealSpec("mi_feast", "A Feast Worthy of Me!",
                        new SupplyItems(Items.COOKED_PORKCHOP, 64),
                        List.of(new ItemStack(Items.GOLDEN_CARROT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_more_meat", "More Meat!",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.GOLDEN_CARROT, 16)),
                        5.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_sweets", "Sweets for Milim",
                        new SupplyItems(Items.COOKIE, 64),
                        List.of(new ItemStack(Items.GOLDEN_APPLE, 2), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_cake", "Cake, and Lots of It!",
                        new SupplyItems(Items.CAKE, 8),
                        List.of(new ItemStack(Items.GOLDEN_APPLE, 2), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("magic_tome_gravity"), 1)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_arena", "A Place to Brawl",
                        new BuildingLevel("barracks", 3),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_strong_town", "Show Me Your Strength",
                        new Population(20),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.GOLD_INGOT, 16)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_mighty_town", "Show Me MORE Strength!",
                        new Population(25),
                        List.of(new ItemStack(Items.DIAMOND, 12), new ItemStack(Items.GOLDEN_APPLE, 2)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("mi_happy_subjects", "Keep Them Cheerful",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.GOLDEN_CARROT, 16)),
                        6.0, 5.0, 16 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_champions", "Champions for Milim",
                        new LendCitizens(Skill.Strength, 10, 2, 3 * DAY, 4),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.GOLDEN_APPLE, 2),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_warriors", "Warriors to Spar",
                        new LendCitizens(Skill.Athletics, 8, 2, 2 * DAY, 3),
                        List.of(new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false)));

        // 🦁 CARRION — beast mats, compost, battlewill manual.
        map.put("carrion", List.of(
                new DealSpec("ca_hides", "Hides for the Beastfolk",
                        new SupplyItems(Items.LEATHER, 48),
                        List.of(new ItemStack(Items.LEATHER, 16), new ItemStack(Items.COOKED_BEEF, 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_meat", "Meat for the Pack",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.COOKED_BEEF, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_bones", "Bones for the Den",
                        new SupplyItems(Items.BONE, 64),
                        List.of(new ItemStack(Items.BONE_MEAL, 32), new ItemStack(Items.LEATHER, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_sinew", "Sinew for Snares",
                        new SupplyItems(Items.STRING, 48),
                        List.of(new ItemStack(Items.STRING, 16), new ItemStack(Items.LEATHER, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_stable", "Dens for the Beasts",
                        new BuildingLevel("stable", 3),
                        List.of(new ItemStack(Items.LEATHER, 24), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(mc("compost"), 4)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_great_pack", "A Great Pack",
                        new Population(18),
                        List.of(new ItemStack(Items.LEATHER, 24), new ItemStack(Items.COOKED_BEEF, 16),
                                new ItemStack(mc("compost"), 4)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_thriving_pack", "A Thriving Pack",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.LEATHER, 16), new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_wild_haven", "A Wild Haven",
                        new Happiness(9.0),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.LEATHER, 24),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        7.0, 5.0, 16 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("ca_hunters", "Hunters Abroad",
                        new LendCitizens(Skill.Agility, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.LEATHER, 16), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("magic_stone"), 4)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_trackers", "Keen Trackers",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.LEATHER, 16)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false)));

        // 🃏 CLAYMAN — schemer: crystals (lean), redstone, illusion tome.
        map.put("clayman", List.of(
                new DealSpec("cl_crystals", "Crystals for the Scheme",
                        new SupplyItems(ten("low_quality_magic_crystal"), 16),
                        List.of(new ItemStack(ten("low_quality_magic_crystal"), 4),
                                new ItemStack(Items.REDSTONE, 16)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_redstone", "Whispers and Wires",
                        new SupplyItems(Items.REDSTONE, 64),
                        List.of(new ItemStack(Items.REDSTONE, 32), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_gold", "Gold to Grease Palms",
                        new SupplyItems(Items.GOLD_INGOT, 32),
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 4),
                                new ItemStack(Items.GOLD_INGOT, 16)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_magic_tithe", "Magicule Tithe",
                        new SupplyItems(ten("medium_quality_magic_crystal"), 8),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 1),
                                new ItemStack(ten("medium_quality_magic_crystal"), 2),
                                new ItemStack(ten("magic_tome_illusion"), 1)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_mystic", "A Site of Dark Power",
                        new BuildingLevel("mysticalsite", 3),
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 3),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_enchanter", "The Puppet-Maker's Workshop",
                        new BuildingLevel("enchanter", 3),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 8), new ItemStack(Items.REDSTONE, 16),
                                new ItemStack(mc("scroll_buff"), 1)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_pawns", "More Pawns",
                        new Population(20),
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 3),
                                new ItemStack(ten("slime_core"), 1)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_obedient", "Obedient Subjects",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.REDSTONE, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        5.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_spies", "Spies Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 1),
                                new ItemStack(Items.REDSTONE, 8),
                                new ItemStack(ten("magic_stone"), 8)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_enforcers", "Enforcers for the Cause",
                        new LendCitizens(Skill.Strength, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🔥 LEON — fire/flame (aloof; small set, no Covenant milestone).
        map.put("leon", List.of(
                new DealSpec("le_magma", "Stones of Fire",
                        new SupplyItems(Items.MAGMA_BLOCK, 32),
                        List.of(new ItemStack(Items.MAGMA_CREAM, 8), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_cinders", "Cinders for the Flame Lord",
                        new SupplyItems(Items.BLAZE_POWDER, 32),
                        List.of(new ItemStack(Items.BLAZE_ROD, 8), new ItemStack(Items.GLOWSTONE, 16)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_hearth", "A Hearth of Flame",
                        new BuildingLevel("smeltery", 3),
                        List.of(new ItemStack(Items.BLAZE_ROD, 6), new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(ten("magic_tome_fire"), 1)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("le_flamebearers", "Flamebearers Abroad",
                        new LendCitizens(Skill.Mana, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.BLAZE_ROD, 4), new ItemStack(Items.DIAMOND, 4)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 🕯 SHIZU — teaching & Ifrit-lore (aloof; small set).
        map.put("shizu", List.of(
                new DealSpec("sh_records", "Records of the Past",
                        new SupplyItems(Items.BOOK, 32),
                        List.of(new ItemStack(Items.BOOK, 16), new ItemStack(Items.PAPER, 8)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("sh_lessons", "Lessons for the Children",
                        new BuildingLevel("school", 3),
                        List.of(new ItemStack(Items.BOOKSHELF, 8), new ItemStack(Items.EXPERIENCE_BOTTLE, 8)),
                        5.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("sh_gentle_town", "A Gentle Town",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 8), new ItemStack(Items.LAPIS_LAZULI, 8),
                                new ItemStack(ten("magic_tome"), 1)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("sh_pupils", "Pupils Abroad",
                        new LendCitizens(Skill.Knowledge, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.BOOKSHELF, 8), new ItemStack(Items.LAPIS_LAZULI, 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 🌐 OTHERWORLDERS — otherworld goods (aloof; small set).
        map.put("otherworlders", List.of(
                new DealSpec("ow_curios", "Curios from Your World",
                        new SupplyItems(Items.GLASS, 32),
                        List.of(new ItemStack(Items.COPPER_INGOT, 16), new ItemStack(Items.AMETHYST_SHARD, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_contraptions", "Strange Contraptions",
                        new SupplyItems(Items.REDSTONE_BLOCK, 16),
                        List.of(new ItemStack(Items.REDSTONE, 24), new ItemStack(Items.IRON_INGOT, 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_settlers", "Settlers from Afar",
                        new Population(15),
                        List.of(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.EMERALD, 8),
                                new ItemStack(mc("scroll_area_tp"), 1)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ow_specialists", "Specialists Abroad",
                        new LendCitizens(Skill.Intelligence, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.AMETHYST_SHARD, 8), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("magic_tome_summoning"), 1)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        return Map.copyOf(map);
    }

    private static Map<String, DealSpec> buildIndex() {
        Map<String, DealSpec> index = new LinkedHashMap<>();
        for (List<DealSpec> table : FACTION_DEALS.values()) {
            for (DealSpec spec : table) {
                index.put(spec.id(), spec);
            }
        }
        for (DealSpec spec : MENDING_DEALS.values()) {
            index.put(spec.id(), spec);
        }
        for (DealSpec spec : COVENANT_DEALS.values()) {
            index.put(spec.id(), spec);
        }
        for (DealSpec spec : COVENANT_TRAINING_DEALS.values()) {
            index.put(spec.id(), spec);
        }
        return Map.copyOf(index);
    }
}
