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

    private static Map<String, List<DealSpec>> buildFactionDeals() {
        Map<String, List<DealSpec>> map = new LinkedHashMap<>();

        // DWARGON — craftsmanship & industry.
        map.put("dwargon", List.of(
                new DealSpec("dw_iron_tribute", "Iron for the Forges",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 24)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_blacksmith", "A Proper Smithy",
                        new BuildingLevel("blacksmith", 3),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_smeltery", "Fires of Industry",
                        new BuildingLevel("smeltery", 3),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.IRON_BLOCK, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_strong_backs", "Strong Backs for the Mines",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(Items.GOLD_INGOT, 32),
                                new ItemStack(Items.EMERALD, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // TEMPEST — community & development.
        map.put("tempest", List.of(
                new DealSpec("tp_provisions", "Provisions for Travellers",
                        new SupplyItems(Items.BREAD, 32),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_growing", "A Growing Town",
                        new Population(15),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_content", "Content People",
                        new Happiness(7.0),
                        List.of(new ItemStack(Items.EMERALD, 24),
                                new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_helping_hands", "Helping Hands",
                        new LendCitizens(Skill.Adaptability, 5, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        8.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false)));

        // JURA ALLIANCE — community & learning.
        map.put("jura_alliance", List.of(
                new DealSpec("ja_harvest", "A Share of the Harvest",
                        new SupplyItems(Items.WHEAT, 64),
                        List.of(new ItemStack(Items.EMERALD, 16)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_school", "Letters for the Young",
                        new BuildingLevel("school", 3),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_scholars", "Scholars Abroad",
                        new LendCitizens(Skill.Knowledge, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.BOOK, 16)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // LUMINOUS — HARD: prove your civilisation, then pay dearly.
        map.put("luminous", List.of(
                new DealSpec("lu_grand_library", "A Light of Learning",
                        new BuildingLevel("library", 5),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_tribute", "Tribute to the Luminary",
                        new SupplyItems(Items.DIAMOND, 32),
                        List.of(new ItemStack(Items.EMERALD, 64)),
                        8.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("lu_university", "Sanctified Halls",
                        new BuildingLevel("university", 4),
                        List.of(new ItemStack(Items.EMERALD, 64),
                                new ItemStack(Items.DIAMOND, 8)),
                        10.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false)));

        // FALMUTH — HARD: war materiel and fortification.
        map.put("falmuth", List.of(
                new DealSpec("fa_war_levy", "The War Levy",
                        new SupplyItems(Items.IRON_BLOCK, 32),
                        List.of(new ItemStack(Items.EMERALD, 48)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_barracks", "Walls and Watchmen",
                        new BuildingLevel("barracks", 3),
                        List.of(new ItemStack(Items.EMERALD, 48),
                                new ItemStack(Items.GOLD_INGOT, 16)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_field_hands", "Hands for the Fields",
                        new LendCitizens(Skill.Stamina, 10, 3, 3 * DAY, 2),
                        List.of(new ItemStack(Items.EMERALD, 40)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false)));

        // MILIM — strength and feasts (her swing multiplier amplifies).
        map.put("milim", List.of(
                new DealSpec("mi_feast", "A Feast Worthy of Me!",
                        new SupplyItems(Items.COOKED_PORKCHOP, 64),
                        List.of(new ItemStack(Items.EMERALD, 24)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_strong_town", "Show Me Your Strength",
                        new Population(20),
                        List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.DIAMOND, 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.FRIENDLY, false)));

        // CARRION — the beast kingdom's offerings.
        map.put("carrion", List.of(
                new DealSpec("ca_hides", "Hides for the Beastfolk",
                        new SupplyItems(Items.LEATHER, 48),
                        List.of(new ItemStack(Items.EMERALD, 20)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_thriving_pack", "A Thriving Pack",
                        new Happiness(8.0),
                        List.of(new ItemStack(Items.EMERALD, 32)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false)));

        // Clayman / Leon / Otherworlders / Shizu — no deal tables.
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
