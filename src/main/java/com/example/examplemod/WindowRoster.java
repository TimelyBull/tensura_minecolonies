package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.Box;
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
 * The Citizen Roster — native BlockUI window, styled to the flat cream/paper
 * mockup (vanilla {@link RosterScreen} kept as a fail-closed fallback).
 *
 * <p>Layout ({@code gui/windowroster.xml}): a HEADER (title "Citizen Roster",
 * "[colony name]" subtitle, a divider, a magicule badge with a purple diamond +
 * a peeking slime), a full-width SEARCH field, a {@link ScrollingList} of
 * compressed bordered row-cards (name · "EP …" · status pill · Summon/Send
 * button), and a FOOTER with "N citizens" / "N at your side" counts (plus a bulk
 * Group Summon/Send bar that appears when ≥2 rows are checked).
 *
 * <p>BlockUI's XML {@code color} attribute only handles named colours reliably,
 * so every custom colour is set in Java ({@link Box#setColor}/{@link Text#setColors});
 * the status pills and action buttons are flat placeholder textures swapped per
 * row. Selection is per-row checkbox toggles (mode-locked), driving the existing
 * {@link Networking.BulkSummonPayload}/{@link Networking.BulkSendPayload}; the
 * per-row button uses {@link Networking.ActOnIdentityPayload}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowRoster extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowroster.xml");

    private static ResourceLocation tm(String path) {
        return ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", path);
    }
    private static ResourceLocation mc(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", path);
    }

    // Centralized texture paths (placeholders; fail-closed — a missing texture
    // must not crash, the window just renders without it).
    static final ResourceLocation PILL_GREEN   = tm("textures/gui/roster/pill_green.png");
    static final ResourceLocation PILL_BLUE     = tm("textures/gui/roster/pill_blue.png");
    static final ResourceLocation BTN_GREEN     = tm("textures/gui/roster/btn_green.png");
    static final ResourceLocation BTN_TAN       = tm("textures/gui/roster/btn_tan.png");
    static final ResourceLocation SEL_UNCHECKED = mc("textures/gui/builderhut/builder_button_mini.png");
    static final ResourceLocation SEL_CHECKED   = mc("textures/gui/builderhut/builder_button_mini_check.png");

    // Pane ids (must match windowroster.xml).
    private static final String ID_SEARCH       = "search";
    private static final String ID_SEARCH_HINT  = "searchHint";
    private static final String ID_LIST         = "roster";
    private static final String ID_COLONY_NAME  = "colonyName";
    private static final String ID_MAGCOUNT     = "magCount";
    private static final String ID_DIVIDER      = "divider";
    private static final String ID_MAGBADGE     = "magBadge";
    private static final String ID_SEARCHBOX    = "searchBox";
    private static final String ID_GROUP_SUM    = "groupSummon";
    private static final String ID_GROUP_SEND   = "groupSend";
    private static final String ID_CLEAR        = "clear";
    private static final String ID_CNT_CITIZENS = "countCitizens";
    private static final String ID_CNT_ATSIDE   = "countAtSide";
    // row pane ids
    private static final String ROW_CARD = "card";
    private static final String ROW_SEL  = "sel";
    private static final String ROW_NAME = "name";
    private static final String ROW_EP   = "ep";
    private static final String ROW_PILL = "pillBg";
    private static final String ROW_PILLTEXT = "pillText";
    private static final String ROW_ACT  = "act";

    // Colours (opaque ARGB for text; RGB for box borders).
    private static final int TXT_DARK    = 0xFF2E2616;
    private static final int TXT_GRAY    = 0xFF7A6E58;
    private static final int TXT_HINT    = 0xFFAFA48C;
    private static final int TXT_GREEN   = 0xFF2F5A28; // "In colony" pill label
    private static final int TXT_BLUE    = 0xFF274A6B; // "At your side" pill label
    private static final int[] BORDER_CARD    = {0x9A, 0x80, 0x55};
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};
    private static final int[] BORDER_BADGE   = {0xA0, 0x82, 0x4E};

    private static final int SELECTION_CAP = ExampleMod.BULK_SUMMON_CAP;

    private static final Comparator<Networking.RosterEntry> EP_DESC =
            Comparator.comparingDouble(Networking.RosterEntry::ep).reversed();

    /** Most recently constructed window — used by {@link #route} to refresh in
     *  place (incl. behind a layered collapse-confirm). Stale refs are harmless. */
    private static WindowRoster instance;

    private List<Networking.RosterEntry> entries;
    private List<Networking.RosterEntry> displayed;
    private String searchText = "";
    private double playerMagicule;
    private String colonyName;

    /** Bulk-selection set, insertion-ordered (drives fan placement server-side). */
    private final Set<UUID> selectedIds = new LinkedHashSet<>();
    /** Mode the batch is locked to (first selected row's mode); -1 = none. */
    private int batchMode = -1;

    private ScrollingList list;
    private TextField searchField;
    private Text searchHint;
    private Button groupSummonButton;
    private Button groupSendButton;
    private Button clearButton;

    public WindowRoster(List<Networking.RosterEntry> entries, double playerMagicule, String colonyName) {
        super(XML);
        instance = this;
        this.entries = entries;
        this.playerMagicule = playerMagicule;
        this.colonyName = colonyName == null ? "" : colonyName;
        this.displayed = filterAndSort(entries, this.searchText);

        registerButton(ROW_ACT, (Button b) -> onRowAction(b));
        registerButton(ROW_SEL, (Button b) -> onToggleRow(b));
        registerButton(ID_GROUP_SUM, (Button b) -> onGroup(1));   // summon checked at-colony
        registerButton(ID_GROUP_SEND, (Button b) -> onGroup(0));  // send checked at-side
        registerButton(ID_CLEAR, (Button b) -> clearSelection());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        instance = this;

        this.searchField       = findPaneOfTypeByID(ID_SEARCH, TextField.class);
        this.searchHint        = findPaneOfTypeByID(ID_SEARCH_HINT, Text.class);
        this.groupSummonButton = findPaneOfTypeByID(ID_GROUP_SUM, Button.class);
        this.groupSendButton   = findPaneOfTypeByID(ID_GROUP_SEND, Button.class);
        this.clearButton       = findPaneOfTypeByID(ID_CLEAR, Button.class);
        this.list              = findPaneOfTypeByID(ID_LIST, ScrollingList.class);

        // Recolour the static border boxes + the dynamic header texts.
        setBoxColor(ID_DIVIDER, BORDER_DIVIDER);
        setBoxColor(ID_SEARCHBOX, BORDER_CARD);
        setBoxColor(ID_MAGBADGE, BORDER_BADGE);
        setTextColor(ID_COLONY_NAME, colonyName.isEmpty() ? "[no colony]" : colonyName, TXT_GRAY);
        setTextColor(ID_MAGCOUNT, formatBig(playerMagicule), TXT_DARK);

        if (this.searchField != null) {
            this.searchField.setText(this.searchText);
            this.searchField.setHandler(tf -> onSearchChanged(tf.getText()));
        }

        if (this.list != null) {
            this.list.setDataProvider(new ScrollingList.DataProvider() {
                @Override public int getElementCount() { return displayed.size(); }
                @Override public void updateElement(int index, Pane rowPane) { bindRow(index, rowPane); }
            });
        }
        updateSearchHint();
        applyEmptyText();
        refreshCounts();
        refreshFooter();
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private void bindRow(int index, Pane rowPane) {
        if (index < 0 || index >= displayed.size()) return;
        Networking.RosterEntry e = displayed.get(index);
        boolean inColony = e.modeByte() == 1;

        Box card = rowPane.findPaneOfTypeByID(ROW_CARD, Box.class);
        if (card != null) card.setColor(BORDER_CARD[0], BORDER_CARD[1], BORDER_CARD[2]);

        Text name = rowPane.findPaneOfTypeByID(ROW_NAME, Text.class);
        if (name != null) { name.setText(Component.literal(e.name())); name.setColors(TXT_DARK); }

        Text ep = rowPane.findPaneOfTypeByID(ROW_EP, Text.class);
        if (ep != null) { ep.setText(Component.literal("EP " + formatBig(e.ep()))); ep.setColors(TXT_GRAY); }

        Image pill = rowPane.findPaneOfTypeByID(ROW_PILL, Image.class);
        if (pill != null) pill.setImage(inColony ? PILL_GREEN : PILL_BLUE, false);
        Text pillText = rowPane.findPaneOfTypeByID(ROW_PILLTEXT, Text.class);
        if (pillText != null) {
            pillText.setText(Component.literal(inColony ? "In colony" : "At your side"));
            pillText.setColors(inColony ? TXT_GREEN : TXT_BLUE);
        }

        ButtonImage sel = rowPane.findPaneOfTypeByID(ROW_SEL, ButtonImage.class);
        if (sel != null) sel.setImage(selectedIds.contains(e.identityId()) ? SEL_CHECKED : SEL_UNCHECKED);

        ButtonImage act = rowPane.findPaneOfTypeByID(ROW_ACT, ButtonImage.class);
        if (act != null) {
            // Keep existing semantics: In colony -> Summon (back); At side -> Send.
            act.setImage(inColony ? BTN_GREEN : BTN_TAN);
            act.setText(Component.literal(inColony ? "Summon" : "Send"));
        }
    }

    private void onRowAction(Button button) {
        int idx = rowIndexOf(button);
        if (idx < 0) return;
        PacketDistributor.sendToServer(new Networking.ActOnIdentityPayload(displayed.get(idx).identityId()));
    }

    private void onToggleRow(Button button) {
        int idx = rowIndexOf(button);
        if (idx < 0) return;
        Networking.RosterEntry e = displayed.get(idx);
        UUID id = e.identityId();
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
            if (selectedIds.isEmpty()) batchMode = -1;
        } else {
            tryAddToSelection(e);
        }
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    private int rowIndexOf(Button button) {
        if (list == null) return -1;
        int idx = list.getListElementIndexByPane(button);
        return (idx >= 0 && idx < displayed.size()) ? idx : -1;
    }

    private boolean tryAddToSelection(Networking.RosterEntry e) {
        if (selectedIds.contains(e.identityId())) return false;
        if (selectedIds.size() >= SELECTION_CAP) return false;
        if (batchMode == -1) {
            batchMode = e.modeByte();
        } else if (e.modeByte() != batchMode) {
            return false; // one batch = one mode = one payload
        }
        selectedIds.add(e.identityId());
        return true;
    }

    // ------------------------------------------------------------------
    // Footer: bulk group actions + counts
    // ------------------------------------------------------------------

    private void onGroup(int mode) {
        if (selectedIds.isEmpty() || batchMode != mode) return;
        List<UUID> ids = new ArrayList<>(selectedIds);
        if (ids.size() > SELECTION_CAP) ids = ids.subList(0, SELECTION_CAP);
        if (mode == 1) {
            PacketDistributor.sendToServer(new Networking.BulkSummonPayload(ids));
        } else {
            PacketDistributor.sendToServer(new Networking.BulkSendPayload(ids));
        }
        clearSelection();
    }

    private void clearSelection() {
        selectedIds.clear();
        batchMode = -1;
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    /** Show the bulk bar only when ≥2 are checked; only the button matching the
     *  selection's mode is shown (selection is mode-locked). */
    private void refreshFooter() {
        boolean show = selectedIds.size() >= 2;
        if (groupSummonButton != null) {
            groupSummonButton.setVisible(show && batchMode == 1);
            groupSummonButton.setText(Component.literal("Group Summon (" + selectedIds.size() + ")"));
        }
        if (groupSendButton != null) {
            groupSendButton.setVisible(show && batchMode == 0);
            groupSendButton.setText(Component.literal("Group Send (" + selectedIds.size() + ")"));
        }
        if (clearButton != null) clearButton.setVisible(show);
    }

    /** Footer counts reflect the WHOLE roster (not the search filter). */
    private void refreshCounts() {
        int atSide = 0;
        for (Networking.RosterEntry e : entries) if (e.modeByte() == 0) atSide++;
        setTextColor(ID_CNT_CITIZENS, entries.size() + (entries.size() == 1 ? " citizen" : " citizens"), TXT_GRAY);
        setTextColor(ID_CNT_ATSIDE, atSide + " at your side", TXT_GRAY);
    }

    // ------------------------------------------------------------------
    // Search + refresh
    // ------------------------------------------------------------------

    private void onSearchChanged(String text) {
        this.searchText = text == null ? "" : text;
        this.displayed = filterAndSort(this.entries, this.searchText);
        updateSearchHint();
        applyEmptyText();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    private void updateSearchHint() {
        if (searchHint != null) {
            searchHint.setVisible(searchText.isEmpty());
            searchHint.setColors(TXT_HINT);
        }
    }

    /** Apply a fresh server roster, preserving search text + live selection. */
    public void setEntries(List<Networking.RosterEntry> newEntries, double magicule, String newColonyName) {
        this.entries = newEntries;
        this.playerMagicule = magicule;
        if (newColonyName != null) this.colonyName = newColonyName;
        this.displayed = filterAndSort(newEntries, this.searchText);
        Set<UUID> live = new HashSet<>(newEntries.size());
        for (Networking.RosterEntry e : newEntries) live.add(e.identityId());
        selectedIds.retainAll(live);
        if (selectedIds.isEmpty()) batchMode = -1;
        setTextColor(ID_COLONY_NAME, colonyName.isEmpty() ? "[no colony]" : colonyName, TXT_GRAY);
        setTextColor(ID_MAGCOUNT, formatBig(playerMagicule), TXT_DARK);
        applyEmptyText();
        refreshCounts();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
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

    /** Compact number formatting (e.g. 23200 -> "23.2k", 15000000 -> "15.0M"). */
    private static String formatBig(double v) {
        if (v >= 1_000_000.0) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
        if (v >= 1_000.0)     return String.format(Locale.ROOT, "%.1fk", v / 1_000.0);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    // ---- small helpers ----

    private void setTextColor(String id, String text, int color) {
        Text pane = findPaneOfTypeByID(id, Text.class);
        if (pane != null) { pane.setText(Component.literal(text)); pane.setColors(color); }
    }

    private void setBoxColor(String id, int[] rgb) {
        Box box = findPaneOfTypeByID(id, Box.class);
        if (box != null) box.setColor(rgb[0], rgb[1], rgb[2]);
    }

    // ------------------------------------------------------------------
    // Open + live-refresh routing
    // ------------------------------------------------------------------

    public static void route(List<Networking.RosterEntry> entries, double playerMagicule, String colonyName) {
        Minecraft mc = Minecraft.getInstance();

        BOScreen rosterScreen = (instance != null) ? safeScreen(instance) : null;

        boolean blockUiConfirm = mc.screen instanceof BOScreen bo
                && bo.getWindow() instanceof WindowCollapseConfirm;
        boolean vanillaConfirmOverBlockUiRoster = rosterScreen != null
                && mc.screen instanceof ConfirmCollapseScreen c && c.getParent() == rosterScreen;

        if (rosterScreen != null
                && (mc.screen == rosterScreen || blockUiConfirm || vanillaConfirmOverBlockUiRoster)) {
            instance.setEntries(entries, playerMagicule, colonyName);
            return;
        }

        if (mc.screen instanceof RosterScreen open) {
            open.setEntries(entries);
            return;
        }
        if (mc.screen instanceof ConfirmCollapseScreen c
                && c.getParent() instanceof RosterScreen parent) {
            parent.setEntries(entries);
            return;
        }

        if (blockUiConfirm) return;

        try {
            new WindowRoster(entries, playerMagicule, colonyName).open();
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
