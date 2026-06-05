package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The goblin roster Screen — Stage C2b GUI.
 *
 *   - Header: title "Goblin Roster"
 *   - Body:   scrollable {@link ObjectSelectionList} of rows
 *             Each row shows: NAME (left) and STATUS (right)
 *               · SUBORDINATE → "At your side" in green
 *               · IN_COLONY   → "In colony"    in gold
 *             Left-click a row → C2S ActOnIdentityPayload(identityId).
 *             Server toggles mode and re-sends the roster, which lands in
 *             {@link ClientRosterHandler#handle} and refreshes this Screen
 *             in place (no reopen).
 *   - Empty:  centred message "No named goblins yet." instead of an empty list.
 */
@OnlyIn(Dist.CLIENT)
public class RosterScreen extends Screen {

    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 16;
    private static final int ROW_HEIGHT    = 22;

    private List<Networking.RosterEntry> entries;
    private RosterList list;

    public RosterScreen(List<Networking.RosterEntry> entries) {
        super(Component.literal("Goblin Roster"));
        this.entries = entries;
    }

    @Override
    protected void init() {
        super.init();
        int listHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        // ObjectSelectionList(Minecraft, width, height, y, itemHeight) in 1.21.1
        this.list = new RosterList(this.minecraft, this.width, listHeight, HEADER_HEIGHT, ROW_HEIGHT);
        this.list.populate(this.entries);
        this.addRenderableWidget(this.list);
    }

    /**
     * Called by {@link ClientRosterHandler} when a refreshed roster arrives
     * while this Screen is open. Rebuilds the list rows in place.
     */
    public void setEntries(List<Networking.RosterEntry> newEntries) {
        this.entries = newEntries;
        if (this.list != null) {
            this.list.populate(newEntries);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // Title at the top
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        // Empty-state message
        if (this.entries.isEmpty()) {
            Component msg = Component.literal("No named goblins yet.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            g.drawCenteredString(this.font, msg, this.width / 2, this.height / 2, 0xFFFFFFFF);
        }
    }

    /** Roster doesn't pause the game; behaves like an inventory screen. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // The list widget + row entries
    // ------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    static class RosterList extends ObjectSelectionList<RosterRow> {
        RosterList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void populate(List<Networking.RosterEntry> entries) {
            this.clearEntries();
            for (Networking.RosterEntry e : entries) {
                this.addEntry(new RosterRow(e));
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    static class RosterRow extends ObjectSelectionList.Entry<RosterRow> {

        private final Networking.RosterEntry data;

        RosterRow(Networking.RosterEntry data) {
            this.data = data;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            // Background highlight on hover
            if (hovered) {
                g.fill(left, top, left + width, top + height, 0x33FFFFFF);
            }
            // Name on the left
            g.drawString(mc.font, data.name(), left + 6, top + (height - mc.font.lineHeight) / 2,
                    0xFFFFFFFF);
            // Status on the right
            String status;
            int color;
            if (data.modeByte() == 1) {
                status = "In colony";
                color  = 0xFFD7A53C; // gold
            } else {
                status = "At your side";
                color  = 0xFF5BD86A; // green
            }
            int statusWidth = mc.font.width(status);
            g.drawString(mc.font, status,
                    left + width - statusWidth - 6,
                    top + (height - mc.font.lineHeight) / 2,
                    color);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 0) {
                PacketDistributor.sendToServer(new Networking.ActOnIdentityPayload(data.identityId()));
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            String mode = data.modeByte() == 1 ? "In colony" : "At your side";
            return Component.literal(data.name() + ", " + mode);
        }
    }
}
