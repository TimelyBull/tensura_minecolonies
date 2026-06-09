package com.example.examplemod;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Client-side handler for {@link Networking.OpenRacePickerPayload}.
 *
 * Approach to the MC-town-hall-UI collision (from the investigation):
 * we use VANILLA SCREEN-STACKING (parent-pointer) rather than a fixed
 * tick delay. The picker is opened with whatever screen is current as
 * its {@code parent} — closing the picker (choice or ESC) returns to
 * that parent.
 *
 * Timing nuance: MC's {@code CreateColonyMessage} sends both
 * {@code ColonyCreatedModEvent} (our hook) AND
 * {@code OpenBuildingUIMessage} (town hall UI) in the same handler
 * invocation, both queued onto the client's network thread within
 * a frame of each other. There's no guarantee which arrives first.
 * We don't need one — both screens-on-screen sequences work correctly
 * via parent-stacking:
 *
 *   - Town hall UI arrives first → picker opens with TH UI as parent.
 *     Picker dismissal returns to TH UI. ✓
 *   - Picker arrives first → picker opens with no parent. Then TH UI
 *     arrives and replaces it. The colony stays pending; the player
 *     can re-engage by right-clicking the town hall (which re-sends
 *     the picker via the right-click hook). ✓
 *
 * To make case 2 disappear too, we defer the screen-set by a single
 * client tick: if MC's TH UI message is in flight, that tick gives it
 * time to install. The deferred handler is also robust against being
 * queued multiple times — repeated payloads just refresh the data.
 */
@OnlyIn(Dist.CLIENT)
public final class RacePickerClientHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int pendingColonyId = -1;
    private static String pendingColonyName = "";
    private static int ticksUntilOpen = 0;

    private RacePickerClientHandler() {}

    /** Installed by {@link ClientEvents#init} into
     *  {@link Networking#racePickerOpenHandler}. */
    public static void handle(Networking.OpenRacePickerPayload payload) {
        // Queue the open for 1 tick later. By then any concurrent
        // OpenBuildingUIMessage from MC will have processed, so the
        // parent-pointer captures MC's TH UI rather than null.
        pendingColonyId   = payload.colonyId();
        pendingColonyName = payload.colonyName();
        ticksUntilOpen    = 1;
    }

    /** Subscribed by {@link ClientEvents#init} to the client tick post.
     *  Cheap when nothing is pending — just an int check. */
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (ticksUntilOpen <= 0) return;
        ticksUntilOpen--;
        if (ticksUntilOpen == 0 && pendingColonyId >= 0) {
            final int colonyId = pendingColonyId;
            final String colonyName = pendingColonyName;
            pendingColonyId = -1;
            pendingColonyName = "";

            Minecraft mc = Minecraft.getInstance();
            // Capture the current screen (MC's town hall window) up front so we
            // can hand it to the vanilla fallback if the BlockUI open fails —
            // the native open() replaces mc.screen as a side effect.
            Screen parent = mc.screen;

            // Native BlockUI window is the primary path; the vanilla screen is
            // kept in the tree purely as a fail-closed safety net (missing
            // BlockUI/MC class, XML parse error, etc.).
            boolean opened = false;
            try {
                opened = WindowRacePicker.tryOpen(colonyId, colonyName);
            } catch (Throwable t) {
                LOGGER.error("[TM] race picker: native BlockUI window failed to open; "
                        + "falling back to the vanilla screen", t);
            }
            if (!opened) {
                mc.setScreen(new RacePickerScreen(parent, colonyId, colonyName));
            }
        }
    }
}
