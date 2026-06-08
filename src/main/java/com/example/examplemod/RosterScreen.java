package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The goblin roster Screen — Stage C2b GUI + roster expansion (Stages 1 & 2a).
 *
 *   - Header: title + vanilla EditBox search field. Typing filters by name
 *             (case-insensitive substring). Rows are sorted by EP descending.
 *   - Body:   scrollable {@link ObjectSelectionList}.
 *               · Left-click a row (no drag) → C2S ActOnIdentityPayload — the
 *                 existing single-toggle behaviour.
 *               · Click-and-DRAG across rows → drag-multi-select for bulk
 *                 summon. Selected rows render with a blue highlight + check
 *                 mark. SUBORDINATE rows are skipped during drag (bulk-send
 *                 is deferred). Hard cap at 9 — rows beyond the 9th are
 *                 silently dropped.
 *   - Footer: when ≥ 2 are selected, "Summon Selected (N)" + "Cancel" buttons
 *             appear. Summon sends a single C2S BulkSummonPayload(List<UUID>);
 *             server applies one cost decision over the total.
 */
@OnlyIn(Dist.CLIENT)
public class RosterScreen extends Screen {

    // Chrome tuned to fit 9 rows of ROW_HEIGHT on a default-scale screen:
    //   HEADER (title + search) + 9*ROW + FOOTER ≈ 38 + 198 + 28 = 264 px.
    private static final int HEADER_HEIGHT = 38;
    private static final int FOOTER_HEIGHT = 28;
    private static final int ROW_HEIGHT    = 22;
    private static final int SEARCH_WIDTH  = 200;
    private static final int SEARCH_HEIGHT = 16;
    private static final int SELECTION_CAP = ExampleMod.BULK_SUMMON_CAP;
    /** Selection-highlight colour (ARGB, 33% alpha). */
    private static final int SELECTED_TINT = 0x553B82FF;

    private static final Comparator<Networking.RosterEntry> EP_DESC =
            Comparator.comparingDouble(Networking.RosterEntry::ep).reversed();

    /** The raw server-supplied list (no filter, unsorted from server's POV). */
    private List<Networking.RosterEntry> entries;
    /** What's currently shown in the list — filtered + sorted view of {@link #entries}. */
    private List<Networking.RosterEntry> displayed;

    private RosterList list;
    private EditBox searchBox;
    private Button summonSelectedButton;
    private Button cancelSelectionButton;
    private String searchText = "";

    // ---- drag-multi-select state ----

    /** Identity UUIDs the user has selected via drag. Insertion-ordered so the
     *  server-side fan placement reflects drag direction. */
    private final Set<UUID> selectedIds = new LinkedHashSet<>();
    /** The row the current mouse-press started on, or null if not pressed on a row. */
    private UUID pressStartId = null;
    /** True once the cursor has crossed onto a row OTHER than {@link #pressStartId}
     *  during the current press — distinguishes drag-multi-select from a plain click. */
    private boolean draggedAcrossRows = false;
    /** Mode of the current batch. Locked to the first row added to selection so a
     *  drag can't mix SUBORDINATE and IN_COLONY (each maps to a distinct server
     *  payload). -1 = no batch active. */
    private int batchMode = -1;

    /** UUID → tickCount-of-click, used to render a brief name-flicker on the row
     *  the player just clicked. Entries auto-expire {@link #FLICKER_DURATION_TICKS}
     *  ticks after being recorded. */
    private final Map<UUID, Long> recentClickTick = new HashMap<>();
    /** Length of the click-flicker in ticks (~12 ticks ≈ 0.6 s). */
    private static final long FLICKER_DURATION_TICKS = 12L;

    public RosterScreen(List<Networking.RosterEntry> entries) {
        super(Component.literal("Citizen Roster"));
        this.entries = entries;
        this.displayed = applyFilterAndSort(entries, this.searchText);
    }

