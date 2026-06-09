package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The citizen roster — NATIVE BlockUI rebuild of {@link RosterScreen}.
 *
 * <p>A genuine MineColonies-style window laid out in {@code gui/windowroster.xml}:
 * a {@code builder_paper_wide2.png} panel, a current-magicule counter, a native
 * {@code <input>} search field, a BlockUI {@link ScrollingList}, and MC image
 * buttons — driven by {@link AbstractWindowSkeleton} like MC's own list windows.
 *
 * <p><b>Selection is a single "paint" model.</b> Each row shows name + EP +
 * colored status, a per-row <i>Summon</i> / <i>Send</i> button (the
 * single-identity action — {@link Networking.ActOnIdentityPayload}), and a
 * non-clickable checkbox <i>image</i> ({@code builder_button_mini} ↔
 * {@code builder_button_mini_check}) that the window paints. All bulk selection
 * goes through {@link #click}/{@link #onMouseDrag}: a press on a row (that no
 * control consumed) starts a paint whose mode is "deselect" if the pressed row
 * is already selected, else "select"; the anchor row toggles immediately and
 * every row the drag passes over follows the same mode. So a single click on a
 * checkbox toggles one row, and dragging through rows selects — or, when started
 * on a selected row, deselects — the rectangles it crosses. The scrollbar's own
 * drags are consumed by {@code super} first, so it still scrolls. Two or more
 * selected rows of one mode reveal the footer bulk action
 * ({@link Networking.BulkSummonPayload} / {@link Networking.BulkSendPayload}).
 *
 * <p><b>Live refresh.</b> {@link #route} refreshes the open window in place
 * after every action (including behind a layered collapse-confirm dialog),
 * preserving search text + selection, and carries the player's magicule for the
 * counter. Fail-closed: {@code route} opens this window in a try/catch and falls
 * back to the still-present vanilla {@link RosterScreen}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowRoster extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowroster.xml");

    // Centralized MC texture paths (fragility-tracking, per the investigation).
    // The XML references these by path; the selection image swaps between the
    // two mini textures at runtime.
    static final ResourceLocation TEX_PANEL =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_paper_wide2.png");
    static final ResourceLocation SEL_UNCHECKED =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_mini.png");
    static final ResourceLocation SEL_CHECKED =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_mini_check.png");

    // Pane ids (must match windowroster.xml).
    private static final String ID_SEARCH   = "search";
    private static final String ID_LIST     = "roster";
    private static final String ID_BULK     = "bulk";
    private static final String ID_CLEAR    = "clear";
    private static final String ID_SELCOUNT = "selCount";
    private static final String ID_MAGCOUNT = "magCount";
    private static final String ROW_SEL    = "sel";
    private static final String ROW_NAME   = "name";
    private static final String ROW_EP     = "ep";
    private static final String ROW_STATUS = "status";
    private static final String ROW_ACT    = "act";

    // Status colors tuned for legibility on the tan paper panel.
    private static final int COLOR_IN_COLONY  = 0xFF8A6D3B; // gold-brown — "In colony"
    private static final int COLOR_AT_SIDE    = 0xFF1F6B2E; // dark green — "At your side"
    private static final int COLOR_NAME       = 0xFF000000;
    private static final int COLOR_EP         = 0xFF555555;

    private static final int SELECTION_CAP = ExampleMod.BULK_SUMMON_CAP;

    /** Row pitch in the scrolling list — matches the row-template height in
     *  windowroster.xml ({@code <view size="100% 22">}, childspacing 0). Used
     *  to map a drag's Y coordinate to a roster row index. */
    private static final int ROW_PIXEL_HEIGHT = 22;

    private static final Comparator<Networking.RosterEntry> EP_DESC =
            Comparator.comparingDouble(Networking.RosterEntry::ep).reversed();

    /**
     * The most recently constructed roster window. Used by {@link #route} to
     * refresh in place. Deliberately NOT cleared in {@code onClosed()} — a
     * collapse-confirm dialog layering over the window fires {@code onClosed},
     * and we still need to find the window to update its data behind the dialog.
     * A stale reference is harmless: {@code route} only refreshes it while
     * {@code mc.screen} actually matches its BOScreen (or a confirm over it).
     */
    private static WindowRoster instance;

    private List<Networking.RosterEntry> entries;
    private List<Networking.RosterEntry> displayed;
    private String searchText = "";
    private double playerMagicule;

    /** Bulk-selection set, insertion-ordered (drives fan placement server-side). */
    private final Set<UUID> selectedIds = new LinkedHashSet<>();
    /** Mode the batch is locked to (first selected row's mode); -1 = none. */
    private int batchMode = -1;

    // ---- paint-drag state ----
    /** True while a press-and-drag selection gesture is in progress. */
    private boolean dragActive = false;
    /** Paint mode for the active gesture: true = deselecting, false = selecting. */
    private boolean dragDeselect = false;
    /** Rows already painted in the current gesture, so a wiggle within one row
     *  doesn't toggle it repeatedly. */
    private final Set<UUID> dragPainted = new HashSet<>();

    private ScrollingList list;
    private TextField searchField;
    private Button bulkButton;
    private Button clearButton;
    private Text selCountText;
    private Text magCountText;

    public WindowRoster(List<Networking.RosterEntry> entries, double playerMagicule) {
        super(XML);
        instance = this;
        this.entries = entries;
        this.playerMagicule = playerMagicule;
        this.displayed = filterAndSort(entries, this.searchText);

        // Per-row action button shares an id across rows; the handler resolves
        // which row via ScrollingList.getListElementIndexByPane (MC idiom).
        // Selection has no button handler — it's owned by click()/onMouseDrag().
        registerButton(ROW_ACT, (Button b) -> onRowAction(b));
        registerButton(ID_BULK, (Button b) -> onBulk());
        registerButton(ID_CLEAR, (Button b) -> clearSelection());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        instance = this;

        this.searchField  = findPaneOfTypeByID(ID_SEARCH, TextField.class);
        this.bulkButton   = findPaneOfTypeByID(ID_BULK, Button.class);
        this.clearButton  = findPaneOfTypeByID(ID_CLEAR, Button.class);
        this.selCountText = findPaneOfTypeByID(ID_SELCOUNT, Text.class);
        this.magCountText = findPaneOfTypeByID(ID_MAGCOUNT, Text.class);
        this.list         = findPaneOfTypeByID(ID_LIST, ScrollingList.class);

        if (this.searchField != null) {
            this.searchField.setText(this.searchText);
            this.searchField.setHandler(tf -> onSearchChanged(tf.getText()));
        }

        if (this.list != null) {
            this.list.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return displayed.size();
                }

                @Override
                public void updateElement(int index, Pane rowPane) {
                    bindRow(index, rowPane);
                }
            });
        }
        updateMagCount();
        applyEmptyText();
        refreshFooter();
    }

    /** Fill one row's panes from {@code displayed.get(index)}. */
    private void bindRow(int index, Pane rowPane) {
        if (index < 0 || index >= displayed.size()) return;
        Networking.RosterEntry e = displayed.get(index);
        boolean inColony = e.modeByte() == 1;

        Text name = rowPane.findPaneOfTypeByID(ROW_NAME, Text.class);
        if (name != null) {
            name.setText(Component.literal(e.name()));
            name.setColors(COLOR_NAME);
        }
        Text ep = rowPane.findPaneOfTypeByID(ROW_EP, Text.class);
        if (ep != null) {
            ep.setText(Component.literal("EP " + formatBig(e.ep())));
            ep.setColors(COLOR_EP);
        }
        Text status = rowPane.findPaneOfTypeByID(ROW_STATUS, Text.class);
        if (status != null) {
            status.setText(Component.literal(inColony ? "In colony" : "At your side"));
            status.setColors(inColony ? COLOR_IN_COLONY : COLOR_AT_SIDE);
        }
        Image sel = rowPane.findPaneOfTypeByID(ROW_SEL, Image.class);
        if (sel != null) {
            sel.setImage(selectedIds.contains(e.identityId()) ? SEL_CHECKED : SEL_UNCHECKED, false);
        }
        Button act = rowPane.findPaneOfTypeByID(ROW_ACT, Button.class);
        if (act != null) {
            act.setText(Component.literal(inColony ? "Summon" : "Send"));
        }
    }

    // ---- per-row action button (single summon/send) ----

    private void onRowAction(Button button) {
        int idx = rowIndexOf(button);
        if (idx < 0) return;
        UUID id = displayed.get(idx).identityId();
        PacketDistributor.sendToServer(new Networking.ActOnIdentityPayload(id));
    }

    private int rowIndexOf(Button button) {
        if (list == null) return -1;
        int idx = list.getListElementIndexByPane(button);
        return (idx >= 0 && idx < displayed.size()) ? idx : -1;
    }

    // ------------------------------------------------------------------
    // Selection paint: click to toggle one, press-and-drag to paint many.
    // ------------------------------------------------------------------

    @Override
    public boolean click(double mx, double my) {
        // Let real controls (action button, search, footer, scrollbar) take the
        // press first. Anything they consume is NOT a selection gesture — this
        // is what keeps a checkbox-style toggle from bouncing back via a drag.
        boolean consumed = super.click(mx, my);
        dragActive = false;
        dragPainted.clear();
        if (!consumed && list != null) {
            int row = rowIndexAt(mx, my);
            if (row >= 0) {
                Networking.RosterEntry e = displayed.get(row);
                // Paint mode: deselect if the pressed row is already selected.
                dragDeselect = selectedIds.contains(e.identityId());
                dragActive = true;
                paintRow(e);
                return true;
            }
        }
        return consumed;
    }

    @Override
    public boolean onMouseDrag(double mx, double my, int button, double dx, double dy) {
        // Scrollbar (and any other child) consumes its own drags first.
        if (super.onMouseDrag(mx, my, button, dx, dy)) {
            return true;
        }
        if (dragActive && button == 0 && list != null) {
            int row = rowIndexAt(mx, my);
            if (row >= 0) {
                paintRow(displayed.get(row));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mx, double my) {
        dragActive = false;
        dragPainted.clear();
        return super.onMouseReleased(mx, my);
    }

    /** Apply the active paint mode to one row (once per gesture). */
    private void paintRow(Networking.RosterEntry e) {
        UUID id = e.identityId();
        if (!dragPainted.add(id)) return; // already painted this gesture
        boolean changed;
        if (dragDeselect) {
            changed = selectedIds.remove(id);
            if (changed && selectedIds.isEmpty()) batchMode = -1;
        } else {
            changed = tryAddToSelection(e);
        }
        if (changed) {
            if (list != null) list.refreshElementPanes();
            refreshFooter();
        }
    }

    /**
     * Map a window-relative point to a roster row index, honouring the list's
     * scroll offset; -1 if the point isn't over a row.
     */
    private int rowIndexAt(double mx, double my) {
        if (list == null) return -1;
        int lx = list.getX();
        int ly = list.getY();
        int lw = list.getWidth();
        int lh = list.getHeight();
        if (mx < lx || mx >= lx + lw || my < ly || my >= ly + lh) return -1;
        double localY = (my - ly) + list.getScrollY();
        int idx = (int) Math.floor(localY / ROW_PIXEL_HEIGHT);
        return (idx >= 0 && idx < displayed.size()) ? idx : -1;
    }

    /**
     * Add a row to the selection, respecting the cap and the mode lock.
     * @return true if the row was newly added (selection changed).
     */
    private boolean tryAddToSelection(Networking.RosterEntry e) {
        if (selectedIds.contains(e.identityId())) return false;
        if (selectedIds.size() >= SELECTION_CAP) return false;
        if (batchMode == -1) {
            batchMode = e.modeByte();
        } else if (e.modeByte() != batchMode) {
            return false; // mixed-mode selection not allowed — one batch = one payload
        }
        selectedIds.add(e.identityId());
        return true;
    }

    private void onBulk() {
        if (selectedIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(selectedIds);
        if (ids.size() > SELECTION_CAP) ids = ids.subList(0, SELECTION_CAP);
        if (batchMode == 0) {
            PacketDistributor.sendToServer(new Networking.BulkSendPayload(ids));
        } else {
            PacketDistributor.sendToServer(new Networking.BulkSummonPayload(ids));
        }
        clearSelection();
    }

    private void clearSelection() {
        selectedIds.clear();
        batchMode = -1;
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    private void onSearchChanged(String text) {
        this.searchText = text == null ? "" : text;
        this.displayed = filterAndSort(this.entries, this.searchText);
        applyEmptyText();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    /** Apply a fresh server roster, preserving search text + live selection. */
    public void setEntries(List<Networking.RosterEntry> newEntries, double magicule) {
        this.entries = newEntries;
        this.playerMagicule = magicule;
        this.displayed = filterAndSort(newEntries, this.searchText);
        // Drop selections that no longer exist server-side.
        Set<UUID> live = new HashSet<>(newEntries.size());
        for (Networking.RosterEntry e : newEntries) live.add(e.identityId());
        selectedIds.retainAll(live);
        if (selectedIds.isEmpty()) batchMode = -1;
        updateMagCount();
        applyEmptyText();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    private void updateMagCount() {
        if (magCountText != null) {
            magCountText.setText(Component.literal("Magicule: " + formatBig(playerMagicule)));
            magCountText.setColors(COLOR_NAME);
        }
    }

    private void refreshFooter() {
        boolean show = selectedIds.size() >= 2;
        if (bulkButton != null) {
            bulkButton.setVisible(show);
            bulkButton.setText(Component.literal(batchMode == 0
                    ? "Send Selected (" + selectedIds.size() + ")"
                    : "Summon Selected (" + selectedIds.size() + ")"));
        }
        if (clearButton != null) clearButton.setVisible(show);
        if (selCountText != null) {
            boolean any = !selectedIds.isEmpty();
            selCountText.setVisible(any);
            if (any) {
                String cap = selectedIds.size() >= SELECTION_CAP ? "  (cap)" : "";
                selCountText.setText(Component.literal(
                        selectedIds.size() + "/" + SELECTION_CAP + " selected" + cap));
            }
        }
    }

    private void applyEmptyText() {
        if (list == null) return;
        String msg = entries.isEmpty()
                ? "No named citizens yet."
                : (displayed.isEmpty() ? "No matches." : "");
        list.setEmptyText(Component.literal(msg));
    }

    private static List<Networking.RosterEntry> filterAndSort(
            List<Networking.RosterEntry> source, String search) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Networking.RosterEntry> out = new ArrayList<>(source.size());
        if (needle.isEmpty()) {
            out.addAll(source);
        } else {
            for (Networking.RosterEntry e : source) {
                if (e.name().toLowerCase(Locale.ROOT).contains(needle)) out.add(e);
            }
        }
        out.sort(EP_DESC);
        return out;
    }

    /** Compact number formatting shared by EP and the magicule counter. */
    private static String formatBig(double v) {
        if (v >= 1_000_000.0) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
        if (v >= 1_000.0)     return String.format(Locale.ROOT, "%.1fk", v / 1_000.0);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    // ------------------------------------------------------------------
    // Open + live-refresh routing (replaces ClientRosterHandler's body).
    // ------------------------------------------------------------------

    /**
     * Route an incoming roster: refresh the open window in place (BlockUI or the
     * vanilla fallback, including while a collapse-confirm dialog is layered over
     * it), or open a fresh window if none is showing. Vanilla {@link RosterScreen}
     * is the fail-closed fallback if the BlockUI window can't be opened.
     */
    public static void route(List<Networking.RosterEntry> entries, double playerMagicule) {
        Minecraft mc = Minecraft.getInstance();

        BOScreen rosterScreen = (instance != null) ? safeScreen(instance) : null;

        // A confirm dialog currently on top — never clobber it by opening a new
        // roster; just refresh the underlying roster's data so it's current when
        // the dialog is dismissed.
        boolean blockUiConfirm = mc.screen instanceof BOScreen bo
                && bo.getWindow() instanceof WindowCollapseConfirm;
        boolean vanillaConfirmOverBlockUiRoster = rosterScreen != null
                && mc.screen instanceof ConfirmCollapseScreen c && c.getParent() == rosterScreen;

        if (rosterScreen != null
                && (mc.screen == rosterScreen || blockUiConfirm || vanillaConfirmOverBlockUiRoster)) {
            instance.setEntries(entries, playerMagicule);
            return;
        }

        // Vanilla fallback roster (and confirm layered over a vanilla roster).
        if (mc.screen instanceof RosterScreen open) {
            open.setEntries(entries);
            return;
        }
        if (mc.screen instanceof ConfirmCollapseScreen c
                && c.getParent() instanceof RosterScreen parent) {
            parent.setEntries(entries);
            return;
        }

        // A BlockUI confirm with no known roster behind it — don't open a roster
        // on top of a mid-decision dialog.
        if (blockUiConfirm) return;

        // Nothing open → open fresh (BlockUI primary, vanilla fail-closed).
        try {
            new WindowRoster(entries, playerMagicule).open();
        } catch (Throwable t) {
            LOGGER.error("[TM] roster: native BlockUI window failed to open; "
                    + "falling back to the vanilla screen", t);
            mc.setScreen(new RosterScreen(entries));
        }
    }

    private static BOScreen safeScreen(WindowRoster w) {
        try {
            return w.getScreen();
        } catch (Throwable t) {
            return null;
        }
    }
}
