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

    /** faction id → Covenant-only TRAINING deal (Tempest physical /
     *  Jura mental — the approved skill split). */
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
        map.put("jura_alliance", new DealSpec("cov_jura", "The Grand Academy",
                new BuildingLevel("university", 5),
                List.of(new ItemStack(Items.EMERALD, 48)),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
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
        // Tempest — PHYSICAL/community: Strength + Stamina + Adaptability.
        map.put("tempest", new DealSpec("cov_train_tempest", "Warrior Training",
                new LendCitizens(Skill.Strength, 5, 2, 2 * DAY, 4,
                        List.of(Skill.Stamina, Skill.Adaptability)),
                List.of(new ItemStack(Items.EMERALD, 16)),
                4.0, 0.0, 2 * DAY, 0, FactionTier.ALLIED, false));
        // Jura — MENTAL: Knowledge + Focus + Intelligence.
        map.put("jura_alliance", new DealSpec("cov_train_jura", "Sage Training",
                new LendCitizens(Skill.Knowledge, 5, 2, 2 * DAY, 4,
                        List.of(Skill.Focus, Skill.Intelligence)),
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

        // ⚒ DWARGON — craftsmanship & industry.
        map.put("dwargon", List.of(
                new DealSpec("dw_iron_tribute", "Iron for the Forges",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 24)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_coal", "Fuel for the Forges",
                        new SupplyItems(Items.COAL, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_silver", "Silver for the Smiths",
                        new SupplyItems(ten("silver_ingot"), 32),
                        List.of(new ItemStack(ten("low_magisteel_ingot"), 4)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_magisteel_quota", "Magisteel Quota",
                        new SupplyItems(ten("low_magisteel_ingot"), 16),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 4),
                                new ItemStack(Items.EMERALD, 16)),
                        7.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_blacksmith", "A Proper Smithy",
                        new BuildingLevel("blacksmith", 3),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_smeltery", "Fires of Industry",
                        new BuildingLevel("smeltery", 3),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.IRON_BLOCK, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_grand_forge", "The Grand Forge",
                        new BuildingLevel("blacksmith", 5),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(ten("high_quality_magic_crystal"), 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("dw_industrious", "An Industrious People",
                        new Population(18),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.IRON_BLOCK, 8)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_strong_backs", "Strong Backs for the Mines",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(Items.GOLD_INGOT, 32),
                                new ItemStack(Items.EMERALD, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_artisans", "Master Artisans Abroad",
                        new LendCitizens(Skill.Creativity, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 6),
                                new ItemStack(Items.EMERALD, 24)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🌿 TEMPEST — community & development.
        map.put("tempest", List.of(
                new DealSpec("tp_provisions", "Provisions for Travellers",
                        new SupplyItems(Items.BREAD, 32),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_market_meat", "Meat for the Market",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.EMERALD, 20)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_timber", "Timber for Expansion",
                        new SupplyItems(Items.OAK_LOG, 48),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_tavern", "A Place to Gather",
                        new BuildingLevel("tavern", 3),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_growing", "A Growing Town",
                        new Population(15),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_bustling", "A Bustling Town",
                        new Population(20),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.DIAMOND, 4)),
                        7.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_content", "Content People",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_joyful", "A Joyful Haven",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        7.0, 5.0, 16 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("tp_helping_hands", "Helping Hands",
                        new LendCitizens(Skill.Adaptability, 5, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        8.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_skilled_hands", "Skilled Hands Abroad",
                        new LendCitizens(Skill.Dexterity, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 📚 JURA ALLIANCE — learning & knowledge.
        map.put("jura_alliance", List.of(
                new DealSpec("ja_harvest", "A Share of the Harvest",
                        new SupplyItems(Items.WHEAT, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_paper", "Paper for the Scribes",
                        new SupplyItems(Items.PAPER, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_books", "A Library's Worth",
                        new SupplyItems(Items.BOOK, 32),
                        List.of(new ItemStack(Items.EMERALD, 16),
                                new ItemStack(Items.LAPIS_LAZULI, 4)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_school", "Letters for the Young",
                        new BuildingLevel("school", 3),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_library", "Halls of Knowledge",
                        new BuildingLevel("library", 3),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_university", "Higher Learning",
                        new BuildingLevel("university", 4),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.BOOK, 8)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_enlightened", "An Enlightened Populace",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.BOOK, 8)),
                        6.0, 5.0, 16 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_scholars", "Scholars Abroad",
                        new LendCitizens(Skill.Knowledge, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.BOOK, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_focus", "Focused Minds Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.BOOK, 8)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_sages", "Sages for the Academy",
                        new LendCitizens(Skill.Intelligence, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.BOOK, 16),
                                new ItemStack(Items.LAPIS_LAZULI, 4),
                                new ItemStack(Items.EMERALD, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // ✦ LUMINOUS — holy & HARD (brutal for a majin to even reach).
        map.put("luminous", List.of(
                new DealSpec("lu_glowstone", "Light for the Cathedral",
                        new SupplyItems(Items.GLOWSTONE, 64),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_gold_tithe", "The Golden Tithe",
                        new SupplyItems(Items.GOLD_BLOCK, 32),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_tribute", "Tribute to the Luminary",
                        new SupplyItems(Items.DIAMOND, 32),
                        List.of(new ItemStack(Items.EMERALD, 64)),
                        8.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_diamond_offering", "The Diamond Offering",
                        new SupplyItems(Items.DIAMOND_BLOCK, 16),
                        List.of(new ItemStack(Items.EMERALD, 64),
                                new ItemStack(Items.GOLD_INGOT, 16)),
                        9.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_grand_library", "A Light of Learning",
                        new BuildingLevel("library", 5),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_university", "Sanctified Halls",
                        new BuildingLevel("university", 4),
                        List.of(new ItemStack(Items.EMERALD, 64),
                                new ItemStack(Items.DIAMOND, 8)),
                        10.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_cathedral", "A Cathedral of Light",
                        new BuildingLevel("mysticalsite", 3),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_hospital", "Sanctuary of Healing",
                        new BuildingLevel("hospital", 4),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        7.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_devout", "A Devout Congregation",
                        new Happiness(9.0),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.DIAMOND, 8)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_faithful", "The Faithful Abroad",
                        new LendCitizens(Skill.Mana, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // ⚔ FALMUTH — war materiel & fortification.
        map.put("falmuth", List.of(
                new DealSpec("fa_iron_quota", "The Iron Quota",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_arrows", "Arrows for the Levy",
                        new SupplyItems(Items.ARROW, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_war_levy", "The War Levy",
                        new SupplyItems(Items.IRON_BLOCK, 32),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_steel", "Blades of Magisteel",
                        new SupplyItems(ten("low_magisteel_ingot"), 16),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_barracks", "Walls and Watchmen",
                        new BuildingLevel("barracks", 3),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.GOLD_INGOT, 16)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_archery", "Towers and Bowmen",
                        new BuildingLevel("archery", 3),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_fortress", "A Mighty Fortress",
                        new BuildingLevel("barracks", 5),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.GOLD_INGOT, 16)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("fa_garrison", "A Standing Garrison",
                        new Population(20),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.IRON_BLOCK, 8)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_field_hands", "Hands for the Fields",
                        new LendCitizens(Skill.Stamina, 10, 3, 3 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 40)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_shock_troops", "Shock Troops Abroad",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 40)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🐉 MILIM — strength & feasts.
        map.put("milim", List.of(
                new DealSpec("mi_feast", "A Feast Worthy of Me!",
                        new SupplyItems(Items.COOKED_PORKCHOP, 64),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_more_meat", "More Meat!",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_sweets", "Sweets for Milim",
                        new SupplyItems(Items.COOKIE, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_cake", "Cake, and Lots of It!",
                        new SupplyItems(Items.CAKE, 8),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_arena", "A Place to Brawl",
                        new BuildingLevel("barracks", 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.DIAMOND, 4)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_strong_town", "Show Me Your Strength",
                        new Population(20),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_mighty_town", "Show Me MORE Strength!",
                        new Population(25),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.DIAMOND, 8)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("mi_happy_subjects", "Keep Them Cheerful",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 16 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_champions", "Champions for Milim",
                        new LendCitizens(Skill.Strength, 10, 2, 3 * DAY, 4),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.DIAMOND, 4)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_warriors", "Warriors to Spar",
                        new LendCitizens(Skill.Athletics, 8, 2, 2 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.DIAMOND, 4)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false)));

        // 🦁 CARRION — beast offerings (hunts are the Covenant milestone).
        map.put("carrion", List.of(
                new DealSpec("ca_hides", "Hides for the Beastfolk",
                        new SupplyItems(Items.LEATHER, 48),
                        List.of(new ItemStack(Items.EMERALD, 20)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_meat", "Meat for the Pack",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_bones", "Bones for the Den",
                        new SupplyItems(Items.BONE, 64),
                        List.of(new ItemStack(Items.EMERALD, 20)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_sinew", "Sinew for Snares",
                        new SupplyItems(Items.STRING, 48),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_stable", "Dens for the Beasts",
                        new BuildingLevel("stable", 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.LEATHER, 16)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_great_pack", "A Great Pack",
                        new Population(18),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.LEATHER, 16)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_thriving_pack", "A Thriving Pack",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_wild_haven", "A Wild Haven",
                        new Happiness(9.0),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        7.0, 5.0, 16 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("ca_hunters", "Hunters Abroad",
                        new LendCitizens(Skill.Agility, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.LEATHER, 8)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_trackers", "Keen Trackers",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 20)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false)));

        // 🃏 CLAYMAN — the schemer (crystals, dark goods, pawns).
        map.put("clayman", List.of(
                new DealSpec("cl_crystals", "Crystals for the Scheme",
                        new SupplyItems(ten("low_quality_magic_crystal"), 16),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_redstone", "Whispers and Wires",
                        new SupplyItems(Items.REDSTONE, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_gold", "Gold to Grease Palms",
                        new SupplyItems(Items.GOLD_INGOT, 32),
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_magic_tithe", "Magicule Tithe",
                        new SupplyItems(ten("medium_quality_magic_crystal"), 8),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 4),
                                new ItemStack(Items.EMERALD, 16)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_mystic", "A Site of Dark Power",
                        new BuildingLevel("mysticalsite", 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(ten("high_quality_magic_crystal"), 4)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_enchanter", "The Puppet-Maker's Workshop",
                        new BuildingLevel("enchanter", 3),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_pawns", "More Pawns",
                        new Population(20),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(ten("medium_quality_magic_crystal"), 8)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_obedient", "Obedient Subjects",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_spies", "Spies Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 4),
                                new ItemStack(Items.EMERALD, 24)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_enforcers", "Enforcers for the Cause",
                        new LendCitizens(Skill.Strength, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 8),
                                new ItemStack(Items.EMERALD, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false)));

        // 🔥 LEON — fire/flame (aloof; small set, no Covenant milestone).
        map.put("leon", List.of(
                new DealSpec("le_magma", "Stones of Fire",
                        new SupplyItems(Items.MAGMA_BLOCK, 32),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_cinders", "Cinders for the Flame Lord",
                        new SupplyItems(Items.BLAZE_POWDER, 32),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_hearth", "A Hearth of Flame",
                        new BuildingLevel("smeltery", 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("le_flamebearers", "Flamebearers Abroad",
                        new LendCitizens(Skill.Mana, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 🕯 SHIZU — teaching & Ifrit-lore (aloof; small set).
        map.put("shizu", List.of(
                new DealSpec("sh_records", "Records of the Past",
                        new SupplyItems(Items.BOOK, 32),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("sh_lessons", "Lessons for the Children",
                        new BuildingLevel("school", 3),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.BOOK, 8)),
                        5.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("sh_gentle_town", "A Gentle Town",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("sh_pupils", "Pupils Abroad",
                        new LendCitizens(Skill.Knowledge, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.BOOK, 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 🌐 OTHERWORLDERS — otherworld goods (aloof; small set).
        map.put("otherworlders", List.of(
                new DealSpec("ow_curios", "Curios from Your World",
                        new SupplyItems(Items.GLASS, 32),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_contraptions", "Strange Contraptions",
                        new SupplyItems(Items.REDSTONE_BLOCK, 16),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_settlers", "Settlers from Afar",
                        new Population(15),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ow_specialists", "Specialists Abroad",
                        new LendCitizens(Skill.Intelligence, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24)),
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
