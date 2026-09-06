package com.example.examplemod;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
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
 * The colonist-form "Release" tab — deceit counterplay (user spec
 * 2026-09-05): an EX-owner viewing their STOLEN subordinate working in the
 * thief's colony can attempt a release from the citizen window (they can't
 * change anything else there — it isn't their colony). BlockUI side-tab
 * mirror of {@link CitizenInterrogateButtonHandler}.
 *
 * Client gate: the citizen is a RACE citizen (RaceTag synced for rendering)
 * AND the local player does NOT own the colony (via the client colony view;
 * unresolvable → shown, the server rejects wrong claimants anyway with
 * "They were never yours to free.").
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenReleaseButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAB_ID  = "tm_releaseTab";
    private static final String ICON_ID = "tm_releaseIcon";

    private static final ResourceLocation TAB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/tab_left_side3.png");
    private static final ResourceLocation ICON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "textures/gui/modules/trade.png");

    private static final int TAB_W = 32, TAB_H = 26;
    private static final int ICON_W = 20, ICON_H = 20;
    private static final int ICON_DX = 5, ICON_DY = 3;
    private static final int[] SLOTS = {170, 196, 222, 248, 274};

    private static Field citizenField;

    private CitizenReleaseButtonHandler() {}

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
        if (mc.level == null || mc.player == null) return;
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) return;
        // Race citizens only (they are the only ones with identities).
        if (RaceTagClientStore.get(entity.getUUID()) == null) return;
        // Not our own colony — a Release only makes sense on someone else's.
        try {
            IColonyView view = IColonyManager.getInstance()
                    .getColonyView(citizen.getColonyId(), mc.level.dimension());
            if (view != null && mc.player.getUUID().equals(view.getPermissions().getOwner())) {
                return;
            }
        } catch (Throwable ignored) {
            // Unresolvable view — show the tab; the server enforces.
        }

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

        Runnable release = () -> PacketDistributor.sendToServer(
                new Networking.ReleasePayload(citizenEntityId));
        citizenWindow.registerButton(TAB_ID, release);
        citizenWindow.registerButton(ICON_ID, release);
    }

    private static int firstFreeSlotY(AbstractWindowCitizen window) {
        for (int y : SLOTS) {
            if (!slotTaken(window, y)) return y;
        }
        return SLOTS[SLOTS.length - 1];
    }

    private static boolean slotTaken(AbstractWindowCitizen window, int y) {
        for (String id : new String[]{"jobTab", "debugTab", "tm_tradeTab", "tm_interrogateTab"}) {
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
            LOGGER.warn("[TM] release tab: could not read AbstractWindowCitizen.citizen", t);
            return null;
        }
    }
}
