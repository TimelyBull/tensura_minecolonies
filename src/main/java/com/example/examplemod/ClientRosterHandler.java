package com.example.examplemod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Routes an incoming RosterResponsePayload — opens a new roster window if none
 * is showing, or refreshes the open one in place (after an action).
 *
 * <p>The roster is now the native BlockUI {@link WindowRoster}; all of the
 * open/refresh routing — including the in-place refresh while a
 * {@link ConfirmCollapseScreen} is layered over it, and the fail-closed
 * fallback to the still-present vanilla {@link RosterScreen} — lives in
 * {@link WindowRoster#route}. This handler is the thin install point hooked
 * into {@link Networking#rosterClientHandler} by {@link ClientEvents#init}.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientRosterHandler {

    private ClientRosterHandler() {}

    public static void handle(List<Networking.RosterEntry> entries) {
        WindowRoster.route(entries);
    }
}
