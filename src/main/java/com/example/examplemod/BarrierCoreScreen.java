package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * Barrier Core menu — styled to match the supplied UI mock and the
 * roster window's MineColonies paper look: MC's builder-paper texture
 * as the panel, the roster's ink/parchment palette, flat paper buttons,
 * and NO text shadows (every string drawn with shadow=false; vanilla
 * drawCenteredString always shadows, so centering is done manually).
 *
 * All values come from the server's {@link Networking.OpenBarrierMenuPayload}
 * snapshot; every button fires a {@link Networking.BarrierMenuActionPayload}
 * and the server replies with a fresh snapshot (live in-place refresh).
 */
@OnlyIn(Dist.CLIENT)
public class BarrierCoreScreen extends Screen {

    private static final ResourceLocation PAPER = ResourceLocation.fromNamespaceAndPath(
            "minecolonies", "textures/gui/builderhut/builder_paper_wide2.png");

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 216;
    private static final int GAUGE_W = 46;
    private static final int GAUGE_H = 92;

    // Roster window palette (WindowRoster constants).
    private static final int TXT_DARK  = 0xFF2E2616;
    private static final int TXT_GRAY  = 0xFF7A6E58;
    private static final int INK       = 0xFF5A4A2E;   // thin borders
    private static final int GAUGE_BG  = 0xFFE9E2D0;
    private static final int GAUGE_FILL= 0xFF4FA8E0;
    private static final int BADGE_BG  = 0xFFE3D7B8;
    private static final int STATUS_BG = 0xFFDDEACB;
    private static final int STATUS_TXT= 0xFF2F5A28;
    private static final int PREVIEW_BG= 0xFF1E2433;   // dark layer-preview box
    private static final int PREVIEW_RING = 0xFF7FD4FF;

    private Networking.OpenBarrierMenuPayload data;

    private Button layerPlus;
    private Button layerMinus;
    private Button visibilityToggle;

    public BarrierCoreScreen(Networking.OpenBarrierMenuPayload data) {
        super(Component.literal("Barrier Core"));
        this.data = data;
    }

