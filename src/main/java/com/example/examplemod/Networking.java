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

    /** One row in the roster reply. {@code modeByte}: 0 = SUBORDINATE, 1 = IN_COLONY. */
    public record RosterEntry(UUID identityId, String name, byte modeByte) {
        public static final StreamCodec<ByteBuf, RosterEntry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC,         RosterEntry::identityId,
                ByteBufCodecs.STRING_UTF8,     RosterEntry::name,
                ByteBufCodecs.BYTE,            RosterEntry::modeByte,
                RosterEntry::new
        );

        public static byte encodeMode(GoblinIdentitySavedData.Mode mode) {
            return (byte) (mode == GoblinIdentitySavedData.Mode.IN_COLONY ? 1 : 0);
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
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(level);

        List<RosterEntry> entries = new ArrayList<>();
        for (GoblinIdentitySavedData.GoblinIdentity identity : saved.all()) {
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
            entries.add(new RosterEntry(identity.identityId, name, RosterEntry.encodeMode(identity.mode)));
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
}
