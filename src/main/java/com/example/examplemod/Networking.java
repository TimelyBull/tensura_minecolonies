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
        registrar.playToClient(
                RosterResponsePayload.TYPE,
                RosterResponsePayload.CODEC,
                Networking::onRosterResponse
        );
    }

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

    private static void sendRosterTo(ServerPlayer sp) {
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
    // Client handler — log only for Stage C2a. C2b will route to a Screen.
    // ------------------------------------------------------------------

    private static void onRosterResponse(RosterResponsePayload payload, IPayloadContext context) {
        // Registered as playToClient → only fires on the logical client.
        // No client-only classes referenced here so this file stays loadable on the server.
        LOGGER.info("[TM] roster received: {} entries", payload.entries().size());
        for (RosterEntry e : payload.entries()) {
            LOGGER.info("[TM]   - {} ({})  id={}", e.name(), e.modeName(), e.identityId());
        }
    }
}
