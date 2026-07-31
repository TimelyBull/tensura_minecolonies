package com.example.examplemod;

import com.minecolonies.api.entity.citizen.Skill;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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
        boolean milestone,
        List<EnchantedReward> enchantedRewards) {

    /** Legacy 10-arg constructor (no enchanted rewards) — keeps all the plain
     *  deal literals unchanged; delegates with an empty enchanted list. */
    public DealSpec(String id, String title, Requirement requirement, List<ItemStack> rewardItems,
                    double standingReward, double standingPenalty, long deadlineTicks,
                    long payoffDelayTicks, FactionTier minTier, boolean milestone) {
        this(id, title, requirement, rewardItems, standingReward, standingPenalty,
                deadlineTicks, payoffDelayTicks, minTier, milestone, List.of());
    }

    /** A reward weapon/armor built ENCHANTED (or Tensura-ENGRAVED) at grant
     *  time. Enchantments live in the dynamic per-world registry, so the stack
     *  is materialised via {@link #resolvedRewards} with a
     *  {@link HolderLookup.Provider}, never at class-load. */
    public record EnchantedReward(Item item, int count, List<EnchantSpec> enchants) {
        public ItemStack build(HolderLookup.Provider registries) {
            ItemStack stack = new ItemStack(item, count);
            HolderLookup.RegistryLookup<Enchantment> lookup =
                    registries.lookupOrThrow(Registries.ENCHANTMENT);
            if (item == Items.ENCHANTED_BOOK) {
                // Enchanted books hold their enchantments in STORED_ENCHANTMENTS
                // (anvil-applyable), NOT the live ENCHANTMENTS component.
                ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                for (EnchantSpec spec : enchants) {
                    stored.set(lookup.getOrThrow(spec.enchantment()), spec.level());
                }
                stack.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
            } else {
                for (EnchantSpec spec : enchants) {
                    stack.enchant(lookup.getOrThrow(spec.enchantment()), spec.level());
                }
            }
            return stack;
        }
    }

    /** One enchantment / Tensura engraving: a registry KEY (safe at class-load)
     *  + level, resolved to a Holder at grant time. */
    public record EnchantSpec(ResourceKey<Enchantment> enchantment, int level) {}

    /** Convenience: a Tensura engraving key (they're enchantments tagged
     *  {@code #tensura:engraving}) by path, e.g. {@code engraving("holy_weapon")}. */
    public static ResourceKey<Enchantment> engraving(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", path));
    }

    /** THE reward accessor every consumer uses — plain reward stacks plus the
     *  enchanted/engraved ones materialised via the world registry. */
    public List<ItemStack> resolvedRewards(HolderLookup.Provider registries) {
        List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack s : rewardItems) out.add(s.copy());
        for (EnchantedReward er : enchantedRewards) out.add(er.build(registries));
        return out;
    }

    /** What the faction asks for. Sealed — the extension seam. */
    public sealed interface Requirement
            permits SupplyItems, BuildingLevel, Population, Happiness, LendCitizens,
                    MendingRite, SupplyBundle, SlayEntities, WinWar, TwoFacedTrial {
        /** Player-facing one-liner ("Supply 64 Iron Ingot"). */
        String summary();
    }

    /** Luminous's Covenant capstone, "The Trial of Light &amp; Dark" — a two-part
     *  ritual with no simple item cost. On accept the player is granted two empty
     *  {@link TrialChalice}s; the "Show of Faith" (light) and "The Blood Sacrifice"
     *  (dark) trackers fill them as their milestones are met, and the deal is
     *  fulfilled by turning in BOTH full chalices. All the real work lives in the
     *  {@code TrialManager} trackers + the trial branch of {@code DiplomacyManager}
     *  accept/deliver — this marker just tags the deal. Both halves get HARDER for
     *  a majin (see docs/faction-rewards-roadmap.md). */
    public record TwoFacedTrial() implements Requirement {
        @Override public String summary() {
            return "Complete the Trial of Light & Dark, then bring the two full chalices";
        }
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

    /** Declare war on a rival settlement and WIN it ({@code count} times).
     *  Event-driven: {@code RivalColonies.resolveWin} → the diplomacy
     *  {@code onWarWon} hook bumps progress (never polled). */
    public record WinWar(int count) implements Requirement {
        @Override public String summary() {
            return count == 1
                    ? "Declare war on a rival colony and win"
                    : "Declare and win " + count + " wars on rival colonies";
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
        for (EnchantedReward er : enchantedRewards) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(er.count()).append(" ")
                    .append(new ItemStack(er.item()).getHoverName().getString())
                    .append(" (enchanted)");
        }
        return sb.length() == 0 ? "the faction's gratitude" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Stage-2 FACTION-FLAVORED deal tables — each faction offers what it
    // values; gating via minTier (better deals as standing rises).
    // Clayman / Leon / Eastern Empire offer small/no sets (hostility-
    // oriented or aloof — relations can open, but limited deals). All
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

    /** faction id → Covenant-only TRAINING deal. Jura-Tempest Federation
     *  fields the physical "Warrior Training"; the old Jura "Sage
     *  Training" was dropped in the merge (one training deal per faction). */
    public static final Map<String, DealSpec> COVENANT_TRAINING_DEALS = buildTrainingDeals();

    private static Map<String, DealSpec> buildCovenantDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        // Dwargon Covenant — GRANTS the Masterwork Weapon Core + the schematic
        // that unlocks the Masterwork weapon line at the Tensura Smithing Bench.
        // (Inverts the old deal, which consumed a core.)
        map.put("dwargon", new DealSpec("cov_dwargon", "The Masterwork Commission",
                new SupplyBundle(List.of(
                        new ItemStack(Items.NETHERITE_BLOCK, 1),
                        new ItemStack(ten("hihiirokane_ingot"), 1))),
                List.of(new ItemStack(ExampleMod.MASTERWORK_FORGING_CORE.get(), 1),
                        new ItemStack(ExampleMod.MASTERWORK_SCHEMATIC.get(), 1)),
                10.0, 0.0, 20 * DAY, 0, FactionTier.ALLIED, true));
        map.put("tempest", new DealSpec("cov_tempest", "A Thriving Metropolis",
                new Population(25),
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2)),   // PLACEHOLDER (tier II)
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        // (Jura's old cov_jura "The Grand Academy" dropped — the merged
        // Jura-Tempest Federation keeps Tempest's cov_tempest as its one
        // Covenant deal.)
        // The Ultimate Brawl ★ — Milim's Covenant capstone. Swapped in from her
        // old PACT deal (2026-07-28): slay the Warden for the custom ABSOLUTE
        // ANNIHILATOR (granted PLAIN — earns its own holy_coat engraving + EP
        // growth via gear_existence). Also grants the STRENGTH skill: SKILL_REWARDS
        // now keys on cov_milim, which is safe because Milim is an ABSTRACT faction
        // (no conquest path, so covenantSkillFor is never consulted) and fulfillDeal
        // grants the skill on covenant completion just like a catalog deal. Deal id
        // stays cov_milim; only the content moved (see mi_ultimate_brawl below).
        map.put("milim", new DealSpec("cov_milim", "The Ultimate Brawl",
                new SlayEntities(java.util.Set.of("minecraft:warden"), 1, "the Warden"),
                List.of(new ItemStack(ExampleMod.ABSOLUTE_ANNIHILATOR.get())),
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        map.put("falmuth", new DealSpec("cov_falmuth", "Prove Your Might",
                new SlayEntities(java.util.Set.of("minecraft:wither"), 1, "the Wither"),
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2)),   // PLACEHOLDER (tier II)
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        map.put("eurazania", new DealSpec("cov_carrion", "The Great Hunt",
                new SlayEntities(java.util.Set.of("minecraft:wither", "minecraft:warden",
                        "minecraft:elder_guardian", "tensura:charybdis", "tensura:ifrit"),
                        3, "great beasts (Wither / Warden / Elder Guardian / Charybdis / Ifrit)"),
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2)),   // PLACEHOLDER (tier II)
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        // Luminous's Covenant — "The Trial of Light & Dark" (real reward, 2026-07-28).
        // A two-faced ritual: prove faith by redeeming villagers (light) AND spill
        // subordinate blood (dark), each filling a chalice; deliver both full to
        // forge the Covenant and receive the Twin Grail. Both halves get harder for
        // a majin. All logic in TrialManager + the trial branch of accept/deliver.
        map.put("luminous", new DealSpec("cov_luminous", "The Trial of Light & Dark",
                new TwoFacedTrial(),
                List.of(new ItemStack(ExampleMod.TWIN_GRAIL.get())),
                10.0, 0.0, 0, 0, FactionTier.ALLIED, true));
        map.put("clayman", new DealSpec("cov_clayman", "Souls for the Core",
                new SlayEntities(java.util.Set.of("minecraft:villager"), 10,
                        "villagers (their souls feed the Charybdis core)"),
                // Phase 3: was empty; Clayman is TIER I (minor) → 32 emeralds.
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),   // PLACEHOLDER (tier I)
                10.0, 0.0, 30 * DAY, 0, FactionTier.ALLIED, true));
        // Phase 1 (faction-rewards roadmap) — Leon + Eastern Empire were
        // raidable towns with NO Covenant milestone; add one each so their
        // alliance can reach COVENANT like the other towns.
        map.put("leon", new DealSpec("cov_leon", "Tribute to the Platinum Saber",
                new SupplyBundle(List.of(
                        new ItemStack(Items.GOLD_BLOCK, 16),
                        new ItemStack(Items.BLAZE_ROD, 16),
                        new ItemStack(Items.NETHERITE_INGOT, 1))),
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 3)),   // PLACEHOLDER (tier III)
                10.0, 0.0, 20 * DAY, 0, FactionTier.ALLIED, true));
        map.put("eastern_empire", new DealSpec("cov_eastern_empire", "The Imperial Compact",
                new SupplyBundle(List.of(
                        new ItemStack(Items.DIAMOND_BLOCK, 4),
                        new ItemStack(Items.AMETHYST_SHARD, 32),
                        new ItemStack(Items.REDSTONE_BLOCK, 16))),
                List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2)),   // PLACEHOLDER (tier II)
                10.0, 0.0, 20 * DAY, 0, FactionTier.ALLIED, true));
        return Map.copyOf(map);
    }

    private static Map<String, DealSpec> buildTrainingDeals() {
        Map<String, DealSpec> map = new LinkedHashMap<>();
        // Jura-Tempest Federation — PHYSICAL/community: Strength + Stamina +
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

    /** Every custom potion reward lasts 1:30 (user-set). */
    private static final int POTION_TICKS = 20 * 90;

    /** One potion effect at the standard {@link #POTION_TICKS} duration. */
    private static net.minecraft.world.effect.MobEffectInstance eff(
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int amplifier) {
        return new net.minecraft.world.effect.MobEffectInstance(effect, POTION_TICKS, amplifier);
    }

    /**
     * A custom potion reward — custom name, colour and effects. Mob effects
     * live in a STATIC registry (unlike enchantments, which need the dynamic
     * per-world one), so these are safe to build right here at class-load and
     * can be plain reward literals — no deferred-build plumbing required.
     */
    private static ItemStack potion(String name, int color,
                                    net.minecraft.world.effect.MobEffectInstance... effects) {
        ItemStack stack = new ItemStack(Items.POTION);
        stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(
                        java.util.Optional.empty(), java.util.Optional.of(color), List.of(effects)));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(name)
                        .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA)
                                .withItalic(false)));
        return stack;
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
    public static List<ItemStack> factionRewardPool(String factionId, HolderLookup.Provider registries) {
        List<ItemStack> pool = new java.util.ArrayList<>();
        for (DealSpec d : FACTION_DEALS.getOrDefault(factionId, List.of())) {
            for (ItemStack stack : d.resolvedRewards(registries)) {
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
        // Dwargon's Body Armor skill rides "Forge a Sentinel" (dw_sentinel),
        // moved 2026-07-06 from The Grand Forge per user direction.
        m.put("dw_sentinel", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.BODY_ARMOR);
        // Tempest grants ONLY Self-Regeneration. It rides the CATALOG capstone
        // deal "Rimuru's Blessing" (tp_slime_pact) — the slime deal (Slime Core +
        // Slime Balls → Staff of Slime + gold). (Phase 0: the leftover
        // ja_sages → Thought Communication mapping was dropped so the
        // conquest/Covenant skill grant stays unambiguous.)
        m.put("tp_slime_pact", io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
        m.put("lu_devout", io.github.manasmods.tensura.registry.skill.ResistanceSkills.HOLY_ATTACK_RESISTANCE);
        m.put("fa_fortress", io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
        // Milim's STRENGTH rides with the Warden fight, which is now her Covenant
        // deal (cov_milim), not a catalog deal (swapped 2026-07-28). fulfillDeal
        // grants it on covenant completion; Milim is abstract so covenantSkillFor
        // (catalog-only) is never consulted for her.
        m.put("cov_milim", io.github.manasmods.tensura.registry.skill.CommonSkills.STRENGTH);
        m.put("ca_wild_haven", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.GIANTIFICATION);
        m.put("cl_marionette", io.github.manasmods.tensura.registry.skill.IntrinsicSkills.CHARM);
        m.put("le_flamebearers", io.github.manasmods.tensura.registry.skill.ResistanceSkills.FLAME_ATTACK_RESISTANCE);
        // Phase 0 decision: Shizu is soft-retired — its sh_pupils skill mapping
        // is purged along with its catalog table + conquest profile.
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
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 15),
                                new ItemStack(ten("bronze_coin"), 20)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_coal", "Fuel for the Forges",
                        new SupplyItems(Items.COAL, 64),
                        List.of(new ItemStack(ten("monster_leather_b"), 12),
                                new ItemStack(ten("bronze_coin"), 12)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_silver", "Silver for the Smiths",
                        new SupplyItems(ten("silver_ingot"), 32),
                        List.of(new ItemStack(ten("magic_stone"), 2),
                                new ItemStack(ten("low_magisteel_nugget"), 6),
                                new ItemStack(ten("silver_coin"), 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_magisteel_quota", "Magisteel Quota",
                        new SupplyItems(ten("low_magisteel_ingot"), 8),
                        List.of(new ItemStack(ten("spear_schematic"), 1),
                                new ItemStack(ten("medium_quality_magic_crystal"), 8),
                                new ItemStack(ten("silver_coin"), 6)),
                        7.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_blacksmith", "A Proper Smithy",
                        new SupplyItems(Items.GOLD_BLOCK, 8),
                        List.of(new ItemStack(Items.IRON_BLOCK, 8), new ItemStack(Items.COAL, 16),
                                new ItemStack(ten("short_sword_schematic"), 1),
                                new ItemStack(ten("bronze_coin"), 15)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                // Fires of Industry: schematic + fuel, no coin (signature reward).
                new DealSpec("dw_smeltery", "Fires of Industry",
                        new SupplyBundle(List.of(new ItemStack(ten("low_magisteel_ingot"), 2),
                                new ItemStack(ten("magic_stone"), 2))),
                        List.of(new ItemStack(ten("magic_staff_schematic"), 1),
                                new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("low_magisteel_ingot"), 2)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Grand Forge — Great Sword Schematic + an ENGRAVED forged
                // blade (High Magisteel Katana with the Crushing engraving).
                new DealSpec("dw_grand_forge", "The Grand Forge",
                        new SupplyBundle(List.of(new ItemStack(ten("pure_magisteel_ingot"), 1),
                                new ItemStack(ten("high_quality_magic_crystal"), 2))),
                        List.of(new ItemStack(ten("great_sword_schematic"), 1)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(ten("high_magisteel_katana"), 1, List.of(
                                new EnchantSpec(engraving("crushing"), 1))))),
                new DealSpec("dw_blades", "A Blade for Every Hand",
                        new SupplyItems(ten("pure_magisteel_ingot"), 2),
                        List.of(new ItemStack(ten("long_sword_schematic"), 1),
                                new ItemStack(ten("kunai_schematic"), 1),
                                new ItemStack(ten("magic_stone"), 8),
                                new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("dw_strong_backs", "Strong Backs for the Mines",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(Items.GOLD_INGOT, 16),
                                new ItemStack(ten("low_magisteel_ingot"), 4),
                                new ItemStack(ten("silver_coin"), 12)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                // Master Artisans: signature schematic + rare ingots, no coin.
                new DealSpec("dw_artisans", "Master Artisans Abroad",
                        new LendCitizens(Skill.Creativity, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("spatial_blade_schematic"), 1),
                                new ItemStack(ten("mithril_ingot"), 1),
                                new ItemStack(ten("orichalcum_ingot"), 1),
                                new ItemStack(ten("magic_tome_earth"), 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // Staff of the Smiths: COMMISSION — you PAY (silver coin) to have
                // a staff forged (coin in the requirement).
                new DealSpec("dw_staff", "Staff of the Smiths",
                        new SupplyBundle(List.of(new ItemStack(ten("magic_stone"), 8),
                                new ItemStack(ten("silver_coin"), 10))),
                        List.of(new ItemStack(ten("medium_magic_staff"), 1)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                // A Master's Tools — an ENCHANTED Diamond Pickaxe (mining kingdom).
                new DealSpec("dw_tools", "A Master's Tools",
                        new SupplyItems(ten("low_magisteel_ingot"), 3),
                        List.of(),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false,
                        List.of(new EnchantedReward(Items.DIAMOND_PICKAXE, 1, List.of(
                                new EnchantSpec(Enchantments.EFFICIENCY, 3),
                                new EnchantSpec(Enchantments.FORTUNE, 2))))),
                new DealSpec("dw_steel_thread", "Threads of Steel",
                        new SupplyBundle(List.of(new ItemStack(Items.IRON_INGOT, 16),
                                new ItemStack(Items.STRING, 16))),
                        List.of(new ItemStack(ten("steel_thread"), 8),
                                new ItemStack(ten("bronze_coin"), 10)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("dw_mountains_heart", "The Mountain's Heart",
                        new SupplyBundle(List.of(new ItemStack(Items.GOLD_INGOT, 30),
                                new ItemStack(Items.DIAMOND, 20), new ItemStack(Items.EMERALD, 10))),
                        List.of(new ItemStack(ten("element_core_earth"), 1),
                                new ItemStack(ten("magic_stone"), 6),
                                new ItemStack(ten("gold_coin"), 4)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                // Forge a Sentinel ★ — grants Dwargon's Body Armor skill (moved
                // here from The Grand Forge). Commission: pay a gold-coin forging
                // fee + 3 anvils; the golem + manual are the reward.
                new DealSpec("dw_sentinel", "Forge a Sentinel",
                        new SupplyBundle(List.of(new ItemStack(ten("high_magisteel_ingot"), 4),
                                new ItemStack(ten("magic_stone"), 1), new ItemStack(Items.BONE, 32),
                                new ItemStack(Items.ANVIL, 3), new ItemStack(ten("gold_coin"), 2))),
                        List.of(new ItemStack(ten("high_magisteel_bone_golem"), 1),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false)));

        // 🌿 TEMPEST (Jura-Tempest Federation) — Tier II trade capital. Reworked
        // 2026-07-06: catalog is now ACTIVE DEALS ONLY (supply / slay / lend) —
        // the Population / Happiness / Building "milestone" deals were removed.
        // Adds hipokute medicine, a Tempest-serpent hunt, and coin/caravan trade.
        map.put("tempest", List.of(
                new DealSpec("tp_provisions", "Provisions for Travellers",
                        new SupplyItems(Items.BREAD, 32),
                        List.of(new ItemStack(Items.IRON_INGOT, 8),
                                new ItemStack(ten("bronze_coin"), 20)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_market_meat", "Meat for the Market",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 16), new ItemStack(Items.EMERALD, 8)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_timber", "Timber for Expansion",
                        new SupplyItems(Items.OAK_LOG, 48),
                        List.of(new ItemStack(Items.BREAD, 16), new ItemStack(Items.COOKED_BEEF, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_helping_hands", "Helping Hands",
                        new LendCitizens(Skill.Adaptability, 5, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("magic_stone"), 6)),
                        8.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_skilled_hands", "Skilled Hands Abroad",
                        new LendCitizens(Skill.Dexterity, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 16),
                                new ItemStack(Items.DIAMOND, 4)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                // Medicine for the Realm — hipokute healing deal (no skill;
                // Tempest's capstone Self-Regeneration lives on tp_slime_pact).
                new DealSpec("tp_medicine", "Medicine for the Realm",
                        new SupplyItems(ten("hipokute_grass"), 16),
                        List.of(new ItemStack(ten("high_potion"), 4),
                                new ItemStack(ten("hipokute_seeds"), 4),
                                new ItemStack(ten("silver_coin"), 12)),
                        7.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_serpents", "Tempest Serpents",
                        new SlayEntities(java.util.Set.of("tensura:tempest_serpent"), 8, "Tempest Serpents"),
                        List.of(new ItemStack(ten("cooked_serpent_meat"), 4),
                                new ItemStack(ten("low_magisteel_ingot"), 2),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("tp_caravan_tolls", "Caravan Tolls",
                        new SupplyBundle(List.of(new ItemStack(Items.EMERALD, 32),
                                new ItemStack(Items.GOLD_INGOT, 8))),
                        List.of(new ItemStack(ten("pouch_a"), 1),
                                new ItemStack(ten("silver_coin"), 12)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                // Re-added 2026-07-06 as ACTIVE deals (were milestone deals).
                new DealSpec("tp_gather", "A Place to Gather",
                        new SupplyBundle(List.of(new ItemStack(Items.GLASS, 32),
                                new ItemStack(Items.BRICKS, 16))),
                        List.of(new ItemStack(Items.BREAD, 16),
                                new ItemStack(ten("bronze_coin"), 15)),
                        5.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("tp_content", "Content People",
                        new SupplyBundle(List.of(new ItemStack(Items.CAKE, 8),
                                new ItemStack(Items.COOKIE, 32))),
                        List.of(new ItemStack(ten("full_potion"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("tp_joyful", "A Joyful Haven",
                        new SupplyItems(Items.GOLDEN_APPLE, 8),
                        List.of(new ItemStack(ten("slime_in_a_bucket"), 1),
                                new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("gold_coin"), 4)),
                        7.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                // tp_slime_pact — the CATALOG capstone deal: grants Tempest's
                // Self-Regeneration skill (★, see SKILL_REWARDS). NOT the
                // COVENANT_DEALS milestone (cov_tempest) — a separate system.
                new DealSpec("tp_slime_pact", "Rimuru's Blessing",
                        new SupplyBundle(List.of(new ItemStack(ten("slime_core"), 1),
                                new ItemStack(Items.SLIME_BALL, 8))),
                        List.of(new ItemStack(ten("slime_staff"), 1),
                                new ItemStack(Items.DIAMOND, 16)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                // 📚 — the former JURA ALLIANCE catalog: supply + lend deals kept;
                // its Building-milestone deals (school / library / university) removed.
                new DealSpec("ja_harvest", "A Share of the Harvest",
                        new SupplyBundle(List.of(new ItemStack(Items.CARROT, 4),
                                new ItemStack(Items.BREAD, 4), new ItemStack(Items.BEETROOT, 4))),
                        List.of(new ItemStack(Items.BOOK, 16), new ItemStack(Items.SUGAR_CANE, 4)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_paper", "Food for the Mind",
                        new SupplyItems(Items.BOOKSHELF, 8),
                        List.of(new ItemStack(Items.GOLDEN_APPLE, 1), new ItemStack(Items.LAPIS_LAZULI, 24)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ja_books", "A Library's Worth",
                        new SupplyItems(Items.BOOK, 32),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 16),
                                new ItemStack(Items.EXPERIENCE_BOTTLE, 4)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_scholars", "Scholars Abroad",
                        new LendCitizens(Skill.Knowledge, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.LAPIS_LAZULI, 16),
                                new ItemStack(Items.EXPERIENCE_BOTTLE, 8),
                                new ItemStack(ten("magic_stone"), 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_focus", "Focused Minds Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 8), new ItemStack(mc("ancienttome"), 1)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_sages", "Sages for the Academy",
                        new LendCitizens(Skill.Intelligence, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 16),
                                new ItemStack(Items.LAPIS_LAZULI, 16), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(mc("ancienttome"), 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // Re-added 2026-07-06 as ACTIVE deals (were Building milestones).
                // ja_grimoire = renamed "Letters for the Young"; task is now
                // trading a Grade-D Grimoire.
                new DealSpec("ja_grimoire", "A Grimoire for the Academy",
                        new SupplyItems(ten("grimoire_d"), 1),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 8),
                                new ItemStack(Items.LAPIS_LAZULI, 24),
                                new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_library", "Halls of Knowledge",
                        new SupplyItems(Items.BOOKSHELF, 16),
                        List.of(new ItemStack(mc("ancienttome"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ja_university", "Higher Learning",
                        new LendCitizens(Skill.Knowledge, 6, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 16),
                                new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(mc("ancienttome"), 1),
                                new ItemStack(ten("gold_coin"), 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // Added 2026-07-06 — enchant/engrave rewards.
                // Forbidden Knowledge — an academy ENCHANTED BOOK (Mending).
                new DealSpec("tp_forbidden", "Forbidden Knowledge",
                        new SupplyItems(ten("grimoire_a"), 1),
                        List.of(new ItemStack(ten("silver_coin"), 8)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(Items.ENCHANTED_BOOK, 1, List.of(
                                new EnchantSpec(Enchantments.MENDING, 1))))),
                // A Scholar's Reward — task is a Grade-C Grimoire (NOT "32 Book",
                // to avoid duplicating "A Library's Worth"). Book: Unbreaking III.
                new DealSpec("ja_scholars_reward", "A Scholar's Reward",
                        new SupplyItems(ten("grimoire_c"), 1),
                        List.of(new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false,
                        List.of(new EnchantedReward(Items.ENCHANTED_BOOK, 1, List.of(
                                new EnchantSpec(Enchantments.UNBREAKING, 3))))),
                // KATANA?!? — Hakurou's blade: an engraved Pure Magisteel Katana.
                new DealSpec("tp_katana", "KATANA?!?",
                        new SupplyBundle(List.of(new ItemStack(ten("pure_magisteel_ingot"), 2),
                                new ItemStack(ten("high_quality_magic_crystal"), 4))),
                        List.of(new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 10 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(ten("pure_magisteel_katana"), 1, List.of(
                                new EnchantSpec(engraving("swift"), 1)))))));

        // ✦ LUMINOUS (Holy Empire) — Tier III premium/holy. Reworked 2026-07-06:
        // the Building/Happiness milestone deals converted to ACTIVE deals
        // (grimoires, holy healing, the Orc-Disaster crusade). Holy tomes only
        // (Recovery / Barrier). Diamonds, gold, enchanted apples, Anti-Magic Mask.
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
                        new SupplyItems(ten("grimoire_c"), 2),
                        List.of(new ItemStack(ten("magic_tome_recovery"), 1),
                                new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_university", "Sanctified Halls",
                        new SupplyItems(Items.GOLD_BLOCK, 8),
                        List.of(new ItemStack(Items.DIAMOND, 12),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_cathedral", "A Cathedral of Light",
                        new SupplyItems(Items.ENCHANTED_GOLDEN_APPLE, 2),
                        List.of(new ItemStack(ten("magic_tome_barrier"), 1),
                                new ItemStack(Items.DIAMOND, 16),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 10 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_hospital", "Sanctuary of Healing",
                        new SupplyItems(ten("hipokute_grass"), 16),
                        List.of(new ItemStack(ten("revival_elixir"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        7.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_devout", "A Devout Congregation",
                        new SlayEntities(java.util.Set.of("tensura:orc_disaster"), 1, "the Orc Disaster"),
                        List.of(new ItemStack(ten("anti_magic_mask"), 1),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1),
                                new ItemStack(ten("gold_coin"), 2)),
                        10.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("lu_faithful", "The Faithful Abroad",
                        new LendCitizens(Skill.Mana, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // Added 2026-07-06.
                new DealSpec("lu_blessed_grass", "Blessed Grass",
                        new SupplyItems(ten("hipokute_grass"), 32),
                        List.of(new ItemStack(ten("high_arcane_potion"), 2),
                                new ItemStack(ten("full_potion"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("lu_grimoire_light", "Grimoire of Light",
                        new SupplyItems(ten("grimoire_b"), 1),
                        List.of(new ItemStack(ten("magic_tome_barrier"), 1),
                                new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                // (Crusader's Trial deferred — needs the "in one night" slay
                // mechanic; see future-ideas.md. Not in the catalog for now.)
                new DealSpec("lu_purest_offering", "The Purest Offering",
                        new SupplyItems(Items.ENCHANTED_GOLDEN_APPLE, 4),
                        List.of(new ItemStack(Items.DIAMOND_BLOCK, 2),
                                new ItemStack(ten("magic_tome_recovery"), 1),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 10 * DAY, 0, FactionTier.ALLIED, false),
                // Added 2026-07-06 — enchanted holy gear + a holy enchanted book.
                new DealSpec("lu_crusaders_blade", "The Crusader's Blade",
                        new SupplyBundle(List.of(new ItemStack(Items.DIAMOND_BLOCK, 4),
                                new ItemStack(Items.GOLD_BLOCK, 16))),
                        List.of(),
                        10.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(Items.NETHERITE_SWORD, 1, List.of(
                                new EnchantSpec(Enchantments.SMITE, 5),
                                new EnchantSpec(Enchantments.LOOTING, 3),
                                new EnchantSpec(Enchantments.UNBREAKING, 3))))),
                new DealSpec("lu_blessed_aegis", "Blessed Aegis",
                        new SupplyItems(Items.DIAMOND_BLOCK, 8),
                        List.of(),
                        8.0, 5.0, 10 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(Items.DIAMOND_CHESTPLATE, 1, List.of(
                                new EnchantSpec(Enchantments.PROTECTION, 4),
                                new EnchantSpec(Enchantments.UNBREAKING, 3))))),
                new DealSpec("lu_sacred_verse", "A Sacred Verse",
                        new SupplyItems(ten("grimoire_c"), 1),
                        List.of(new ItemStack(ten("silver_coin"), 6)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false,
                        List.of(new EnchantedReward(Items.ENCHANTED_BOOK, 1, List.of(
                                new EnchantSpec(Enchantments.SMITE, 5)))))));

        // ⚔ FALMUTH — war metal, magisteel, battlewill manual, enhancement tome.
        map.put("falmuth", List.of(
                new DealSpec("fa_iron_quota", "The Iron Quota",
                        new SupplyItems(Items.IRON_INGOT, 64),
                        List.of(new ItemStack(Items.GOLD_INGOT, 8), new ItemStack(ten("bronze_coin"), 20)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_arrows", "Arrows for the Levy",
                        new SupplyItems(Items.ARROW, 64),
                        List.of(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.GOLD_INGOT, 4),
                                new ItemStack(ten("bronze_coin"), 15)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                // I Need More Steel! — the Diamond Sword is granted ENCHANTED
                // (Sharpness III + Looting + Unbreaking), built at grant time
                // via the enchanted-reward path (11th DealSpec arg).
                new DealSpec("fa_war_levy", "I Need More Steel!",
                        new SupplyItems(Items.IRON_BLOCK, 10),
                        List.of(new ItemStack(Items.GOLD_BLOCK, 5),
                                new ItemStack(ten("bronze_coin"), 15)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.NEUTRAL, false,
                        List.of(new EnchantedReward(Items.DIAMOND_SWORD, 1, List.of(
                                new EnchantSpec(Enchantments.SHARPNESS, 3),
                                new EnchantSpec(Enchantments.LOOTING, 3),
                                new EnchantSpec(Enchantments.UNBREAKING, 3))))),
                new DealSpec("fa_steel", "Blades of Magisteel",
                        new SupplyItems(ten("low_magisteel_ingot"), 4),
                        List.of(new ItemStack(Items.GOLD_BLOCK, 4),
                                new ItemStack(ten("magic_tome_enhancement"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_barracks", "Walls and Watchmen",
                        new SupplyItems(Items.IRON_BLOCK, 32),
                        List.of(new ItemStack(ten("low_magisteel_ingot"), 3),
                                potion("Siegebreaker's Tonic", 0xD9A334,
                                        eff(net.minecraft.world.effect.MobEffects.DIG_SPEED, 1),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 0)),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 6 * DAY, 0, FactionTier.ALLIED, false),
                // The Siege Train — Falmuth's war machine (Siegebreaker's Tonic).
                new DealSpec("fa_siege_train", "The Siege Train",
                        new SupplyBundle(List.of(new ItemStack(Items.TNT, 8),
                                new ItemStack(Items.IRON_BLOCK, 8))),
                        List.of(potion("Siegebreaker's Tonic", 0xD9A334,
                                        eff(net.minecraft.world.effect.MobEffects.DIG_SPEED, 1),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 0)),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_archery", "Towers and Bowmen",
                        new SupplyItems(Items.ARROW, 128),
                        List.of(new ItemStack(ten("short_bow"), 1), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(ten("bronze_coin"), 20)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("fa_fortress", "A Mighty Defense",
                        new SlayEntities(java.util.Set.of("minecraft:pillager", "minecraft:vindicator",
                                "minecraft:evoker", "minecraft:ravager"), 30, "raiders"),
                        List.of(new ItemStack(ten("long_sword_schematic"), 1),
                                new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("gold_coin"), 2)),
                        10.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("fa_garrison", "A Standing Garrison",
                        new LendCitizens(Skill.Stamina, 10, 3, 3 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_BLOCK, 3),
                                potion("Crusader's Draught", 0xC0392B,
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 0)),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_field_hands", "Hands for the Fields",
                        new LendCitizens(Skill.Stamina, 10, 5, 3 * DAY, 2),
                        List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("fa_shock_troops", "Shock Troops Abroad",
                        new LendCitizens(Skill.Strength, 8, 3, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 3), new ItemStack(Items.DIAMOND, 8)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("fa_powder", "Powder for the Cannons",
                        new SupplyItems(Items.GUNPOWDER, 32),
                        List.of(new ItemStack(Items.GOLD_INGOT, 8), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("bronze_coin"), 20)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false)));

        // 🐉 MILIM — feast food, diamonds, gravity tome, battlewill manual.
        map.put("milim", List.of(
                new DealSpec("mi_feast", "A Feast Worthy of Me!",
                        new SupplyItems(Items.COOKED_PORKCHOP, 64),
                        List.of(new ItemStack(Items.GOLDEN_CARROT, 16), new ItemStack(Items.GOLD_INGOT, 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_more_meat", "More Meat!",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(Items.GOLDEN_CARROT, 16),
                                potion("Dragon's Vigor", 0xE0218A,
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 1),
                                        eff(net.minecraft.world.effect.MobEffects.REGENERATION, 0))),
                        5.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("mi_fireworks", "Bring Me Fireworks!",
                        new SupplyItems(Items.FIREWORK_ROCKET, 16),
                        List.of(new ItemStack(Items.GOLD_INGOT, 6), new ItemStack(ten("bronze_coin"), 12),
                                new ItemStack(Items.GOLDEN_CARROT, 4)),
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
                new DealSpec("mi_arena", "For the Love of the Game",
                        new SlayEntities(java.util.Set.of("minecraft:zombie", "minecraft:skeleton",
                                "minecraft:spider", "minecraft:creeper", "minecraft:cave_spider",
                                "minecraft:husk", "minecraft:stray", "minecraft:drowned",
                                "minecraft:enderman", "minecraft:witch", "minecraft:pillager",
                                "minecraft:vindicator", "minecraft:zombie_villager"), 45, "monsters"),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.GOLDEN_APPLE, 2),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_strong_town", "Oooo Shiny!",
                        new SupplyItems(Items.DIAMOND_BLOCK, 8),
                        List.of(new ItemStack(ten("silver_apple"), 4), new ItemStack(Items.GOLDEN_APPLE, 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        8.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_mighty_town", "Prove Your Strength",
                        new SlayEntities(java.util.Set.of("minecraft:wither"), 1, "the Wither"),
                        List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2),
                                new ItemStack(Items.DIAMOND, 12), new ItemStack(ten("gold_coin"), 4),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        8.0, 5.0, 20 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("mi_happy_subjects", "Keep Them Cheerful",
                        new SupplyBundle(List.of(new ItemStack(Items.CAKE, 8),
                                new ItemStack(Items.COOKIE, 64))),
                        List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1),
                                new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_champions", "Champions for Milim",
                        new LendCitizens(Skill.Strength, 10, 2, 3 * DAY, 4),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.GOLDEN_APPLE, 2),
                                new ItemStack(ten("battlewill_manual"), 1)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("mi_warriors", "Warriors to Spar",
                        new LendCitizens(Skill.Athletics, 8, 2, 2 * DAY, 3),
                        List.of(new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("mi_hoard", "A Dragon's Hoard",
                        new SupplyItems(Items.GOLDEN_CARROT, 64),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.GOLD_INGOT, 8),
                                new ItemStack(Items.GOLDEN_APPLE, 1), new ItemStack(ten("silver_coin"), 8)),
                        7.0, 5.0, 5 * DAY, 0, FactionTier.FRIENDLY, false),
                // Apito's Jelly — swapped in from Milim's old Covenant deal
                // (2026-07-28): an ALLIED-tier (pact) delivery paying golden
                // apples. Deal id stays mi_ultimate_brawl; only the content moved.
                // No skill reward now — STRENGTH moved with the Warden fight to
                // cov_milim above. (The old 2 gold coins were dropped so the
                // covenant's Annihilator is the clean signature reward.)
                new DealSpec("mi_ultimate_brawl", "Apito's Jelly",
                        new SupplyItems(ExampleMod.APITOS_JELLY.get(), 1),
                        List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 3)),
                        9.0, 5.0, 15 * DAY, 0, FactionTier.ALLIED, false)));

        // 🦁 EURAZANIA (the Beast Kingdom; renamed from carrion) — monster
        // leather + beast mats + coin ladder + active hunts. (ids keep ca_.)
        map.put("eurazania", List.of(
                new DealSpec("ca_hides", "Hides for the Beastfolk",
                        new SupplyItems(Items.LEATHER, 32),
                        List.of(new ItemStack(ten("monster_leather_b"), 8),
                                new ItemStack(ten("bronze_coin"), 12)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_meat", "Meat for the Pack",
                        new SupplyItems(Items.COOKED_BEEF, 64),
                        List.of(new ItemStack(ten("meaty_stew"), 8),
                                new ItemStack(ten("bronze_coin"), 10)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_bones", "Bones for the Den",
                        new SupplyItems(Items.BONE, 64),
                        List.of(new ItemStack(Items.BONE_MEAL, 32),
                                potion("Beast-Blood Draught", 0x8B3A1E,
                                        eff(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 0),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0),
                                        eff(net.minecraft.world.effect.MobEffects.JUMP, 0)),
                                new ItemStack(ten("bronze_coin"), 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_sinew", "Sinew for Snares",
                        new SupplyItems(Items.STRING, 48),
                        List.of(new ItemStack(ten("monster_leather_b"), 12),
                                new ItemStack(ten("bronze_coin"), 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                // A Proper Den (was "Dens for the Beasts" / BuildingLevel) — now
                // an active supply deal.
                new DealSpec("ca_stable", "A Proper Den",
                        new SupplyBundle(List.of(new ItemStack(Items.CAMPFIRE, 2),
                                new ItemStack(Items.OAK_LOG, 16))),
                        List.of(new ItemStack(ten("silver_coin"), 20)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // A Great Hunt (was "A Great Pack" / Population) — now a slay hunt.
                new DealSpec("ca_great_pack", "A Great Hunt",
                        new SlayEntities(java.util.Set.of("minecraft:zombie", "minecraft:skeleton",
                                "minecraft:spider", "minecraft:creeper", "minecraft:husk",
                                "minecraft:stray", "minecraft:drowned", "minecraft:enderman",
                                "minecraft:pillager", "minecraft:vindicator"), 40, "monsters"),
                        List.of(new ItemStack(ten("monster_leather_a"), 10),
                                new ItemStack(ten("beast_horn"), 4),
                                new ItemStack(ten("silver_coin"), 12)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                // A Thriving Pack (was Happiness) — now a supply deal.
                new DealSpec("ca_thriving_pack", "A Thriving Pack",
                        new SupplyItems(ten("monster_leather_a"), 20),
                        List.of(new ItemStack(ten("full_potion"), 1), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("silver_coin"), 8)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Charybdis Hunt ★ (was "A Wild Haven"/Happiness) — the
                // capstone Charybdis hunt (name matched to the task).
                new DealSpec("ca_wild_haven", "The Charybdis Hunt",
                        new SlayEntities(java.util.Set.of("tensura:charybdis"), 1, "Charybdis"),
                        List.of(new ItemStack(ten("armorsaurus_scalemail_gear_schematic"), 1),
                                new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("gold_coin"), 3)),
                        7.0, 5.0, 16 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("ca_hunters", "Hunters Abroad",
                        new LendCitizens(Skill.Agility, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("monster_leather_b"), 8),
                                new ItemStack(ten("magic_stone"), 4),
                                new ItemStack(ten("silver_coin"), 6)),
                        7.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_trackers", "Keen Trackers",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(ten("monster_leather_c"), 6),
                                new ItemStack(ten("gold_coin"), 2)),
                        6.0, 5.0, 2 * DAY, 0, FactionTier.ALLIED, false),
                // The Beast-Horn Spear — the gathered horns forged into the
                // faction's signature spear (Sharpness V + swift engraving).
                new DealSpec("ca_horn_spear", "The Beast-Horn Spear",
                        new SupplyItems(ten("beast_horn"), 12),
                        List.of(new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("gold_coin"), 3)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(ten("beast_horn_spear"), 1, List.of(
                                new EnchantSpec(Enchantments.SHARPNESS, 5),
                                new EnchantSpec(engraving("swift"), 1))))),
                new DealSpec("ca_tiger_cull", "Blade Tiger Cull",
                        new SlayEntities(java.util.Set.of("tensura:blade_tiger"), 10, "Blade Tigers"),
                        List.of(new ItemStack(ten("blade_tiger_steak"), 8),
                                new ItemStack(ten("monster_leather_b"), 6),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_armorsaurus", "Armorsaurus Hide",
                        new SlayEntities(java.util.Set.of("tensura:armorsaurus"), 4, "Armorsaurus"),
                        List.of(new ItemStack(ten("armorsaurus_scale"), 8),
                                new ItemStack(ten("monster_leather_c"), 6),
                                new ItemStack(ten("silver_coin"), 12)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ca_tanner", "The Tanner's Trade",
                        new SupplyItems(ten("monster_leather_b"), 12),
                        List.of(new ItemStack(ten("silver_coin"), 20), new ItemStack(Items.DIAMOND, 4)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ca_pack_hunters", "Pack Hunters",
                        new SlayEntities(java.util.Set.of("tensura:direwolf"), 10, "Direwolves"),
                        List.of(new ItemStack(ten("monster_leather_special_a"), 2),
                                new ItemStack(Items.GOLDEN_APPLE, 2),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false)));

        // 🃏 CLAYMAN — schemer: crystals (lean), redstone, illusion tome.
        map.put("clayman", List.of(
                new DealSpec("cl_crystals", "Magic Crystals",
                        new SupplyItems(ten("low_quality_magic_crystal"), 16),
                        List.of(new ItemStack(Items.REDSTONE, 16),
                                new ItemStack(ten("bronze_coin"), 12)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_redstone", "Whispers and Wires",
                        new SupplyItems(Items.REDSTONE, 64),
                        List.of(new ItemStack(ten("low_quality_magic_crystal"), 8),
                                potion("Potion of Invisibility", 0x7F8392,
                                        eff(net.minecraft.world.effect.MobEffects.INVISIBILITY, 0)),
                                new ItemStack(ten("bronze_coin"), 10)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_gold", "Gold to Grease Palms",
                        new SupplyItems(Items.GOLD_INGOT, 32),
                        List.of(new ItemStack(ten("low_quality_magic_crystal"), 8),
                                potion("Potion of Night Vision", 0x1F1FA1,
                                        eff(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 0)),
                                new ItemStack(ten("bronze_coin"), 12)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                // Obedient Subjects (was Happiness) — now an active supply deal.
                new DealSpec("cl_obedient", "Obedient Subjects",
                        new SupplyItems(Items.REDSTONE, 24),
                        List.of(new ItemStack(ten("medium_quality_magic_crystal"), 2),
                                new ItemStack(ten("bronze_coin"), 8)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("cl_magic_tithe", "Magicule Tithe",
                        new SupplyItems(ten("medium_quality_magic_crystal"), 8),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 1),
                                new ItemStack(ten("magic_tome_illusion"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 6 * DAY, 0, FactionTier.FRIENDLY, false),
                // A Site of Dark Power (was BuildingLevel) — now an active supply deal.
                new DealSpec("cl_mystic", "A Site of Dark Power",
                        new SupplyBundle(List.of(new ItemStack(ten("medium_quality_magic_crystal"), 8),
                                new ItemStack(ten("magic_stone"), 4))),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(mc("scroll_buff"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Puppet-Maker's Workshop (was BuildingLevel) — now active supply.
                new DealSpec("cl_enchanter", "The Puppet-Maker's Workshop",
                        new SupplyBundle(List.of(new ItemStack(Items.LAPIS_LAZULI, 16),
                                new ItemStack(Items.REDSTONE, 16))),
                        List.of(new ItemStack(mc("scroll_buff"), 2),
                                new ItemStack(Items.LAPIS_LAZULI, 32),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // More Pawns (was Population) — now a 10-citizen levy.
                new DealSpec("cl_pawns", "More Pawns",
                        new LendCitizens(Skill.Stamina, 1, 10, 3 * DAY, 2),
                        List.of(new ItemStack(ten("slime_core"), 1),
                                new ItemStack(ten("medium_quality_magic_crystal"), 3),
                                new ItemStack(ten("silver_coin"), 10)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_spies", "Spies Abroad",
                        new LendCitizens(Skill.Focus, 6, 2, 2 * DAY, 2),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 1),
                                new ItemStack(ten("magic_tome_illusion"), 1),
                                new ItemStack(ten("silver_coin"), 6)),
                        7.0, 5.0, 2 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("cl_enforcers", "Enforcers for the Cause",
                        new LendCitizens(Skill.Strength, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // A Grand Illusion — the grade-A grimoire commission.
                new DealSpec("cl_grand_illusion", "A Grand Illusion",
                        new SupplyItems(ten("grimoire_a"), 1),
                        List.of(new ItemStack(ten("magic_tome_illusion"), 1),
                                new ItemStack(ten("high_quality_magic_crystal"), 2),
                                new ItemStack(ten("gold_coin"), 3)),
                        9.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false),
                // The Marionette ★ — Clayman's capstone (grants the CHARM skill):
                // pull the strings of a war and win it.
                new DealSpec("cl_marionette", "The Marionette",
                        new WinWar(1),
                        List.of(new ItemStack(ten("high_quality_magic_crystal"), 3),
                                new ItemStack(ten("slime_core"), 1),
                                new ItemStack(ten("gold_coin"), 4)),
                        10.0, 5.0, 24 * DAY, 0, FactionTier.ALLIED, false)));

        // 🔥 LEON — the Platinum Saber (Leon Cromwell): a master SWORDSMAN and
        // spirit SUMMONER whose signature is the fire spirit IFRIT (his anchor
        // boss). So: fire ELEMENT, but a martial/saber spine (Battlewill, the
        // Platinum Blade fire-katana) — not a generic flame faction. Tier III.
        // Covenant milestone is cov_leon (see buildCovenantDeals).
        map.put("leon", List.of(
                new DealSpec("le_magma", "Stones of Fire",
                        new SupplyItems(Items.MAGMA_BLOCK, 32),
                        List.of(new ItemStack(Items.MAGMA_CREAM, 8), new ItemStack(Items.GOLD_INGOT, 8)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_cinders", "Cinders Needed",
                        new SupplyItems(Items.BLAZE_POWDER, 32),
                        List.of(new ItemStack(Items.GLOWSTONE, 16), new ItemStack(ten("bronze_coin"), 12)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_coal", "Fuel for the Furnaces",
                        new SupplyItems(Items.COAL, 64),
                        List.of(new ItemStack(Items.BLAZE_POWDER, 16), new ItemStack(ten("bronze_coin"), 12)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("le_obsidian", "Obsidian for the Keep",
                        new SupplyItems(Items.OBSIDIAN, 16),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(ten("bronze_coin"), 20)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                // A Hearth of Flame (was smeltery 3) — now an active supply deal.
                new DealSpec("le_hearth", "A Hearth of Flame",
                        new SupplyBundle(List.of(new ItemStack(Items.BLAZE_ROD, 8),
                                new ItemStack(Items.MAGMA_BLOCK, 16))),
                        List.of(new ItemStack(ten("magic_tome_fire"), 1),
                                potion("Flamewarden's Brew", 0xE25822,
                                        eff(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 0),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0)),
                                new ItemStack(ten("silver_coin"), 10)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // Aid Needed (was "The Flame Legion" / Population) — a 6-citizen levy.
                new DealSpec("le_legion", "Aid Needed",
                        new LendCitizens(Skill.Stamina, 1, 6, 3 * DAY, 2),
                        List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.BLAZE_ROD, 8),
                                new ItemStack(ten("silver_coin"), 12)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                // Molten Cores (was "A Burning Devotion"/Happiness) — now an
                // active supply deal (name matched to the magma-cream task).
                new DealSpec("le_zealots", "Molten Cores",
                        new SupplyItems(Items.MAGMA_CREAM, 12),
                        List.of(potion("Flamewarden's Brew", 0xE25822,
                                        eff(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 0),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0)),
                                new ItemStack(Items.DIAMOND, 6),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("le_flamebearers", "Flamebearers Abroad",
                        new LendCitizens(Skill.Mana, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.BLAZE_ROD, 4), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("magic_tome_fire"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Greater Forge (was smeltery 5) — now an active supply deal.
                new DealSpec("le_greater_forge", "The Greater Forge",
                        new SupplyBundle(List.of(new ItemStack(ten("high_magisteel_ingot"), 4),
                                new ItemStack(ten("high_quality_magic_crystal"), 8))),
                        List.of(new ItemStack(ten("magic_tome_enhancement"), 1), new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("gold_coin"), 3)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("le_knights", "Flame Knights Abroad",
                        new LendCitizens(Skill.Strength, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(ten("high_magisteel_ingot"), 3), new ItemStack(Items.DIAMOND, 8),
                                new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                // Trial by Fire — a fire hunt (Flamewarden's Brew + martial manual).
                new DealSpec("le_trial", "Trial by Fire",
                        new SlayEntities(java.util.Set.of("minecraft:blaze", "minecraft:magma_cube"),
                                30, "blazes & magma cubes"),
                        List.of(potion("Flamewarden's Brew", 0xE25822,
                                        eff(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 0),
                                        eff(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 0)),
                                new ItemStack(ten("magic_tome_fire"), 1),
                                new ItemStack(ten("battlewill_manual"), 1),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 12 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Platinum Blade — the Platinum Saber's flame katana
                // (Fire Aspect II + Sharpness IV), built at grant time.
                new DealSpec("le_platinum_blade", "The Platinum Blade",
                        new SupplyItems(ten("high_magisteel_ingot"), 4),
                        List.of(new ItemStack(ten("gold_coin"), 3)),
                        9.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(ten("high_magisteel_katana"), 1, List.of(
                                new EnchantSpec(Enchantments.FIRE_ASPECT, 2),
                                new EnchantSpec(Enchantments.SHARPNESS, 4)))))));

        // 🕯 SHIZU — soft-retired (Phase 0 decision): its catalog table,
        // conquest profile (ConquestPayoff) and sh_pupils skill mapping were
        // PURGED. The enum value + auto-built mending deal are kept for
        // old-save compatibility. No settlement generates for it.

        // 🌐 EASTERN EMPIRE (re-themed from otherworlders) — exotic magitech
        // goods. Phase 3 (faction-rewards roadmap): expanded from 4 to 10
        // deals at TIER II (Major) value, matching Falmuth. Covenant milestone
        // is cov_eastern_empire (see buildCovenantDeals). Deal ids keep ow_.
        map.put("eastern_empire", List.of(
                new DealSpec("ow_curios", "Gimme Glass",
                        new SupplyItems(Items.GLASS, 32),
                        List.of(new ItemStack(Items.AMETHYST_SHARD, 8), new ItemStack(ten("bronze_coin"), 12)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_contraptions", "Strange Contraptions",
                        new SupplyItems(Items.REDSTONE_BLOCK, 16),
                        List.of(new ItemStack(Items.AMETHYST_SHARD, 4), new ItemStack(ten("bronze_coin"), 12)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_amethyst", "Resonant Crystals",
                        new SupplyItems(Items.AMETHYST_SHARD, 32),
                        List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(ten("bronze_coin"), 15)),
                        5.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_copper", "Gimme Copper",
                        new SupplyItems(Items.COPPER_BLOCK, 16),
                        List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(ten("bronze_coin"), 12)),
                        4.0, 5.0, 3 * DAY, 0, FactionTier.NEUTRAL, false),
                // A Different Civilization (was "Settlers from Afar" / Population)
                // — now an active supply deal.
                new DealSpec("ow_settlers", "A Different Civilization",
                        new SupplyBundle(List.of(new ItemStack(Items.AMETHYST_SHARD, 16),
                                new ItemStack(Items.COPPER_BLOCK, 8), new ItemStack(Items.EMERALD, 8))),
                        List.of(new ItemStack(mc("scroll_area_tp"), 1), new ItemStack(Items.DIAMOND, 6),
                                new ItemStack(ten("silver_coin"), 10)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // The Magitech Foundry (was smeltery 3) — now an active supply deal.
                new DealSpec("ow_foundry", "The Magitech Foundry",
                        new SupplyBundle(List.of(new ItemStack(Items.IRON_BLOCK, 8),
                                new ItemStack(Items.AMETHYST_SHARD, 16))),
                        List.of(new ItemStack(ten("magic_tome_summoning"), 1),
                                new ItemStack(ten("medium_quality_magic_crystal"), 2),
                                new ItemStack(ten("silver_coin"), 10)),
                        6.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                // Crystal Requisition (was "A Well-Ordered City" / Happiness) —
                // now an active supply deal (name matched to the amethyst task).
                new DealSpec("ow_order", "Crystal Requisition",
                        new SupplyItems(Items.AMETHYST_SHARD, 24),
                        List.of(potion("Imperial Stimulant", 0x2AA9E0,
                                        eff(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 1),
                                        eff(net.minecraft.world.effect.MobEffects.DIG_SPEED, 0)),
                                new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("silver_coin"), 10)),
                        7.0, 5.0, 8 * DAY, 0, FactionTier.FRIENDLY, false),
                new DealSpec("ow_specialists", "Specialists Abroad",
                        new LendCitizens(Skill.Intelligence, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(Items.AMETHYST_SHARD, 8), new ItemStack(Items.DIAMOND, 4),
                                new ItemStack(ten("magic_tome_summoning"), 1),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                // An Imperial Garrison (was barracks 4) — now an active supply
                // deal rewarding an enchanted imperial blade.
                new DealSpec("ow_garrison", "An Imperial Garrison",
                        new SupplyBundle(List.of(new ItemStack(ten("high_magisteel_ingot"), 4),
                                new ItemStack(Items.AMETHYST_SHARD, 8))),
                        List.of(new ItemStack(ten("gold_coin"), 3)),
                        8.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false,
                        List.of(new EnchantedReward(ten("high_magisteel_sword"), 1, List.of(
                                new EnchantSpec(Enchantments.SHARPNESS, 4),
                                new EnchantSpec(Enchantments.UNBREAKING, 3))))),
                new DealSpec("ow_engineers", "Engineers Abroad",
                        new LendCitizens(Skill.Dexterity, 8, 2, 3 * DAY, 3),
                        List.of(new ItemStack(Items.DIAMOND, 8), new ItemStack(ten("high_quality_magic_crystal"), 1),
                                new ItemStack(ten("gold_coin"), 2)),
                        8.0, 5.0, 3 * DAY, 0, FactionTier.ALLIED, false),
                new DealSpec("ow_levy", "The Imperial Levy",
                        new SupplyItems(ten("low_magisteel_ingot"), 4),
                        List.of(new ItemStack(ten("low_magisteel_nugget"), 6)),
                        4.0, 5.0, 4 * DAY, 0, FactionTier.NEUTRAL, false),
                new DealSpec("ow_conscripts", "Arcane Conscripts",
                        new LendCitizens(Skill.Mana, 6, 2, 3 * DAY, 2),
                        List.of(new ItemStack(ten("magic_tome_summoning"), 1),
                                potion("Imperial Stimulant", 0x2AA9E0,
                                        eff(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 1),
                                        eff(net.minecraft.world.effect.MobEffects.DIG_SPEED, 0)),
                                new ItemStack(ten("silver_coin"), 8)),
                        6.0, 5.0, 3 * DAY, 0, FactionTier.FRIENDLY, false),
                // A New Type of Soldier — the Empire fields a magitech construct.
                new DealSpec("ow_champion", "A New Type of Soldier",
                        new SupplyBundle(List.of(new ItemStack(ten("high_quality_magic_crystal"), 4),
                                new ItemStack(Items.AMETHYST_SHARD, 8),
                                new ItemStack(ten("low_magisteel_ingot"), 4))),
                        List.of(new ItemStack(ten("low_magisteel_bone_golem"), 1),
                                new ItemStack(ten("gold_coin"), 4)),
                        9.0, 5.0, 12 * DAY, 0, FactionTier.ALLIED, false)));

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
