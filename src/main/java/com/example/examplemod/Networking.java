package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Stage C2a — networking foundation for the goblin-roster menu.
 *
 * Round-trip:
 *   client keybind → RequestRosterPayload (C2S) → server reads identities
 *     → RosterResponsePayload (S2C) → client logs.
 *
 * NeoForge 1.21.1 payload API:
 *   - Each payload implements {@link CustomPacketPayload} and exposes a Type + StreamCodec.
 *   - Registered in {@link RegisterPayloadHandlersEvent} via {@link PayloadRegistrar}.
 *   - {@code playToServer} / {@code playToClient} for direction.
 *   - {@link IPayloadContext#player()} resolves to ServerPlayer (server) / LocalPlayer (client).
 */
public final class Networking {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Networking() {}

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /** Called from the mod constructor via the mod event bus. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ExampleMod.MODID).versioned("1");
        registrar.playToServer(
                RequestRosterPayload.TYPE,
                RequestRosterPayload.CODEC,
                Networking::onRequestRoster
        );
        registrar.playToServer(
                ActOnIdentityPayload.TYPE,
                ActOnIdentityPayload.CODEC,
                Networking::onActOnIdentity
        );
        registrar.playToServer(
                ConfirmCollapsePayload.TYPE,
                ConfirmCollapsePayload.CODEC,
                Networking::onConfirmCollapse
        );
        registrar.playToServer(
                BulkSummonPayload.TYPE,
                BulkSummonPayload.CODEC,
                Networking::onBulkSummon
        );
        registrar.playToServer(
                BulkSendPayload.TYPE,
                BulkSendPayload.CODEC,
                Networking::onBulkSend
        );
        registrar.playToClient(
                OpenEnvoyDialoguePayload.TYPE,
                OpenEnvoyDialoguePayload.CODEC,
                Networking::onOpenEnvoyDialogue
        );
        registrar.playToServer(
                EnvoyResponsePayload.TYPE,
                EnvoyResponsePayload.CODEC,
                Networking::onEnvoyResponse
        );
        registrar.playToServer(
                OpenSubordinateTradePayload.TYPE,
                OpenSubordinateTradePayload.CODEC,
                Networking::onOpenSubordinateTrade
        );
        registrar.playToClient(
                RosterResponsePayload.TYPE,
                RosterResponsePayload.CODEC,
                Networking::onRosterResponse
        );
        registrar.playToClient(
                OpenCollapseConfirmPayload.TYPE,
                OpenCollapseConfirmPayload.CODEC,
                Networking::onOpenCollapseConfirm
        );
        // Stage B picker — server tells client to open the race picker
        // for a newly-created or still-pending colony.
        registrar.playToClient(
                OpenRacePickerPayload.TYPE,
                OpenRacePickerPayload.CODEC,
                Networking::onOpenRacePicker
        );
        // Stage B picker — client's choice routes back to server which
        // writes the config and dispatches the flavour message.
        registrar.playToServer(
                RaceChoicePayload.TYPE,
                RaceChoicePayload.CODEC,
                Networking::onRaceChoice
        );
        // Stage F1 — goblin-tag sync. NeoForge 1.21.1 entity attachments
        // do not auto-sync to the client; this payload drives the client
        // mirror (RaceTagClientStore).
        registrar.playToClient(
                SyncRaceTagPayload.TYPE,
                SyncRaceTagPayload.CODEC,
                Networking::onSyncRaceTag
        );
        // Harvest Festival — per-citizen skill bonuses synced to the owner so the
        // citizen window can draw the blue "+X".
        registrar.playToClient(
                FestivalBonusPayload.TYPE,
                FestivalBonusPayload.CODEC,
                Networking::onFestivalBonus
        );
        // Citizen-side trade button — player clicks "Trade" in
        // MainWindowCitizen → CitizenTradeButtonHandler fires this
        // C2S payload → server opens the merchant trade screen if
        // the citizen's subordinate body is loaded and trade-ready.
        registrar.playToServer(
                OpenCitizenTradePayload.TYPE,
                OpenCitizenTradePayload.CODEC,
                Networking::onOpenCitizenTrade
        );
        // Assassin LURKING flag — drives the Great-Sage-only red
        // nameplate on the client.
        registrar.playToClient(
                SyncAssassinFlagPayload.TYPE,
                SyncAssassinFlagPayload.CODEC,
                Networking::onSyncAssassinFlag
        );
        // Barrier Core menu — server opens/refreshes the menu with live
        // tank + layer state; client buttons fire actions back.
        registrar.playToClient(
                OpenBarrierMenuPayload.TYPE,
                OpenBarrierMenuPayload.CODEC,
                Networking::onOpenBarrierMenu
        );
        registrar.playToServer(
                BarrierMenuActionPayload.TYPE,
                BarrierMenuActionPayload.CODEC,
                Networking::onBarrierMenuAction
        );
        // Diplomacy Stage 1 — tab snapshot (S2C), tab actions (C2S),
        // inbound faction-envoy dialogue (S2C) + its response (C2S).
        registrar.playToClient(
                DiplomacySnapshotPayload.TYPE,
                DiplomacySnapshotPayload.CODEC,
                Networking::onDiplomacySnapshot
        );
        registrar.playToServer(
                DiplomacyActionPayload.TYPE,
                DiplomacyActionPayload.CODEC,
                Networking::onDiplomacyAction
        );
        registrar.playToClient(
                OpenFactionEnvoyPayload.TYPE,
                OpenFactionEnvoyPayload.CODEC,
                Networking::onOpenFactionEnvoy
        );
        registrar.playToServer(
                FactionEnvoyResponsePayload.TYPE,
                FactionEnvoyResponsePayload.CODEC,
                Networking::onFactionEnvoyResponse
        );
        // Stage 2 — the lend-citizen picker (S2C candidates, C2S choice).
        registrar.playToClient(
                OpenLendPickerPayload.TYPE,
                OpenLendPickerPayload.CODEC,
                Networking::onOpenLendPicker
        );
        registrar.playToServer(
                LendConfirmPayload.TYPE,
                LendConfirmPayload.CODEC,
                Networking::onLendConfirm
        );
        // The alliance prompt — fired when OPEN relations reach ALLIED.
        registrar.playToClient(
                OpenAlliancePromptPayload.TYPE,
                OpenAlliancePromptPayload.CODEC,
                Networking::onOpenAlliancePrompt
        );
        registrar.playToServer(
                AllianceResponsePayload.TYPE,
                AllianceResponsePayload.CODEC,
                Networking::onAllianceResponse
        );
    }

    /**
     * Client-side delegate for incoming roster responses. Installed by
     * {@code ClientEvents.init} to point at the Screen-opening logic. Default
     * implementation logs (server-safe; runs only if the client never installed
     * a real handler, which shouldn't happen).
     *
     * Using a Consumer field rather than a direct method reference keeps this
     * file loadable on the server JVM — no client-only classes referenced.
     */
    public static Consumer<RosterResponsePayload> rosterClientHandler = payload -> {
        LOGGER.info("[TM] roster (no client handler installed): {} entries", payload.entries().size());
    };

    /** Client-side delegate for festival skill-bonus sync. Installed by ClientEvents. */
    public static Consumer<FestivalBonusPayload> festivalBonusClientHandler = payload -> {
        LOGGER.info("[TM] festival bonus (no client handler): {} entries", payload.entries().size());
    };

    /** Client-side delegate for the collapse-confirm prompt. Installed by
     *  {@code ClientEvents.init} to point at the Screen-opening logic. */
    public static Consumer<OpenCollapseConfirmPayload> confirmCollapseClientHandler = payload -> {
        LOGGER.info("[TM] confirm collapse (no client handler installed) for {}", payload.identityId());
    };

    /** Client-side delegate for race-tag sync payloads. Installed by
     *  {@link ClientEvents#init} to point at {@link RaceTagClientStore#onPayload}.
     *  Default (no-op-ish) implementation logs; should never run in practice
     *  because the client side always installs its store handler at startup. */
    public static Consumer<SyncRaceTagPayload> raceTagClientHandler = payload -> {
        LOGGER.info("[TM] race tag (no client handler) entity={} present={}",
                payload.entityUuid(), payload.present());
    };

    /** Client-side delegate for the race-picker open prompt. Installed
     *  by {@link ClientEvents#init} to point at the deferred screen-open
     *  handler. */
    public static Consumer<OpenRacePickerPayload> racePickerOpenHandler = payload -> {
        LOGGER.info("[TM] race picker (no client handler) colony={} name={}",
                payload.colonyId(), payload.colonyName());
    };

    /** Client-side delegate for envoy dialogue open. Installed by ClientEvents. */
    public static Consumer<OpenEnvoyDialoguePayload> envoyDialogueClientHandler = payload -> {
        LOGGER.info("[TM] envoy dialogue (no client handler) entity={} member={} colony={} condMask={}",
                payload.entityId(), payload.memberId(), payload.colonyId(), payload.conditionMask());
    };

    // ------------------------------------------------------------------
    // Payloads
    // ------------------------------------------------------------------

    /** C2S: client asks the server for this player's roster. No payload fields. */
    public record RequestRosterPayload() implements CustomPacketPayload {
        public static final Type<RequestRosterPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "request_roster"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestRosterPayload> CODEC =
                StreamCodec.unit(new RequestRosterPayload());

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: server returns the player's roster entries, the player's current
     *  magicule (counter), the player's primary colony name (subtitle), and
     *  that colony's reputation (header tier line; 50.0 when no colony). */
    public record RosterResponsePayload(List<RosterEntry> entries, double playerMagicule,
                                        String colonyName, double colonyReputation)
            implements CustomPacketPayload {
        public static final Type<RosterResponsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "roster_response"));

        public static final StreamCodec<ByteBuf, RosterResponsePayload> CODEC =
                StreamCodec.composite(
                        RosterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                        RosterResponsePayload::entries,
                        ByteBufCodecs.DOUBLE,
                        RosterResponsePayload::playerMagicule,
                        ByteBufCodecs.STRING_UTF8,
                        RosterResponsePayload::colonyName,
                        ByteBufCodecs.DOUBLE,
                        RosterResponsePayload::colonyReputation,
                        RosterResponsePayload::new
                );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One festival skill bonus: colony + citizen + skill (enum ordinal) + amount. */
    public record FestivalBonusEntry(int colonyId, int citizenId, int skillOrdinal, int bonus) {
        public static final StreamCodec<ByteBuf, FestivalBonusEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FestivalBonusEntry::colonyId,
                ByteBufCodecs.VAR_INT, FestivalBonusEntry::citizenId,
                ByteBufCodecs.VAR_INT, FestivalBonusEntry::skillOrdinal,
                ByteBufCodecs.VAR_INT, FestivalBonusEntry::bonus,
                FestivalBonusEntry::new
        );
    }

    /** S2C: the owner's full set of festival per-citizen skill bonuses (replaces
     *  the client mirror). Drives the blue "+X" in the citizen window. */
    public record FestivalBonusPayload(List<FestivalBonusEntry> entries) implements CustomPacketPayload {
        public static final Type<FestivalBonusPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "festival_bonus"));

        public static final StreamCodec<ByteBuf, FestivalBonusPayload> CODEC = StreamCodec.composite(
                FestivalBonusEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                FestivalBonusPayload::entries,
                FestivalBonusPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: client clicks a row in the roster menu. Server decides whether to
     * send or summon based on the identity's CURRENT mode (authoritative
     * server state), not what the client thought it was.
     */
    public record ActOnIdentityPayload(UUID identityId) implements CustomPacketPayload {
        public static final Type<ActOnIdentityPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "act_on_identity"));

        public static final StreamCodec<ByteBuf, ActOnIdentityPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ActOnIdentityPayload::identityId,
                ActOnIdentityPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: insufficient magicule — open the collapse-confirmation Screen.
     * Carries enough state for the dialog to render its body and reply.
     */
    public record OpenCollapseConfirmPayload(UUID identityId,
                                             String goblinName,
                                             double cost,
                                             double currentMagicule) implements CustomPacketPayload {
        public static final Type<OpenCollapseConfirmPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_collapse_confirm"));

        public static final StreamCodec<ByteBuf, OpenCollapseConfirmPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,        OpenCollapseConfirmPayload::identityId,
                ByteBufCodecs.STRING_UTF8,    OpenCollapseConfirmPayload::goblinName,
                ByteBufCodecs.DOUBLE,         OpenCollapseConfirmPayload::cost,
                ByteBufCodecs.DOUBLE,         OpenCollapseConfirmPayload::currentMagicule,
                OpenCollapseConfirmPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: bulk summon — N selected identity UUIDs (max 9). Server runs
     * one cost decision against the total cost and either summons all,
     * summons-all-with-overspend (Sleep Mode), or refuses outright.
     */
    public record BulkSummonPayload(List<UUID> identityIds) implements CustomPacketPayload {
        public static final Type<BulkSummonPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "bulk_summon"));

        public static final StreamCodec<ByteBuf, BulkSummonPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(ExampleMod.BULK_SUMMON_CAP)),
                BulkSummonPayload::identityIds,
                BulkSummonPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: symmetric bulk send (subordinates → colony). Same cost-band rules
     * as {@link BulkSummonPayload}, applied to the SUBORDINATE-mode subset.
     * Each identity goes to its own colony's town hall (per-identity colonyId),
     * so a single batch can fan across multiple colonies if the player has them.
     */
    public record BulkSendPayload(List<UUID> identityIds) implements CustomPacketPayload {
        public static final Type<BulkSendPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "bulk_send"));

        public static final StreamCodec<ByteBuf, BulkSendPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(ExampleMod.BULK_SUMMON_CAP)),
                BulkSendPayload::identityIds,
                BulkSendPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: a player right-clicked an envoy; the server has validated it.
     * Open the diplomacy dialogue. Body text + nameplate are looked up
     * client-side from {@link EnvoyDialogue} keyed on memberId — keeping
     * the wire small and the dialogue copy single-sourced.
     */
    public record OpenEnvoyDialoguePayload(int entityId, byte memberId, int colonyId,
                                           byte conditionMask, byte reputationTierId)
            implements CustomPacketPayload {
        public static final Type<OpenEnvoyDialoguePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_envoy_dialogue"));
        public static final StreamCodec<ByteBuf, OpenEnvoyDialoguePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenEnvoyDialoguePayload::entityId,
                ByteBufCodecs.BYTE,    OpenEnvoyDialoguePayload::memberId,
                ByteBufCodecs.VAR_INT, OpenEnvoyDialoguePayload::colonyId,
                ByteBufCodecs.BYTE,    OpenEnvoyDialoguePayload::conditionMask,
                ByteBufCodecs.BYTE,    OpenEnvoyDialoguePayload::reputationTierId,
                OpenEnvoyDialoguePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: open (or live-refresh) the Barrier Core menu. Carries the full
     * tank + layer snapshot; the client renders the gauge and buttons
     * from this and never computes anything itself. Re-sent by the server
     * after every menu action (roster-refresh pattern).
     */
    public record OpenBarrierMenuPayload(net.minecraft.core.BlockPos pos, double stored,
                                         double capacity, int layers, boolean canMultiLayer,
                                         double drainPerSec, int tier, String colonyName,
                                         boolean wallVisible)
            implements CustomPacketPayload {
        public static final Type<OpenBarrierMenuPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_barrier_menu"));
        public static final StreamCodec<ByteBuf, OpenBarrierMenuPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    net.minecraft.core.BlockPos.STREAM_CODEC.encode(buf, p.pos);
                    buf.writeDouble(p.stored);
                    buf.writeDouble(p.capacity);
                    buf.writeByte(p.layers);
                    buf.writeBoolean(p.canMultiLayer);
                    buf.writeDouble(p.drainPerSec);
                    buf.writeByte(p.tier);
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.colonyName);
                    buf.writeBoolean(p.wallVisible);
                },
                buf -> new OpenBarrierMenuPayload(
                        net.minecraft.core.BlockPos.STREAM_CODEC.decode(buf),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readByte(),
                        buf.readBoolean(),
                        buf.readDouble(),
                        buf.readByte(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: a Barrier Core menu button. Actions: 0 add 3k, 1 take 3k,
     *  2 MIN (withdraw all), 3 MAX (fill from player), 4 layer +,
     *  5 layer −. Server validates reach + gate and re-sends the menu. */
    public record BarrierMenuActionPayload(net.minecraft.core.BlockPos pos, byte action)
            implements CustomPacketPayload {
        public static final byte ACTION_ADD = 0, ACTION_TAKE = 1, ACTION_MIN = 2,
                ACTION_MAX = 3, ACTION_LAYER_PLUS = 4, ACTION_LAYER_MINUS = 5,
                ACTION_TOGGLE_VISIBLE = 6;
        public static final Type<BarrierMenuActionPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "barrier_menu_action"));
        public static final StreamCodec<ByteBuf, BarrierMenuActionPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, BarrierMenuActionPayload::pos,
                ByteBufCodecs.BYTE, BarrierMenuActionPayload::action,
                BarrierMenuActionPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: mark/unmark an entity as a LURKING assassin on the client
     *  (the red nameplate shows only if the viewing player has Great
     *  Sage — that check is client-side). */
    public record SyncAssassinFlagPayload(UUID entityUuid, boolean lurking)
            implements CustomPacketPayload {
        public static final Type<SyncAssassinFlagPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "sync_assassin_flag"));
        public static final StreamCodec<ByteBuf, SyncAssassinFlagPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SyncAssassinFlagPayload::entityUuid,
                ByteBufCodecs.BOOL, SyncAssassinFlagPayload::lurking,
                SyncAssassinFlagPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client-side delegate for the assassin flag (server-safe default). */
    public static Consumer<SyncAssassinFlagPayload> assassinFlagClientHandler = payload ->
            LOGGER.info("[TM] assassin flag (no client handler): {} {}",
                    payload.entityUuid(), payload.lurking());

    private static void onSyncAssassinFlag(SyncAssassinFlagPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> assassinFlagClientHandler.accept(payload));
    }

    /** Client-side delegate for the barrier menu open/refresh. Installed
     *  by ClientEvents (server-safe default logs). */
    public static Consumer<OpenBarrierMenuPayload> barrierMenuClientHandler = payload ->
            LOGGER.info("[TM] barrier menu (no client handler): {} {}/{}",
                    payload.pos(), payload.stored(), payload.capacity());

    private static void onOpenBarrierMenu(OpenBarrierMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> barrierMenuClientHandler.accept(payload));
    }

    /** Build + send the menu snapshot for {@code be} to {@code player}. */
    static void sendBarrierMenuTo(ServerPlayer player, BarrierBlockEntity be) {
        ServerLevel level = player.serverLevel();
        IColony colony = IColonyManager.getInstance().getClosestColony(level, be.getBlockPos());
        PacketDistributor.sendToPlayer(player, new OpenBarrierMenuPayload(
                be.getBlockPos(),
                be.getPoolStored(),
                be.getCapacity(),
                be.getActiveLayers(),
                BarrierBlockEntity.isDemonLordOrHero(player),
                be.getLastDrainPerSecond(),
                be.getTier(),
                colony != null ? colony.getName() : "",
                be.isWallVisible()));
    }

    private static void onBarrierMenuAction(BarrierMenuActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            ServerLevel level = sp.serverLevel();
            // Reach + existence validation — the menu is a remote control
            // for a block; standard interaction distance applies.
            if (payload.pos().distToCenterSqr(sp.position()) > 8 * 8) return;
            if (!(level.getBlockEntity(payload.pos()) instanceof BarrierBlockEntity be)) return;

            switch (payload.action()) {
                case BarrierMenuActionPayload.ACTION_ADD ->
                        be.channelFromPlayer(sp);
                case BarrierMenuActionPayload.ACTION_TAKE ->
                        be.withdrawToPlayer(sp, BarrierBlockEntity.PLAYER_CHANNEL_PER_CLICK);
                case BarrierMenuActionPayload.ACTION_MIN ->
                        be.withdrawToPlayer(sp, Double.MAX_VALUE);
                case BarrierMenuActionPayload.ACTION_MAX ->
                        be.channelFromPlayer(sp, Double.MAX_VALUE);
                case BarrierMenuActionPayload.ACTION_LAYER_PLUS ->
                        be.trySetLayers(be.getActiveLayers() + 1, sp);
                case BarrierMenuActionPayload.ACTION_LAYER_MINUS ->
                        be.trySetLayers(be.getActiveLayers() - 1, sp);
                case BarrierMenuActionPayload.ACTION_TOGGLE_VISIBLE ->
                        be.setWallVisible(!be.isWallVisible());
                default -> { }
            }
            sendBarrierMenuTo(sp, be); // live refresh
        });
    }

    /**
     * C2S: the player clicked Accept ({@code accepted=true}) or Decline
     * ({@code accepted=false}) in the envoy dialogue. Server re-validates
     * (entity still exists, still has ENVOY_TAG, state is ALIVE, player
     * still owns the colony) before mutating state.
     */
    public record EnvoyResponsePayload(int entityId, boolean accepted) implements CustomPacketPayload {
        public static final Type<EnvoyResponsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "envoy_response"));
        public static final StreamCodec<ByteBuf, EnvoyResponsePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EnvoyResponsePayload::entityId,
                ByteBufCodecs.BOOL,    EnvoyResponsePayload::accepted,
                EnvoyResponsePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: player clicked the "Trade" tab on a named subordinate's inventory
     * screen. Server resolves the entity by id, validates ownership through
     * the identity store, and opens the merchant trading screen — preserving
     * the subordinate's profession / merchant level / persisted offers (all
     * round-tripped via {@code TensuraMerchantEntity}'s own NBT serialisation
     * so naming did NOT clear them).
     */
    public record OpenSubordinateTradePayload(int entityId) implements CustomPacketPayload {
        public static final Type<OpenSubordinateTradePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_subordinate_trade"));
        public static final StreamCodec<ByteBuf, OpenSubordinateTradePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenSubordinateTradePayload::entityId,
                OpenSubordinateTradePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: player clicked "Proceed (collapse)" in the confirm dialog.
     * Server force-collapses (sets magicule to 0) and runs the original action.
     * (Decline sends NO packet — client just dismisses the dialog.)
     */
    public record ConfirmCollapsePayload(UUID identityId) implements CustomPacketPayload {
        public static final Type<ConfirmCollapsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "confirm_collapse"));

        public static final StreamCodec<ByteBuf, ConfirmCollapsePayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ConfirmCollapsePayload::identityId,
                ConfirmCollapsePayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: server tells the client about a race-tag change on a given
     * entity. {@code present=true} → install/update the client-side mirror
     * with {@code identityId} + {@code raceId} + {@code variant};
     * {@code present=false} → remove the mirror entry (summon path,
     * or any clear).
     *
     * Wire ID kept as {@code "sync_goblin_tag"} for protocol stability
     * with same-session clients; only the Java identifier is renamed.
     *
     * Sent in three situations (see ExampleMod):
     *   (1) {@code sendNamedMobToColony} → broadcast to all players already
     *       tracking the freshly-spawned citizen body.
     *   (2) {@code summonNamedMob}, just before the citizen body is
     *       discarded → broadcast a clear so any other player viewing it
     *       drops the mirror immediately.
     *   (3) {@code onStartTracking} → unicast to a player who just started
     *       tracking an already-tagged citizen (relog, chunk re-enter,
     *       dimension change).
     */
    public record SyncRaceTagPayload(UUID entityUuid, boolean present,
                                     UUID identityId, byte raceId, byte[] variant,
                                     String profession)
            implements CustomPacketPayload {

        public static final Type<SyncRaceTagPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "sync_goblin_tag"));

        public static final StreamCodec<ByteBuf, SyncRaceTagPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,         SyncRaceTagPayload::entityUuid,
                ByteBufCodecs.BOOL,            SyncRaceTagPayload::present,
                UUIDUtil.STREAM_CODEC,         SyncRaceTagPayload::identityId,
                ByteBufCodecs.BYTE,            SyncRaceTagPayload::raceId,
                ByteBufCodecs.byteArray(256),  SyncRaceTagPayload::variant,
                ByteBufCodecs.STRING_UTF8,     SyncRaceTagPayload::profession,
                SyncRaceTagPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static SyncRaceTagPayload of(UUID entityUuid, RaceTag tag) {
            return new SyncRaceTagPayload(entityUuid, true,
                    tag.identityId(), (byte) tag.race().getId(), tag.encodeVariant(),
                    tag.profession());
        }

        public static SyncRaceTagPayload clear(UUID entityUuid) {
            return new SyncRaceTagPayload(entityUuid, false,
                    new UUID(0L, 0L), (byte) 0, new byte[0], "");
        }
    }

    /** C2S — player clicks the Trade button on the citizen's
     *  MineColonies info screen. Server resolves the citizen's
     *  {@code RaceTag} → identity, validates ownership and merchant
     *  capability, then opens the merchant screen if the citizen's
     *  subordinate body is currently in the world. */
    public record OpenCitizenTradePayload(int citizenEntityId)
            implements CustomPacketPayload {

        public static final Type<OpenCitizenTradePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_citizen_trade"));

        public static final StreamCodec<ByteBuf, OpenCitizenTradePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenCitizenTradePayload::citizenEntityId,
                OpenCitizenTradePayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: open the race picker for the given colony. Carries the
     * colony's display name so the screen can show it without a
     * round-trip lookup.
     *
     * Sent in two situations:
     *  - on {@code ColonyCreatedModEvent} for the colony's owner
     *  - on {@code PlayerLoggedInEvent} / town-hall right-click if
     *    the owner returns to a still-pending colony
     */
    public record OpenRacePickerPayload(int colonyId, String colonyName)
            implements CustomPacketPayload {
        public static final Type<OpenRacePickerPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_race_picker"));
        public static final StreamCodec<ByteBuf, OpenRacePickerPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,      OpenRacePickerPayload::colonyId,
                ByteBufCodecs.STRING_UTF8,  OpenRacePickerPayload::colonyName,
                OpenRacePickerPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: the player's choice from the race picker. {@code choice}
     * is encoded with the {@code RACE_CHOICE_*} constants below — NOT
     * the {@link Race#getId()} encoding (which has only GOBLIN/ORC and
     * no "default" slot). Three values:
     *
     * <pre>
     * 0 = DEFAULT — no race configured, vanilla MineColonies citizens
     * 1 = GOBLIN  — Race.GOBLIN
     * 2 = ORC     — Race.ORC
     * </pre>
     *
     * The server-side handler ({@link ExampleMod#handleRaceChoice})
     * dispatches.
     */
    public record RaceChoicePayload(int colonyId, byte choice)
            implements CustomPacketPayload {
        public static final byte CHOICE_DEFAULT = 0;
        public static final byte CHOICE_GOBLIN  = 1;
        public static final byte CHOICE_ORC     = 2;

        public static final Type<RaceChoicePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "race_choice"));
        public static final StreamCodec<ByteBuf, RaceChoicePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RaceChoicePayload::colonyId,
                ByteBufCodecs.BYTE,    RaceChoicePayload::choice,
                RaceChoicePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * One row in the roster reply.
     *
     * {@code modeByte}: 0 = SUBORDINATE, 1 = IN_COLONY.
     *
     * {@code ep}: the live body's current Tensura EP (aura + magicule), read
     * server-side from the live body's {@link IExistence} — the citizen if
     * IN_COLONY, the goblin if SUBORDINATE — consistent with how the
     * send/summon cost gate reads the same target. Used client-side for
     * EP-desc sorting in the roster Screen. If the live body cannot be
     * resolved (chunk unloaded, dim mismatch), 0.0 is sent so sorting stays
     * deterministic and the row still renders.
     */
    public record RosterEntry(UUID identityId, String name, byte modeByte, double ep) {
        public static final StreamCodec<ByteBuf, RosterEntry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,         RosterEntry::identityId,
                ByteBufCodecs.STRING_UTF8,     RosterEntry::name,
                ByteBufCodecs.BYTE,            RosterEntry::modeByte,
                ByteBufCodecs.DOUBLE,          RosterEntry::ep,
                RosterEntry::new
        );

        public static byte encodeMode(RaceIdentitySavedData.Mode mode) {
            return (byte) (mode == RaceIdentitySavedData.Mode.IN_COLONY ? 1 : 0);
        }

        public String modeName() {
            return modeByte == 1 ? "IN_COLONY" : "SUBORDINATE";
        }
    }

    // ------------------------------------------------------------------
    // Server handler — build and send the roster for the requesting player
    // ------------------------------------------------------------------

    private static void onRequestRoster(RequestRosterPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        // SavedData reads + colony lookups must run on the main thread.
        context.enqueueWork(() -> sendRosterTo(sp));
    }

    static void sendRosterTo(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        UUID playerUUID = sp.getUUID();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);

        List<RosterEntry> entries = new ArrayList<>();
        for (RaceIdentitySavedData.RaceIdentity identity : saved.all()) {
            // Filter: only identities owned by this player.
            // ownerPlayerUUID is set at naming time from player.getUUID(), which
            // also becomes IExistence.permanentOwner via Tensura's submitNaming —
            // equivalent filters, but reading from our field avoids navigating
            // the entity snapshot's ManasCoreStorage subtag.
            if (identity.ownerPlayerUUID == null) continue;  // legacy / orphan
            if (!playerUUID.equals(identity.ownerPlayerUUID)) continue;

            IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
            ICitizenData cd = colony != null ? colony.getCitizenManager().getCivilian(identity.citizenId) : null;
            String name = cd != null ? cd.getName() : "?";
            double ep = ExampleMod.readEPForRoster(sp, identity);
            entries.add(new RosterEntry(identity.identityId, name, RosterEntry.encodeMode(identity.mode), ep));
        }

        double magicule = ExampleMod.currentMagicule(sp);
        IColony primary = IColonyManager.getInstance().getIColonyByOwner(level, playerUUID);
        String colonyName = primary != null ? primary.getName() : "";
        // Reputation of the header colony — read through the manager (the
        // sole storage door). No colony → neutral default, hidden client-side.
        double reputation = primary != null
                ? ReputationManager.getReputation(primary)
                : ReputationManager.DEFAULT_REPUTATION;
        LOGGER.info("[TM] roster: sending {} entries (magicule {}) to {}",
                entries.size(), magicule, sp.getName().getString());
        PacketDistributor.sendToPlayer(sp,
                new RosterResponsePayload(entries, magicule, colonyName, reputation));
    }

    // ------------------------------------------------------------------
    // Server handler — menu action (send or summon by identity UUID)
    // ------------------------------------------------------------------

    private static void onActOnIdentity(ActOnIdentityPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            ExampleMod.handleMenuAction(sp, payload.identityId());
            // Always push a fresh roster after an action so the open Screen
            // reflects the new state (toggled mode, or unchanged if the action
            // failed/queued for confirmation and an advisory was sent).
            sendRosterTo(sp);
        });
    }

    private static void onConfirmCollapse(ConfirmCollapsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            ExampleMod.handleConfirmCollapse(sp, payload.identityId());
            sendRosterTo(sp);
        });
    }

    private static void onBulkSummon(BulkSummonPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            ExampleMod.handleBulkSummon(sp, payload.identityIds());
            sendRosterTo(sp);
        });
    }

    private static void onBulkSend(BulkSendPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            ExampleMod.handleBulkSend(sp, payload.identityIds());
            sendRosterTo(sp);
        });
    }

    private static void onOpenEnvoyDialogue(OpenEnvoyDialoguePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> envoyDialogueClientHandler.accept(payload));
    }

    private static void onOpenSubordinateTrade(OpenSubordinateTradePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleOpenSubordinateTrade(sp, payload.entityId()));
    }

    private static void onEnvoyResponse(EnvoyResponsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleEnvoyResponse(sp, payload.entityId(), payload.accepted()));
    }

    // ------------------------------------------------------------------
    // Client handler — delegate to the installed Consumer (defaults to log).
    // Wrapping in enqueueWork ensures Screen interactions run on the
    // client main thread.
    // ------------------------------------------------------------------

    private static void onRosterResponse(RosterResponsePayload payload, IPayloadContext context) {
        // Registered as playToClient → only fires on the logical client.
        context.enqueueWork(() -> rosterClientHandler.accept(payload));
    }

    private static void onFestivalBonus(FestivalBonusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> festivalBonusClientHandler.accept(payload));
    }

    private static void onOpenCollapseConfirm(OpenCollapseConfirmPayload payload, IPayloadContext context) {
        // Diagnostic: confirms the S2C prompt actually reached the client.
        LOGGER.info("[TM] client received collapse-confirm prompt: goblin='{}' cost={} have={}",
                payload.goblinName(), payload.cost(), payload.currentMagicule());
        context.enqueueWork(() -> confirmCollapseClientHandler.accept(payload));
    }

    private static void onSyncRaceTag(SyncRaceTagPayload payload, IPayloadContext context) {
        // enqueueWork bounces to the client main thread so the store mutation
        // happens off the netty thread (the store is a ConcurrentHashMap so
        // it'd be safe anyway, but downstream renderer reads expect main).
        context.enqueueWork(() -> raceTagClientHandler.accept(payload));
    }

    private static void onOpenRacePicker(OpenRacePickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> racePickerOpenHandler.accept(payload));
    }

    private static void onOpenCitizenTrade(OpenCitizenTradePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleOpenCitizenTrade(sp, payload.citizenEntityId()));
    }

    private static void onRaceChoice(RaceChoicePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleRaceChoice(sp, payload.colonyId(), payload.choice()));
    }

    // ------------------------------------------------------------------
    // Diplomacy Stage 1
    // ------------------------------------------------------------------

    /** S2C: the full Diplomacy-tab snapshot. The body is a CompoundTag
     *  built server-side by {@code DiplomacyManager.buildSnapshot} — the
     *  client renders strings and never computes anything itself. */
    public record DiplomacySnapshotPayload(net.minecraft.nbt.CompoundTag data)
            implements CustomPacketPayload {
        public static final Type<DiplomacySnapshotPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "diplomacy_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DiplomacySnapshotPayload> CODEC =
                StreamCodec.of(
                        (buf, p) -> buf.writeNbt(p.data),
                        buf -> new DiplomacySnapshotPayload((net.minecraft.nbt.CompoundTag) buf.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: a Diplomacy-tab action. Server validates everything and
     *  re-sends the snapshot (the barrier-menu live-refresh pattern). */
    public record DiplomacyActionPayload(byte action, String factionId, String dealId,
                                         boolean flag)
            implements CustomPacketPayload {
        public static final byte ACTION_OPEN_TAB = 0;
        public static final byte ACTION_SEND_ENVOY = 1;   // flag = with gift
        public static final byte ACTION_ACCEPT_DEAL = 2;
        public static final byte ACTION_DELIVER = 3;
        public static final byte ACTION_COLLECT = 4;
        /** Clear the faction's "!" new-offer badge (tab clicked). */
        public static final byte ACTION_MARK_SEEN = 5;
        public static final Type<DiplomacyActionPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "diplomacy_action"));
        public static final StreamCodec<ByteBuf, DiplomacyActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE, DiplomacyActionPayload::action,
                ByteBufCodecs.STRING_UTF8, DiplomacyActionPayload::factionId,
                ByteBufCodecs.STRING_UTF8, DiplomacyActionPayload::dealId,
                ByteBufCodecs.BOOL, DiplomacyActionPayload::flag,
                DiplomacyActionPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: a faction envoy was right-clicked — open the accept/decline
     *  dialogue for the named faction. */
    public record OpenFactionEnvoyPayload(int entityId, String factionId, String factionName)
            implements CustomPacketPayload {
        public static final Type<OpenFactionEnvoyPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_faction_envoy"));
        public static final StreamCodec<ByteBuf, OpenFactionEnvoyPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenFactionEnvoyPayload::entityId,
                ByteBufCodecs.STRING_UTF8, OpenFactionEnvoyPayload::factionId,
                ByteBufCodecs.STRING_UTF8, OpenFactionEnvoyPayload::factionName,
                OpenFactionEnvoyPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: the faction-envoy dialogue's Accept/Decline. */
    public record FactionEnvoyResponsePayload(int entityId, boolean accepted)
            implements CustomPacketPayload {
        public static final Type<FactionEnvoyResponsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "faction_envoy_response"));
        public static final StreamCodec<ByteBuf, FactionEnvoyResponsePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FactionEnvoyResponsePayload::entityId,
                ByteBufCodecs.BOOL, FactionEnvoyResponsePayload::accepted,
                FactionEnvoyResponsePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: OPEN relations reached the ALLIED band — pop the alliance
     *  Accept/Decline prompt. */
    public record OpenAlliancePromptPayload(String factionId, String factionName,
                                            double standing)
            implements CustomPacketPayload {
        public static final Type<OpenAlliancePromptPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_alliance_prompt"));
        public static final StreamCodec<ByteBuf, OpenAlliancePromptPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenAlliancePromptPayload::factionId,
                ByteBufCodecs.STRING_UTF8, OpenAlliancePromptPayload::factionName,
                ByteBufCodecs.DOUBLE, OpenAlliancePromptPayload::standing,
                OpenAlliancePromptPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: the alliance prompt's Accept ({@code accepted=true}) or
     *  Decline. Server re-validates state + standing before mutating. */
    public record AllianceResponsePayload(String factionId, boolean accepted)
            implements CustomPacketPayload {
        public static final Type<AllianceResponsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "alliance_response"));
        public static final StreamCodec<ByteBuf, AllianceResponsePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, AllianceResponsePayload::factionId,
                ByteBufCodecs.BOOL, AllianceResponsePayload::accepted,
                AllianceResponsePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: open the lend-citizen picker. The body is an NBT compound
     *  (factionId, dealId, title, count, skillName, minLevel, duration
     *  days, boost + a candidates list of {id, name, level}) — built by
     *  {@link #sendLendPickerTo}. */
    public record OpenLendPickerPayload(net.minecraft.nbt.CompoundTag data)
            implements CustomPacketPayload {
        public static final Type<OpenLendPickerPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_lend_picker"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenLendPickerPayload> CODEC =
                StreamCodec.of(
                        (buf, p) -> buf.writeNbt(p.data),
                        buf -> new OpenLendPickerPayload((net.minecraft.nbt.CompoundTag) buf.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: the picker's confirmed citizen ids. Server re-validates
     *  everything (eligibility, count, offer still live) before the
     *  citizens actually leave. */
    public record LendConfirmPayload(String factionId, String dealId,
                                     List<Integer> citizenIds)
            implements CustomPacketPayload {
        public static final Type<LendConfirmPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "lend_confirm"));
        public static final StreamCodec<ByteBuf, LendConfirmPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LendConfirmPayload::factionId,
                ByteBufCodecs.STRING_UTF8, LendConfirmPayload::dealId,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), LendConfirmPayload::citizenIds,
                LendConfirmPayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client-side delegate for the lend picker. Installed by ClientEvents. */
    public static Consumer<OpenLendPickerPayload> lendPickerClientHandler = payload ->
            LOGGER.info("[TM] lend picker (no client handler installed)");

    /** Build + send the lend-picker snapshot. */
    static void sendLendPickerTo(ServerPlayer player, BossFaction faction, DealSpec spec,
                                 DealSpec.LendCitizens lend,
                                 List<com.minecolonies.api.colony.ICitizenData> candidates) {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putString("factionId", faction.id());
        root.putString("factionName", faction.displayName());
        root.putString("dealId", spec.id());
        root.putString("title", spec.title());
        root.putInt("count", lend.count());
        root.putString("skillName", lend.skill().name());
        root.putInt("minLevel", lend.minLevel());
        root.putInt("days", (int) (lend.durationTicks() / 24_000L));
        root.putInt("boost", lend.skillBoost());
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (com.minecolonies.api.colony.ICitizenData candidate : candidates) {
            net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
            c.putInt("id", candidate.getId());
            c.putString("name", candidate.getName());
            c.putInt("level", candidate.getCitizenSkillHandler().getLevel(lend.skill()));
            list.add(c);
        }
        root.put("candidates", list);
        PacketDistributor.sendToPlayer(player, new OpenLendPickerPayload(root));
    }

    private static void onOpenLendPicker(OpenLendPickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> lendPickerClientHandler.accept(payload));
    }

    private static void onLendConfirm(LendConfirmPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            DiplomacyManager.handleLendConfirm(sp, payload.factionId(), payload.dealId(),
                    payload.citizenIds());
            sendDiplomacySnapshot(sp);
        });
    }

    /** Client-side delegate for the alliance prompt. Installed by ClientEvents. */
    public static Consumer<OpenAlliancePromptPayload> alliancePromptClientHandler = payload ->
            LOGGER.info("[TM] alliance prompt (no client handler): {}", payload.factionId());

    private static void onOpenAlliancePrompt(OpenAlliancePromptPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> alliancePromptClientHandler.accept(payload));
    }

    private static void onAllianceResponse(AllianceResponsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() ->
                DiplomacyManager.handleAllianceResponse(sp, payload.factionId(), payload.accepted()));
    }

    /** Client-side delegate for the diplomacy snapshot (opens/refreshes
     *  the Diplomacy screen). Installed by ClientEvents. */
    public static Consumer<DiplomacySnapshotPayload> diplomacyClientHandler = payload ->
            LOGGER.info("[TM] diplomacy snapshot (no client handler installed)");

    /** Client-side delegate for the faction-envoy dialogue. */
    public static Consumer<OpenFactionEnvoyPayload> factionEnvoyClientHandler = payload ->
            LOGGER.info("[TM] faction envoy dialogue (no client handler): {}", payload.factionId());

    static void sendDiplomacySnapshot(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new DiplomacySnapshotPayload(DiplomacyManager.buildSnapshot(player)));
    }

    private static void onDiplomacySnapshot(DiplomacySnapshotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> diplomacyClientHandler.accept(payload));
    }

    private static void onOpenFactionEnvoy(OpenFactionEnvoyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> factionEnvoyClientHandler.accept(payload));
    }

    private static void onFactionEnvoyResponse(FactionEnvoyResponsePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() ->
                DiplomacyManager.handleFactionEnvoyResponse(sp, payload.entityId(), payload.accepted()));
    }

    private static void onDiplomacyAction(DiplomacyActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> {
            String failure = null;
            BossFaction faction = BossFaction.byId(payload.factionId());
            switch (payload.action()) {
                case DiplomacyActionPayload.ACTION_OPEN_TAB -> { }
                case DiplomacyActionPayload.ACTION_SEND_ENVOY -> failure = faction == null
                        ? "unknown faction" : DiplomacyManager.sendEnvoy(sp, faction, payload.flag());
                case DiplomacyActionPayload.ACTION_ACCEPT_DEAL -> failure = faction == null
                        ? "unknown faction" : DiplomacyManager.acceptDeal(sp, faction, payload.dealId());
                case DiplomacyActionPayload.ACTION_DELIVER -> failure = faction == null
                        ? "unknown faction" : DiplomacyManager.deliver(sp, faction);
                case DiplomacyActionPayload.ACTION_COLLECT -> failure = faction == null
                        ? "unknown faction" : DiplomacyManager.collect(sp, faction);
                case DiplomacyActionPayload.ACTION_MARK_SEEN ->
                        DiplomacyManager.markOffersSeen(sp, payload.factionId());
                default -> { }
            }
            if (failure != null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(failure)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            sendDiplomacySnapshot(sp); // live refresh, every action
        });
    }
}