    /** Open a new menu, or refresh the one already showing for this core. */
    public static void openOrRefresh(Networking.OpenBarrierMenuPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BarrierCoreScreen open && open.data.pos().equals(payload.pos())) {
            open.refresh(payload);
        } else {
            mc.setScreen(new BarrierCoreScreen(payload));
        }
    }

    private void refresh(Networking.OpenBarrierMenuPayload payload) {
        this.data = payload;
        updateButtonStates();
    }

    private int panelX() { return (this.width - PANEL_W) / 2; }
    private int panelY() { return (this.height - PANEL_H) / 2; }

    // ------------------------------------------------------------------
    // Paper-styled button — flat parchment fill, 1px ink border, dark
    // label without shadow. Matches the roster window's tan buttons far
    // closer than the vanilla gray widget.
    // ------------------------------------------------------------------
    private static class PaperButton extends Button {
        PaperButton(int x, int y, int w, int h, Component label, OnPress onPress) {
            super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int fill = !this.active ? 0xFFCFC6B0
                     : this.isHoveredOrFocused() ? 0xFFF2E5BE : 0xFFE8D9B5;
            g.fill(getX(), getY(), getX() + width, getY() + height, INK);
            g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, fill);
            int color = this.active ? TXT_DARK : TXT_GRAY;
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            int tx = getX() + (width - font.width(getMessage())) / 2;
            int ty = getY() + (height - 8) / 2;
            g.drawString(font, getMessage(), tx, ty, color, false);
        }
    }

    private PaperButton paper(int x, int y, int w, int h, String label, byte action) {
        return new PaperButton(x, y, w, h, Component.literal(label), b -> send(action));
    }

    @Override
    protected void init() {
        super.init();
        int px = panelX(), py = panelY();
        int gaugeX = px + 20, gaugeY = py + 56;

        // Magicule − / + (±3k per click) and MIN / MAX
        addRenderableWidget(paper(gaugeX - 6, gaugeY + GAUGE_H + 16, 22, 14, "-",
                Networking.BarrierMenuActionPayload.ACTION_TAKE));
        addRenderableWidget(paper(gaugeX + GAUGE_W - 16, gaugeY + GAUGE_H + 16, 22, 14, "+",
                Networking.BarrierMenuActionPayload.ACTION_ADD));
        addRenderableWidget(paper(gaugeX - 6, gaugeY + GAUGE_H + 34, 32, 14, "MIN",
                Networking.BarrierMenuActionPayload.ACTION_MIN));
        addRenderableWidget(paper(gaugeX + GAUGE_W - 26, gaugeY + GAUGE_H + 34, 32, 14, "MAX",
                Networking.BarrierMenuActionPayload.ACTION_MAX));

        // Layers − / + flanking the "N / 3" readout under the preview box.
        int lx = px + 128, ly = py + 158;
        layerMinus = addRenderableWidget(paper(lx, ly, 22, 14, "-",
                Networking.BarrierMenuActionPayload.ACTION_LAYER_MINUS));
        layerPlus = addRenderableWidget(paper(lx + 88, ly, 22, 14, "+",
                Networking.BarrierMenuActionPayload.ACTION_LAYER_PLUS));

        // Wall visibility toggle (visual only).
        visibilityToggle = addRenderableWidget(new PaperButton(px + PANEL_W - 120, py + PANEL_H - 24,
                70, 14, visibilityLabel(),
                b -> send(Networking.BarrierMenuActionPayload.ACTION_TOGGLE_VISIBLE)));
        visibilityToggle.setTooltip(Tooltip.create(Component.literal(
                "Show or hide the barrier walls. The protective field keeps working either way.")));

        addRenderableWidget(new PaperButton(px + PANEL_W - 44, py + PANEL_H - 24, 36, 14,
                Component.literal("Close"), b -> onClose()));

        updateButtonStates();
    }

    private Component visibilityLabel() {
        return Component.literal(data.wallVisible() ? "Wall: Shown" : "Wall: Hidden");
    }

    private void updateButtonStates() {
        if (layerPlus == null) return;
        if (visibilityToggle != null) visibilityToggle.setMessage(visibilityLabel());
        boolean canRaise = data.layers() < BarrierBlockEntity.MAX_LAYERS && data.canMultiLayer();
        layerPlus.active = canRaise;
        layerPlus.setTooltip(data.canMultiLayer() ? null : Tooltip.create(
                Component.literal("Layers 2–3 require a true Demon Lord or true Hero")));
        layerMinus.active = data.layers() > 1;
    }

    private void send(byte action) {
        PacketDistributor.sendToServer(new Networking.BarrierMenuActionPayload(data.pos(), action));
    }

    /** Centered text WITHOUT the vanilla drop shadow. */
    private void drawCenteredNoShadow(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(this.font, text, cx - this.font.width(text) / 2, y, color, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Widgets only — panel chrome is in renderBackground (1.21's
        // Screen.render re-applies the blur internally; see
        // ConfirmCollapseScreen for the pattern).
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();

        // MineColonies builder-paper panel (texture stretched to size),
        // with a thin ink frame like the roster's card borders.
        g.blit(PAPER, px, py, 0, 0, 0, PANEL_W, PANEL_H, 400, 260);
        hline(g, px, px + PANEL_W, py, INK);
        hline(g, px, px + PANEL_W, py + PANEL_H - 1, INK);
        vline(g, py, py + PANEL_H, px, INK);
        vline(g, py, py + PANEL_H, px + PANEL_W - 1, INK);

        // Header — bold-ish title (drawn twice, 1px apart, the BlockUI
        // look), colony subtitle, CAP badge.
        g.drawString(this.font, "Barrier Core", px + 14, py + 11, TXT_DARK, false);
        g.drawString(this.font, "Barrier Core", px + 15, py + 11, TXT_DARK, false);
        if (!data.colonyName().isEmpty()) {
            g.drawString(this.font, data.colonyName(), px + 14, py + 24, TXT_GRAY, false);
        }
        String cap = "◆ CAP " + compact(data.capacity());
        int capW = this.font.width(cap) + 10;
        g.fill(px + PANEL_W - capW - 11, py + 8, px + PANEL_W - 9, py + 23, INK);
        g.fill(px + PANEL_W - capW - 10, py + 9, px + PANEL_W - 10, py + 22, BADGE_BG);
        g.drawString(this.font, cap, px + PANEL_W - capW - 5, py + 12, TXT_DARK, false);
        hline(g, px + 12, px + PANEL_W - 12, py + 36, INK);

        // ---- MAGICULES gauge ----
        int gaugeX = px + 20, gaugeY = py + 56;
        g.drawString(this.font, "MAGICULES", gaugeX - 6, py + 43, TXT_GRAY, false);
        g.fill(gaugeX - 1, gaugeY - 1, gaugeX + GAUGE_W + 1, gaugeY + GAUGE_H + 1, INK);
        g.fill(gaugeX, gaugeY, gaugeX + GAUGE_W, gaugeY + GAUGE_H, GAUGE_BG);
        float fill = data.capacity() <= 0 ? 0f
                : (float) Math.max(0, Math.min(1, data.stored() / data.capacity()));
        int fillPx = Math.round(GAUGE_H * fill);
        g.fill(gaugeX, gaugeY + GAUGE_H - fillPx, gaugeX + GAUGE_W, gaugeY + GAUGE_H, GAUGE_FILL);
        drawCenteredNoShadow(g, Math.round(fill * 100) + "%",
                gaugeX + GAUGE_W / 2, gaugeY + GAUGE_H / 2 - 4, TXT_DARK);
        drawCenteredNoShadow(g, compact(data.stored()) + " / " + compact(data.capacity()),
                gaugeX + GAUGE_W / 2, gaugeY + GAUGE_H + 5, TXT_GRAY);

        // ---- BARRIER LAYERS ----
        int lx = px + 128, boxY = py + 56;
        g.drawString(this.font, "BARRIER LAYERS", lx, py + 43, TXT_GRAY, false);
        int boxW = 110, boxH = 92;
        g.fill(lx - 1, boxY - 1, lx + boxW + 1, boxY + boxH + 1, INK);
        g.fill(lx, boxY, lx + boxW, boxY + boxH, PREVIEW_BG);
        // Concentric square outlines — one per ACTIVE layer (solid),
        // remaining slots as faint dotted hints.
        int pcx = lx + boxW / 2, pcy = boxY + boxH / 2;
        for (int i = 0; i < BarrierBlockEntity.MAX_LAYERS; i++) {
            int half = 14 + i * 12;
            boolean active = i < data.layers();
            int color = active ? PREVIEW_RING : 0xFF3A4458;
            rect(g, pcx - half, pcy - half, pcx + half, pcy + half, color);
        }
        g.fill(pcx - 3, pcy - 3, pcx + 3, pcy + 3, PREVIEW_RING); // the core

        // Layer count between the −/+ buttons.
        drawCenteredNoShadow(g, data.layers() + " / " + BarrierBlockEntity.MAX_LAYERS,
                lx + 55, py + 161, TXT_DARK);
        // Drain line.
        String drain = data.drainPerSec() > 0
                ? String.format(Locale.ROOT, "Drain ▼ %,.0f mag/s", data.drainPerSec())
                : "Drain: none";
        g.drawString(this.font, drain, lx, py + 178, TXT_GRAY, false);

        // ---- status strip ----
        String status;
        if (data.stored() <= 0) {
            status = "Barrier DOWN — no magicule";
        } else if (data.drainPerSec() > 0) {
            long secs = (long) (data.stored() / data.drainPerSec());
            status = String.format(Locale.ROOT, "Barrier stable — ~%dm %02ds to empty",
                    secs / 60, secs % 60);
        } else {
            status = "Barrier stable";
        }
        g.fill(px + 11, py + PANEL_H - 27, px + PANEL_W - 125, py + PANEL_H - 11, INK);
        g.fill(px + 12, py + PANEL_H - 26, px + PANEL_W - 126, py + PANEL_H - 12, STATUS_BG);
        g.drawString(this.font, status, px + 16, py + PANEL_H - 23, STATUS_TXT, false);

        // "Tier N core" footnote, left of the toggle.
        g.drawString(this.font, "Tier " + data.tier(), px + 14, py + 165, TXT_GRAY, false);
    }

    private static void hline(GuiGraphics g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    private static void vline(GuiGraphics g, int y0, int y1, int x, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }

    /** 1px rectangle outline. */
    private static void rect(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        hline(g, x0, x1, y0, color);
        hline(g, x0, x1, y1 - 1, color);
        vline(g, y0, y1, x0, color);
        vline(g, y0, y1, x1 - 1, color);
    }

    private static String compact(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    @Override public boolean isPauseScreen() { return false; }
}