    @Override
    protected void init() {
        super.init();

        // Search bar
        int searchX = (this.width - SEARCH_WIDTH) / 2;
        int searchY = 18;
        this.searchBox = new EditBox(this.font, searchX, searchY, SEARCH_WIDTH, SEARCH_HEIGHT,
                Component.literal("search"));
        this.searchBox.setHint(Component.literal("Search by name…")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(s -> {
            this.searchText = s == null ? "" : s;
            this.displayed = applyFilterAndSort(this.entries, this.searchText);
            if (this.list != null) {
                this.list.populate(this.displayed);
            }
        });
        this.searchBox.setValue(this.searchText);
        this.addRenderableWidget(this.searchBox);

        // List
        int listHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        this.list = new RosterList(this.minecraft, this.width, listHeight, HEADER_HEIGHT, ROW_HEIGHT);
        this.list.populate(this.displayed);
        this.addRenderableWidget(this.list);

        // Bulk-action buttons — created always, visibility toggled by selection size.
        int buttonY = this.height - FOOTER_HEIGHT + 4;
        int summonButtonW = 160;
        int cancelButtonW = 80;
        int gap = 8;
        int totalW = summonButtonW + gap + cancelButtonW;
        int rowX = (this.width - totalW) / 2;
        this.summonSelectedButton = Button.builder(
                Component.literal("Summon Selected (0)"),
                btn -> this.onSummonSelectedClicked())
                .bounds(rowX, buttonY, summonButtonW, 20)
                .build();
        this.cancelSelectionButton = Button.builder(
                Component.literal("Cancel"),
                btn -> this.clearSelection())
                .bounds(rowX + summonButtonW + gap, buttonY, cancelButtonW, 20)
                .build();
        this.addRenderableWidget(this.summonSelectedButton);
        this.addRenderableWidget(this.cancelSelectionButton);
        this.refreshSelectionWidgets();
    }

    public void setEntries(List<Networking.RosterEntry> newEntries) {
        this.entries = newEntries;
        this.displayed = applyFilterAndSort(newEntries, this.searchText);
        if (this.list != null) {
            this.list.populate(this.displayed);
        }
        // Drop selected IDs that no longer exist server-side (e.g. died,
        // ownership changed). Visible selection always reflects live state.
        java.util.HashSet<UUID> live = new java.util.HashSet<>(newEntries.size());
        for (Networking.RosterEntry e : newEntries) live.add(e.identityId());
        this.selectedIds.retainAll(live);
        if (this.selectedIds.isEmpty()) {
            this.batchMode = -1;
        }
        this.refreshSelectionWidgets();
    }

    private static List<Networking.RosterEntry> applyFilterAndSort(
            List<Networking.RosterEntry> source, String search) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Networking.RosterEntry> out = new ArrayList<>(source.size());
        if (needle.isEmpty()) {
            out.addAll(source);
        } else {
            for (Networking.RosterEntry e : source) {
                if (e.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(e);
                }
            }
        }
        out.sort(EP_DESC);
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

        if (this.displayed.isEmpty()) {
            String msgText = this.entries.isEmpty()
                    ? "No named citizens yet."
                    : "No matches for \"" + this.searchText + "\".";
            Component msg = Component.literal(msgText)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            g.drawCenteredString(this.font, msg, this.width / 2, this.height / 2, 0xFFFFFFFF);
        }

        // Selection-status line just above the buttons.
        if (!this.selectedIds.isEmpty()) {
            String capMarker = this.selectedIds.size() >= SELECTION_CAP ? "  (cap)" : "";
            Component sel = Component.literal(
                    this.selectedIds.size() + "/" + SELECTION_CAP + " selected" + capMarker)
                    .withStyle(ChatFormatting.AQUA);
            g.drawCenteredString(this.font, sel, this.width / 2,
                    this.height - FOOTER_HEIGHT - 4, 0xFFFFFFFF);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------
    // Drag-multi-select event handling
    //
    // Strategy: we intercept mouse events BEFORE super so the
    // ObjectSelectionList never sees a row click — its built-in behaviour
    // is to fire the row's own mouseClicked, which would send the
    // single-toggle ActOnIdentityPayload before we know whether this is
    // a click or a drag. Instead we:
    //   - record press-start row in mouseClicked, suppress the row event
    //   - on mouseDragged, detect crossing to a different row → enter
    //     drag-multi-select mode, add both rows to selection
    //   - on mouseReleased: if we never crossed rows, fire the original
    //     single-toggle on the start row; otherwise leave selection
    //     populated for the "Summon Selected" button
    // Search box and bulk-action buttons get a chance to handle the click
    // BEFORE we run row-hit detection. Scrollbar / outside-list clicks
    // fall through to super so scrolling still works.
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Let buttons + search box handle first (their bounding boxes don't
        // overlap the list area).
        if (this.searchBox != null && this.searchBox.mouseClicked(mx, my, btn)) {
            this.setFocused(this.searchBox);
            return true;
        }
        if (btn == 0 && this.summonSelectedButton.visible
                && this.summonSelectedButton.mouseClicked(mx, my, btn)) return true;
        if (btn == 0 && this.cancelSelectionButton.visible
                && this.cancelSelectionButton.mouseClicked(mx, my, btn)) return true;

        // Left-click inside the list's row area → custom drag-start.
        // For everything else (right-click, scrollbar, outside), fall through.
        if (btn == 0 && this.list != null) {
            RosterRow hit = this.list.entryAtScreenY(mx, my);
            if (hit != null) {
                this.pressStartId = hit.data.identityId();
                this.draggedAcrossRows = false;
                return true;
            }
        }
        // Defocus the search box if clicking elsewhere.
        this.setFocused(null);
        if (this.searchBox != null) this.searchBox.setFocused(false);
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (btn == 0 && this.pressStartId != null && this.list != null) {
            RosterRow hit = this.list.entryAtScreenY(mx, my);
            if (hit != null) {
                UUID id = hit.data.identityId();
                if (!id.equals(this.pressStartId) && !this.draggedAcrossRows) {
                    // First crossing — enter drag-multi-select. Pull in the
                    // start row too, subject to filter (it must still be IN_COLONY).
                    this.draggedAcrossRows = true;
                    tryAddToSelection(this.pressStartId);
                }
                if (this.draggedAcrossRows) {
                    tryAddToSelection(id);
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (btn == 0 && this.pressStartId != null) {
            UUID start = this.pressStartId;
            boolean wasDrag = this.draggedAcrossRows;
            this.pressStartId = null;
            this.draggedAcrossRows = false;

            if (!wasDrag) {
                if (this.selectedIds.contains(start)) {
                    // Clicking an already-selected row unselects it (does NOT
                    // teleport). Clear batchMode if this leaves selection empty.
                    this.selectedIds.remove(start);
                    if (this.selectedIds.isEmpty()) this.batchMode = -1;
                } else {
                    // Plain click on an unselected row — fire the existing
                    // single-toggle, and record the click for the flicker pulse.
                    PacketDistributor.sendToServer(new Networking.ActOnIdentityPayload(start));
                    if (this.minecraft != null && this.minecraft.level != null) {
                        this.recentClickTick.put(start, this.minecraft.level.getGameTime());
                    }
                }
            }
            // wasDrag: selection persists, button row will react via refresh.
            this.refreshSelectionWidgets();
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    /** Add the given row to the selection if it's eligible.
     *  Eligibility: not already selected + under the cap + matches the batch
     *  mode (locked by the first row added). */
    private void tryAddToSelection(UUID id) {
        if (this.selectedIds.contains(id)) return;
        if (this.selectedIds.size() >= SELECTION_CAP) return;
        Networking.RosterEntry e = findById(id);
        if (e == null) return;
        if (this.batchMode == -1) {
            this.batchMode = e.modeByte();
        } else if (e.modeByte() != this.batchMode) {
            return; // mixed-mode drag — skip off-mode rows silently
        }
        this.selectedIds.add(id);
        this.refreshSelectionWidgets();
    }

    private Networking.RosterEntry findById(UUID id) {
        for (Networking.RosterEntry e : this.entries) {
            if (e.identityId().equals(id)) return e;
        }
        return null;
    }

    private void refreshSelectionWidgets() {
        boolean show = this.selectedIds.size() >= 2;
        if (this.summonSelectedButton != null) {
            this.summonSelectedButton.visible = show;
            String label = this.batchMode == 0
                    ? "Send Selected (" + this.selectedIds.size() + ") to Colony"
                    : "Summon Selected (" + this.selectedIds.size() + ")";
            this.summonSelectedButton.setMessage(Component.literal(label));
        }
        if (this.cancelSelectionButton != null) {
            this.cancelSelectionButton.visible = show;
        }
    }

    /**
     * Compute the name colour for a row, mixing in a quick "button press"
     * flicker if the row was clicked within the last {@link #FLICKER_DURATION_TICKS}
     * ticks. Two strobe peaks across the duration (gold) sandwiched with
     * the default white, then back to default. Forgets the record once the
     * flicker has elapsed so the map doesn't accumulate dead entries.
     */
    private int flickerColorFor(UUID id, float partialTick, int defaultColor) {
        Long clickedAt = this.recentClickTick.get(id);
        if (clickedAt == null || this.minecraft == null || this.minecraft.level == null) {
            return defaultColor;
        }
        long now = this.minecraft.level.getGameTime();
        float ticksSince = (now - clickedAt) + partialTick;
        if (ticksSince < 0 || ticksSince >= FLICKER_DURATION_TICKS) {
            this.recentClickTick.remove(id);
            return defaultColor;
        }
        // Two-peak strobe: brightness oscillates over the duration.
        float t = ticksSince / (float) FLICKER_DURATION_TICKS;          // 0..1
        float pulse = (float) Math.abs(Math.sin(t * Math.PI * 2.0));     // 0..1, two peaks
        // Lerp white → gold (0xFFD7A53C) by pulse intensity.
        int targetR = 0xD7, targetG = 0xA5, targetB = 0x3C;
        int r = (int) (0xFF + (targetR - 0xFF) * pulse);
        int gC = (int) (0xFF + (targetG - 0xFF) * pulse);
        int b = (int) (0xFF + (targetB - 0xFF) * pulse);
        return 0xFF000000 | (r << 16) | (gC << 8) | b;
    }

    private void clearSelection() {
        this.selectedIds.clear();
        this.batchMode = -1;
        this.refreshSelectionWidgets();
    }

    private void onSummonSelectedClicked() {
        if (this.selectedIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(this.selectedIds);
        if (ids.size() > SELECTION_CAP) ids = ids.subList(0, SELECTION_CAP);
        // Dispatch based on the mode the batch was locked to.
        if (this.batchMode == 0) {
            PacketDistributor.sendToServer(new Networking.BulkSendPayload(ids));
        } else {
            PacketDistributor.sendToServer(new Networking.BulkSummonPayload(ids));
        }
        // Clear local selection optimistically; the server's roster refresh
        // will land momentarily.
        this.clearSelection();
    }

    // ------------------------------------------------------------------
    // The list widget + row entries
    // ------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    class RosterList extends ObjectSelectionList<RosterRow> {
        RosterList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void populate(List<Networking.RosterEntry> entries) {
            this.clearEntries();
            for (Networking.RosterEntry e : entries) {
                this.addEntry(new RosterRow(e));
            }
        }

        /** Translate screen coords to a row entry, honouring the list's scroll
         *  offset and rejecting hits in the scrollbar gutter. Returns null if
         *  the point is outside the list body or in the scrollbar. */
        RosterRow entryAtScreenY(double mx, double my) {
            if (mx < this.getX() || mx > this.getRight()) return null;
            if (my < this.getY() || my > this.getY() + this.height) return null;
            // Avoid hijacking scrollbar clicks — let super handle them via
            // mouseScrolled / mouseClicked fall-through.
            int scrollbarX = this.getScrollbarPosition();
            if (mx >= scrollbarX && mx <= scrollbarX + 6) return null;
            return this.getEntryAtPosition(mx, my);
        }
    }

    @OnlyIn(Dist.CLIENT)
    class RosterRow extends ObjectSelectionList.Entry<RosterRow> {

        final Networking.RosterEntry data;

        RosterRow(Networking.RosterEntry data) {
            this.data = data;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            boolean selected = RosterScreen.this.selectedIds.contains(this.data.identityId());

            if (selected) {
                g.fill(left, top, left + width, top + height, SELECTED_TINT);
            } else if (hovered) {
                g.fill(left, top, left + width, top + height, 0x33FFFFFF);
            }

            int textY = top + (height - mc.font.lineHeight) / 2;
            int textLeft = left + 6;
            if (selected) {
                // Check mark left of the name to signal selected state.
                g.drawString(mc.font, "✓", textLeft, textY, 0xFF7BBFFF);
                textLeft += mc.font.width("✓") + 4;
            }

            int nameColor = RosterScreen.this.flickerColorFor(this.data.identityId(),
                    partialTick, 0xFFFFFFFF);
            g.drawString(mc.font, data.name(), textLeft, textY, nameColor);
            int nameWidth = mc.font.width(data.name());
            String epText = "EP " + formatEP(data.ep());
            g.drawString(mc.font, epText, textLeft + nameWidth + 8, textY, 0xFFAAAAAA);

            String status;
            int color;
            if (data.modeByte() == 1) {
                status = "In colony";
                color  = 0xFFD7A53C;
            } else {
                status = "At your side";
                color  = 0xFF5BD86A;
            }
            int statusWidth = mc.font.width(status);
            g.drawString(mc.font, status,
                    left + width - statusWidth - 6, textY, color);
        }

        @Override
        public Component getNarration() {
            String mode = data.modeByte() == 1 ? "In colony" : "At your side";
            return Component.literal(data.name() + ", EP " + formatEP(data.ep()) + ", " + mode);
        }

        private static String formatEP(double ep) {
            if (ep >= 1_000_000.0) return String.format(Locale.ROOT, "%.1fM", ep / 1_000_000.0);
            if (ep >= 1_000.0)     return String.format(Locale.ROOT, "%.1fk", ep / 1_000.0);
            return String.format(Locale.ROOT, "%.0f", ep);
        }
    }
}
