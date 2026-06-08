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
        registrar.playToClient(
                SyncBeastTagPayload.TYPE,
                SyncBeastTagPayload.CODEC,
                Networking::onSyncBeastTag
        );
        // Stage L3 polish — trigger a named GeckoLib animation on the
        // shadow entity rendered for a beast-guard citizen. Used by
        // BeastGuardCombatAI.doAttack to play the leap animation
        // (the leap MOVEMENT is server-side; the animation can only
        // run on the client, which has no other way to detect it).
        registrar.playToClient(
                TriggerSpiderAnimPayload.TYPE,
                TriggerSpiderAnimPayload.CODEC,
                Networking::onTriggerSpiderAnim
        );
        registrar.playToServer(
                OpenCitizenTradePayload.TYPE,
                OpenCitizenTradePayload.CODEC,
                Networking::onOpenCitizenTrade
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
    public static Consumer<List<RosterEntry>> rosterClientHandler = entries -> {
        LOGGER.info("[TM] roster (no client handler installed): {} entries", entries.size());
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

    /** Client-side delegate for beast tag sync — parallel to
     *  {@link #raceTagClientHandler}. Installed by ClientEvents. */
    public static Consumer<SyncBeastTagPayload> beastTagClientHandler = payload -> {
        LOGGER.info("[TM] beast tag (no client handler) entity={} present={}",
                payload.entityUuid(), payload.present());
    };

    /** Client-side delegate for trigger-spider-anim payloads. Installed
     *  by ClientEvents to forward to the spider render handler. Default
     *  is a logging stub so the field is loadable on the server JVM. */
    public static Consumer<TriggerSpiderAnimPayload> triggerSpiderAnimHandler = payload -> {
        LOGGER.info("[TM] trigger spider anim (no client handler) entity={} ctrl={} anim={}",
                payload.entityUuid(), payload.controllerName(), payload.animName());
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

    /** S2C: server returns the player's roster entries. */
    public record RosterResponsePayload(List<RosterEntry> entries) implements CustomPacketPayload {
        public static final Type<RosterResponsePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "roster_response"));

        public static final StreamCodec<ByteBuf, RosterResponsePayload> CODEC =
                StreamCodec.composite(
                        RosterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                        RosterResponsePayload::entries,
                        RosterResponsePayload::new
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
    public record OpenEnvoyDialoguePayload(int entityId, byte memberId, int colonyId, byte conditionMask)
            implements CustomPacketPayload {
        public static final Type<OpenEnvoyDialoguePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_envoy_dialogue"));
        public static final StreamCodec<ByteBuf, OpenEnvoyDialoguePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenEnvoyDialoguePayload::entityId,
                ByteBufCodecs.BYTE,    OpenEnvoyDialoguePayload::memberId,
                ByteBufCodecs.VAR_INT, OpenEnvoyDialoguePayload::colonyId,
                ByteBufCodecs.BYTE,    OpenEnvoyDialoguePayload::conditionMask,
                OpenEnvoyDialoguePayload::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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
                                     UUID identityId, byte raceId, byte[] variant)
            implements CustomPacketPayload {

        public static final Type<SyncRaceTagPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "sync_goblin_tag"));

        public static final StreamCodec<ByteBuf, SyncRaceTagPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,         SyncRaceTagPayload::entityUuid,
                ByteBufCodecs.BOOL,            SyncRaceTagPayload::present,
                UUIDUtil.STREAM_CODEC,         SyncRaceTagPayload::identityId,
                ByteBufCodecs.BYTE,            SyncRaceTagPayload::raceId,
                ByteBufCodecs.byteArray(256),  SyncRaceTagPayload::variant,
                SyncRaceTagPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static SyncRaceTagPayload of(UUID entityUuid, RaceTag tag) {
            return new SyncRaceTagPayload(entityUuid, true,
                    tag.identityId(), (byte) tag.race().getId(), tag.encodeVariant());
        }

        public static SyncRaceTagPayload clear(UUID entityUuid) {
            return new SyncRaceTagPayload(entityUuid, false,
                    new UUID(0L, 0L), (byte) 0, new byte[0]);
        }
    }

    /** Beast-tag sync — parallel to {@link SyncRaceTagPayload} but for
     *  {@link BeastTag}. Stage 1 carries no per-citizen variant data
     *  (the spider has no per-instance appearance fields), so the
     *  payload is tighter than the race-tag one. */
    public record SyncBeastTagPayload(UUID entityUuid, boolean present,
                                      UUID identityId, byte beastId)
            implements CustomPacketPayload {

        public static final Type<SyncBeastTagPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "sync_beast_tag"));

        public static final StreamCodec<ByteBuf, SyncBeastTagPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,  SyncBeastTagPayload::entityUuid,
                ByteBufCodecs.BOOL,     SyncBeastTagPayload::present,
                UUIDUtil.STREAM_CODEC,  SyncBeastTagPayload::identityId,
                ByteBufCodecs.BYTE,     SyncBeastTagPayload::beastId,
                SyncBeastTagPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static SyncBeastTagPayload of(UUID entityUuid, BeastTag tag) {
            return new SyncBeastTagPayload(entityUuid, true,
                    tag.identityId() != null ? tag.identityId() : new UUID(0L, 0L),
                    (byte) tag.beast().getId());
        }

        public static SyncBeastTagPayload clear(UUID entityUuid) {
            return new SyncBeastTagPayload(entityUuid, false,
                    new UUID(0L, 0L), (byte) 0);
        }
    }

    /** S2C — instruct the client renderer to call
     *  {@code shadow.triggerAnim(controllerName, animName)} on the
     *  shadow entity rendered for the citizen identified by
     *  {@code entityUuid}.
     *
     *  <p>Used by the beast-guard combat AI to play the spider's leap
     *  animation when the server-side combat code decides to pounce.
     *  The animation is purely visual — GeckoLib's triggerableAnims
     *  run a one-shot animation independent of the entity's logical
     *  state, so misfires are harmless (worst case: extra flailing).
     *
     *  <p>String-based rather than enum-based to keep the payload
     *  generic — future beasts will use the same payload with their
     *  own controller / anim names.
     */
    public record TriggerSpiderAnimPayload(UUID entityUuid,
                                            String controllerName,
                                            String animName)
            implements CustomPacketPayload {

        public static final Type<TriggerSpiderAnimPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "trigger_spider_anim"));

        public static final StreamCodec<ByteBuf, TriggerSpiderAnimPayload> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,    TriggerSpiderAnimPayload::entityUuid,
                ByteBufCodecs.STRING_UTF8, TriggerSpiderAnimPayload::controllerName,
                ByteBufCodecs.STRING_UTF8, TriggerSpiderAnimPayload::animName,
                TriggerSpiderAnimPayload::new
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S — player clicks the Trade button on a citizen's MC window.
     *  Server resolves the citizen's {@code RaceTag} → identity, validates
     *  ownership and merchant-capability, then opens the merchant screen.
     *  Stage 1: opens trade only when the citizen's subordinate body is
     *  also currently in the world (i.e. summon-back-first flow).
     *  Future: reconstruct a transient merchant from the snapshot. */
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

        LOGGER.info("[TM] roster: sending {} entries to {}", entries.size(), sp.getName().getString());
        PacketDistributor.sendToPlayer(sp, new RosterResponsePayload(entries));
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
        context.enqueueWork(() -> rosterClientHandler.accept(payload.entries()));
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

    private static void onSyncBeastTag(SyncBeastTagPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> beastTagClientHandler.accept(payload));
    }

    private static void onTriggerSpiderAnim(TriggerSpiderAnimPayload payload, IPayloadContext context) {
        // Bounce to the client main thread — GeckoLib's animation cache
        // is touched by render, which runs on main.
        context.enqueueWork(() -> triggerSpiderAnimHandler.accept(payload));
    }

    private static void onOpenCitizenTrade(OpenCitizenTradePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleOpenCitizenTrade(sp, payload.citizenEntityId()));
    }

    private static void onOpenRacePicker(OpenRacePickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> racePickerOpenHandler.accept(payload));
    }

    private static void onRaceChoice(RaceChoicePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sp)) return;
        context.enqueueWork(() -> ExampleMod.handleRaceChoice(sp, payload.colonyId(), payload.choice()));
    }
}
