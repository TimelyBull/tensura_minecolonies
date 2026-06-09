package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Race-picker window — the NATIVE BlockUI rebuild of {@link RacePickerScreen}.
 *
 * <p>Where the old screen was a hand-drawn vanilla {@link net.minecraft.client.gui.screens.Screen}
 * (dark fill panel + vanilla buttons), this is a genuine MineColonies-style
 * window: a {@code builder_paper_wide2.png} content panel, two
 * {@code builder_button_large.png} image buttons, and black-on-paper text,
 * all laid out in {@code gui/windowracepicker.xml} and driven by
 * {@link AbstractWindowSkeleton} — exactly like MC's own GUIs.
 *
 * <p><b>Parent stacking.</b> Built with the current (town hall) window as its
 * BlockUI {@code parent}, so {@link AbstractWindowSkeleton#close()} pops back
 * to the town hall on both a race pick and ESC — the same return-to-parent
 * behaviour the old screen implemented manually with {@code setScreen(parent)}.
 *
 * <p><b>Wiring is unchanged.</b> The two buttons fire the same
 * {@link Networking.RaceChoicePayload} (same {@code colonyId}, same choice
 * bytes) the vanilla screen did — only the presentation changed.
 *
 * <p><b>Fail-closed.</b> Opening goes through {@link #tryOpen} which the
 * client handler calls inside a try/catch; if BlockUI/MC internals or the XML
 * are unavailable, the handler falls back to the still-present vanilla
 * {@link RacePickerScreen}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowRacePicker extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The XML layout for this window. */
    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowracepicker.xml");

    // Texture ResourceLocations are centralized here for fragility-tracking
    // (per the investigation): the XML references these MC assets by path, so
    // if MC ever moves/renames them the breakage is documented in one place.
    static final ResourceLocation TEX_PANEL =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_paper_wide2.png");
    static final ResourceLocation TEX_BUTTON =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_large.png");

    private static final String BTN_DEFAULT = "raceDefault";
    private static final String BTN_GOBLIN  = "raceGoblin";
    private static final String ID_SUBTITLE = "subtitle";

    private final int colonyId;
    private final String colonyName;

    public WindowRacePicker(BOWindow parent, int colonyId, String colonyName) {
        super(parent, XML);
        this.colonyId   = colonyId;
        this.colonyName = colonyName;

        // Route clicks exactly as MC's own windows do — id → runnable. Both
        // buttons fire the unchanged RaceChoicePayload.
        registerButton(BTN_DEFAULT, this::pickDefault);
        registerButton(BTN_GOBLIN, this::pickGoblin);
    }

    @Override
    public void onOpened() {
        super.onOpened();
        // The colony name is dynamic, so it's filled in here rather than in XML.
        Text subtitle = findPaneOfTypeByID(ID_SUBTITLE, Text.class);
        if (subtitle != null) {
            subtitle.setText(Component.literal(colonyName)
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
        }
    }

    private void pickDefault() { pick(Networking.RaceChoicePayload.CHOICE_DEFAULT); }

    private void pickGoblin() { pick(Networking.RaceChoicePayload.CHOICE_GOBLIN); }

    /** Send the choice to the server and close back to the parent (town hall). */
    private void pick(byte choice) {
        PacketDistributor.sendToServer(new Networking.RaceChoicePayload(colonyId, choice));
        close();
    }

    /**
     * Open the picker as a native BlockUI window, stacking it on the current
     * (town hall) window so closing returns to it. Returns {@code true} on a
     * successful open; the client handler treats any throwable or a
     * {@code false} return as a signal to fall back to the vanilla screen.
     */
    public static boolean tryOpen(int colonyId, String colonyName) {
        Minecraft mc = Minecraft.getInstance();
        // The town hall UI is itself a BlockUI window; grab it as parent so
        // close() pops back to it. If the current screen isn't a BlockUI
        // screen (or there is none), parent is null and the window simply
        // closes to the game on dismissal — same as the old no-parent case.
        BOWindow parent = (mc.screen instanceof BOScreen bo) ? bo.getWindow() : null;
        new WindowRacePicker(parent, colonyId, colonyName).open();
        LOGGER.debug("[TM] race picker: opened native BlockUI window for colony {} ({})",
                colonyId, colonyName);
        return true;
    }
}
