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

import java.util.UUID;

/**
 * Layered confirmation dialog for the "force Sleep Mode" overspend path.
 *
 *   Parent (RosterScreen or null) is rendered behind a translucent dark
 *   overlay so the player sees what they came from. The dialog body is then
 *   rendered on top with the warning text + two buttons.
 *
 *   Decline → close this Screen, return to parent (or nothing). NO packet sent.
 *   Accept  → send {@link Networking.ConfirmCollapsePayload}, return to parent.
 *             Server will set magicule = 0 (exactly), run the original action,
 *             and Tensura's natural Sleep Mode pipeline triggers next tick.
 */
@OnlyIn(Dist.CLIENT)
public class ConfirmCollapseScreen extends Screen {

    private final Screen parent;
    private final UUID identityId;
    private final String goblinName;
    private final double cost;
    private final double currentMagicule;

    public ConfirmCollapseScreen(Screen parent, UUID identityId, String goblinName,
                                 double cost, double currentMagicule) {
        super(Component.literal("Force Sleep Mode?"));
        this.parent          = parent;
        this.identityId      = identityId;
        this.goblinName      = goblinName;
        this.cost            = cost;
        this.currentMagicule = currentMagicule;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int buttonY = this.height / 2 + 50;

        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                b -> cancel()
        ).bounds(cx - 110, buttonY, 100, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Proceed (collapse)").withStyle(ChatFormatting.RED),
                b -> proceed()
        ).bounds(cx + 10, buttonY, 110, 20).build());
    }

    private void cancel() {
        // Decline → close, charge nothing, change nothing. NO server packet.
        Minecraft.getInstance().setScreen(parent);
    }

    private void proceed() {
        // Accept → server force-collapses (magicule=0) and runs the action.
        PacketDistributor.sendToServer(new Networking.ConfirmCollapsePayload(identityId));
        Minecraft.getInstance().setScreen(parent);
    }

    /** Layer effect: render parent behind a dark overlay, then dialog on top. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            // Render parent without mouse hover state (-1 == "outside the window").
            parent.render(g, -1, -1, partialTick);
        }
        g.fill(0, 0, this.width, this.height, 0xC0000000); // 75% dark
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title — red + bold for emphasis. Covers both cases the boundary
        // catches: spending == magicule (depletes to 0) and spending > magicule
        // (overspends). Both result in Sleep Mode entry on the next tick.
        Component title = Component.literal("This would empty your magicule.")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawCenteredString(this.font, title, cx, cy - 60, 0xFFFFFFFF);

        // Body — explicit consequence
        String[] lines = {
                "Forcing the move for '" + goblinName + "' will collapse you",
                "into Sleep Mode — you'll be defenceless and could die.",
                "",
                String.format("Cost: %.1f magicule    You have: %.1f", cost, currentMagicule),
        };
        int y = cy - 35;
        for (String line : lines) {
            g.drawCenteredString(this.font, line, cx, y, 0xFFFFFFFF);
            y += 12;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
