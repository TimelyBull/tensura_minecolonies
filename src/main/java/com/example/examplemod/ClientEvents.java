package com.example.examplemod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only event handlers. Loaded conditionally from {@link ExampleMod}'s
 * constructor via {@code FMLEnvironment.dist.isClient()} so the server JVM
 * never touches client classes.
 *
 * Stage C2a:
 *   - Registers the "Open Goblin Roster" keybind (default G, unbound in vanilla).
 *   - On press, fires {@link Networking.RequestRosterPayload} to the server.
 *   - The S2C response is logged by {@link Networking#onRosterResponse}.
 *
 * Stage C2b will replace the log-only handler with a Screen.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEvents {

    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            "key.tensura_minecolonies.open_roster",
            KeyConflictContext.IN_GAME,                       // disabled while a Screen is open
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,                                  // default: G — unbound in vanilla 1.21.1
            "key.categories.tensura_minecolonies"
    );

    private ClientEvents() {}

    /** Wire up the client-side listeners. Called once from the mod constructor. */
    public static void init(IEventBus modBus) {
        modBus.addListener(ClientEvents::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTickPost);

        // Stage C2b — install the Screen-opening handler. Replaces the
        // log-only default in Networking.rosterClientHandler.
        Networking.rosterClientHandler = ClientRosterHandler::handle;

        // Magicule cost — install the collapse-confirm prompt handler.
        Networking.confirmCollapseClientHandler = ConfirmCollapseHandler::handle;

        // Stage F1 — race-tag client mirror. Server sends SyncRaceTagPayload
        // on send/summon and on player tracking start; the store keeps a
        // UUID → RaceTag map that the renderers (goblin / orc / future)
        // consult during render to pick the right model.
        Networking.raceTagClientHandler = RaceTagClientStore::onPayload;
        Networking.beastTagClientHandler = BeastTagClientStore::onPayload;

        // Stage L3 polish — server-driven GeckoLib triggers for beast
        // shadows (e.g. spider leap animation). The render handler
        // owns the shadow pool and is the only thing that can locate
        // the right shadow for a given citizen UUID.
        Networking.triggerSpiderAnimHandler = KnightSpiderCitizenRenderHandler::onTriggerAnimPayload;

        // Stage B picker — server sends OpenRacePickerPayload when a
        // colony needs the race choice. Client defers screen-open by 1
        // tick so MC's town hall UI (sent in the same network burst)
        // captures as the picker's parent for clean dismissal.
        Networking.racePickerOpenHandler = RacePickerClientHandler::handle;
        NeoForge.EVENT_BUS.addListener(RacePickerClientHandler::onClientTickPost);

        // Envoy Stage 2 — open the diplomacy dialogue when the server
        // confirms a right-click on an envoy. memberId on the wire
        // decodes via ColonyMember.byId on this side.
        Networking.envoyDialogueClientHandler = payload -> {
            ColonyMember member = ColonyMember.byId(payload.memberId() & 0xFF);
            java.util.EnumSet<EnvoyCondition> conditions =
                    EnvoyCondition.fromMask(payload.conditionMask());
            net.minecraft.client.Minecraft.getInstance()
                    .setScreen(new EnvoyDialogueScreen(payload.entityId(), member, conditions));
        };

        // Stage F3 — render-path interception. Cancels the default citizen
        // render and draws the goblin model for tagged citizens via
        // GoblinCitizenRenderer; untagged citizens pass through to
        // MineColonies' RenderBipedCitizen unchanged.
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class,
                GoblinCitizenRenderHandler::onRenderLivingPre);

        // Race-system Stage 1 — orc render path. Cancels the event for
        // ORC-tagged citizens and renders a shadow OrcEntity in their
        // place. GOBLIN-tagged citizens fall through to the goblin
        // handler above; untagged citizens fall through to MineColonies.
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class,
                OrcCitizenRenderHandler::onRenderLivingPre);

        // LIZARDMAN — same shadow-entity GeckoLib pattern as orc.
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class,
                LizardmanCitizenRenderHandler::onRenderLivingPre);

        // DWARF — same vanilla biped + PlayerModel overlay pattern as goblin.
        // SCALE = 0.5 is applied server-side at materialisation; the renderer
        // multiplies by entity.getScale() automatically.
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class,
                DwarfCitizenRenderHandler::onRenderLivingPre);

        // KNIGHT SPIDER (beast) — shadow-entity GeckoLib pattern. Gated by
        // BeastTag rather than RaceTag. Citizen body keeps humanoid hitbox
        // (SCALE 1.0); the spider visual is decoration only.
        NeoForge.EVENT_BUS.addListener(RenderLivingEvent.Pre.class,
                KnightSpiderCitizenRenderHandler::onRenderLivingPre);

        // Cleanup hooks: drop the mirror entry when an entity leaves the
        // client world (chunk unload, discard, dimension change), and wipe
        // the whole map on disconnect so a relog or world-switch starts
        // from a clean state.
        NeoForge.EVENT_BUS.addListener(ClientEvents::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientLoggingOut);

        // Stage L2 — trade button FLIP: removed from subordinate UI,
        // moved to the CITIZEN window (MainWindowCitizen). The
        // SubordinateTradeButtonHandler is no longer registered; the
        // file stays for now in case Stage 2 wants to re-enable it.
        NeoForge.EVENT_BUS.addListener(CitizenTradeButtonHandler::onScreenInitPost);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_ROSTER);
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        // consumeClick() returns true once per discrete press; the while-loop
        // drains buffered presses if multiple landed in one tick.
        while (OPEN_ROSTER.consumeClick()) {
            PacketDistributor.sendToServer(new Networking.RequestRosterPayload());
        }
    }

    private static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        // Cheap: hashmap remove on every entity-leave. Could narrow to
        // AbstractEntityCitizen, but the cost difference is negligible.
        java.util.UUID uuid = event.getEntity().getUUID();
        RaceTagClientStore.removeForEntity(uuid);
        // Drop the per-citizen orc shadow too — the pool is keyed by
        // citizen UUID; same-tick cleanup keeps the pool bounded.
        OrcCitizenRenderHandler.removeForEntity(uuid);
        LizardmanCitizenRenderHandler.removeForEntity(uuid);
        KnightSpiderCitizenRenderHandler.removeForEntity(uuid);
        BeastTagClientStore.removeForEntity(uuid);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RaceTagClientStore.clearAll();
        // Drop the cached goblin renderer too — its Context references the
        // outgoing world's resource manager and baked models. Next session
        // will rebuild lazily against fresh ones.
        GoblinCitizenRenderHandler.invalidate();
        OrcCitizenRenderHandler.invalidate();
        LizardmanCitizenRenderHandler.invalidate();
        DwarfCitizenRenderHandler.invalidate();
        KnightSpiderCitizenRenderHandler.invalidate();
        BeastTagClientStore.clearAll();
    }
}
