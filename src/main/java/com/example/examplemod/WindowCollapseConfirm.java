package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;

/**
 * The magicule-overspend confirmation — NATIVE BlockUI rebuild of
 * {@link ConfirmCollapseScreen}, titled "Notice:".
 *
 * <p>Shown only when the player can't afford a single send/summon but could
 * still proceed by collapsing into Sleep Mode, AND has Tensura's Sage / Great
 * Sage skill (the server gates the prompt — see
 * {@link ExampleMod#hasMagiculeWarningSkill}). Content is the same as the old
 * vanilla dialog; only the presentation changed.
 *
 * <p>Stacks on the roster window as its BlockUI parent (like the race picker),
 * so {@link AbstractWindowSkeleton#close()} returns to the roster on both
 * Cancel and Proceed. Cancel sends nothing; Proceed fires the unchanged
 * {@link Networking.ConfirmCollapsePayload}. Fail-closed: {@link #tryOpen} is
 * called inside a try/catch by {@link ConfirmCollapseHandler}, which falls back
 * to the still-present vanilla {@link ConfirmCollapseScreen}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowCollapseConfirm extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowcollapseconfirm.xml");

    static final ResourceLocation TEX_PANEL =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_paper_wide2.png");
    static final ResourceLocation TEX_BUTTON =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_large.png");

    private static final String BTN_CANCEL  = "cancel";
    private static final String BTN_PROCEED = "proceed";
    private static final String ID_BODY1    = "body1";
    private static final String ID_COSTLINE = "costLine";

    private final UUID identityId;
    private final String goblinName;
    private final double cost;
    private final double currentMagicule;

    public WindowCollapseConfirm(BOWindow parent, UUID identityId, String goblinName,
                                 double cost, double currentMagicule) {
        super(parent, XML);
        this.identityId      = identityId;
        this.goblinName      = goblinName;
        this.cost            = cost;
        this.currentMagicule = currentMagicule;
        registerButton(BTN_CANCEL, this::onCancel);
        registerButton(BTN_PROCEED, this::onProceed);
    }

    @Override
    public void onOpened() {
        super.onOpened();
        Text body1 = findPaneOfTypeByID(ID_BODY1, Text.class);
        if (body1 != null) {
            body1.setText(Component.literal(
                    "Forcing the move for '" + goblinName + "' will collapse you"));
        }
        Text costLine = findPaneOfTypeByID(ID_COSTLINE, Text.class);
        if (costLine != null) {
            costLine.setText(Component.literal(String.format(Locale.ROOT,
                    "Cost: %.1f    You have: %.1f", cost, currentMagicule)));
        }
    }

    /** Decline → close back to the parent (roster). No packet sent. */
    private void onCancel() {
        close();
    }

    /** Accept → server force-collapses (magicule = 0) and runs the action. */
    private void onProceed() {
        PacketDistributor.sendToServer(new Networking.ConfirmCollapsePayload(identityId));
        close();
    }

    /**
     * Open as a native BlockUI window stacked on the current (roster) window.
     * Returns true on success; the handler treats a throwable as the signal to
     * fall back to the vanilla {@link ConfirmCollapseScreen}.
     */
    public static boolean tryOpen(UUID identityId, String goblinName,
                                  double cost, double currentMagicule) {
        Minecraft mc = Minecraft.getInstance();
        BOWindow parent = (mc.screen instanceof BOScreen bo) ? bo.getWindow() : null;
        new WindowCollapseConfirm(parent, identityId, goblinName, cost, currentMagicule).open();
        LOGGER.debug("[TM] collapse-confirm: opened native BlockUI window for '{}'", goblinName);
        return true;
    }
}
