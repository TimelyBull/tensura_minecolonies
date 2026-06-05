package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Routes an incoming RosterResponsePayload to either:
 *   - a NEW {@link RosterScreen} if none is open (initial keybind press), or
 *   - the EXISTING {@link RosterScreen} to refresh its list (after an action).
 *
 * Installed into {@link Networking#rosterClientHandler} by {@link ClientEvents#init}.
 * Loads only on the client thanks to the @OnlyIn marker + lazy class loading
 * (only ClientEvents on the client side ever references this class).
 */
@OnlyIn(Dist.CLIENT)
public final class ClientRosterHandler {

    private ClientRosterHandler() {}

    public static void handle(List<Networking.RosterEntry> entries) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RosterScreen open) {
            open.setEntries(entries);
        } else {
            mc.setScreen(new RosterScreen(entries));
        }
    }
}
