package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * Barrier Core menu — placeholder visuals matching the supplied UI mock
 * (tensura_barrier_core_UI.jpeg); final art swaps in later without logic
 * changes.
 *
 * <pre>
 *   Barrier Core                       [CAP 28k]
 *   {colony name}
 *   MAGICULES                 BARRIER LAYERS
 *   [ gauge  ]                 −  1 / 3  +
 *   [ fills  ]                 Drain ▼ 18 mag/s
 *   x / y
 *   [−] ±3k [+]
 *   [MIN] [MAX]
 *   Barrier stable            ~6m 23s to empty
 * </pre>
 *
 * All values come from the server's {@link Networking.OpenBarrierMenuPayload}
 * snapshot; every button fires a {@link Networking.BarrierMenuActionPayload}
 * and the server replies with a fresh snapshot (live in-place refresh,
 * the roster pattern). The + button past 1 layer is enabled only when
 * the snapshot says the player is a Demon Lord / Hero — the server
 * re-validates regardless.
 */
@OnlyIn(Dist.CLIENT)
public class BarrierCoreScreen extends Screen {

    private static final int PANEL_W = 250;
    private static final int PANEL_H = 210;
    private static final int GAUGE_W = 44;
    private static final int GAUGE_H = 96;

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

    @Override
    protected void init() {
        super.init();
        int px = panelX(), py = panelY();
        int gaugeX = px + 18, gaugeY = py + 52;

        // Magicule − / + (±3k per click)
        addRenderableWidget(Button.builder(Component.literal("-"), b -> send(Networking.BarrierMenuActionPayload.ACTION_TAKE))
                .bounds(gaugeX - 4, gaugeY + GAUGE_H + 16, 20, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> send(Networking.BarrierMenuActionPayload.ACTION_ADD))
                .bounds(gaugeX + GAUGE_W - 16, gaugeY + GAUGE_H + 16, 20, 16).build());
        addRenderableWidget(Button.builder(Component.literal("MIN"), b -> send(Networking.BarrierMenuActionPayload.ACTION_MIN))
                .bounds(gaugeX - 4, gaugeY + GAUGE_H + 36, 30, 16).build());
        addRenderableWidget(Button.builder(Component.literal("MAX"), b -> send(Networking.BarrierMenuActionPayload.ACTION_MAX))
                .bounds(gaugeX + GAUGE_W - 26, gaugeY + GAUGE_H + 36, 30, 16).build());

        // Layers − / +
        int lx = px + 130, ly = py + 92;
        layerMinus = addRenderableWidget(Button.builder(Component.literal("-"),
                        b -> send(Networking.BarrierMenuActionPayload.ACTION_LAYER_MINUS))
                .bounds(lx, ly, 20, 16).build());
        layerPlus = addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> send(Networking.BarrierMenuActionPayload.ACTION_LAYER_PLUS))
                .bounds(lx + 78, ly, 20, 16).build());

        // Wall visibility toggle — visual only; the field and spawn
        // protection keep working while hidden.
        visibilityToggle = addRenderableWidget(Button.builder(visibilityLabel(),
                        b -> send(Networking.BarrierMenuActionPayload.ACTION_TOGGLE_VISIBLE))
                .bounds(lx, ly + 52, 98, 16)
                .tooltip(Tooltip.create(Component.literal(
                        "Show or hide the barrier walls. The protective field "
                        + "keeps working either way.")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(px + PANEL_W - 58, py + PANEL_H - 24, 50, 16).build());

        updateButtonStates();
    }

    private Component visibilityLabel() {
        return Component.literal(data.wallVisible() ? "Wall: Visible" : "Wall: Hidden");
    }

    private void updateButtonStates() {
        if (layerPlus == null) return;
        if (visibilityToggle != null) visibilityToggle.setMessage(visibilityLabel());
        boolean canRaise = data.layers() < BarrierBlockEntity.MAX_LAYERS
                && (data.layers() < 1 || data.canMultiLayer());
        layerPlus.active = canRaise;
        layerPlus.setTooltip(data.canMultiLayer() ? null : Tooltip.create(
                Component.literal("Layers 2–3 require a true Demon Lord or true Hero")
                        .withStyle(ChatFormatting.RED)));
        layerMinus.active = data.layers() > 1;
    }

    private void send(byte action) {
        PacketDistributor.sendToServer(new Networking.BarrierMenuActionPayload(data.pos(), action));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();

        // Panel (placeholder: cream card + border, mirroring the mock)
        g.fill(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF2E2616);
        g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xFFEFE6CC);

        // Header
        g.drawString(this.font, Component.literal("Barrier Core").withStyle(ChatFormatting.BOLD),
                px + 12, py + 10, 0xFF2E2616, false);
        if (!data.colonyName().isEmpty()) {
            g.drawString(this.font, data.colonyName(), px + 12, py + 24, 0xFF7A6E58, false);
        }
        String cap = "CAP " + compact(data.capacity());
        int capW = this.font.width(cap) + 10;
        g.fill(px + PANEL_W - capW - 10, py + 8, px + PANEL_W - 8, py + 22, 0xFFCFE7F7);
        g.drawString(this.font, cap, px + PANEL_W - capW - 5, py + 11, 0xFF274A6B, false);

        // Gauge
        int gaugeX = px + 18, gaugeY = py + 52;
        g.drawString(this.font, "MAGICULES", gaugeX - 4, py + 40, 0xFF7A6E58, false);
        g.fill(gaugeX - 1, gaugeY - 1, gaugeX + GAUGE_W + 1, gaugeY + GAUGE_H + 1, 0xFF2E2616);
        g.fill(gaugeX, gaugeY, gaugeX + GAUGE_W, gaugeY + GAUGE_H, 0xFFDCE9F2);
        float fill = data.capacity() <= 0 ? 0f
                : (float) Math.max(0, Math.min(1, data.stored() / data.capacity()));
        int fillPx = Math.round(GAUGE_H * fill);
        g.fill(gaugeX, gaugeY + GAUGE_H - fillPx, gaugeX + GAUGE_W, gaugeY + GAUGE_H, 0xFF4FA8E0);
        String pct = Math.round(fill * 100) + "%";
        g.drawCenteredString(this.font, pct, gaugeX + GAUGE_W / 2, gaugeY + GAUGE_H / 2 - 4, 0xFF274A6B);
        g.drawCenteredString(this.font,
                compact(data.stored()) + " / " + compact(data.capacity()),
                gaugeX + GAUGE_W / 2, gaugeY + GAUGE_H + 4, 0xFF274A6B);

        // Layers
        int lx = px + 130, ly = py + 92;
        g.drawString(this.font, "BARRIER LAYERS", lx, py + 40, 0xFF7A6E58, false);
        g.drawCenteredString(this.font,
                Component.literal(data.layers() + " / " + BarrierBlockEntity.MAX_LAYERS),
                lx + 49, ly + 4, 0xFF2E2616);
        String drain = data.drainPerSec() > 0
                ? String.format(Locale.ROOT, "Drain ▼ %,.0f mag/s", data.drainPerSec())
                : "Drain — none";
        g.drawString(this.font, drain, lx, ly + 24, 0xFF7A6E58, false);
        g.drawString(this.font, "Tier " + data.tier() + " core", lx, ly + 38, 0xFF7A6E58, false);
        // (visibility toggle button sits at ly + 52)

        // Status strip
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
        g.fill(px + 8, py + PANEL_H - 26, px + PANEL_W - 62, py + PANEL_H - 10, 0xFFD9EFC9);
        g.drawString(this.font, status, px + 12, py + PANEL_H - 22, 0xFF2F5A28, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private static String compact(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    @Override public boolean isPauseScreen() { return false; }
}
