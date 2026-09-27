package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.EntityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rival-colony arc — Stage D: the CONQUEST PAYOFF. Runs when an assault is
 * WON (Stage C's {@link RivalColonies#resolveWin}, gated by Stage B's
 * {@code isConquestEligible} — boss dead AND ≥60% defenders killed). It grants
 * the rewards and turns the settlement into a defeated husk. <b>It does NOT
 * found a second colony</b> (DESIGN CHANGE 2) — citizens go to the player's
 * EXISTING colony.
 *
 * <p><b>Phase 2 rework (2026-09-26, developer-approved).</b> A player's FIRST
 * conquest of a faction pays the full haul; every later conquest of that
 * faction (settlements are worldgen, so there can be many) pays a REDUCED
 * haul. Tracked per player in {@link SettlementSavedData#hasConquered}.
 * <ol>
 *   <li><b>Citizen levy</b> — 8–10 citizens by faction tier (IV 10 · III 9 ·
 *       II 8; half on repeats), of the faction's RACE: Dwargon → dwarves,
 *       Jura-Tempest → goblins + lizardmen, the human nations → colonists.
 *       Each arrives with a modest trained skill pair (+{@value #PRIMARY_BOOST}
 *       / +{@value #SECONDARY_BOOST}; was +20–25). Capped by housing.</li>
 *   <li><b>The Covenant item — FIRST conquest only</b> — the same unique item
 *       the faction's Covenant deal pays, handed straight to the player.
 *       (Future: faction-specific war trophies replace this — future-ideas.md.)</li>
 *   <li><b>The faction's skill — FIRST conquest only</b> — the same skill
 *       diplomacy grants, by force (idempotent).</li>
 *   <li><b>Loot chest</b> — coins sized to the faction's tier + a few
 *       ordinary themed goods ({@link #GOODS}) + a few rewards from the
 *       faction's diplomacy deal tables ({@link #addDealRewards}: no repeats,
 *       amounts varied 50–100%, at most one unstackable item). The old
 *       draw-WITH-replacement from {@code factionRewardPool} could hand out
 *       several copies of a deal's top reward.</li>
 * </ol>
 *
 * <p>The boss's death during the assault already fired the Layer-1
 * marked-kill world-rep fan-out; this class does NOT touch reputation.
 * ⚠ All counts/coins/goods are BALANCE GUESSES (unplayed).
 */
public final class ConquestPayoff {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConquestPayoff.class);

    private ConquestPayoff() {}

    // ------------------------------------------------------------------
    // Per-faction levy profiles
    // ------------------------------------------------------------------

    /** Skill levels added to each levied citizen (MineColonies skills run 1–99). */
    static final int PRIMARY_BOOST = 12;
    static final int SECONDARY_BOOST = 8;

    /** Which kind of citizens a faction's levy is made of. */
    enum LevyRace { COLONISTS, DWARVES, GOBLINS_AND_LIZARDMEN }

    /** A faction's levy: who they are, the two skills they arrive trained in,
     *  and a role label for the chat message. Head count comes from the tier. */
    record CitizenProfile(LevyRace race, Skill primary, Skill secondary, String role) {}

    private static final Map<String, CitizenProfile> PROFILES = new LinkedHashMap<>();
    static {
        // Dwargon — dwarven miners & smiths.
        PROFILES.put("dwargon", new CitizenProfile(LevyRace.DWARVES, Skill.Strength, Skill.Stamina, "miners"));
        // Falmuth — a militaristic kingdom: hardened soldiery.
        PROFILES.put("falmuth", new CitizenProfile(LevyRace.COLONISTS, Skill.Stamina, Skill.Strength, "soldiers"));
        // Luminous — the Holy Empire: clergy-mages & scholars.
        PROFILES.put("luminous", new CitizenProfile(LevyRace.COLONISTS, Skill.Mana, Skill.Knowledge, "clergy"));
        // Leon — a demon lord's elite retinue.
        PROFILES.put("leon", new CitizenProfile(LevyRace.COLONISTS, Skill.Strength, Skill.Mana, "retainers"));
        // Eastern Empire — magitech military levy.
        PROFILES.put("eastern_empire", new CitizenProfile(LevyRace.COLONISTS, Skill.Strength, Skill.Intelligence,
                "imperial soldiers"));
        // Jura-Tempest Federation — the forest nation's monster peoples.
        PROFILES.put("tempest", new CitizenProfile(LevyRace.GOBLINS_AND_LIZARDMEN, Skill.Knowledge, Skill.Adaptability,
                "townsfolk"));
        // Held back behind the TR:N gate (kept so the gate lifting "just works").
        PROFILES.put("milim", new CitizenProfile(LevyRace.COLONISTS, Skill.Strength, Skill.Agility, "dragon faithful"));
        PROFILES.put("fulbrosia", new CitizenProfile(LevyRace.COLONISTS, Skill.Agility, Skill.Focus, "harpy attendants"));
    }

    private static final CitizenProfile DEFAULT_PROFILE =
            new CitizenProfile(LevyRace.COLONISTS, Skill.Stamina, Skill.Adaptability, "captives");

    static CitizenProfile profileFor(String factionId) {
        return PROFILES.getOrDefault(factionId, DEFAULT_PROFILE);
    }

    /** Faction tier (the four-tier ladder in faction-rewards-roadmap.md). */
    private static int tierOf(String factionId) {
        return switch (factionId) {
            case "luminous", "leon", "dwargon", "milim" -> 4;
            case "eastern_empire", "eurazania", "fulbrosia" -> 3;
            case "clayman" -> 1;
            default -> 2;   // falmuth, tempest
        };
    }

    /** Levy head count: 8–10 by tier; halved (rounded up) on a repeat conquest. */
    static int levyCount(String factionId, boolean firstConquest) {
        int full = switch (tierOf(factionId)) {
            case 4 -> 10;
            case 3 -> 9;
            default -> 8;
        };
        return firstConquest ? full : (full + 1) / 2;
    }

    // ------------------------------------------------------------------
    // Loot — coins by tier + ordinary themed goods
    // ------------------------------------------------------------------

    /** Ordinary themed goods per faction — deliberately NOT the diplomacy
     *  deals' special rewards. A conquest draws {@link #GOODS_FIRST} of these
     *  (without repeats) on the first win, {@link #GOODS_REPEAT} after. */
    private static final Map<String, List<ItemStack>> GOODS = new LinkedHashMap<>();
    static {
        GOODS.put("dwargon", List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.GOLD_INGOT, 8),
                new ItemStack(Items.COPPER_INGOT, 24), new ItemStack(Items.COAL, 32),
                new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.ANVIL, 1)));
        GOODS.put("falmuth", List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.ARROW, 32),
                new ItemStack(Items.SHIELD, 1), new ItemStack(Items.BREAD, 24),
                new ItemStack(Items.LEATHER, 12), new ItemStack(Items.EMERALD, 6)));
        GOODS.put("luminous", List.of(new ItemStack(Items.GOLD_INGOT, 8), new ItemStack(Items.LAPIS_LAZULI, 16),
                new ItemStack(Items.GLOWSTONE_DUST, 16), new ItemStack(Items.BOOK, 8),
                new ItemStack(Items.CANDLE, 8), new ItemStack(Items.DIAMOND, 2)));
        GOODS.put("leon", List.of(new ItemStack(Items.GOLD_INGOT, 12), new ItemStack(Items.BLAZE_ROD, 6),
                new ItemStack(Items.MAGMA_CREAM, 6), new ItemStack(Items.QUARTZ, 16),
                new ItemStack(Items.FIRE_CHARGE, 8), new ItemStack(Items.DIAMOND, 2)));
        GOODS.put("eastern_empire", List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.AMETHYST_SHARD, 12),
                new ItemStack(Items.REDSTONE, 32), new ItemStack(Items.COPPER_INGOT, 24),
                new ItemStack(Items.GLASS, 32), new ItemStack(Items.DIAMOND, 2)));
        GOODS.put("tempest", List.of(new ItemStack(Items.BREAD, 24), new ItemStack(Items.EMERALD, 8),
                new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.HONEY_BOTTLE, 6),
                new ItemStack(Items.SLIME_BALL, 12), new ItemStack(Items.LEATHER, 12)));
    }

    private static final int GOODS_FIRST = 5;
    private static final int GOODS_REPEAT = 3;

    /** How many rewards from the faction's diplomacy deal tables go in the
     *  chest too (first / repeat conquest). Drawn WITHOUT repeats, each at a
     *  random 50–100% of the deal's own amount, and at most ONE unstackable
     *  item (a weapon, a schematic…) per chest, so a conquest can't hand out a
     *  pile of the deals' best rewards. */
    private static final int DEAL_REWARDS_FIRST = 3;
    private static final int DEAL_REWARDS_REPEAT = 1;

    /** Coins for the chest by tier: {gold, silver}; halved on repeats. */
    private static int[] coinsFor(String factionId) {
        return switch (tierOf(factionId)) {
            case 4 -> new int[] {5, 16};
            case 3 -> new int[] {4, 12};
            case 1 -> new int[] {2, 8};
            default -> new int[] {3, 10};
        };
    }

    private static final int CHEST_SLOTS = 27;

    // ------------------------------------------------------------------
    // The payoff
    // ------------------------------------------------------------------

    /**
     * Apply the full conquest payoff for a WON assault. {@code level} is
     * the SETTLEMENT's level (where the husk + loot land); the player has
     * already been teleported home, so the citizen levy targets the
     * player's CURRENT-level colony.
     */
    static void apply(ServerLevel level, Settlement s, ServerPlayer player) {
        BossFaction faction = BossFaction.byId(s.factionId);
        String factionName = faction != null ? faction.displayName() : s.factionId;

        SettlementSavedData saved = SettlementSavedData.get(level);
        boolean first = !saved.hasConquered(player.getUUID(), s.factionId);
        saved.markConquered(player.getUUID(), s.factionId);

        grantCitizenLevy(player, s, factionName, first);
        if (first) {
            grantCovenantItem(player, s, factionName);
            grantCovenantSkill(player, s, factionName);
        } else {
            player.sendSystemMessage(Component.literal("You have conquered the " + factionName
                    + " before — this victory pays a smaller haul.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        spawnLootChests(level, s, factionName, first);
        convertToHusk(level, s);

        LOGGER.info("[TM] rival: payoff complete for settlement #{} ({}, {} conquest) — now a husk",
                s.id, s.factionId, first ? "FIRST" : "repeat");
    }

    // --- 1. citizen levy (to the EXISTING colony, cap-aware) -----------

    private static void grantCitizenLevy(ServerPlayer player, Settlement s, String factionName, boolean first) {
        ServerLevel level = player.serverLevel();
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, player.getUUID());
        CitizenProfile profile = profileFor(s.factionId);
        int wanted = levyCount(s.factionId, first);

        if (colony == null || !colony.getServerBuildingManager().hasTownHall()) {
            // EDGE CASE — no colony to receive them: skip the levy, notify,
            // keep the other rewards.
            player.sendSystemMessage(Component.literal("The " + factionName
                    + " levy has no colony to join — found one to take captives next time.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            LOGGER.info("[TM] rival: conquest citizen levy skipped — {} owns no colony",
                    player.getName().getString());
            return;
        }

        // Housing cap: add what fits; report the remainder rather than dropping silently.
        int current = colony.getCitizenManager().getCurrentCitizenCount();
        int max = colony.getCitizenManager().getMaxCitizens();
        int headroom = Math.max(0, max - current);
        int toAdd = Math.min(wanted, headroom);

        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos spawnAt = EntityUtils.getSpawnPoint(level, th);
        if (spawnAt == null) spawnAt = th;

        int added = 0;
        for (int i = 0; i < toAdd; i++) {
            try {
                ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
                if (data == null) break;
                Race race = raceFor(profile.race(), i, level);
                if (race != null) {
                    // A grown, unnamed race citizen — the same path envoy seeds
                    // and immigrants use (identity + variant + race skill bias).
                    ExampleMod.mintRaceCitizen(level, colony, data, race, false);
                } else {
                    data.setName(capitalize(factionName) + " " + capitalize(profile.role()) + " " + (i + 1));
                }
                data.setIsChild(false);
                data.getCitizenSkillHandler().incrementLevel(profile.primary(), PRIMARY_BOOST);
                data.getCitizenSkillHandler().incrementLevel(profile.secondary(), SECONDARY_BOOST);
                if (data.getEntity().isEmpty()) {
                    // force=true: a levy is earned, not a random move-in, so it
                    // must get a body even when the town hall's "move in" is off.
                    colony.getCitizenManager().spawnOrCreateCivilian(
                            data, level, java.util.List.of(spawnAt), true);
                }
                added++;
            } catch (Throwable t) {
                LOGGER.warn("[TM] rival: conquest citizen #{} failed", i, t);
            }
        }

        player.sendSystemMessage(Component.literal(added + " " + factionName + " "
                + profile.role() + " join your colony (trained in "
                + profile.primary().name() + " & " + profile.secondary().name() + ").")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        if (added < wanted) {
            int unhoused = wanted - added;
            player.sendSystemMessage(Component.literal(unhoused + " more could not be housed — "
                    + "your colony is at capacity (" + current + "/" + max + "). Expand to take more.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        LOGGER.info("[TM] rival: conquest levy — {} {} added to colony {} (wanted {}, headroom {})",
                added, profile.role(), colony.getID(), wanted, headroom);
    }

    /** The race of levied citizen #{@code i}, or null for a plain colonist.
     *  Jura-Tempest alternates goblin / lizardman, starting on a random one. */
    private static Race raceFor(LevyRace levy, int i, ServerLevel level) {
        return switch (levy) {
            case DWARVES -> Race.DWARF;
            case GOBLINS_AND_LIZARDMEN -> (i % 2 == 0) ? Race.GOBLIN : Race.LIZARDMAN;
            case COLONISTS -> null;
        };
    }

    // --- 2. the Covenant item (first conquest only) --------------------

    private static void grantCovenantItem(ServerPlayer player, Settlement s, String factionName) {
        DealSpec covenant = DealSpec.COVENANT_DEALS.get(s.factionId);
        if (covenant == null) return;
        for (ItemStack stack : covenant.resolvedRewards(player.level().registryAccess())) {
            if (stack.isEmpty()) continue;
            ItemStack give = stack.copy();
            String name = give.getHoverName().getString();
            if (!player.getInventory().add(give)) player.drop(give, false);
            player.sendSystemMessage(Component.literal("Among the " + factionName + "'s treasures: "
                    + name + ".").withStyle(net.minecraft.ChatFormatting.GOLD));
        }
        LOGGER.info("[TM] rival: first conquest of {} — Covenant item granted to {}",
                s.factionId, player.getName().getString());
    }

    // --- 3. the faction's skill (first conquest only) ------------------

    private static void grantCovenantSkill(ServerPlayer player, Settlement s, String factionName) {
        var supplier = DealSpec.covenantSkillFor(s.factionId);
        if (supplier == null) {
            LOGGER.info("[TM] rival: {} has no capstone skill — no skill reward", s.factionId);
            return;
        }
        try {
            DiplomacyManager.grantSkillReward(player, supplier.get());
            LOGGER.info("[TM] rival: conquest granted {}'s Covenant skill to {} (by force)",
                    s.factionId, player.getName().getString());
        } catch (Throwable t) {
            LOGGER.warn("[TM] rival: conquest skill grant failed for {}", s.factionId, t);
        }
    }

    // --- 4. loot chest: coins + ordinary goods --------------------------

    private static void spawnLootChests(ServerLevel level, Settlement s, String factionName, boolean first) {
        List<ItemStack> loot = new ArrayList<>();
        int[] coins = coinsFor(s.factionId);
        int gold = first ? coins[0] : (coins[0] + 1) / 2;
        int silver = first ? coins[1] : coins[1] / 2;
        addCoin(loot, "gold_coin", gold);
        addCoin(loot, "silver_coin", silver);

        List<ItemStack> goods = new ArrayList<>(GOODS.getOrDefault(s.factionId, List.of()));
        Collections.shuffle(goods, new java.util.Random(level.getRandom().nextLong()));
        int take = Math.min(goods.size(), first ? GOODS_FIRST : GOODS_REPEAT);
        for (int i = 0; i < take; i++) loot.add(goods.get(i).copy());

        addDealRewards(level, s.factionId, loot, first ? DEAL_REWARDS_FIRST : DEAL_REWARDS_REPEAT);

        if (loot.isEmpty()) {
            LOGGER.info("[TM] rival: {} conquest has no loot — no chest", s.factionId);
            return;
        }
        BlockPos at = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, s.center);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        BlockEntity be = level.getBlockEntity(at);
        if (!(be instanceof Container container)) return;
        int slots = Math.min(CHEST_SLOTS, container.getContainerSize());
        int placed = 0;
        for (ItemStack stack : loot) {
            if (placed >= slots) break;
            container.setItem(placed++, stack);
        }
        LOGGER.info("[TM] rival: conquest loot — {} stacks in a chest at {}", placed, at);
    }

    /** A few rewards from the faction's diplomacy deals, amounts varied. */
    private static void addDealRewards(ServerLevel level, String factionId, List<ItemStack> loot, int want) {
        List<ItemStack> pool = new ArrayList<>();
        java.util.Set<Item> seen = new java.util.HashSet<>();
        for (ItemStack stack : DealSpec.factionRewardPool(factionId, level.registryAccess())) {
            if (isCoin(stack) || !seen.add(stack.getItem())) continue;   // coins are paid separately; no repeats
            pool.add(stack);
        }
        Collections.shuffle(pool, new java.util.Random(level.getRandom().nextLong()));
        boolean unstackableTaken = false;
        int added = 0;
        for (ItemStack stack : pool) {
            if (added >= want) break;
            ItemStack give = stack.copy();
            if (give.getMaxStackSize() == 1) {
                if (unstackableTaken) continue;
                unstackableTaken = true;
            } else {
                int full = give.getCount();
                int min = Math.max(1, (full + 1) / 2);
                give.setCount(min + level.getRandom().nextInt(full - min + 1));   // 50–100% of the deal amount
            }
            loot.add(give);
            added++;
        }
    }

    private static boolean isCoin(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("tensura") && id.getPath().endsWith("_coin");
    }

    private static void addCoin(List<ItemStack> loot, String path, int count) {
        if (count <= 0) return;
        Item coin = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("tensura", path)).orElse(null);
        if (coin != null) loot.add(new ItemStack(coin, count));
    }

    // --- 4. defeated-husk conversion -----------------------------------

    /**
     * Convert the settlement to a permanent DEFEATED HUSK: buildings
     * REMAIN (a sacked ruin), the boss is dead, the surviving defenders
     * are cleared, and the {@code conquered} flag makes it inert — never
     * re-discovered-to-war, never garrison-reset. Structure-type-agnostic
     * (town husks and dwarven-village husks behave identically).
     */
    private static void convertToHusk(ServerLevel level, Settlement s) {
        // Clear any defenders still standing (the win needed only ≥60%).
        for (UUID u : new ArrayList<>(s.garrisonUuids)) {
            Entity e = level.getEntity(u);
            if (e != null && !e.isRemoved()) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                        e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
                e.discard();
            }
        }
        s.garrisonUuids.clear();
        s.conquered = true;       // inert — see RivalColonies gates (declare/garrison/assault)
        s.assaulted = false;
        s.bossUuid = null;        // the anchor is gone for good
        // conquestReached stays true (it recorded the WIN); buildings stay.
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
