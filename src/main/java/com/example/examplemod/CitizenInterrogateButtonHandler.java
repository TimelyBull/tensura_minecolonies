package com.example.examplemod;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.citizen.AbstractWindowCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * The citizen-window [Interrogate] side tab — counterplay to the deceit
 * suite (docs/tr-nightmare-integration.md §6; user spec 2026-09-05).
 *
 * Mirror of {@link CitizenTradeButtonHandler}'s native-BlockUI tab: MC's own
 * tab background + our icon, inserted at the bottom of the z-order in the
 * first free nav slot. The tab appears ONLY when this client has been told
 * the citizen is behaving strangely ({@link SuspicionClientHandler} flag —
 * which the server only sends to the citizen's owner), so the button's very
 * existence is part of the tell. Click → C2S
 * {@link Networking.InterrogatePayload}; the SERVER decides dismissal vs
 * truth (unmasking ranks + magicule cost).
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenInterrogateButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAB_ID  = "tm_interrogateTab";
    private static final String ICON_ID = "tm_interrogateIcon";

    private static final ResourceLocation TAB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/tab_left_side3.png");
    /** Reuse the trade icon slot styling; a dedicated icon can replace this
     *  texture later without code changes. */
    private static final ResourceLocation ICON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "textures/gui/modules/trade.png");

    private static final int TAB_W = 32, TAB_H = 26;
    private static final int ICON_W = 20, ICON_H = 20;
    private static final int ICON_DX = 5, ICON_DY = 3;
    // Nav slots below the trade tab's candidates — the two tabs coexist by
    // each probing for the first slot the other hasn't taken (the trade tab
    // registers first; we take the next one down).
    private static final int[] SLOTS = {170, 196, 222, 248};

    private static Field citizenField;

    private CitizenInterrogateButtonHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof com.ldtteam.blockui.BOScreen boScreen)) return;
        BOWindow window;
        try {
            window = boScreen.getWindow();
        } catch (Throwable t) {
            return;
        }
        if (!(window instanceof AbstractWindowCitizen citizenWindow)) return;
        if (citizenWindow.findPaneByID(TAB_ID) != null) return;

        ICitizenDataView citizen = readCitizen(citizenWindow);
        if (citizen == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) return;
        // The gate: only a citizen this client was WARNED about (suspicion
        // flag — server sends those to the owner only).
        if (!SuspicionClientHandler.isFlagged(entity.getUUID())) return;

        final int citizenEntityId = citizen.getEntityId();
        final int tabY = firstFreeSlotY(citizenWindow);

        ButtonImage tab = new ButtonImage();
        tab.setID(TAB_ID);
        tab.setImage(TAB_TEXTURE);
        tab.setSize(TAB_W, TAB_H);
        tab.setPosition(0, tabY);
        tab.setHandler(citizenWindow);

        ButtonImage icon = new ButtonImage();
        icon.setID(ICON_ID);
        icon.setImage(ICON_TEXTURE);
        icon.setSize(ICON_W, ICON_H);
        icon.setPosition(ICON_DX, tabY + ICON_DY);
        icon.setHandler(citizenWindow);

        citizenWindow.addChild(tab, 0);
        citizenWindow.addChild(icon, 1);

        Runnable interrogate = () -> PacketDistributor.sendToServer(
                new Networking.InterrogatePayload(citizenEntityId));
        citizenWindow.registerButton(TAB_ID, interrogate);
        citizenWindow.registerButton(ICON_ID, interrogate);
    }

    /** First nav slot not already occupied by a visible pane (MC's own tabs
     *  or our trade tab, which initialises before us). */
    private static int firstFreeSlotY(AbstractWindowCitizen window) {
        for (int y : SLOTS) {
            if (!slotTaken(window, y)) return y;
        }
        return SLOTS[SLOTS.length - 1];
    }

    private static boolean slotTaken(AbstractWindowCitizen window, int y) {
        for (String id : new String[]{"jobTab", "debugTab", "tm_tradeTab", "tm_releaseTab"}) {
            Pane p = window.findPaneByID(id);
            if (p != null && p.isVisible() && p.getY() == y) return true;
        }
        return false;
    }

    private static ICitizenDataView readCitizen(AbstractWindowCitizen window) {
        try {
            if (citizenField == null) {
                citizenField = AbstractWindowCitizen.class.getDeclaredField("citizen");
                citizenField.setAccessible(true);
            }
            Object v = citizenField.get(window);
            return v instanceof ICitizenDataView c ? c : null;
        } catch (Throwable t) {
            LOGGER.warn("[TM] interrogate tab: could not read AbstractWindowCitizen.citizen", t);
            return null;
        }
    }
}
