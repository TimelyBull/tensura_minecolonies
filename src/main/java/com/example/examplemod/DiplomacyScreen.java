package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Diplomacy Stage 1 — the Factions tab (docs/diplomacy.md #5): the
 * [Roster | Diplomacy] strip's second surface, server-snapshot-driven
 * (the BarrierCoreScreen pattern: the server sends a full
 * {@code DiplomacySnapshotPayload} on open and after every action; this
 * screen renders strings and never computes anything itself).
 *
 * <p>Layout: faction list on the left (progressive — uncontacted
 * factions render greyed), the selected faction's detail on the right
 * (tier chip, relations state, Send-envoy buttons or the deal list with
 * a progress bar). Grows in Stages 2–4 (lending pickers, reward
 * showcases, the mending ritual).
 */
@OnlyIn(Dist.CLIENT)
public class DiplomacyScreen extends Screen {

    private record OfferRow(String dealId, String title, String req, String reward, int daysLeft) {}

    private record ActiveRow(String title, String req, String reward, byte state, int pct,
                             int hoursLeft, boolean canDeliver, boolean canCollect) {}

    private record FactionRow(String id, String name, double standing, String tier,
                              int tierColor, RelationsState state, boolean closed,
                              boolean pendingReply, boolean canSend,
                              List<OfferRow> offers, ActiveRow active) {}

    private static final int LIST_X = 16;
    private static final int LIST_W = 120;
    private static final int LIST_TOP = 40;
    private static final int ROW_H = 18;
    private static final int DETAIL_X = LIST_X + LIST_W + 18;

    private boolean enabled;
    private List<FactionRow> factions = new ArrayList<>();
    private int selected = 0;

    public DiplomacyScreen(CompoundTag snapshot) {
        super(Component.literal("Diplomacy"));
        parse(snapshot);
    }

    /** Opens the screen, or live-refreshes it if already open (the
     *  server re-sends the snapshot after every action). */
    public static void openOrRefresh(CompoundTag snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DiplomacyScreen open) {
            open.parse(snapshot);
            open.rebuild();
        } else {
            mc.setScreen(new DiplomacyScreen(snapshot));
        }
    }

    private void parse(CompoundTag snapshot) {
        this.enabled = snapshot.getBoolean("enabled");
        List<FactionRow> rows = new ArrayList<>();
        ListTag list = snapshot.getList("factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag f = list.getCompound(i);
            List<OfferRow> offers = new ArrayList<>();
            ListTag offerList = f.getList("offers", Tag.TAG_COMPOUND);
            for (int j = 0; j < offerList.size(); j++) {
                CompoundTag o = offerList.getCompound(j);
                offers.add(new OfferRow(o.getString("dealId"), o.getString("title"),
                        o.getString("req"), o.getString("reward"), o.getInt("daysLeft")));
            }
            ActiveRow active = null;
            if (f.contains("active")) {
                CompoundTag d = f.getCompound("active");
                active = new ActiveRow(d.getString("title"), d.getString("req"),
                        d.getString("reward"), d.getByte("state"), d.getInt("progressPct"),
                        d.getInt("hoursLeft"), d.getBoolean("canDeliver"),
                        d.getBoolean("canCollect"));
            }
            rows.add(new FactionRow(f.getString("id"), f.getString("name"),
                    f.getDouble("standing"), f.getString("tier"), f.getInt("tierColor"),
                    RelationsState.byId(f.getByte("state")), f.getBoolean("closed"),
                    f.getBoolean("pendingReply"), f.getBoolean("canSend"),
                    offers, active));
        }
        this.factions = rows;
        if (selected >= rows.size()) selected = 0;
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    @Override
    protected void init() {
        super.init();

        // The tab strip — Roster | Diplomacy (we're on Diplomacy).
        this.addRenderableWidget(Button.builder(Component.literal("Roster"),
                btn -> PacketDistributor.sendToServer(new Networking.RequestRosterPayload()))
                .bounds(LIST_X, 12, 70, 20).build());

        if (!enabled) return;

        // Faction list (left column).
        for (int i = 0; i < factions.size(); i++) {
            FactionRow row = factions.get(i);
            final int index = i;
            String label = (row.state() != RelationsState.NONE ? "● " : "") + row.name();
            Button button = Button.builder(Component.literal(label), btn -> {
                this.selected = index;
                rebuild();
            }).bounds(LIST_X, LIST_TOP + i * ROW_H, LIST_W, ROW_H - 2).build();
            button.active = index != selected;
            this.addRenderableWidget(button);
        }

        // Detail-pane actions for the selected faction.
        if (factions.isEmpty()) return;
        FactionRow row = factions.get(selected);
        int x = DETAIL_X;
        int y = LIST_TOP + 78; // below the text block drawn in render()

        if (row.state() == RelationsState.NONE && !row.closed()) {
            this.addRenderableWidget(Button.builder(Component.literal("Send Envoy"),
                    btn -> sendAction(Networking.DiplomacyActionPayload.ACTION_SEND_ENVOY,
                            row.id(), "", false))
                    .bounds(x, y, 110, 20).build()).active = row.canSend();
            this.addRenderableWidget(Button.builder(Component.literal("Send with Gift (8 Gold)"),
                    btn -> sendAction(Networking.DiplomacyActionPayload.ACTION_SEND_ENVOY,
                            row.id(), "", true))
                    .bounds(x + 116, y, 150, 20).build()).active = row.canSend();
        }

        if (row.state() != RelationsState.NONE) {
            int offerY = y;
            for (OfferRow offer : row.offers()) {
                this.addRenderableWidget(Button.builder(
                        Component.literal("Accept: " + offer.title()),
                        btn -> sendAction(Networking.DiplomacyActionPayload.ACTION_ACCEPT_DEAL,
                                row.id(), offer.dealId(), false))
                        .bounds(x, offerY + 24, 170, 20).build())
                        .active = row.active() == null;
                offerY += 46;
            }
            if (row.active() != null) {
                int actY = this.height - 56;
                if (row.active().canDeliver()) {
                    this.addRenderableWidget(Button.builder(Component.literal("Deliver"),
                            btn -> sendAction(Networking.DiplomacyActionPayload.ACTION_DELIVER,
                                    row.id(), "", false))
                            .bounds(x, actY, 90, 20).build());
                }
                if (row.active().canCollect()) {
                    this.addRenderableWidget(Button.builder(Component.literal("Collect"),
                            btn -> sendAction(Networking.DiplomacyActionPayload.ACTION_COLLECT,
                                    row.id(), "", false))
                            .bounds(x, actY, 90, 20).build());
                }
            }
        }

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                btn -> this.onClose())
                .bounds(this.width - 86, 12, 70, 20).build());
    }

    private void sendAction(byte action, String factionId, String dealId, boolean flag) {
        PacketDistributor.sendToServer(
                new Networking.DiplomacyActionPayload(action, factionId, dealId, flag));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, Component.literal("Diplomacy").withStyle(ChatFormatting.BOLD),
                (this.width - this.font.width("Diplomacy")) / 2, 18, 0xFFFFFF, false);

        if (!enabled) {
            graphics.drawString(this.font,
                    "The faction system is disabled (enableFactionSystem=false).",
                    LIST_X, LIST_TOP + 8, 0xAAAAAA, false);
            return;
        }
        if (factions.isEmpty()) return;
        FactionRow row = factions.get(selected);
        int x = DETAIL_X;
        int y = LIST_TOP;

        // Header: name + tier chip + relations state.
        graphics.drawString(this.font, Component.literal(row.name()).withStyle(ChatFormatting.BOLD),
                x, y, 0xFFFFFF, false);
        graphics.drawString(this.font,
                row.tier() + String.format(" (%.1f)", row.standing()),
                x, y + 14, row.tierColor(), false);
        graphics.drawString(this.font,
                Component.literal(row.state().displayName()).withStyle(row.state().color()),
                x, y + 28, 0xFFFFFF, false);

        // Status line.
        String status;
        if (row.closed()) {
            status = row.name() + " will not treat with you.";
        } else if (row.pendingReply()) {
            status = "Your envoy awaits a reply...";
        } else if (row.state() == RelationsState.NONE) {
            status = "No relations. Send an envoy to open diplomacy.";
        } else if (row.offers().isEmpty() && row.active() == null) {
            status = "No offers on the table today.";
        } else {
            status = "";
        }
        if (!status.isEmpty()) {
            graphics.drawString(this.font, status, x, y + 46, 0xAAAAAA, false);
        }

        // Offers (text above each Accept button).
        if (row.state() != RelationsState.NONE) {
            int offerY = y + 78;
            for (OfferRow offer : row.offers()) {
                graphics.drawString(this.font,
                        offer.title() + " — " + offer.req(), x, offerY, 0xFFFFFF, false);
                graphics.drawString(this.font,
                        "Pays: " + offer.reward() + "  (offer lapses in " + offer.daysLeft() + "d)",
                        x, offerY + 11, 0x9A9A9A, false);
                offerY += 46;
            }

            // Active deal + progress bar.
            ActiveRow active = row.active();
            if (active != null) {
                int barY = this.height - 88;
                String stateText = switch (active.state()) {
                    case ActiveDeal.STATE_AWAITING_PAYOFF -> "payment on its way";
                    case ActiveDeal.STATE_READY -> "ready to collect";
                    default -> active.hoursLeft() + "h left";
                };
                graphics.drawString(this.font,
                        "Active: " + active.title() + " — " + active.req(),
                        x, barY - 24, 0xFFFFFF, false);
                graphics.drawString(this.font,
                        "Pays: " + active.reward() + "  (" + stateText + ")",
                        x, barY - 13, 0x9A9A9A, false);
                int barW = 200;
                graphics.fill(x, barY, x + barW, barY + 8, 0xFF333333);
                graphics.fill(x, barY, x + barW * active.pct() / 100, barY + 8, 0xFF3B82FF);
                graphics.drawString(this.font, active.pct() + "%",
                        x + barW + 6, barY, 0xFFFFFF, false);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
