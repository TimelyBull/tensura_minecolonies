package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.EntityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Diplomacy Stage 1 — the sole door to {@link DiplomacySavedData} and
 * the driver for the whole layer (docs/diplomacy.md): envoy-based entry
 * (race-gated), the relations tier system, the deal lifecycle, standing
 * movers + decay. Every standing read/write goes through
 * {@link WorldReputationManager} (Layer 1's sole door); every entry
 * point here is additionally gated by {@code factionSystemEnabled}.
 *
 * <p>Driven from {@code ExampleMod.onServerTickPost} on the shared 1 s
 * cadence; the daily pass (offer refresh, decay, inbound envoy rolls)
 * rolls over on the overworld day inside {@link #tick}.
 *
 * <p><b>Collapse freebie:</b> the per-second collapse check derives
 * everything from Layer-1 standing — so ANY standing crash below WARY
 * (including the Orc Disaster's forced-HOSTILE clamp on Clayman)
 * shatters relations with no event-specific code.
 */
public final class DiplomacyManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiplomacyManager.class);

    // ------------------------------------------------------------------
    // Tuning constants (all reported in docs/diplomacy.md)
    // ------------------------------------------------------------------

    static final long DAY = 24_000L;

    // --- standing movers (through WorldRepReason.DIPLOMACY) ---
    /** Relations opened (either direction). */
    static final double STANDING_ENTRY_OPEN = +2.0;
    /** Outbound envoy gift accepted with the envoy. */
    static final double STANDING_GIFT = +2.0;
    /** Offer ignored to expiry. */
    static final double STANDING_OFFER_EXPIRED = -1.0;
    // (deal success/failure magnitudes live on each DealSpec: +4 supply,
    //  +6 building/population/happiness, +10 pact; −5 failure.)

    // --- decay (the daily pass; idle days only) ---
    /** OPEN relations fray without attention. */
    static final double DIPLOMACY_DECAY_PER_DAY = 0.5;
    /** A PACT barely decays — but a fully abandoned alliance eventually
     *  frays to WARY and shatters, which is a story. */
    static final double ALLIANCE_DECAY_PER_DAY = 0.1;

    // --- entry timing ---
    /** Outbound envoy → faction reply delay. */
    static final long REPLY_DELAY_TICKS = DAY;
    /** Minimum gap between outbound envoys to the same faction. */
    static final long SEND_COOLDOWN_TICKS = DAY;
    /** The faction accepts when standing is at least this (WARY floor). */
    static final double ENTRY_ACCEPT_STANDING = 20.0;
    /** Inbound envoy: per-faction chance per day for eligible players. */
    static final double INBOUND_CHANCE_PER_DAY = 0.10;
    /** Minimum gap between inbound envoy visits per player. */
    static final long INBOUND_COOLDOWN_TICKS = 3 * DAY;
    /** Inbound envoys require at least NEUTRAL standing. */
    static final double INBOUND_MIN_STANDING = 40.0;

    // --- the outbound gift ---
    static final Item GIFT_ITEM = Items.GOLD_INGOT;
    static final int GIFT_COUNT = 8;

    // --- offers ---
    /** Concurrent offers per OPEN faction. */
    static final int MAX_OFFERS = 3;
    /** Un-accepted offers expire (and nudge standing −1) after this. */
    static final long OFFER_EXPIRY_TICKS = 3 * DAY;

    /** Relations shatter below this standing (the WARY floor — also what
     *  makes the Orc Disaster's forced-HOSTILE clamp break Clayman
     *  relations for free). */
    static final double COLLAPSE_STANDING = 20.0;

    // --- Stage 3: relationship rewards ---
    /** Alliance buff refresh window (re-applied every 1 s tick while
     *  the pact holds; lapses by itself when it doesn't). */
    static final int ALLIANCE_BUFF_DURATION_TICKS = 60;
    /** Caravan goods claimable once per this period per PACT faction. */
    static final long CARAVAN_COOLDOWN_TICKS = DAY;
    /** Caravan-home travel cooldown. */
    static final long TRAVEL_COOLDOWN_TICKS = 12_000L; // half a day
    /** Clayman's spare-Disaster gift: standing floor + once ever. */
    static final String SPARE_BOSS_GIFT_ID = "clayman_spare_disaster";
    static final double SPARE_BOSS_MIN_STANDING = 60.0;

    // --- COVENANT (the tier above ALLIANCE) ---
    /** Standing the PACT must crawl to before the faction's unique
     *  milestone deal unlocks. */
    static final double COVENANT_THRESHOLD = 95.0;
    /** Post-PACT deal-standing-gain damp (the sharp drop): standing
     *  rewards are multiplied by this once ALLIED relations exist. */
    static final double PACT_GAIN_DAMP = 0.25;
    /** Covenant perk: SupplyItems deal targets are reduced by this. */
    static final double COVENANT_SUPPLY_DISCOUNT = 0.25;
    /** Covenant grinders (auto-delivery on the daily pass): dwargon =
     *  industry, carrion = beast/hunt — different material sets. */
    private static final Map<String, List<ItemStack>> GRINDER_GOODS = Map.of(
            "dwargon", List.of(new ItemStack(Items.IRON_INGOT, 16),
                    new ItemStack(Items.COAL, 32), new ItemStack(Items.GOLD_INGOT, 6)),
            "carrion", List.of(new ItemStack(Items.LEATHER, 16),
                    new ItemStack(Items.BONE, 16), new ItemStack(Items.STRING, 16)));
    /** Clayman covenant: summoned spare Disasters regroup this long. */
    static final long SUMMON_DISASTER_COOLDOWN_TICKS = 4 * DAY;
    /** Milim covenant: one Drago Nova per REAL-LIFE hour. */
    static final long NOVA_CLAIM_INTERVAL_MILLIS = 3_600_000L;

    // --- The offer reroll button ---
    /** Reroll cost: this many high-quality magic crystals, consumed. */
    static final int REROLL_CRYSTAL_COST = 4;
    /** Reroll cooldown per faction (half an in-game day). */
    static final long REROLL_COOLDOWN_TICKS = 12_000L;
    /** Synthetic lastCaravan keys (never collide with faction ids). */
    private static final String KEY_REROLL = "_reroll_";
    private static final String KEY_SUMMON = "_summon_clayman";

    private static net.minecraft.world.item.Item highQualityCrystal() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "tensura", "high_quality_magic_crystal"));
    }

    // --- Stage 4: the mending ritual ---
    /** Mending reopens the door at a LOW standing (WARY band) —
     *  forgiveness to REBUILD, not restoration. 25 clears the envoy
     *  accept floor (20) so the faction can be courted again. */
    static final double MENDING_REOPEN_STANDING = 25.0;

    /** Which passive buff each ALLIANCE grants (re-applied while PACT
     *  holds). Representative set — the authoring seam for more. */
    private static final Map<String, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>>
            ALLIANCE_BUFFS = Map.of(
                    "dwargon", net.minecraft.world.effect.MobEffects.DIG_SPEED,
                    "tempest", net.minecraft.world.effect.MobEffects.REGENERATION,
                    "jura_alliance", net.minecraft.world.effect.MobEffects.LUCK,
                    "luminous", net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
                    "falmuth", net.minecraft.world.effect.MobEffects.DAMAGE_BOOST,
                    "milim", net.minecraft.world.effect.MobEffects.DAMAGE_BOOST,
                    "carrion", net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);

    /** Daily caravan goods per PACT faction (trade access — faction
     *  wares without a shop UI; the authoring seam for more). */
    private static final Map<String, List<ItemStack>> FACTION_GOODS = Map.of(
            "dwargon", List.of(new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.GOLD_INGOT, 4)),
            "tempest", List.of(new ItemStack(Items.BREAD, 16), new ItemStack(Items.EMERALD, 4)),
            "jura_alliance", List.of(new ItemStack(Items.WHEAT, 16), new ItemStack(Items.EMERALD, 4)),
            "luminous", List.of(new ItemStack(Items.GOLD_INGOT, 6), new ItemStack(Items.DIAMOND, 2)),
            "falmuth", List.of(new ItemStack(Items.IRON_INGOT, 16), new ItemStack(Items.EMERALD, 4)),
            "milim", List.of(new ItemStack(Items.COOKED_PORKCHOP, 16), new ItemStack(Items.EMERALD, 4)),
            "carrion", List.of(new ItemStack(Items.LEATHER, 12), new ItemStack(Items.EMERALD, 4)));

    // --- the alliance prompt (replaces the pact milestone deal) ---
    /** Re-send an unanswered alliance prompt after this long (covers a
     *  dismissed/ESC'd dialog without per-second spam). */
    static final long ALLIANCE_PROMPT_RETRY_TICKS = 1_200L; // 1 min
    /** Declining the alliance drops standing to the closest value that
     *  is NOT in the ALLIED band (80 − 1). */
    static final double ALLIANCE_DECLINE_STANDING = FactionTier.ALLIED.minInclusive() - 1;

    /** (transient) player → (faction id → last prompt tick). In-memory
     *  on purpose: a relog simply re-prompts, which is correct. */
    private static final Map<UUID, Map<String, Long>> alliancePromptSent = new HashMap<>();

    private DiplomacyManager() {}

    static boolean isEnabled() {
        return WorldReputationManager.isFactionSystemEnabled();
    }

    // ------------------------------------------------------------------
    // Relations state
    // ------------------------------------------------------------------

    public static RelationsState getState(ServerLevel level, UUID player, BossFaction faction) {
        if (!isEnabled()) return RelationsState.NONE;
        return RelationsState.byId(DiplomacySavedData.get(level).getState(player, faction.id()));
    }

    static void openRelations(ServerLevel level, UUID player, BossFaction faction) {
        if (!isEnabled()) return;
        DiplomacySavedData data = DiplomacySavedData.get(level);
        data.setState(player, faction.id(), RelationsState.OPEN.id());
        data.setLastActivity(player, faction.id(), level.getGameTime());
        WorldReputationManager.modifyStanding(level, player, faction,
                STANDING_ENTRY_OPEN, WorldRepReason.DIPLOMACY);
        LOGGER.info("[TM] diplomacy: player {} × {} relations OPENED", player, faction.id());
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online != null) {
            online.sendSystemMessage(Component.literal("Relations with "
                    + faction.displayName() + " are open — they will offer deals.")
                    .withStyle(faction.color()));
        }
    }

    // ------------------------------------------------------------------
    // Entry — outbound (the tab's Send-envoy button)
    // ------------------------------------------------------------------

    /** @return a player-facing failure string, or null on success. */
    static String sendEnvoy(ServerPlayer player, BossFaction faction, boolean withGift) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);

        // The Orc Disaster door — checked FIRST.
        if (WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) {
            return faction.displayName() + " will not treat with you.";
        }
        if (getState(level, uuid, faction) != RelationsState.NONE) {
            return "relations with " + faction.displayName() + " are already open";
        }
        if (data.getPendingReply(uuid, faction.id()) != null) {
            return "your envoy to " + faction.displayName() + " has not yet returned";
        }
        long now = level.getGameTime();
        if (now - data.getLastSend(uuid, faction.id()) < SEND_COOLDOWN_TICKS) {
            return faction.displayName() + " needs time before receiving another envoy";
        }
        if (withGift) {
            int consumed = consumeItems(player, GIFT_ITEM, GIFT_COUNT);
            if (consumed < GIFT_COUNT) {
                // Partial consumption shouldn't happen (we check first),
                // but refund defensively if it somehow did.
                if (consumed > 0) giveItems(player, List.of(new ItemStack(GIFT_ITEM, consumed)));
                return "a gift needs " + GIFT_COUNT + " "
                        + new ItemStack(GIFT_ITEM).getHoverName().getString();
            }
            WorldReputationManager.modifyStanding(level, uuid, faction,
                    STANDING_GIFT, WorldRepReason.DIPLOMACY);
            player.sendSystemMessage(Component.literal(
                    "Your gift precedes you to " + faction.displayName() + ".")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        data.setLastSend(uuid, faction.id(), now);
        data.setPendingReply(uuid, faction.id(), now + REPLY_DELAY_TICKS);
        player.sendSystemMessage(Component.literal("Your envoy departs for "
                + faction.displayName() + " — expect a reply in a day.")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        LOGGER.info("[TM] diplomacy: player {} sent envoy to {} (gift {})",
                uuid, faction.id(), withGift);
        return null;
    }

    private static void processPendingReplies(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        // Copy to avoid concurrent modification while clearing.
        for (Map.Entry<UUID, Map<String, Long>> byPlayer
                : new HashMap<>(data.allPendingReplies()).entrySet()) {
            UUID player = byPlayer.getKey();
            for (Map.Entry<String, Long> e : new HashMap<>(byPlayer.getValue()).entrySet()) {
                if (now < e.getValue()) continue;
                data.clearPendingReply(player, e.getKey());
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;
                double standing = WorldReputationManager.getStanding(level, player, faction);
                boolean accepted = standing >= ENTRY_ACCEPT_STANDING
                        && !WorldReputationManager.isDiplomacyClosed(level, player, faction);
                ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
                if (accepted) {
                    openRelations(level, player, faction);
                } else if (online != null) {
                    online.sendSystemMessage(Component.literal(faction.displayName()
                            + " rebuffs your envoy. (They think too little of you — standing "
                            + String.format("%.0f", standing) + ".)")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Entry — inbound (faction envoys; race-gated; daily roll)
    // ------------------------------------------------------------------

    private static void rollInboundEnvoys(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            if (now - data.getLastInbound(uuid) < INBOUND_COOLDOWN_TICKS) continue;
            IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, uuid);
            if (colony == null || !colony.getServerBuildingManager().hasTownHall()) continue;
            boolean majin = WorldReputationManager.isMajinSide(player);
            for (BossFaction faction : BossFaction.values()) {
                FactionProfile profile = FactionProfile.byId(faction.id());
                if (profile == null || !profile.sendsEnvoysTo(majin)) continue; // the race gate
                if (getState(level, uuid, faction) != RelationsState.NONE) continue;
                if (WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) continue;
                if (WorldReputationManager.getStanding(level, uuid, faction)
                        < INBOUND_MIN_STANDING) continue;
                if (level.getRandom().nextDouble() >= INBOUND_CHANCE_PER_DAY) continue;
                if (spawnFactionEnvoy(level, player, colony, faction)) {
                    data.setLastInbound(uuid, now);
                }
                break; // one inbound envoy per player per day at most
            }
        }
    }

    private static boolean spawnFactionEnvoy(ServerLevel level, ServerPlayer player,
                                             IColony colony, BossFaction faction) {
        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        // No stacking: skip if a faction envoy for this player already
        // waits near the town hall (covers reload-orphaned envoys too).
        for (Villager existing : level.getEntitiesOfClass(Villager.class,
                new AABB(th).inflate(64))) {
            if (existing.hasData(Attachments.FACTION_ENVOY.get())) {
                FactionEnvoyTag tag = existing.getData(Attachments.FACTION_ENVOY.get());
                if (tag != null && player.getUUID().equals(tag.targetPlayer())) return false;
            }
        }
        BlockPos spawnAt = EntityUtils.getSpawnPoint(level, th);
        if (spawnAt == null) spawnAt = th;
        Villager envoy = EntityType.VILLAGER.create(level);
        if (envoy == null) return false;
        envoy.moveTo(spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        envoy.setCustomName(Component.literal("Envoy of " + faction.displayName())
                .withStyle(faction.color()));
        envoy.setCustomNameVisible(true);
        envoy.restrictTo(th, 15);
        envoy.setData(Attachments.FACTION_ENVOY.get(),
                new FactionEnvoyTag(player.getUUID(), faction.id()));
        if (!level.addFreshEntity(envoy)) return false;
        player.sendSystemMessage(Component.literal("An envoy of " + faction.displayName()
                + " has arrived at " + colony.getName() + " seeking an audience.")
                .withStyle(faction.color()));
        LOGGER.info("[TM] diplomacy: inbound envoy of {} spawned for player {} at colony {}",
                faction.id(), player.getUUID(), colony.getID());
        return true;
    }

    /** The inbound envoy's Accept/Decline (from the dialogue screen). */
    static void handleFactionEnvoyResponse(ServerPlayer player, int entityId, boolean accepted) {
        ServerLevel level = player.serverLevel();
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.hasData(Attachments.FACTION_ENVOY.get())) return;
        FactionEnvoyTag tag = entity.getData(Attachments.FACTION_ENVOY.get());
        if (tag == null || !player.getUUID().equals(tag.targetPlayer())) return;
        BossFaction faction = BossFaction.byId(tag.factionId());

        if (accepted && faction != null && isEnabled()
                && getState(level, player.getUUID(), faction) == RelationsState.NONE
                && !WorldReputationManager.isDiplomacyClosed(level, player.getUUID(), faction)) {
            openRelations(level, player.getUUID(), faction);
        } else if (!accepted && faction != null) {
            player.sendSystemMessage(Component.literal("The envoy of " + faction.displayName()
                    + " departs without ceremony.").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        level.sendParticles(ParticleTypes.POOF,
                entity.getX(), entity.getY() + entity.getBbHeight() / 2.0, entity.getZ(),
                24, 0.4, 0.4, 0.4, 0.02);
        entity.discard();
    }

    // ------------------------------------------------------------------
    // Deals — accept / deliver / collect / detect / fail
    // ------------------------------------------------------------------

    /** @return failure string or null. */
    static String acceptDeal(ServerPlayer player, BossFaction faction, String dealId) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        DealSpec spec = DealSpec.byId(dealId);
        boolean mending = DealSpec.isMendingDeal(spec);
        // The mending rite is offered to a FORECLOSED faction — relations
        // are NONE by definition; everything else needs them open.
        if (mending) {
            if (!WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) {
                return "no atonement is owed to " + faction.displayName();
            }
        } else if (getState(level, uuid, faction) == RelationsState.NONE) {
            return "no relations with " + faction.displayName();
        }
        if (data.getDeal(uuid, faction.id()) != null) {
            return "a deal with " + faction.displayName() + " is already underway";
        }
        boolean offered = data.getOffers(uuid, faction.id()).stream()
                .anyMatch(o -> o.dealId().equals(dealId));
        if (!offered || spec == null) return "that offer is no longer on the table";
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, uuid);
        if (colony == null && !mending) return "you need a colony to honor a deal";

        // LENDING deals don't start here — accepting opens the citizen
        // PICKER; the deal is created by handleLendConfirm once the
        // player has chosen who goes.
        if (spec.requirement() instanceof DealSpec.LendCitizens lend) {
            List<ICitizenData> eligible = eligibleLendCitizens(level, colony, lend);
            if (eligible.size() < lend.count()) {
                return "the colony lacks " + lend.count() + " eligible citizens ("
                        + lend.skill().name() + " ≥ " + lend.minLevel()
                        + ", vanilla colonists only)";
            }
            Networking.sendLendPickerTo(player, faction, spec, lend, eligible);
            return null;
        }

        ActiveDeal deal = new ActiveDeal();
        deal.dealId = dealId;
        deal.colonyId = colony != null ? colony.getID() : -1;
        deal.acceptedTick = level.getGameTime();
        deal.deadlineTick = level.getGameTime() + spec.deadlineTicks();
        data.setDeal(uuid, faction.id(), deal);
        // Remove from offers.
        List<DiplomacySavedData.Offer> remaining = new ArrayList<>(data.getOffers(uuid, faction.id()));
        remaining.removeIf(o -> o.dealId().equals(dealId));
        data.setOffers(uuid, faction.id(), remaining);
        data.setLastActivity(uuid, faction.id(), level.getGameTime());
        player.sendSystemMessage(Component.literal("Deal struck with " + faction.displayName()
                + ": " + spec.title() + " — " + spec.requirement().summary() + ".")
                .withStyle(faction.color()));
        LOGGER.info("[TM] diplomacy: player {} accepted deal '{}' with {} (colony {})",
                uuid, dealId, faction.id(), colony.getID());
        return null;
    }

    // ------------------------------------------------------------------
    // Stage 2 — citizen lending (VANILLA colonists only; the
    // RaceIdentity-keyed race-citizens are excluded to avoid the
    // citizenId collision documented in docs/diplomacy.md)
    // ------------------------------------------------------------------

    /** The lendable subset: adult VANILLA colonists (no RaceIdentity for
     *  this colony+citizenId) meeting the skill bar. */
    static List<ICitizenData> eligibleLendCitizens(ServerLevel level, IColony colony,
                                                   DealSpec.LendCitizens lend) {
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        List<ICitizenData> out = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (data.isChild()) continue;
            if (data.getCitizenSkillHandler().getLevel(lend.skill()) < lend.minLevel()) continue;
            if (isRaceCitizen(identities, colony.getID(), data.getId())) continue;
            out.add(data);
        }
        return out;
    }

    /** True when this (colony, citizenId) belongs to a named
     *  race-citizen — matched on BOTH keys (the bare citizenId lookup
     *  scans across colonies and could false-positive). */
    private static boolean isRaceCitizen(RaceIdentitySavedData identities,
                                         int colonyId, int citizenId) {
        for (RaceIdentitySavedData.RaceIdentity identity : identities.all()) {
            if (identity.colonyId == colonyId && identity.citizenId == citizenId) return true;
        }
        return false;
    }

    /** The picker's confirmation: snapshot + remove the chosen citizens
     *  and start the lend (state AWAITING_PAYOFF — the requirement is
     *  fulfilled by the act of lending; the payoff timer IS the lend). */
    static void handleLendConfirm(ServerPlayer player, String factionIdArg, String dealId,
                                  List<Integer> citizenIds) {
        if (!isEnabled()) return;
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        BossFaction faction = BossFaction.byId(factionIdArg);
        DealSpec spec = DealSpec.byId(dealId);
        if (faction == null || spec == null
                || !(spec.requirement() instanceof DealSpec.LendCitizens lend)) {
            return;
        }
        DiplomacySavedData data = DiplomacySavedData.get(level);
        // Re-validate everything — the world may have moved since the picker.
        if (getState(level, uuid, faction) == RelationsState.NONE) return;
        if (data.getDeal(uuid, faction.id()) != null) return;
        if (data.getOffers(uuid, faction.id()).stream()
                .noneMatch(o -> o.dealId().equals(dealId))) {
            fail(player, "that offer is no longer on the table");
            return;
        }
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, uuid);
        if (colony == null) return;
        if (citizenIds.size() != lend.count()
                || new java.util.HashSet<>(citizenIds).size() != lend.count()) {
            fail(player, "pick exactly " + lend.count() + " citizens");
            return;
        }
        List<ICitizenData> eligible = eligibleLendCitizens(level, colony, lend);
        List<ICitizenData> chosen = new ArrayList<>();
        for (int id : citizenIds) {
            ICitizenData match = eligible.stream()
                    .filter(c -> c.getId() == id).findFirst().orElse(null);
            if (match == null) {
                fail(player, "a chosen citizen is no longer eligible");
                return;
            }
            chosen.add(match);
        }

        // Snapshot, then remove — the workforce genuinely drops.
        ActiveDeal deal = new ActiveDeal();
        deal.dealId = dealId;
        deal.colonyId = colony.getID();
        deal.acceptedTick = level.getGameTime();
        deal.payoffAtTick = level.getGameTime() + lend.durationTicks();
        deal.deadlineTick = deal.payoffAtTick; // lending can't deadline-fail
        deal.state = ActiveDeal.STATE_AWAITING_PAYOFF;
        StringBuilder names = new StringBuilder();
        for (ICitizenData citizen : chosen) {
            deal.lentCitizens.add(citizen.serializeNBT(level.registryAccess()));
            if (names.length() > 0) names.append(", ");
            names.append(citizen.getName());
            // Despawn the body with the envoy poof, then drop the data.
            citizen.getEntity().ifPresent(entity -> {
                level.sendParticles(ParticleTypes.POOF,
                        entity.getX(), entity.getY() + entity.getBbHeight() / 2.0, entity.getZ(),
                        24, 0.4, 0.4, 0.4, 0.02);
                entity.discard();
            });
            colony.getCitizenManager().removeCivilian(citizen);
        }
        data.setDeal(uuid, faction.id(), deal);
        List<DiplomacySavedData.Offer> remaining = new ArrayList<>(data.getOffers(uuid, faction.id()));
        remaining.removeIf(o -> o.dealId().equals(dealId));
        data.setOffers(uuid, faction.id(), remaining);
        data.setLastActivity(uuid, faction.id(), level.getGameTime());
        player.sendSystemMessage(Component.literal(names + " depart for "
                + faction.displayName() + " — they return in "
                + (lend.durationTicks() / DAY) + " days, trained.")
                .withStyle(faction.color()));
        LOGGER.info("[TM] diplomacy: player {} LENT {} citizens to {} (deal '{}')",
                uuid, chosen.size(), faction.id(), dealId);
    }

    /**
     * Bring lent citizens home: resurrect each snapshot at the colony's
     * town hall ({@code resetId=true} — fresh ids; vanilla colonists
     * carry no RaceIdentity, so nothing can collide), train them, spawn
     * the bodies. The ORIGINAL colony is preferred; if it was deleted
     * mid-lend, any colony the player still owns receives them; if NONE
     * exists, returns false (the caller keeps waiting — citizens stay
     * safe in the deal NBT rather than vanishing).
     */
    /** {@code lend} may be null only when {@code trained} is false (the
     *  orphaned-deal cleanup path — no spec left to read the skill from). */
    private static boolean returnLentCitizens(ServerLevel level, UUID player,
                                              ActiveDeal deal, DealSpec.LendCitizens lend,
                                              boolean trained) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(deal.colonyId, level);
        if (colony == null) {
            colony = IColonyManager.getInstance().getIColonyByOwner(level, player);
        }
        if (colony == null || !colony.getServerBuildingManager().hasTownHall()) return false;
        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos spawnAt = EntityUtils.getSpawnPoint(level, th);
        if (spawnAt == null) spawnAt = th;

        for (int i = 0; i < deal.lentCitizens.size(); i++) {
            CompoundTag snapshot = deal.lentCitizens.getCompound(i);
            try {
                ICitizenData returned = colony.getCitizenManager()
                        .resurrectCivilianData(snapshot, true, level, spawnAt);
                if (returned == null) continue;
                if (trained && lend != null) {
                    returned.getCitizenSkillHandler().incrementLevel(lend.skill(), lend.skillBoost());
                    // Covenant training: the secondary skills too.
                    for (var secondary : lend.secondarySkills()) {
                        returned.getCitizenSkillHandler().incrementLevel(secondary,
                                Math.max(1, lend.skillBoost() / 2));
                    }
                }
                if (returned.getEntity().isEmpty()) {
                    colony.getCitizenManager().spawnOrCreateCitizen(returned, level, spawnAt);
                }
            } catch (Throwable t) {
                LOGGER.error("[TM] diplomacy: lent-citizen return failed (deal '{}')",
                        deal.dealId, t);
            }
        }
        deal.lentCitizens = new net.minecraft.nbt.ListTag();
        return true;
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message)
                .withStyle(net.minecraft.ChatFormatting.RED));
    }

    /** The Deliver button — SupplyItems PUSH fulfillment. */
    static String deliver(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        ActiveDeal deal = data.getDeal(uuid, faction.id());
        DealSpec spec = deal == null ? null : DealSpec.byId(deal.dealId);
        if (deal == null || spec == null || deal.state != ActiveDeal.STATE_ACTIVE) {
            return "nothing to deliver";
        }
        // Stage 4 — the mending rite is performed, not delivered.
        if (spec.requirement() instanceof DealSpec.MendingRite rite) {
            return performMendingRite(player, faction, deal, rite);
        }
        // Covenant commissions: a BUNDLE delivered all-or-nothing.
        if (spec.requirement() instanceof DealSpec.SupplyBundle bundle) {
            var inventory = player.getInventory();
            for (ItemStack needed : bundle.items()) {
                int held = 0;
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (!stack.isEmpty() && stack.is(needed.getItem())) held += stack.getCount();
                }
                if (held < needed.getCount()) {
                    return "the commission needs " + needed.getCount() + " "
                            + needed.getHoverName().getString() + " (you carry " + held + ")";
                }
            }
            for (ItemStack needed : bundle.items()) {
                consumeItems(player, needed.getItem(), needed.getCount());
            }
            fulfillDeal(level, uuid, faction, deal, spec);
            return null;
        }
        if (!(spec.requirement() instanceof DealSpec.SupplyItems supply)) {
            return "this deal isn't fulfilled by delivery";
        }
        // Covenant perk: reduced deal costs — the supply target shrinks.
        int target = getState(level, uuid, faction) == RelationsState.COVENANT
                ? (int) Math.ceil(supply.count() * (1.0 - COVENANT_SUPPLY_DISCOUNT))
                : supply.count();
        int remaining = target - deal.progress;
        int consumed = consumeItems(player, supply.item(), remaining);
        if (consumed <= 0) {
            return "you carry no " + new ItemStack(supply.item()).getHoverName().getString();
        }
        deal.progress += consumed;
        data.setDeal(uuid, faction.id(), deal);
        data.setLastActivity(uuid, faction.id(), level.getGameTime());
        player.sendSystemMessage(Component.literal("Delivered " + consumed + " — "
                + deal.progress + "/" + target + ".")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        if (deal.progress >= target) {
            fulfillDeal(level, uuid, faction, deal, spec);
        }
        return null;
    }

    /**
     * Stage 4 — THE MENDING RITE. The steep price, all-or-nothing in
     * one act: the tribute is consumed from the player's inventory AND
     * the player's STRONGEST named subordinate (EP ≥ the rite's floor;
     * its body must be present) is sacrificed — identity and both
     * bodies removed permanently. Fulfilment calls the long-stubbed
     * {@code reopenDiplomacy} and sets standing to a LOW base
     * ({@link #MENDING_REOPEN_STANDING}) — forgiveness to REBUILD, not
     * restoration. Repeatable: a re-foreclosed faction offers the rite
     * again.
     */
    private static String performMendingRite(ServerPlayer player, BossFaction faction,
                                             ActiveDeal deal, DealSpec.MendingRite rite) {
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        if (!WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) {
            // The door opened some other way — the rite is moot.
            DiplomacySavedData.get(level).removeDeal(uuid, faction.id());
            return "no atonement is owed to " + faction.displayName();
        }

        // The offering: the strongest owned named subordinate whose body
        // is present (EP reads 0 for unresolvable bodies — the offering
        // must be brought to the altar, as it were).
        RaceIdentitySavedData identities = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity offering = null;
        double bestEP = 0;
        for (RaceIdentitySavedData.RaceIdentity identity : identities.all()) {
            if (identity.ownerPlayerUUID == null || !uuid.equals(identity.ownerPlayerUUID)) continue;
            double ep = ExampleMod.readEPForRoster(player, identity);
            if (ep > bestEP) { bestEP = ep; offering = identity; }
        }
        if (offering == null || bestEP < rite.minSacrificeEP()) {
            return "the rite demands a named subordinate of EP "
                    + (long) rite.minSacrificeEP() + "+ — present and yours to give"
                    + (offering != null
                            ? " (your strongest offers only " + (long) bestEP + ")" : "");
        }
        // The tribute: counted before anything is consumed.
        int held = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(rite.tributeItem())) held += stack.getCount();
        }
        if (held < rite.tributeCount()) {
            return "the rite demands " + rite.tributeCount() + " "
                    + new ItemStack(rite.tributeItem()).getHoverName().getString()
                    + " (you carry " + held + ")";
        }

        // All validated — the price is paid. Tribute first, then the life.
        consumeItems(player, rite.tributeItem(), rite.tributeCount());
        String name = sacrificeIdentity(level, offering);

        WorldReputationManager.reopenDiplomacy(level, uuid, faction);
        WorldReputationManager.setStanding(level, uuid, faction,
                MENDING_REOPEN_STANDING, WorldRepReason.DIPLOMACY);
        DiplomacySavedData data = DiplomacySavedData.get(level);
        data.removeDeal(uuid, faction.id());
        data.setOffers(uuid, faction.id(), new ArrayList<>());
        data.setLastActivity(uuid, faction.id(), level.getGameTime());
        player.sendSystemMessage(Component.literal(name + " is given to the rite. "
                + faction.displayName() + " acknowledges the atonement — the door is open"
                + " again, though you start from almost nothing.")
                .withStyle(faction.color()));
        LOGGER.info("[TM] diplomacy: player {} MENDED {} — sacrificed '{}' ({} EP) + {} tribute;"
                + " standing reset to {}",
                uuid, faction.id(), name, String.format("%.0f", bestEP),
                rite.tributeCount(), MENDING_REOPEN_STANDING);
        return null;
    }

    /** Remove a named subordinate COMPLETELY — both bodies + the
     *  identity record (the death-hook removal, performed as a rite). */
    private static String sacrificeIdentity(ServerLevel level,
                                            RaceIdentitySavedData.RaceIdentity identity) {
        String name = "Your subordinate";
        // The citizen body (IN_COLONY).
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
        if (colony != null) {
            ICitizenData citizen = colony.getCitizenManager().getCivilian(identity.citizenId);
            if (citizen != null) {
                name = citizen.getName();
                citizen.getEntity().ifPresent(entity -> {
                    level.sendParticles(ParticleTypes.POOF,
                            entity.getX(), entity.getY() + entity.getBbHeight() / 2.0, entity.getZ(),
                            32, 0.4, 0.6, 0.4, 0.03);
                    entity.discard();
                });
                colony.getCitizenManager().removeCivilian(citizen);
            }
        }
        // The wild body (SUBORDINATE).
        if (identity.mobEntityUUID != null) {
            net.minecraft.world.entity.Entity mob = level.getEntity(identity.mobEntityUUID);
            if (mob != null && !mob.isRemoved()) {
                if (mob.hasCustomName() && mob.getCustomName() != null) {
                    name = mob.getCustomName().getString();
                }
                level.sendParticles(ParticleTypes.POOF,
                        mob.getX(), mob.getY() + mob.getBbHeight() / 2.0, mob.getZ(),
                        32, 0.4, 0.6, 0.4, 0.03);
                mob.discard();
            }
        }
        RaceIdentitySavedData.get(level).removeIdentity(identity);
        return name;
    }

    /** The Collect button — hands over a READY deal's item reward. */
    static String collect(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        ActiveDeal deal = data.getDeal(uuid, faction.id());
        DealSpec spec = deal == null ? null : DealSpec.byId(deal.dealId);
        if (deal == null || spec == null || deal.state != ActiveDeal.STATE_READY) {
            return "nothing to collect";
        }
        giveItems(player, spec.rewardItems());
        data.removeDeal(uuid, faction.id());
        player.sendSystemMessage(Component.literal(faction.displayName()
                + " delivers your payment: " + spec.rewardSummary() + ".")
                .withStyle(faction.color()));
        return null;
    }

    /** Requirement met → pay standing (damped post-PACT), forge the
     *  COVENANT on milestone deals, start the payoff. */
    private static void fulfillDeal(ServerLevel level, UUID player, BossFaction faction,
                                    ActiveDeal deal, DealSpec spec) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        RelationsState stateNow = getState(level, player, faction);
        double gain = spec.standingReward()
                * (stateNow.compareTo(RelationsState.PACT) >= 0 ? PACT_GAIN_DAMP : 1.0);
        WorldReputationManager.modifyStanding(level, player, faction,
                gain, WorldRepReason.DIPLOMACY);
        data.setLastActivity(player, faction.id(), level.getGameTime());
        if (spec.milestone()) {
            // Milestone deals are now the COVENANT forges (the alliance
            // pact is a prompt; the mending rite bypasses this path).
            data.setState(player, faction.id(), RelationsState.COVENANT.id());
            ServerPlayer covenantOnline = level.getServer().getPlayerList().getPlayer(player);
            if (covenantOnline != null) {
                covenantOnline.sendSystemMessage(Component.literal(
                        "A COVENANT is forged with " + faction.displayName()
                        + " — their deepest gifts are yours.").withStyle(faction.color()));
            }
        }
        long now = level.getGameTime();
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (spec.payoffDelayTicks() > 0) {
            deal.state = ActiveDeal.STATE_AWAITING_PAYOFF;
            deal.payoffAtTick = now + spec.payoffDelayTicks();
            data.setDeal(player, faction.id(), deal);
        } else if (online != null) {
            // INSTANT reward (user-requested): the payment lands the
            // moment the deal fulfills — no Collect click.
            giveItems(online, spec.rewardItems());
            data.removeDeal(player, faction.id());
        } else {
            // Player offline (a polled milestone completed without them)
            // — hold the payment for a Collect on the tab.
            deal.state = ActiveDeal.STATE_READY;
            data.setDeal(player, faction.id(), deal);
        }
        LOGGER.info("[TM] diplomacy: player {} FULFILLED deal '{}' with {} (+{} standing{})",
                player, spec.id(), faction.id(), spec.standingReward(),
                spec.milestone() ? ", PACT formed" : "");
        if (online != null) {
            online.sendSystemMessage(Component.literal(
                    "Deal fulfilled — " + faction.displayName() + " is pleased."
                    + (spec.payoffDelayTicks() > 0
                            ? " Payment arrives within the day."
                            : " They deliver your payment: " + spec.rewardSummary() + "."))
                    .withStyle(faction.color()));
        }
    }

    private static void failDeal(ServerLevel level, UUID player, String factionId,
                                 ActiveDeal deal, DealSpec spec) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        data.removeDeal(player, factionId);
        BossFaction faction = BossFaction.byId(factionId);
        if (faction != null && spec != null) {
            WorldReputationManager.modifyStanding(level, player, faction,
                    -spec.standingPenalty(), WorldRepReason.DIPLOMACY);
        }
        LOGGER.info("[TM] diplomacy: player {} FAILED deal '{}' with {} (deadline)",
                player, deal.dealId, factionId);
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online != null && faction != null && spec != null) {
            online.sendSystemMessage(Component.literal("You broke your word — the "
                    + spec.title() + " deal with " + faction.displayName() + " has lapsed.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    /** Deadlines, payoff timers, and polled milestone requirements. */
    private static void processDeals(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        for (Map.Entry<UUID, Map<String, ActiveDeal>> byPlayer
                : new HashMap<>(data.allDeals()).entrySet()) {
            UUID player = byPlayer.getKey();
            for (Map.Entry<String, ActiveDeal> e : new HashMap<>(byPlayer.getValue()).entrySet()) {
                ActiveDeal deal = e.getValue();
                DealSpec spec = DealSpec.byId(deal.dealId);
                if (spec == null) {
                    // Orphaned deal (its id left the registry — e.g. a
                    // version change). If citizens are riding inside,
                    // bring them home UNTRAINED before cleanup; no
                    // colony available → keep waiting, never delete
                    // citizens with the deal.
                    if (!deal.lentCitizens.isEmpty()
                            && !returnLentCitizens(level, player, deal, null, false)) {
                        continue;
                    }
                    data.removeDeal(player, e.getKey());
                    continue;
                }

                if (deal.state == ActiveDeal.STATE_AWAITING_PAYOFF && now >= deal.payoffAtTick) {
                    BossFaction awaitingFaction = BossFaction.byId(e.getKey());
                    ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);

                    // Stage 2 — a LENDING deal's payoff is the citizens
                    // coming home trained (+ the reward). If no colony
                    // can receive them (deleted mid-lend, no town hall),
                    // keep waiting — they stay safe in the deal NBT.
                    if (spec.requirement() instanceof DealSpec.LendCitizens lend
                            && !deal.lentCitizens.isEmpty()) {
                        if (!returnLentCitizens(level, player, deal, lend, true)) continue;
                        if (awaitingFaction != null) {
                            WorldReputationManager.modifyStanding(level, player, awaitingFaction,
                                    spec.standingReward(), WorldRepReason.DIPLOMACY);
                            data.setLastActivity(player, e.getKey(), now);
                        }
                        if (online != null) {
                            giveItems(online, spec.rewardItems());
                            data.removeDeal(player, e.getKey());
                            online.sendSystemMessage(Component.literal(
                                    "Your citizens return from "
                                    + (awaitingFaction != null
                                            ? awaitingFaction.displayName() : "abroad")
                                    + ", trained (+" + lend.skillBoost() + " "
                                    + lend.skill().name() + ") — with payment: "
                                    + spec.rewardSummary() + ".")
                                    .withStyle(net.minecraft.ChatFormatting.GREEN));
                        } else {
                            deal.state = ActiveDeal.STATE_READY; // items held for Collect
                            data.setDeal(player, e.getKey(), deal);
                        }
                        LOGGER.info("[TM] diplomacy: player {} lend deal '{}' COMPLETED — citizens returned",
                                player, deal.dealId);
                        continue;
                    }

                    deal.state = ActiveDeal.STATE_READY;
                    data.setDeal(player, e.getKey(), deal);
                    if (online != null) {
                        online.sendSystemMessage(Component.literal(
                                "A caravan has arrived — your payment for '" + spec.title()
                                + "' awaits collection.").withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                    continue;
                }
                if (deal.state != ActiveDeal.STATE_ACTIVE) continue;

                // Polled milestone requirements (population / happiness;
                // BuildingLevel has the event hook, this is its backstop).
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction != null && isRequirementMet(level, deal, spec)) {
                    fulfillDeal(level, player, faction, deal, spec);
                    continue;
                }
                if (now >= deal.deadlineTick) {
                    failDeal(level, player, e.getKey(), deal, spec);
                }
            }
        }
    }

    /** Live check for the polled requirement kinds. SupplyItems is
     *  push-only (the Deliver button owns its progress). */
    private static boolean isRequirementMet(ServerLevel level, ActiveDeal deal, DealSpec spec) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(deal.colonyId, level);
        if (colony == null) return false;
        return switch (spec.requirement()) {
            case DealSpec.BuildingLevel req -> hasBuildingAtLevel(colony,
                    req.schematicName(), req.level());
            case DealSpec.Population req ->
                    colony.getCitizenManager().getCurrentCitizenCount() >= req.citizens();
            case DealSpec.Happiness req -> colony.getOverallHappiness() >= req.average();
            case DealSpec.SupplyItems ignored -> false;
            // Lending fulfils through its own payoff machinery.
            case DealSpec.LendCitizens ignored -> false;
            // The rite is performed via its own action.
            case DealSpec.MendingRite ignored -> false;
            // Bundles deliver via the button; hunts track via kills.
            case DealSpec.SupplyBundle ignored -> false;
            case DealSpec.SlayEntities ignored -> false;
        };
    }

    private static boolean hasBuildingAtLevel(IColony colony, String schematicName, int minLevel) {
        for (var building : colony.getServerBuildingManager().getBuildings().values()) {
            if (schematicName.equalsIgnoreCase(building.getSchematicName())
                    && building.getBuildingLevel() >= minLevel) {
                return true;
            }
        }
        return false;
    }

    /** SlayEntities tracking — called from the death hook for every
     *  player kill: bump matching active hunt deals. */
    static void onPlayerKill(ServerLevel level, UUID killer,
                             net.minecraft.world.entity.LivingEntity victim) {
        if (!isEnabled()) return;
        DiplomacySavedData data = DiplomacySavedData.get(level);
        Map<String, ActiveDeal> byFaction = data.allDeals().get(killer);
        if (byFaction == null) return;
        String victimType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(victim.getType()).toString();
        for (Map.Entry<String, ActiveDeal> e : new HashMap<>(byFaction).entrySet()) {
            ActiveDeal deal = e.getValue();
            if (deal.state != ActiveDeal.STATE_ACTIVE) continue;
            DealSpec spec = DealSpec.byId(deal.dealId);
            BossFaction faction = BossFaction.byId(e.getKey());
            if (spec == null || faction == null) continue;
            if (!(spec.requirement() instanceof DealSpec.SlayEntities hunt)) continue;
            if (!hunt.entityTypeIds().contains(victimType)) continue;
            deal.progress++;
            data.setDeal(killer, e.getKey(), deal);
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(killer);
            if (online != null) {
                online.sendSystemMessage(Component.literal("'" + spec.title() + "' — "
                        + deal.progress + "/" + hunt.count() + ".")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
            }
            if (deal.progress >= hunt.count()) {
                fulfillDeal(level, killer, faction, deal, spec);
            }
        }
    }

    /** Event-driven BuildingLevel fulfillment — called from the existing
     *  BuildingConstructionModEvent hook in ExampleMod. */
    static void onBuildingCompleted(ServerLevel level, IColony colony) {
        if (!isEnabled()) return;
        DiplomacySavedData data = DiplomacySavedData.get(level);
        for (Map.Entry<UUID, Map<String, ActiveDeal>> byPlayer
                : new HashMap<>(data.allDeals()).entrySet()) {
            for (Map.Entry<String, ActiveDeal> e : new HashMap<>(byPlayer.getValue()).entrySet()) {
                ActiveDeal deal = e.getValue();
                if (deal.state != ActiveDeal.STATE_ACTIVE || deal.colonyId != colony.getID()) continue;
                DealSpec spec = DealSpec.byId(deal.dealId);
                BossFaction faction = BossFaction.byId(e.getKey());
                if (spec == null || faction == null) continue;
                if (spec.requirement() instanceof DealSpec.BuildingLevel
                        && isRequirementMet(level, deal, spec)) {
                    fulfillDeal(level, byPlayer.getKey(), faction, deal, spec);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Offers — daily refresh per OPEN faction
    // ------------------------------------------------------------------

    private static void refreshOffers(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        for (Map.Entry<UUID, Map<String, Byte>> byPlayer
                : new HashMap<>(data.allStates()).entrySet()) {
            UUID player = byPlayer.getKey();
            IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, player);
            for (Map.Entry<String, Byte> e : byPlayer.getValue().entrySet()) {
                RelationsState state = RelationsState.byId(e.getValue());
                if (state == RelationsState.NONE) continue;
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;

                List<DiplomacySavedData.Offer> current =
                        new ArrayList<>(data.getOffers(player, e.getKey()));
                // Silently prune offers whose deal id no longer resolves
                // (the registry changed between versions) — otherwise
                // dead entries clog the MAX_OFFERS slots invisibly.
                current.removeIf(o -> DealSpec.byId(o.dealId()) == null);
                // Expire ignored offers (−1 standing nudge each).
                int before = current.size();
                current.removeIf(o -> now >= o.expiresTick());
                for (int i = 0; i < before - current.size(); i++) {
                    WorldReputationManager.modifyStanding(level, player, faction,
                            STANDING_OFFER_EXPIRED, WorldRepReason.DIPLOMACY);
                }

                FactionTier tier = WorldReputationManager.getTier(level, player, faction);
                ActiveDeal active = data.getDeal(player, e.getKey());
                // Stage 2 — offers come from THIS faction's flavored
                // table. Eligible specs are collected first and drawn
                // RANDOMLY, so a deal late in the table (the lends)
                // isn't permanently shadowed by the ones above it.
                List<DealSpec> candidateSpecs = new ArrayList<>(DealSpec.tableFor(e.getKey()));
                // The COVENANT milestone deal — offered only at PACT once
                // standing has crawled to the threshold (forges Covenant).
                DealSpec covenantDeal = DealSpec.COVENANT_DEALS.get(e.getKey());
                if (covenantDeal != null && state == RelationsState.PACT
                        && WorldReputationManager.getStanding(level, player, faction)
                                >= COVENANT_THRESHOLD) {
                    candidateSpecs.add(covenantDeal);
                }
                // Covenant-only training deals (Tempest/Jura) — offered
                // once the Covenant is forged.
                DealSpec trainingDeal = DealSpec.COVENANT_TRAINING_DEALS.get(e.getKey());
                if (trainingDeal != null && state == RelationsState.COVENANT) {
                    candidateSpecs.add(trainingDeal);
                }
                List<DealSpec> eligibleSpecs = new ArrayList<>();
                for (DealSpec spec : candidateSpecs) {
                    if (tier.compareTo(spec.minTier()) < 0) continue;
                    // Normal-table milestones (none today) require OPEN;
                    // the Covenant milestone gates on PACT above, so let
                    // it through here.
                    if (spec.milestone() && state != RelationsState.OPEN
                            && !DealSpec.COVENANT_DEALS.containsValue(spec)) {
                        if (state != RelationsState.PACT) continue;
                    }
                    if (active != null && active.dealId.equals(spec.id())) continue;
                    if (current.stream().anyMatch(o -> o.dealId().equals(spec.id()))) continue;
                    // Offer-time filter: never offer an already-met
                    // requirement. Lend offers are NOT staffing-filtered
                    // (user decision): the player should SEE the deal —
                    // "3 citizens with Strength >= 8" is a goal to train
                    // toward; accepting while understaffed explains what
                    // is missing instead.
                    if (colony != null && !(spec.requirement() instanceof DealSpec.SupplyItems)
                            && !(spec.requirement() instanceof DealSpec.LendCitizens)
                            && !(spec.requirement() instanceof DealSpec.SupplyBundle)
                            && !(spec.requirement() instanceof DealSpec.SlayEntities)) {
                        ActiveDeal probe = new ActiveDeal();
                        probe.colonyId = colony.getID();
                        if (isRequirementMet(level, probe, spec)) continue;
                    }
                    eligibleSpecs.add(spec);
                }
                while (current.size() < MAX_OFFERS && !eligibleSpecs.isEmpty()) {
                    DealSpec pick = eligibleSpecs.remove(
                            level.getRandom().nextInt(eligibleSpecs.size()));
                    current.add(new DiplomacySavedData.Offer(pick.id(), now + OFFER_EXPIRY_TICKS));
                }
                data.setOffers(player, e.getKey(), current);
            }
        }

        // Stage 4 — the mending pass: a FORECLOSED faction's offer list
        // is exactly ONE deal — the Rite of Atonement. (Foreclosure
        // collapsed relations to NONE, so the normal pass above never
        // touches these factions.)
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            for (BossFaction faction : BossFaction.values()) {
                if (!WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) continue;
                DealSpec mend = DealSpec.MENDING_DEALS.get(faction.id());
                if (mend == null) continue;
                if (data.getDeal(uuid, faction.id()) != null) continue; // rite underway
                List<DiplomacySavedData.Offer> offers = data.getOffers(uuid, faction.id());
                if (offers.size() == 1 && offers.get(0).dealId().equals(mend.id())
                        && now < offers.get(0).expiresTick()) {
                    continue;
                }
                List<DiplomacySavedData.Offer> only = new ArrayList<>();
                only.add(new DiplomacySavedData.Offer(mend.id(), now + OFFER_EXPIRY_TICKS));
                data.setOffers(uuid, faction.id(), only);
            }
        }
    }

    // ------------------------------------------------------------------
    // Collapse + decay
    // ------------------------------------------------------------------

    /** Relations shatter when standing crashes below WARY — derived
     *  purely from Layer-1 standing, so the Orc Disaster clamp (or
     *  /worldrep set) breaks relations with no extra code. Online
     *  players only (offline standing reads use the neutral base). */
    private static void checkCollapse(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            Map<String, Byte> byFaction = data.allStates().get(uuid);
            if (byFaction == null) continue;
            for (Map.Entry<String, Byte> e : new HashMap<>(byFaction).entrySet()) {
                if (RelationsState.byId(e.getValue()) == RelationsState.NONE) continue;
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;
                if (WorldReputationManager.getStanding(level, uuid, faction)
                        >= COLLAPSE_STANDING) continue;
                // Lent citizens come HOME when relations shatter —
                // untrained, no reward, but never lost.
                ActiveDeal collapsing = data.getDeal(uuid, e.getKey());
                DealSpec collapsingSpec = collapsing == null ? null : DealSpec.byId(collapsing.dealId);
                if (collapsing != null && collapsingSpec != null
                        && collapsingSpec.requirement() instanceof DealSpec.LendCitizens lend
                        && !collapsing.lentCitizens.isEmpty()) {
                    if (returnLentCitizens(level, uuid, collapsing, lend, false)) {
                        player.sendSystemMessage(Component.literal(
                                "Your lent citizens are sent home as relations break down.")
                                .withStyle(net.minecraft.ChatFormatting.GRAY));
                    } else {
                        // No colony can receive them this second — keep
                        // the deal (and the citizens) until one can.
                        continue;
                    }
                }
                data.setState(uuid, e.getKey(), RelationsState.NONE.id());
                data.removeDeal(uuid, e.getKey());
                data.setOffers(uuid, e.getKey(), new ArrayList<>());
                LOGGER.info("[TM] diplomacy: player {} × {} relations COLLAPSED (standing below WARY)",
                        uuid, e.getKey());
                player.sendSystemMessage(Component.literal("Your relations with "
                        + faction.displayName() + " have collapsed!")
                        .withStyle(net.minecraft.ChatFormatting.DARK_RED));
            }
        }
    }

    /** Daily decay — idle relationships fray. Only positive EARNED
     *  standing decays (diplomacy gains fade; grudges don't heal here). */
    private static void tickDecay(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            Map<String, Byte> byFaction = data.allStates().get(uuid);
            if (byFaction == null) continue;
            for (Map.Entry<String, Byte> e : byFaction.entrySet()) {
                RelationsState state = RelationsState.byId(e.getValue());
                if (state == RelationsState.NONE) continue;
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;
                // Idle check: no deal underway and nothing happened today.
                if (data.getDeal(uuid, e.getKey()) != null) continue;
                if (now - data.getLastActivity(uuid, e.getKey()) < DAY) continue;
                double earned = WorldReputationManager.getEarned(level, uuid, faction);
                if (earned <= 0) continue;
                double rate = state == RelationsState.PACT
                        ? ALLIANCE_DECAY_PER_DAY : DIPLOMACY_DECAY_PER_DAY;
                WorldReputationManager.modifyStanding(level, uuid, faction,
                        -Math.min(rate, earned), WorldRepReason.DIPLOMACY);
            }
        }
    }

    /** The "!" badge clears: mark the faction's CURRENT offers seen
     *  (called when the player clicks its tab in the Diplomacy window). */
    static void markOffersSeen(ServerPlayer player, String factionId) {
        if (!isEnabled()) return;
        ServerLevel level = player.serverLevel();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        List<String> ids = data.getOffers(player.getUUID(), factionId).stream()
                .map(DiplomacySavedData.Offer::dealId).toList();
        data.markOffersSeen(player.getUUID(), factionId, ids);
    }

    // ------------------------------------------------------------------
    // Stage 3 — relationship rewards + action coupling
    // ------------------------------------------------------------------

    /** Pillar 1 — alliance buffs: re-applied every second while the
     *  PACT holds (ambient, icon-only). Lapses by itself otherwise. */
    private static void tickAllianceBuffs(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            for (BossFaction faction : BossFaction.values()) {
                if (getState(level, player.getUUID(), faction) != RelationsState.PACT) continue;
                var effect = ALLIANCE_BUFFS.get(faction.id());
                if (effect == null) continue;
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        effect, ALLIANCE_BUFF_DURATION_TICKS, 0, true, false, true));
            }
        }
    }

    /** Pillar 1 — the daily caravan: PACT-tier trade access. */
    static String claimCaravan(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        if (getState(level, uuid, faction) != RelationsState.PACT) {
            return "only ALLIES receive " + faction.displayName() + "'s caravans";
        }
        List<ItemStack> goods = FACTION_GOODS.get(faction.id());
        if (goods == null) return faction.displayName() + " sends no caravans";
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        if (now - data.getLastCaravan(uuid, faction.id()) < CARAVAN_COOLDOWN_TICKS) {
            return "the next caravan from " + faction.displayName() + " arrives tomorrow";
        }
        data.setLastCaravan(uuid, faction.id(), now);
        giveItems(player, goods);
        player.sendSystemMessage(Component.literal("A caravan from " + faction.displayName()
                + " delivers its wares.").withStyle(faction.color()));
        return null;
    }

    /** Pillar 1 — the QOL perk: the allied caravan network carries the
     *  player home (their town hall). Needs any PACT. */
    static String travelHome(ServerPlayer player) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        boolean anyPact = false;
        for (BossFaction faction : BossFaction.values()) {
            if (getState(level, uuid, faction) == RelationsState.PACT) { anyPact = true; break; }
        }
        if (!anyPact) return "the caravan network carries only ALLIES";
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        if (now - data.getLastTravel(uuid) < TRAVEL_COOLDOWN_TICKS) {
            return "the caravans need time before carrying you again";
        }
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, uuid);
        if (colony == null || !colony.getServerBuildingManager().hasTownHall()) {
            return "you have no town hall to travel to";
        }
        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos at = EntityUtils.getSpawnPoint(level, th);
        if (at == null) at = th;
        data.setLastTravel(uuid, now);
        player.teleportTo(level, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        level.sendParticles(ParticleTypes.POOF, player.getX(), player.getY() + 1, player.getZ(),
                24, 0.4, 0.4, 0.4, 0.02);
        player.sendSystemMessage(Component.literal("The allied caravan network carries you home.")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        return null;
    }

    /** Pillar 1 — the standing-gift quest reward: high Clayman standing
     *  earns a SPARE Orc Disaster, spawned UNMARKED (no FactionMarkTag)
     *  — killing it carries ZERO faction consequences; it's a gift to
     *  defeat freely (boss loot + the colony/envoy rewards still apply). */
    static String claimGift(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "the faction system is disabled";
        if (faction != BossFaction.CLAYMAN) return faction.displayName() + " offers no gift";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        if (data.hasClaimedGift(uuid, SPARE_BOSS_GIFT_ID)) {
            return "Clayman's gift was already given";
        }
        if (WorldReputationManager.getStanding(level, uuid, faction) < SPARE_BOSS_MIN_STANDING) {
            return "Clayman thinks too little of you for gifts (standing "
                    + (int) SPARE_BOSS_MIN_STANDING + "+ required)";
        }
        var type = io.github.manasmods.tensura.registry.entity.MonsterEntityTypes.ORC_DISASTER.get();
        net.minecraft.world.entity.Mob boss = type.create(level);
        if (boss == null) return "the gift could not manifest";
        int dx = level.getRandom().nextInt(17) - 8;
        int dz = 12 + level.getRandom().nextInt(9);
        BlockPos pos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                player.blockPosition().offset(dx, 0, dz));
        boss.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        boss.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, null);
        boss.setPersistenceRequired();
        // Deliberately NO FactionMarkTag: an unmarked boss is just a
        // boss — the Layer-1 movers ignore it entirely.
        if (!level.addFreshEntity(boss)) return "the gift could not manifest";
        data.markGiftClaimed(uuid, SPARE_BOSS_GIFT_ID);
        player.sendSystemMessage(Component.literal(
                "Clayman's gift arrives: a spare Orc Disaster, yours to slay freely.")
                .withStyle(faction.color()));
        LOGGER.info("[TM] diplomacy: player {} claimed the spare Orc Disaster gift", uuid);
        return null;
    }

    /**
     * Pillar 3 — the majin-downgrade watch: when the player's race side
     * flips to MAJIN, majin-sensitive allied factions (those that court
     * humans but never majin — the Holy bloc) drop PACT → OPEN. The
     * Layer-1 live base already cooled their standing; this adds the
     * relations-state consequence.
     */
    private static void tickSideWatch(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            byte side = (byte) (WorldReputationManager.isMajinSide(player) ? 1 : 0);
            int last = data.getLastSide(uuid);
            if (last == side) continue;
            data.setLastSide(uuid, side);
            if (last == -1 || side == 0) continue; // first observation / became human
            for (BossFaction faction : BossFaction.values()) {
                FactionProfile profile = FactionProfile.byId(faction.id());
                boolean majinSensitive = profile != null
                        && profile.sendsEnvoysToHuman() && !profile.sendsEnvoysToMajin();
                if (!majinSensitive) continue;
                if (getState(level, uuid, faction) != RelationsState.PACT) continue;
                data.setState(uuid, faction.id(), RelationsState.OPEN.id());
                player.sendSystemMessage(Component.literal("Word of what you have become reaches "
                        + faction.displayName() + " — the alliance is reduced to wary diplomacy.")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
                LOGGER.info("[TM] diplomacy: player {} became majin — {} PACT downgraded to OPEN",
                        uuid, faction.id());
            }
        }
    }

    // ------------------------------------------------------------------
    // The alliance prompt — OPEN relations reaching ALLIED (80+) pop an
    // Accept/Decline dialog (replaces the pact milestone deal)
    // ------------------------------------------------------------------

    private static void checkAlliancePrompts(ServerLevel level) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();
            Map<String, Byte> byFaction = data.allStates().get(uuid);
            if (byFaction == null) continue;
            for (Map.Entry<String, Byte> e : byFaction.entrySet()) {
                if (RelationsState.byId(e.getValue()) != RelationsState.OPEN) continue;
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;
                if (WorldReputationManager.isDiplomacyClosed(level, uuid, faction)) continue;
                double standing = WorldReputationManager.getStanding(level, uuid, faction);
                if (FactionTier.forValue(standing) != FactionTier.ALLIED) continue;
                Map<String, Long> sent = alliancePromptSent
                        .computeIfAbsent(uuid, k -> new HashMap<>());
                Long last = sent.get(e.getKey());
                if (last != null && now - last < ALLIANCE_PROMPT_RETRY_TICKS) continue;
                sent.put(e.getKey(), now);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new Networking.OpenAlliancePromptPayload(
                                faction.id(), faction.displayName(), standing));
                LOGGER.info("[TM] diplomacy: alliance prompt sent to {} for {} (standing {})",
                        uuid, faction.id(), String.format("%.1f", standing));
            }
        }
    }

    /** The prompt's Accept/Decline. Accept → PACT, standing untouched.
     *  Decline → standing drops to just below the ALLIED band (79). */
    static void handleAllianceResponse(ServerPlayer player, String factionId, boolean accepted) {
        if (!isEnabled()) return;
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        BossFaction faction = BossFaction.byId(factionId);
        if (faction == null) return;
        DiplomacySavedData data = DiplomacySavedData.get(level);
        // Re-validate — the world may have moved since the prompt.
        if (getState(level, uuid, faction) != RelationsState.OPEN) return;
        if (FactionTier.forValue(WorldReputationManager.getStanding(level, uuid, faction))
                != FactionTier.ALLIED) {
            return;
        }
        if (accepted) {
            data.setState(uuid, faction.id(), RelationsState.PACT.id());
            data.setLastActivity(uuid, faction.id(), level.getGameTime());
            player.sendSystemMessage(Component.literal("The pact is sealed — you and "
                    + faction.displayName() + " are ALLIES.").withStyle(faction.color()));
            LOGGER.info("[TM] diplomacy: player {} × {} ALLIANCE accepted", uuid, faction.id());
        } else {
            WorldReputationManager.setStanding(level, uuid, faction,
                    ALLIANCE_DECLINE_STANDING, WorldRepReason.DIPLOMACY);
            player.sendSystemMessage(Component.literal(faction.displayName()
                    + " takes the refusal coolly — some warmth is lost.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            LOGGER.info("[TM] diplomacy: player {} × {} ALLIANCE declined → standing {}",
                    uuid, faction.id(), ALLIANCE_DECLINE_STANDING);
        }
        Map<String, Long> sent = alliancePromptSent.get(uuid);
        if (sent != null) sent.remove(faction.id());
    }

    // ------------------------------------------------------------------
    // COVENANT per-faction rewards
    // ------------------------------------------------------------------

    /** Daily passive GRINDERS — Dwargon (industry) + Carrion (beast/hunt)
     *  Covenants deliver materials straight to the player's inventory
     *  (or drop at their feet) once per day. Different material sets. */
    private static void tickCovenantGrinders(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            for (Map.Entry<String, List<ItemStack>> e : GRINDER_GOODS.entrySet()) {
                BossFaction faction = BossFaction.byId(e.getKey());
                if (faction == null) continue;
                if (getState(level, player.getUUID(), faction) != RelationsState.COVENANT) continue;
                giveItems(player, e.getValue());
                player.sendSystemMessage(Component.literal(faction.displayName()
                        + "'s grinder delivers its yield.").withStyle(faction.color()));
            }
        }
    }

    /** Covenant gift — Milim's Drago Nova: one obtainable per REAL-LIFE
     *  hour (wall-clock, not game time, per the brief). */
    static String claimDragoNova(ServerPlayer player) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        if (getState(level, uuid, BossFaction.MILIM) != RelationsState.COVENANT) {
            return "only Milim's Covenant grants the Drago Nova";
        }
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long nowMs = System.currentTimeMillis();
        long lastMs = data.getDragoNovaClaimMillis(uuid);
        if (nowMs - lastMs < NOVA_CLAIM_INTERVAL_MILLIS) {
            long minsLeft = (NOVA_CLAIM_INTERVAL_MILLIS - (nowMs - lastMs)) / 60_000L;
            return "Milim's gift recharges — about " + minsLeft + " min left";
        }
        data.setDragoNovaClaimMillis(uuid, nowMs);
        giveItems(player, List.of(new ItemStack(ExampleMod.DRAGO_NOVA.get(), 1)));
        player.sendSystemMessage(Component.literal("Milim grants you a Drago Nova!")
                .withStyle(BossFaction.MILIM.color()));
        return null;
    }

    /** Covenant gift — Clayman: summon a spare Orc Disaster to fight
     *  freely (UNMARKED, no faction penalty), 4-day regroup cooldown. */
    static String summonOrcDisaster(ServerPlayer player) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        if (getState(level, uuid, BossFaction.CLAYMAN) != RelationsState.COVENANT) {
            return "only Clayman's Covenant grants the summon";
        }
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        if (now - data.getLastCaravan(uuid, KEY_SUMMON) < SUMMON_DISASTER_COOLDOWN_TICKS) {
            return "Clayman's horde needs time to regroup";
        }
        var type = io.github.manasmods.tensura.registry.entity.MonsterEntityTypes.ORC_DISASTER.get();
        net.minecraft.world.entity.Mob boss = type.create(level);
        if (boss == null) return "the summon failed";
        int dx = level.getRandom().nextInt(17) - 8;
        int dz = 12 + level.getRandom().nextInt(9);
        BlockPos pos = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                player.blockPosition().offset(dx, 0, dz));
        boss.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        boss.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, null);
        boss.setPersistenceRequired();
        // UNMARKED — no FactionMarkTag, so the Layer-1 movers ignore it.
        if (!level.addFreshEntity(boss)) return "the summon failed";
        data.setLastCaravan(uuid, KEY_SUMMON, now);
        player.sendSystemMessage(Component.literal(
                "Clayman lends you an Orc Disaster — slay it freely.")
                .withStyle(BossFaction.CLAYMAN.color()));
        return null;
    }

    /** Covenant gift — Luminous: a hero-evolution boon granting 3
     *  elemental SPIRITS the player lacks. Does NOTHING if the player
     *  already has spirits (the investigated cleanliness check). */
    static String grantLuminousSpirits(ServerPlayer player) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        if (getState(level, player.getUUID(), BossFaction.LUMINOUS) != RelationsState.COVENANT) {
            return "only Luminous's Covenant grants the spirits";
        }
        int granted = LuminousSpirits.grantStarterSpirits(player);
        if (granted < 0) return "this boon is for those without spirits — it would do nothing";
        player.sendSystemMessage(Component.literal(
                "Luminous bestows " + granted + " spirits upon you.")
                .withStyle(BossFaction.LUMINOUS.color()));
        return null;
    }

    /** Covenant perk — the caravan/teleport network across Covenant
     *  faction LOCATIONS. Settlements are the rival-colony arc; until
     *  then this falls back to the town-hall travel (the perk is wired,
     *  the per-faction target is the documented stub). */
    static String travelToCovenant(ServerPlayer player, String factionId) {
        // v1: no settlements exist yet — route to the home town hall,
        // same as the Stage-3 caravan-home perk. Faction target stubbed.
        return travelHome(player);
    }

    // ------------------------------------------------------------------
    // The offer REROLL button (4 high magic crystals, per-faction cd)
    // ------------------------------------------------------------------

    static String rerollOffers(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "the faction system is disabled";
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        if (getState(level, uuid, faction) == RelationsState.NONE) {
            return "no relations with " + faction.displayName();
        }
        DiplomacySavedData data = DiplomacySavedData.get(level);
        long now = level.getGameTime();
        // Reroll cooldown is stored under a synthetic per-faction key.
        if (now - data.getLastCaravan(uuid, KEY_REROLL + faction.id()) < REROLL_COOLDOWN_TICKS) {
            return faction.displayName() + "'s offers were just rerolled — wait a while";
        }
        net.minecraft.world.item.Item crystal = highQualityCrystal();
        int held = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(crystal)) held += stack.getCount();
        }
        if (held < REROLL_CRYSTAL_COST) {
            return "a reroll costs " + REROLL_CRYSTAL_COST + " "
                    + new ItemStack(crystal).getHoverName().getString() + " (you carry " + held + ")";
        }
        consumeItems(player, crystal, REROLL_CRYSTAL_COST);
        data.setLastCaravan(uuid, KEY_REROLL + faction.id(), now);
        // Clear this faction's offers + reset its seen-set; the daily
        // refresh logic refills immediately via refreshSingleFaction.
        data.setOffers(uuid, faction.id(), new ArrayList<>());
        refreshSingleFaction(level, player, faction);
        player.sendSystemMessage(Component.literal(faction.displayName()
                + "'s offers are rerolled.").withStyle(faction.color()));
        return null;
    }

    /** Refill ONE faction's offers immediately (the reroll path). A
     *  thin wrapper that runs the same eligibility logic as the daily
     *  {@link #refreshOffers} for a single faction. */
    private static void refreshSingleFaction(ServerLevel level, ServerPlayer player,
                                             BossFaction faction) {
        // Force the per-faction refresh by clearing the day anchor for
        // this one faction is overkill — instead, reuse refreshOffers
        // which is idempotent and only refills empty slots.
        refreshOffers(level);
    }

    // ------------------------------------------------------------------
    // The driver — 1 s cadence + the overworld-day daily pass
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (!isEnabled()) return;
        ServerLevel overworld = server.overworld();
        try {
            processPendingReplies(overworld);
            processDeals(overworld);
            checkCollapse(overworld);
            checkAlliancePrompts(overworld);
            tickAllianceBuffs(overworld);
            tickSideWatch(overworld);
        } catch (Throwable t) {
            LOGGER.warn("[TM] diplomacy: tick failed", t);
        }

        DiplomacySavedData data = DiplomacySavedData.get(overworld);
        long day = overworld.getDayTime() / DAY;
        if (day != data.lastProcessedDay()) {
            data.setLastProcessedDay(day);
            try {
                refreshOffers(overworld);
                tickDecay(overworld);
                rollInboundEnvoys(overworld);
                tickCovenantGrinders(overworld);
            } catch (Throwable t) {
                LOGGER.warn("[TM] diplomacy: daily pass failed", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // The tab snapshot (S2C payload body)
    // ------------------------------------------------------------------

    static CompoundTag buildSnapshot(ServerPlayer player) {
        CompoundTag root = new CompoundTag();
        boolean enabled = isEnabled();
        root.putBoolean("enabled", enabled);
        if (!enabled) return root;
        ServerLevel level = player.serverLevel();
        UUID uuid = player.getUUID();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        root.putBoolean("majin", WorldReputationManager.isMajinSide(player));
        long now = level.getGameTime();
        boolean anyPact = false;
        for (BossFaction faction : BossFaction.values()) {
            if (getState(level, uuid, faction) == RelationsState.PACT) { anyPact = true; break; }
        }
        root.putBoolean("canTravel", anyPact
                && now - data.getLastTravel(uuid) >= TRAVEL_COOLDOWN_TICKS);

        ListTag factions = new ListTag();
        for (BossFaction faction : BossFaction.values()) {
            CompoundTag f = new CompoundTag();
            f.putString("id", faction.id());
            f.putString("name", faction.displayName());
            double standing = WorldReputationManager.getStanding(level, uuid, faction);
            FactionTier tier = FactionTier.forValue(standing);
            RelationsState state = getState(level, uuid, faction);
            f.putDouble("standing", standing);
            f.putString("tier", tier.displayName());
            f.putInt("tierColor", tier.color().getColor() == null ? 0xFFFFFF : tier.color().getColor());
            f.putByte("state", state.id());
            boolean closed = WorldReputationManager.isDiplomacyClosed(level, uuid, faction);
            f.putBoolean("closed", closed);
            boolean pending = data.getPendingReply(uuid, faction.id()) != null;
            f.putBoolean("pendingReply", pending);
            f.putBoolean("canSend", state == RelationsState.NONE && !closed && !pending
                    && now - data.getLastSend(uuid, faction.id()) >= SEND_COOLDOWN_TICKS);
            // Stage 3 — reward availability for the detail pane.
            f.putBoolean("canCaravan", state == RelationsState.PACT
                    && FACTION_GOODS.containsKey(faction.id())
                    && now - data.getLastCaravan(uuid, faction.id()) >= CARAVAN_COOLDOWN_TICKS);
            f.putBoolean("giftAvailable", faction == BossFaction.CLAYMAN
                    && !data.hasClaimedGift(uuid, SPARE_BOSS_GIFT_ID)
                    && WorldReputationManager.getStanding(level, uuid, faction)
                            >= SPARE_BOSS_MIN_STANDING);
            // Covenant reward buttons (detail pane) — only at COVENANT.
            boolean covenant = state == RelationsState.COVENANT;
            f.putBoolean("covenant", covenant);
            f.putBoolean("canReroll", state != RelationsState.NONE
                    && now - data.getLastCaravan(uuid, KEY_REROLL + faction.id())
                            >= REROLL_COOLDOWN_TICKS);
            f.putBoolean("novaReward", covenant && faction == BossFaction.MILIM
                    && System.currentTimeMillis() - data.getDragoNovaClaimMillis(uuid)
                            >= NOVA_CLAIM_INTERVAL_MILLIS);
            f.putBoolean("summonReward", covenant && faction == BossFaction.CLAYMAN
                    && now - data.getLastCaravan(uuid, KEY_SUMMON) >= SUMMON_DISASTER_COOLDOWN_TICKS);
            f.putBoolean("spiritReward", covenant && faction == BossFaction.LUMINOUS);
            // Clayman Covenant: raid INTEL — the next incoming march, if armed.
            if (covenant && faction == BossFaction.CLAYMAN) {
                f.putString("intel", LoreEvents.raidIntelFor(level, uuid));
            }

            ListTag offers = new ListTag();
            java.util.Set<String> seen = data.getSeenOffers(uuid, faction.id());
            boolean hasNew = false;
            for (DiplomacySavedData.Offer offer : data.getOffers(uuid, faction.id())) {
                DealSpec spec = DealSpec.byId(offer.dealId());
                if (spec == null) continue;
                if (!seen.contains(offer.dealId())) hasNew = true;
                CompoundTag o = new CompoundTag();
                o.putString("dealId", spec.id());
                o.putString("title", spec.title());
                o.putString("req", spec.requirement().summary());
                o.putString("reward", spec.rewardSummary());
                o.putInt("daysLeft", (int) Math.max(0, (offer.expiresTick() - now) / DAY));
                offers.add(o);
            }
            f.put("offers", offers);
            f.putBoolean("hasNew", hasNew);

            ActiveDeal deal = data.getDeal(uuid, faction.id());
            DealSpec spec = deal == null ? null : DealSpec.byId(deal.dealId);
            if (deal != null && spec != null) {
                CompoundTag d = new CompoundTag();
                d.putString("dealId", spec.id());
                d.putString("title", spec.title());
                d.putString("req", spec.requirement().summary());
                d.putString("reward", spec.rewardSummary());
                d.putByte("state", deal.state);
                boolean lendDeal = spec.requirement() instanceof DealSpec.LendCitizens;
                int pct;
                if (spec.requirement() instanceof DealSpec.SupplyItems supply) {
                    pct = (int) Math.round(100.0 * deal.progress / Math.max(1, supply.count()));
                } else if (lendDeal && deal.state == ActiveDeal.STATE_AWAITING_PAYOFF) {
                    // The lend's % bar is TIME — how far through the away days.
                    long total = Math.max(1, deal.payoffAtTick - deal.acceptedTick);
                    pct = (int) Math.min(100, (now - deal.acceptedTick) * 100 / total);
                } else {
                    pct = deal.state == ActiveDeal.STATE_ACTIVE ? 0 : 100;
                }
                if (deal.state != ActiveDeal.STATE_ACTIVE && !lendDeal) pct = 100;
                d.putInt("progressPct", Math.min(100, pct));
                d.putInt("hoursLeft", (int) Math.max(0, (deal.deadlineTick - now) / 1000));
                d.putBoolean("lend", lendDeal);
                d.putInt("returnHours", (int) Math.max(0, (deal.payoffAtTick - now) / 1000));
                d.putBoolean("rite", spec.requirement() instanceof DealSpec.MendingRite);
                d.putBoolean("canDeliver", deal.state == ActiveDeal.STATE_ACTIVE
                        && (spec.requirement() instanceof DealSpec.SupplyItems
                                || spec.requirement() instanceof DealSpec.MendingRite));
                d.putBoolean("canCollect", deal.state == ActiveDeal.STATE_READY);
                f.put("active", d);
            }
            factions.add(f);
        }
        root.put("factions", factions);
        return root;
    }

    // ------------------------------------------------------------------
    // Inventory helpers (the barrier-crystal consume idiom)
    // ------------------------------------------------------------------

    private static int consumeItems(ServerPlayer player, Item item, int max) {
        int consumed = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && consumed < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(item)) continue;
            int take = Math.min(max - consumed, stack.getCount());
            stack.shrink(take);
            consumed += take;
        }
        if (consumed > 0) player.getInventory().setChanged();
        return consumed;
    }

    private static void giveItems(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            if (!player.getInventory().add(copy)) {
                player.drop(copy, false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Debug (/diplomacy)
    // ------------------------------------------------------------------

    static String debugForceOpen(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "faction system disabled";
        openRelations(player.serverLevel(), player.getUUID(), faction);
        return "relations with " + faction.displayName() + " forced OPEN";
    }

    static String debugRefreshOffers(ServerPlayer player) {
        if (!isEnabled()) return "faction system disabled";
        refreshOffers(player.serverLevel());
        return "offers refreshed";
    }

    static String debugReplyNow(ServerPlayer player, BossFaction faction) {
        if (!isEnabled()) return "faction system disabled";
        ServerLevel level = player.serverLevel();
        DiplomacySavedData data = DiplomacySavedData.get(level);
        if (data.getPendingReply(player.getUUID(), faction.id()) == null) {
            return "no envoy is awaiting a reply from " + faction.displayName();
        }
        data.setPendingReply(player.getUUID(), faction.id(), level.getGameTime());
        return "reply from " + faction.displayName() + " arrives this second";
    }
}
