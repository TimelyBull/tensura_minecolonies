package com.example.examplemod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
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
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,                                  // default: G — unbound in vanilla 1.21.1
            "key.categories.tensura_minecolonies"
    );

    private ClientEvents() {}

    /** Wire up the client-side listeners. Called once from the mod constructor. */
    public static void init(IEventBus modBus) {
        modBus.addListener(ClientEvents::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTickPost);
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
}
