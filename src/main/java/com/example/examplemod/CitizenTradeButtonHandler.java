package com.example.examplemod;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.BOScreen;
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
 * Citizen-side Trade button, rendered as a NATIVE BlockUI tab in the
 * MineColonies citizen window's left tab strip (matching the
 * inventory / happiness / family tabs).
 *
 * <p><b>Why a real tab now (was a vanilla overlay).</b> The earlier
 * implementation drew a vanilla {@code Button} floating over the BlockUI
 * window via {@code ScreenEvent.Render.Post} + a manual click hit-test in
 * {@code ScreenEvent.MouseButtonPressed.Pre}, because BlockUI's
 * {@code BOScreen} doesn't render vanilla widgets or route input to them,
 * and clips BlockUI children placed outside the window interior (see
 * {@code docs/decisions.md} ~1018–1054). Those blockers only apply to
 * off-window placement / vanilla widgets — a BlockUI {@link ButtonImage}
 * added <i>inside</i> the window renders and receives clicks through
 * BlockUI's own pipeline, identical to MC's own tabs. So the overlay is
 * replaced with an in-window tab.
 *
 * <p><b>How it matches MC's tabs.</b> MC's nav strip (citizen/nav.xml)
 * builds each tab as a {@code ButtonImage} tab background
 * ({@code minecolonies:textures/gui/modules/tab_left_side*.png}, 32×26 at
 * x=0) with a 20×20 icon button layered on top at {@code (5, tabY+3)}.
 * We add the same pair, reusing MC's tab texture and shipping our own
 * {@code trade.png} icon, and route the click exactly as MC does — a
 * {@code registerButton(id, runnable)} handler keyed by the button id.
 *
 * <p><b>Slot.</b> Existing visible tabs sit at y = 36/64/92/118/144
 * (main/request/inventory/happiness/family); {@code jobTab} (170) and
 * {@code debugTab} (196) are visible only when applicable. The trade tab
 * takes the first free slot at/after 170 so it never overlaps.
 *
 * <p><b>Eligibility.</b> Only merchant race citizens (GOBLIN / LIZARDMAN /
 * DWARF — NOT orc, NOT plain colonists) get the tab. The trade flow it
 * fires ({@link Networking.OpenCitizenTradePayload}) is unchanged.
 *
 * <p>Added on {@code ScreenEvent.Init.Post} for every
 * {@link AbstractWindowCitizen} sub-page (main / requests / inventory /
 * happiness / family / job), so the tab is present on each — re-added per
 * window build, which is fine because each tab switch builds a new window.
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenTradeButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAB_ID  = "tm_tradeTab";
    private static final String ICON_ID = "tm_tradeIcon";

    /** Reuse MC's own tab background so the tab is pixel-identical to the
     *  native tabs. side3 matches the lower nav region (job/debug use it). */
    private static final ResourceLocation TAB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/tab_left_side3.png");
    /** Our shipped 20×20 trade icon. */
    private static final ResourceLocation ICON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "textures/gui/modules/trade.png");

    // Tab geometry mirrors citizen/nav.xml exactly.
    private static final int TAB_W = 32, TAB_H = 26;
    private static final int ICON_W = 20, ICON_H = 20;
    private static final int ICON_DX = 5, ICON_DY = 3;
    // Free nav slots after familyTab(144): jobTab(170), debugTab(196), then 222.
    private static final int SLOT_AFTER_FAMILY = 170;
    private static final int SLOT_AFTER_JOB    = 196;
    private static final int SLOT_AFTER_DEBUG  = 222;

    /** Cached reflective handle to {@code AbstractWindowCitizen.citizen}
     *  (the base class exposes no public getter; only MainWindowCitizen does). */
    private static Field citizenField;

    private CitizenTradeButtonHandler() {}

    /**
     * Add the trade tab when a citizen window finishes initialising.
     * Click handling and rendering are owned by BlockUI — no render or
     * mouse hooks needed.
     */
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;

        BOWindow window;
        try {
            window = boScreen.getWindow();
        } catch (Throwable t) {
            return;
        }
        if (!(window instanceof AbstractWindowCitizen citizenWindow)) return;

        // Re-init guard: never add the tab twice to the same window.
        if (citizenWindow.findPaneByID(TAB_ID) != null) return;

        ICitizenDataView citizen = readCitizen(citizenWindow);
        if (citizen == null) return;

        // The citizen entity must be loaded client-side so we can read its
        // RaceTag (the merchant-eligibility signal). If not loaded (viewing
        // from far away), no tab.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) return;
        RaceTag tag = RaceTagClientStore.get(entity.getUUID());
        if (tag == null) return;
        // Only merchant races trade: GOBLIN / LIZARDMAN / DWARF. Orcs don't.
        if (tag.race() == Race.ORC) return;

        final int citizenEntityId = citizen.getEntityId();
        final int tabY = computeFreeSlotY(citizenWindow);

        // Tab background — reuses MC's own texture, so it looks native.
        ButtonImage tab = new ButtonImage();
        tab.setID(TAB_ID);
        tab.setImage(TAB_TEXTURE);
        tab.setSize(TAB_W, TAB_H);
        tab.setPosition(0, tabY);
        tab.setHandler(citizenWindow);

        // Icon layered on top, same offset MC uses for its tab icons.
        ButtonImage icon = new ButtonImage();
        icon.setID(ICON_ID);
        icon.setImage(ICON_TEXTURE);
        icon.setSize(ICON_W, ICON_H);
        icon.setPosition(ICON_DX, tabY + ICON_DY);
        icon.setHandler(citizenWindow);

        citizenWindow.addChild(tab);
        citizenWindow.addChild(icon);

        // Route the click exactly as MC's own tabs do: a handler keyed by the
        // button id, dispatched from AbstractWindowSkeleton.onButtonClicked.
        // Both tab and icon fire the same (unchanged) trade payload.
        Runnable openTrade = () -> sendOpenTrade(citizenEntityId);
        citizenWindow.registerButton(TAB_ID, openTrade);
        citizenWindow.registerButton(ICON_ID, openTrade);
    }

    /**
     * First free tab slot at or after familyTab. jobTab (170) shows for
     * working citizens; debugTab (196) shows in dev — both set their
     * visibility in the AbstractWindowCitizen constructor (before this
     * hook), so reading {@code isVisible()} here is reliable.
     */
    private static int computeFreeSlotY(AbstractWindowCitizen window) {
        if (!isPaneVisible(window, "jobTab")) return SLOT_AFTER_FAMILY; // 170 free
        if (!isPaneVisible(window, "debugTab")) return SLOT_AFTER_JOB;  // 196 free
        return SLOT_AFTER_DEBUG;                                        // 222
    }

    private static boolean isPaneVisible(AbstractWindowCitizen window, String id) {
        Pane p = window.findPaneByID(id);
        return p != null && p.isVisible();
    }

    /** Read the protected {@code citizen} field shared by all
     *  {@link AbstractWindowCitizen} sub-windows (no public getter on the
     *  base). Cached after first success; fails closed (no tab) on error. */
    private static ICitizenDataView readCitizen(AbstractWindowCitizen window) {
        try {
            if (citizenField == null) {
                citizenField = AbstractWindowCitizen.class.getDeclaredField("citizen");
                citizenField.setAccessible(true);
            }
            Object v = citizenField.get(window);
            return v instanceof ICitizenDataView c ? c : null;
        } catch (Throwable t) {
            LOGGER.warn("[TM] trade tab: could not read AbstractWindowCitizen.citizen", t);
            return null;
        }
    }

    private static void sendOpenTrade(int citizenEntityId) {
        PacketDistributor.sendToServer(
                new Networking.OpenCitizenTradePayload(citizenEntityId));
    }
}
