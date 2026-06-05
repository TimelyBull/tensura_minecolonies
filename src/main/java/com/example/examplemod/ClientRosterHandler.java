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
        } else if (mc.screen instanceof ConfirmCollapseScreen confirm) {
            // The confirm dialog is layered over a Screen — typically the
            // RosterScreen, possibly null. The server always pushes a roster
            // refresh after an action (including the "prompt sent" case),
            // and that arrives a moment after the confirm prompt. We must
            // NOT replace mc.screen here, or the confirm dialog would be
            // overwritten by a fresh RosterScreen — exactly what was making
            // the prompt invisible.
            //
            // Instead, update the parent roster's data so it shows the
            // refreshed list when the dialog is dismissed.
            if (confirm.getParent() instanceof RosterScreen rosterParent) {
                rosterParent.setEntries(entries);
            }
            // else: confirm has no roster parent (came from /summongoblin or
            // sneak-click); nothing to refresh, dialog stays open.
        } else {
            mc.setScreen(new RosterScreen(entries));
        }
    }
}
