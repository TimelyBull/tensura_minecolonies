package com.example.examplemod;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * Client-side handler for the S2C collapse-confirmation prompt. Opens a
 * {@link ConfirmCollapseScreen} layered on top of whatever Screen is
 * currently open (typically the {@link RosterScreen}; possibly null if the
 * prompt was triggered from {@code /summongoblin} or the sneak-right-click).
 *
 * Installed into {@link Networking#confirmCollapseClientHandler} by
 * {@link ClientEvents#init}.
 */
@OnlyIn(Dist.CLIENT)
public final class ConfirmCollapseHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfirmCollapseHandler() {}

    public static void handle(Networking.OpenCollapseConfirmPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen; // could be RosterScreen, or null (in-game)
        LOGGER.info("[TM] opening ConfirmCollapseScreen (parent = {})",
                parent == null ? "<none>" : parent.getClass().getSimpleName());
        mc.setScreen(new ConfirmCollapseScreen(
                parent,
                payload.identityId(),
                payload.goblinName(),
                payload.cost(),
                payload.currentMagicule()
        ));
    }
}
