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
 *   Renders the underlying world dimmed with vanilla's blur, then a
 *   bounded dialog panel centred on screen with the warning text and
 *   two buttons. Parent screen (RosterScreen, if we came from it) is
 *   NOT redrawn behind — the original layered approach let it bleed
 *   through at low overlay alpha and caused widget bounds to overflow
 *   when the parent's own widgets re-laid-out under our coordinate
 *   space.
 *
 *   Decline → close this Screen, return to parent (or nothing). NO packet sent.
 *   Accept  → send {@link Networking.ConfirmCollapsePayload}, return to parent.
 *             Server will set magicule = 0 (exactly), run the original action,
 *             and Tensura's natural Sleep Mode pipeline triggers next tick.
 */
@OnlyIn(Dist.CLIENT)
public class ConfirmCollapseScreen extends Screen {

    /** Modal panel size — fixed in pixels so widget bounds stay sane on
     *  any window size (the panel is centred via this.width / this.height). */
    private static final int DIALOG_WIDTH  = 360;
    private static final int DIALOG_HEIGHT = 160;

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
        int dialogX = (this.width  - DIALOG_WIDTH)  / 2;
        int dialogY = (this.height - DIALOG_HEIGHT) / 2;

        // Buttons sit at the bottom of the dialog panel, 8px from its
        // bottom edge. Centred horizontally with a 10px gap between them.
        int buttonY = dialogY + DIALOG_HEIGHT - 28;
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                b -> cancel()
        ).bounds(cx - 110, buttonY, 100, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Proceed (collapse)").withStyle(ChatFormatting.RED),
                b -> proceed()
        ).bounds(cx + 10, buttonY, 110, 20).build());
    }

    /** Exposed for {@link ClientRosterHandler} so it can refresh the parent
     *  list without replacing this Screen when a roster response arrives. */
    public Screen getParent() {
        return parent;
    }

    private void cancel() {
        // Decline → close, charge nothing, change nothing. NO server packet.
        Minecraft.getInstance().setScreen(parent);
    }

    /** ESC should behave like Cancel (return to parent), not close everything. */
    @Override
    public void onClose() {
        cancel();
    }

    private void proceed() {
        // Accept → server force-collapses (magicule=0) and runs the action.
        PacketDistributor.sendToServer(new Networking.ConfirmCollapsePayload(identityId));
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * Backdrop: vanilla's blur + menu darken (covers world fully, no
     * see-through), then a contained dialog panel with a 1px white
     * border and dark interior, plus a drop shadow.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        int x0 = (this.width  - DIALOG_WIDTH)  / 2;
        int y0 = (this.height - DIALOG_HEIGHT) / 2;
        int x1 = x0 + DIALOG_WIDTH;
        int y1 = y0 + DIALOG_HEIGHT;

        // Drop shadow (offset 4px) — drawn before the panel so it sits behind.
        g.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0xA0000000);
        // 1px white outer border
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFFFFFFFF);
        // Solid dark interior — opaque so no see-through under any condition.
        g.fill(x0, y0, x1, y1, 0xFF181818);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int dialogTop = (this.height - DIALOG_HEIGHT) / 2;

        // Title — red + bold for emphasis. Covers both cases the boundary
        // catches: spending == magicule (depletes to 0) and spending > magicule
        // (overspends). Both result in Sleep Mode entry on the next tick.
        Component title = Component.literal("This would empty your magicule.")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawCenteredString(this.font, title, cx, dialogTop + 16, 0xFFFFFFFF);

        // Body — explicit consequence
        String[] lines = {
                "Forcing the move for '" + goblinName + "' will collapse you",
                "into Sleep Mode — you'll be defenceless and could die.",
                "",
                String.format("Cost: %.1f magicule    You have: %.1f", cost, currentMagicule),
        };
        int y = dialogTop + 44;
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
