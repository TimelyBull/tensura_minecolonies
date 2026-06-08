package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Race-picker modal for a newly-created (or still-pending) colony.
 *
 * Two buttons — Default citizens / Goblin (the STARTER races). Orc was
 * removed from the picker when it moved to the EARNED tier (envoy-only,
 * alongside Dwarf and Lizardman). Picking dispatches a
 * {@link Networking.RaceChoicePayload} to the server and returns to the
 * parent screen (typically MC's town hall building UI).
 *
 * <p>{@code CHOICE_ORC = 2} is intentionally retained on
 * {@link Networking.RaceChoicePayload} and in the server-side
 * {@code handleRaceChoice} switch as a defensive path — admin commands
 * ({@code /setcolonyrace}) drive {@code ColonyMember.ORC} through a
 * different API, but the byte path remains a valid landing for any
 * legacy in-flight payload (e.g. mid-update old clients).
 *
 * ESC behavior: closes back to parent WITHOUT sending a choice. The
 * colony stays in {@code pendingChoice} on the server — the player can
 * re-engage by right-clicking the town hall, by logging in again, or
 * via {@code /setcolonyrace}.
 *
 * Visual shape mirrors {@link ConfirmCollapseScreen} — vanilla blur
 * underneath, then a centered bounded panel with drop-shadow and a
 * 1px border. Opaque interior — no see-through regardless of what's
 * behind.
 */
@OnlyIn(Dist.CLIENT)
public class RacePickerScreen extends Screen {

    private static final int DIALOG_WIDTH  = 460;
    private static final int DIALOG_HEIGHT = 230;

    private final Screen parent;
    private final int colonyId;
    private final String colonyName;

    public RacePickerScreen(Screen parent, int colonyId, String colonyName) {
        super(Component.literal("Choose colony race"));
        this.parent     = parent;
        this.colonyId   = colonyId;
        this.colonyName = colonyName;
    }

    public int getColonyId() { return colonyId; }
    public Screen getParent() { return parent; }

    @Override
    protected void init() {
        super.init();
        int dialogX = (this.width  - DIALOG_WIDTH)  / 2;
        int dialogY = (this.height - DIALOG_HEIGHT) / 2;

        int buttonY = dialogY + DIALOG_HEIGHT - 32;
        int buttonW = 120;
        int gap = 16;
        int totalW = buttonW * 2 + gap;
        int firstX = (this.width - totalW) / 2;

        addRenderableWidget(Button.builder(
                Component.literal("Default citizens"),
                b -> pick(Networking.RaceChoicePayload.CHOICE_DEFAULT)
        ).bounds(firstX, buttonY, buttonW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Goblin").withStyle(ChatFormatting.GREEN),
                b -> pick(Networking.RaceChoicePayload.CHOICE_GOBLIN)
        ).bounds(firstX + buttonW + gap, buttonY, buttonW, 20).build());
    }

    private void pick(byte choice) {
        PacketDistributor.sendToServer(new Networking.RaceChoicePayload(colonyId, choice));
        Minecraft.getInstance().setScreen(parent);
    }

    /** ESC → return to parent without picking. The pending state stays
     *  set on the server so the player can re-engage later. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        int x0 = (this.width  - DIALOG_WIDTH)  / 2;
        int y0 = (this.height - DIALOG_HEIGHT) / 2;
        int x1 = x0 + DIALOG_WIDTH;
        int y1 = y0 + DIALOG_HEIGHT;

        // Drop shadow, then white border, then dark opaque interior.
        g.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0xA0000000);
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFFFFFFFF);
        g.fill(x0, y0, x1, y1, 0xFF181818);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int dialogTop = (this.height - DIALOG_HEIGHT) / 2;

        Component title = Component.literal("Choose your colony's race")
                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        g.drawCenteredString(this.font, title, cx, dialogTop + 14, 0xFFFFFFFF);

        Component subtitle = Component.literal(colonyName)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        g.drawCenteredString(this.font, subtitle, cx, dialogTop + 30, 0xFFCCCCCC);

        // Per-race descriptions on separate lines. Only the STARTER races
        // appear here — Orc / Dwarf / Lizardman are earned via diplomacy
        // (envoy system) and have their own flavour text in
        // EnvoyDialogue. A short line at the bottom calls out that other
        // races exist but arrive later.
        String[] lines = {
                "",
                "Default citizens — standard MineColonies population.",
                "",
                "Goblin — small, nimble, prolific; quick to grow but fragile.",
                "",
                "Wild goblins will spawn in your territory once you choose them.",
                "You must name each one to convert them into a citizen.",
                "",
                "Other races (Orc, Lizardman, Dwarf) arrive later through diplomacy.",
        };
        int y = dialogTop + 52;
        for (String line : lines) {
            g.drawCenteredString(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
