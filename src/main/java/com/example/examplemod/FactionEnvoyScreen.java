package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The inbound FACTION envoy's relations dialogue (diplomacy Stage 1) —
 * the EnvoyDialogueScreen pattern reduced to its essentials: a modal
 * panel with the faction's offer and Accept/Decline routing back as a
 * {@code FactionEnvoyResponsePayload}.
 */
@OnlyIn(Dist.CLIENT)
public class FactionEnvoyScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 110;

    private final int entityId;
    private final String factionName;

    public FactionEnvoyScreen(int entityId, String factionName) {
        super(Component.literal("Envoy of " + factionName));
        this.entityId = entityId;
        this.factionName = factionName;
    }

    @Override
    protected void init() {
        super.init();
        int px = (this.width - PANEL_W) / 2;
        int py = (this.height - PANEL_H) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Open Relations"),
                btn -> respond(true))
                .bounds(px + 14, py + PANEL_H - 32, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Decline"),
                btn -> respond(false))
                .bounds(px + PANEL_W - 94, py + PANEL_H - 32, 80, 20).build());
    }

    private void respond(boolean accepted) {
        PacketDistributor.sendToServer(
                new Networking.FactionEnvoyResponsePayload(entityId, accepted));
        this.onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        // Panel chrome lives in renderBackground (the 1.21 blur fix —
        // Screen.render re-applies blur after renderBackground only).
        int px = (this.width - PANEL_W) / 2;
        int py = (this.height - PANEL_H) / 2;
        graphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xE0101014);
        graphics.fill(px, py, px + PANEL_W, py + 1, 0xFF8A8A8A);
        graphics.fill(px, py + PANEL_H - 1, px + PANEL_W, py + PANEL_H, 0xFF8A8A8A);
        graphics.fill(px, py, px + 1, py + PANEL_H, 0xFF8A8A8A);
        graphics.fill(px + PANEL_W - 1, py, px + PANEL_W, py + PANEL_H, 0xFF8A8A8A);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int px = (this.width - PANEL_W) / 2;
        int py = (this.height - PANEL_H) / 2;
        Component title = Component.literal("Envoy of " + factionName)
                .withStyle(ChatFormatting.BOLD);
        graphics.drawString(this.font, title,
                px + (PANEL_W - this.font.width(title)) / 2, py + 12, 0xFFFFFF, false);
        graphics.drawString(this.font,
                factionName + " proposes to open diplomatic relations.",
                px + 14, py + 34, 0xCCCCCC, false);
        graphics.drawString(this.font,
                "Accept, and they will begin offering deals.",
                px + 14, py + 48, 0x9A9A9A, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
