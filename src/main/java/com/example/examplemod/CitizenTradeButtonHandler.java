package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.citizen.MainWindowCitizen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.WeakHashMap;

/**
 * Citizen-side Trade button — rendered OFF the BlockUI window so it
 * doesn't overlap MineColonies' citizen info screen.
 *
 * <p><b>Why a vanilla overlay instead of a BlockUI child widget.</b>
 * BOScreen's render() does NOT call {@code super.render}, and its
 * mouseClicked() forwards directly to the BOWindow without consulting
 * the screen's children list. Vanilla widgets added via
 * {@code event.addListener} therefore get neither drawn nor input
 * events. Additionally, BlockUI's {@code View.childIsVisible} clips any
 * child whose position lies outside the parent window's interior — so
 * we can't even use a BlockUI {@code ButtonImage} positioned off-window.
 *
 * <p>Approach: draw a vanilla Button via {@link ScreenEvent.Render.Post}
 * (fires AFTER BOScreen finishes drawing) at screen-pixel coords in
 * the top-right corner of the BOScreen. Intercept clicks via
 * {@link ScreenEvent.MouseButtonPressed.Pre} with a manual bounds
 * check; if the click falls on our button, fire the trade payload and
 * cancel the event so BOScreen never sees it.
 *
 * <p>Per-screen state tracks the currently-eligible button (one button
 * per open citizen window). The state evicts itself when the screen
 * closes via the WeakHashMap.
 *
 * <p>Only shows for citizens with a merchant-capable {@link RaceTag}
 * (GOBLIN / LIZARDMAN / DWARF — orc excluded).
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenTradeButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Button geometry — upper-right quadrant of the screen, biased
     *  toward the right border (button's center sits at ~75% across,
     *  ~20% down), so it's clearly inside the upper-right quadrant
     *  and visibly closer to the right edge than to screen center.
     *  Sized so the label "Trade" fits comfortably with the vanilla
     *  button textures. */
    private static final int BUTTON_W = 70;
    private static final int BUTTON_H = 20;

    /** Per-open-screen state. WeakHashMap so a closed screen evicts
     *  automatically without us having to hook close events. */
    private static final WeakHashMap<BOScreen, ScreenState> STATES = new WeakHashMap<>();

    private static final class ScreenState {
        final int citizenEntityId;
        final Button button;
        ScreenState(int citizenEntityId, Button button) {
            this.citizenEntityId = citizenEntityId;
            this.button = button;
        }
    }

    private CitizenTradeButtonHandler() {}

    // ----- Hooks (wired in ClientEvents) -------------------------------

    /** Fires when a screen finishes initialising. We resolve the citizen,
     *  determine eligibility, and stash a Button instance for the render
     *  hook to draw. Click handling goes through {@link #onMouseClickedPre}. */
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BOScreen boScreen)) {
            STATES.remove(event.getScreen());
            return;
        }
        BOWindow window;
        try {
            window = boScreen.getWindow();
        } catch (Throwable t) {
            STATES.remove(boScreen);
            return;
        }
        if (!(window instanceof MainWindowCitizen mwc)) {
            STATES.remove(boScreen);
            return;
        }

        ICitizenDataView citizen;
        try {
            citizen = mwc.getCitizen();
        } catch (Throwable t) {
            LOGGER.warn("[TM] citizen trade button: MainWindowCitizen.getCitizen threw", t);
            STATES.remove(boScreen);
            return;
        }
        if (citizen == null) {
            STATES.remove(boScreen);
            return;
        }

        // The citizen entity must be loaded client-side so we can look
        // up the RaceTag. If not (opening from colony overview while
        // far away), no button — the player has no way to know what
        // race they are from the UI alone, and we can't trust the
        // server-side validation without it.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { STATES.remove(boScreen); return; }
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) { STATES.remove(boScreen); return; }
        RaceTag tag = RaceTagClientStore.get(entity.getUUID());
        if (tag == null) { STATES.remove(boScreen); return; }
        if (tag.race() == Race.ORC) { STATES.remove(boScreen); return; }

        final int citizenEntityId = citizen.getEntityId();
        Button button = Button.builder(
                Component.literal("Trade").withStyle(ChatFormatting.YELLOW),
                b -> sendOpenTrade(citizenEntityId))
                // Coords are recomputed on every render so window
                // resize doesn't desync; initial placeholder here.
                .bounds(0, 0, BUTTON_W, BUTTON_H)
                .build();
        STATES.put(boScreen, new ScreenState(citizenEntityId, button));
    }

    /** Draw the button as an overlay AFTER BOScreen finishes rendering. */
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;
        ScreenState state = STATES.get(boScreen);
        if (state == null) return;

        // Upper-right quadrant, biased toward the right border. The
        // button's CENTER sits at (0.75 × screen width, 0.20 × screen
        // height). With screen-width 400 GUI px (default 1080p × scale
        // 3), that puts the center near x=300, y=72 — clearly in the
        // right half (300 vs. midpoint 200) and clearly in the upper
        // half (72 vs. midpoint 180).
        int x = (boScreen.width * 3 / 4) - (BUTTON_W / 2);
        int y = (boScreen.height / 5) - (BUTTON_H / 2);
        state.button.setX(x);
        state.button.setY(y);

        GuiGraphics gfx = event.getGuiGraphics();
        state.button.render(gfx, event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    /** Intercept clicks on our button before BOScreen swallows them. */
    public static void onMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != InputConstants.MOUSE_BUTTON_LEFT) return;
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;
        ScreenState state = STATES.get(boScreen);
        if (state == null) return;

        double mx = event.getMouseX();
        double my = event.getMouseY();
        Button btn = state.button;
        if (mx >= btn.getX() && mx < btn.getX() + btn.getWidth()
                && my >= btn.getY() && my < btn.getY() + btn.getHeight()) {
            btn.onClick(mx, my);
            event.setCanceled(true);
        }
    }

    private static void sendOpenTrade(int citizenEntityId) {
        PacketDistributor.sendToServer(
                new Networking.OpenCitizenTradePayload(citizenEntityId));
    }
}
