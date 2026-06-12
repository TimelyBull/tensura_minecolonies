package com.example.examplemod;

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
 * addon door, and the Stage-2/3 extension point (faction-flavored deal
 * TABLES are data swaps over {@link #DEALS}).
 *
 * <p><b>The sealed {@link Requirement} seam:</b> Stage 1 ships
 * SupplyItems / BuildingLevel / Population / Happiness. Stage 2 adds
 * {@code LendCitizens} as one more permitted variant — the framework
 * (state machine, payoff timers, persistence) needs no reshaping
 * (feasibility verified: MineColonies' serializeNBT → removeCivilian →
 * resurrectCivilianData round-trip + ICitizenSkillHandler.incrementLevel).
 *
 * @param standingReward  paid on FULFILLMENT through the sole door
 *                        (WorldRepReason.DIPLOMACY)
 * @param standingPenalty charged on deadline FAILURE
 * @param deadlineTicks   accept → due window
 * @param payoffDelayTicks 0 = reward collectible immediately on
 *                        fulfillment; >0 = AWAITING_PAYOFF (the caravan
 *                        travels) before the reward is collectible
 * @param minTier         offer gating — better deals at higher standing
 * @param milestone       the ALLIANCE-PACT marker: fulfillment promotes
 *                        relations OPEN → PACT
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

    /** What the faction asks for. Sealed — Stage 2 adds LendCitizens. */
    public sealed interface Requirement
            permits SupplyItems, BuildingLevel, Population, Happiness {
        /** Player-facing one-liner ("Supply 64 Iron Ingot"). */
        String summary();
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
    // The Stage-1 STARTER set — faction-neutral your-colony deals that
    // prove the loop (supply / build / population / happiness + the
    // alliance-pact milestone). Faction-FLAVORED tables are Stage 3.
    // All numbers are tuning values; days = 24 000 ticks.
    // ------------------------------------------------------------------

    static final long DAY = 24_000L;

    public static final Map<String, DealSpec> DEALS = buildDeals();

    public static DealSpec byId(String id) {
        return DEALS.get(id);
    }

    private static Map<String, DealSpec> buildDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        // All Stage-1 starter rewards are INSTANT (payoffDelayTicks 0,
        // user-requested) — the AWAITING_PAYOFF machinery stays for
        // Stage-2 deals that want a travelling caravan.
        put(map, new DealSpec("supply_iron", "Iron Shipment",
                new SupplyItems(Items.IRON_INGOT, 64),
                List.of(new ItemStack(Items.GOLD_INGOT, 24)),
                4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false));
        put(map, new DealSpec("supply_food", "Provisions for the Road",
                new SupplyItems(Items.BREAD, 32),
                List.of(new ItemStack(Items.EMERALD, 16)),
                4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false));
        put(map, new DealSpec("raise_library", "A Seat of Learning",
                new BuildingLevel("library", 3),
                List.of(new ItemStack(Items.EMERALD, 32)),
                6.0, 5.0, 12 * DAY, 0, FactionTier.NEUTRAL, false));
        put(map, new DealSpec("growing_town", "A Growing Town",
                new Population(15),
                List.of(new ItemStack(Items.EMERALD, 24)),
                6.0, 5.0, 20 * DAY, 0, FactionTier.NEUTRAL, false));
        put(map, new DealSpec("content_people", "Content People",
                new Happiness(7.0),
                List.of(new ItemStack(Items.EMERALD, 24),
                        new ItemStack(Items.GOLD_INGOT, 8)),
                6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false));
        // The ALLIANCE-PACT milestone — offered only at ALLIED 80+ while
        // OPEN; fulfillment promotes relations to PACT (+10).
        put(map, new DealSpec("alliance_pact", "The Alliance Pact",
                new SupplyItems(Items.DIAMOND, 16),
                List.of(new ItemStack(Items.EMERALD, 64)),
                10.0, 5.0, 6 * DAY, 0, FactionTier.ALLIED, true));
        return Map.copyOf(map);
    }

    private static void put(Map<String, DealSpec> map, DealSpec spec) {
        map.put(spec.id(), spec);
    }
}
