package com.example.examplemod;

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
    static final int MAX_OFFERS = 2;
    /** Un-accepted offers expire (and nudge standing −1) after this. */
    static final long OFFER_EXPIRY_TICKS = 3 * DAY;

    /** Relations shatter below this standing (the WARY floor — also what
     *  makes the Orc Disaster's forced-HOSTILE clamp break Clayman
     *  relations for free). */
    static final double COLLAPSE_STANDING = 20.0;

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
        if (getState(level, uuid, faction) == RelationsState.NONE) {
            return "no relations with " + faction.displayName();
        }
        if (data.getDeal(uuid, faction.id()) != null) {
            return "a deal with " + faction.displayName() + " is already underway";
        }
        boolean offered = data.getOffers(uuid, faction.id()).stream()
                .anyMatch(o -> o.dealId().equals(dealId));
        DealSpec spec = DealSpec.byId(dealId);
        if (!offered || spec == null) return "that offer is no longer on the table";
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, uuid);
        if (colony == null) return "you need a colony to honor a deal";

        ActiveDeal deal = new ActiveDeal();
        deal.dealId = dealId;
        deal.colonyId = colony.getID();
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
        if (!(spec.requirement() instanceof DealSpec.SupplyItems supply)) {
            return "this deal isn't fulfilled by delivery";
        }
        int remaining = supply.count() - deal.progress;
        int consumed = consumeItems(player, supply.item(), remaining);
        if (consumed <= 0) {
            return "you carry no " + new ItemStack(supply.item()).getHoverName().getString();
        }
        deal.progress += consumed;
        data.setDeal(uuid, faction.id(), deal);
        data.setLastActivity(uuid, faction.id(), level.getGameTime());
        player.sendSystemMessage(Component.literal("Delivered " + consumed + " — "
                + deal.progress + "/" + supply.count() + ".")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        if (deal.progress >= supply.count()) {
            fulfillDeal(level, uuid, faction, deal, spec);
        }
        return null;
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

    /** Requirement met → pay standing, promote pact, start the payoff. */
    private static void fulfillDeal(ServerLevel level, UUID player, BossFaction faction,
                                    ActiveDeal deal, DealSpec spec) {
        DiplomacySavedData data = DiplomacySavedData.get(level);
        WorldReputationManager.modifyStanding(level, player, faction,
                spec.standingReward(), WorldRepReason.DIPLOMACY);
        data.setLastActivity(player, faction.id(), level.getGameTime());
        if (spec.milestone()) {
            data.setState(player, faction.id(), RelationsState.PACT.id());
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
            online.sendSystemMessage(Component.literal((spec.milestone()
                    ? "The pact is sealed — you and " + faction.displayName() + " are ALLIES."
                    : "Deal fulfilled — " + faction.displayName() + " is pleased.")
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
                if (spec == null) { data.removeDeal(player, e.getKey()); continue; }

                if (deal.state == ActiveDeal.STATE_AWAITING_PAYOFF && now >= deal.payoffAtTick) {
                    deal.state = ActiveDeal.STATE_READY;
                    data.setDeal(player, e.getKey(), deal);
                    ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
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
                // Expire ignored offers (−1 standing nudge each).
                int before = current.size();
                current.removeIf(o -> now >= o.expiresTick());
                for (int i = 0; i < before - current.size(); i++) {
                    WorldReputationManager.modifyStanding(level, player, faction,
                            STANDING_OFFER_EXPIRED, WorldRepReason.DIPLOMACY);
                }

                FactionTier tier = WorldReputationManager.getTier(level, player, faction);
                ActiveDeal active = data.getDeal(player, e.getKey());
                for (DealSpec spec : DealSpec.DEALS.values()) {
                    if (current.size() >= MAX_OFFERS) break;
                    if (tier.compareTo(spec.minTier()) < 0) continue;
                    // The pact is offered once, while still OPEN.
                    if (spec.milestone() && state != RelationsState.OPEN) continue;
                    if (active != null && active.dealId.equals(spec.id())) continue;
                    if (current.stream().anyMatch(o -> o.dealId().equals(spec.id()))) continue;
                    // Offer-time filter: never offer an already-met requirement.
                    if (colony != null && !(spec.requirement() instanceof DealSpec.SupplyItems)) {
                        ActiveDeal probe = new ActiveDeal();
                        probe.colonyId = colony.getID();
                        if (isRequirementMet(level, probe, spec)) continue;
                    }
                    current.add(new DiplomacySavedData.Offer(spec.id(), now + OFFER_EXPIRY_TICKS));
                }
                data.setOffers(player, e.getKey(), current);
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

            ListTag offers = new ListTag();
            for (DiplomacySavedData.Offer offer : data.getOffers(uuid, faction.id())) {
                DealSpec spec = DealSpec.byId(offer.dealId());
                if (spec == null) continue;
                CompoundTag o = new CompoundTag();
                o.putString("dealId", spec.id());
                o.putString("title", spec.title());
                o.putString("req", spec.requirement().summary());
                o.putString("reward", spec.rewardSummary());
                o.putInt("daysLeft", (int) Math.max(0, (offer.expiresTick() - now) / DAY));
                offers.add(o);
            }
            f.put("offers", offers);

            ActiveDeal deal = data.getDeal(uuid, faction.id());
            DealSpec spec = deal == null ? null : DealSpec.byId(deal.dealId);
            if (deal != null && spec != null) {
                CompoundTag d = new CompoundTag();
                d.putString("dealId", spec.id());
                d.putString("title", spec.title());
                d.putString("req", spec.requirement().summary());
                d.putString("reward", spec.rewardSummary());
                d.putByte("state", deal.state);
                int pct;
                if (spec.requirement() instanceof DealSpec.SupplyItems supply) {
                    pct = (int) Math.round(100.0 * deal.progress / Math.max(1, supply.count()));
                } else {
                    pct = deal.state == ActiveDeal.STATE_ACTIVE ? 0 : 100;
                }
                if (deal.state != ActiveDeal.STATE_ACTIVE) pct = 100;
                d.putInt("progressPct", Math.min(100, pct));
                d.putInt("hoursLeft", (int) Math.max(0, (deal.deadlineTick - now) / 1000));
                d.putBoolean("canDeliver", deal.state == ActiveDeal.STATE_ACTIVE
                        && spec.requirement() instanceof DealSpec.SupplyItems);
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
