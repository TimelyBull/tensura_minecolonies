package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonImage;
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
 * The subordinate roster — native BlockUI window, restyled toward the concept
 * art (a custom dark/gold panel via {@code gui/roster_bg.png} with BlockUI panes
 * on top). Replaces the vanilla {@link RosterScreen}, which is kept as a
 * fail-closed fallback.
 *
 * <p>Layout ({@code gui/windowroster.xml}): a HEADER strip (slime icon, colony
 * name, awakening + player line, town-hall level, population, magicule counter,
 * races), the "SUBORDINATES" title + a search field, the column headers
 * (Name / Location / EP / Action), a {@link ScrollingList} of rows, and a FOOTER
 * of "Group Summon" / "Group Send" bulk buttons.
 *
 * <p><b>Selection is per-row checkbox toggles</b> (BlockUI can't do the old drag
 * gesture — see {@code docs/decisions.md}). Each row's checkbox is a clickable
 * {@link ButtonImage} swapping the plain / checked mini textures; clicking it
 * toggles that identity in {@link #selectedIds}. The selection is mode-locked
 * (one batch = one mode), exactly as before, so a batch maps cleanly to a single
 * {@link Networking.BulkSummonPayload} / {@link Networking.BulkSendPayload}. The
 * per-row action button ({@link Networking.ActOnIdentityPayload}) and the group
 * buttons share that mode logic.
 */
@OnlyIn(Dist.CLIENT)
public class WindowRoster extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowroster.xml");

    // Centralized texture paths (fail-closed: a missing texture must not crash).
    static final ResourceLocation TEX_BACKGROUND =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "textures/gui/roster_bg.png");
    static final ResourceLocation TEX_SLIME =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "textures/gui/roster_slime.png");
    static final ResourceLocation SEL_UNCHECKED =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_mini.png");
    static final ResourceLocation SEL_CHECKED =
            ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/builderhut/builder_button_mini_check.png");

    // Pane ids (must match windowroster.xml).
    private static final String ID_SEARCH      = "search";
    private static final String ID_LIST        = "roster";
    private static final String ID_GROUP_SUM   = "groupSummon";
    private static final String ID_GROUP_SEND  = "groupSend";
    private static final String ID_SELCOUNT    = "selCount";
    // header pane ids
    private static final String ID_COLONY_NAME = "colonyName";
    private static final String ID_AWAKENING   = "awakening";
    private static final String ID_PLAYER_NAME = "playerName";
    private static final String ID_TOWNHALL    = "townhall";
    private static final String ID_POPULATION  = "population";
    private static final String ID_MAGCOUNT    = "magCount";
    private static final String ID_RACES       = "races";
    // row pane ids
    private static final String ROW_SEL    = "sel";
    private static final String ROW_NAME   = "name";
    private static final String ROW_LOC    = "location";
    private static final String ROW_EP     = "ep";
    private static final String ROW_ACT    = "act";

    // Colors tuned for light text on the dark concept panel.
    private static final int COLOR_NAME        = 0xFFFFFFFF;
    private static final int COLOR_EP           = 0xFFCFCFCF;
    private static final int COLOR_LOC_SIDE     = 0xFF8FE0A0; // light green — "By Your Side"
    private static final int COLOR_LOC_COLONY   = 0xFFE6C77A; // light gold — "At The Colony"
    private static final int COLOR_COLONY_NAME  = 0xFFEAD9A0;
    private static final int COLOR_AWAKEN       = 0xFFB57EDC; // purple
    private static final int COLOR_PLAYER       = 0xFFE6C77A;
    private static final int COLOR_INFO         = 0xFFD8D8D8;
    private static final int COLOR_MAGICULE     = 0xFF66D0E0; // cyan

    private static final int SELECTION_CAP = ExampleMod.BULK_SUMMON_CAP;

    private static final Comparator<Networking.RosterEntry> EP_DESC =
            Comparator.comparingDouble(Networking.RosterEntry::ep).reversed();

    /** Most recently constructed window — used by {@link #route} to refresh in
     *  place (incl. behind a layered collapse-confirm). A stale reference is
     *  harmless; {@code route} only refreshes it while it's actually showing. */
    private static WindowRoster instance;

    private List<Networking.RosterEntry> entries;
    private List<Networking.RosterEntry> displayed;
    private String searchText = "";
    private double playerMagicule;
    private Networking.RosterHeader header;

    /** Bulk-selection set, insertion-ordered (drives fan placement server-side). */
    private final Set<UUID> selectedIds = new LinkedHashSet<>();
    /** Mode the batch is locked to (first selected row's mode); -1 = none. */
    private int batchMode = -1;

    private ScrollingList list;
    private TextField searchField;
    private Button groupSummonButton;
    private Button groupSendButton;
    private Text selCountText;

    public WindowRoster(List<Networking.RosterEntry> entries, double playerMagicule,
                        Networking.RosterHeader header) {
        super(XML);
        instance = this;
        this.entries = entries;
        this.playerMagicule = playerMagicule;
        this.header = header != null ? header : Networking.RosterHeader.empty();
        this.displayed = filterAndSort(entries, this.searchText);

        // Per-row controls share an id across rows; the handler resolves which
        // row via ScrollingList.getListElementIndexByPane (MC idiom).
        registerButton(ROW_ACT, (Button b) -> onRowAction(b));
        registerButton(ROW_SEL, (Button b) -> onToggleRow(b));
        registerButton(ID_GROUP_SUM, (Button b) -> onGroup(1));   // Summon at-colony selection
        registerButton(ID_GROUP_SEND, (Button b) -> onGroup(0));  // Send at-side selection
    }

    @Override
    public void onOpened() {
        super.onOpened();
        instance = this;

        this.searchField       = findPaneOfTypeByID(ID_SEARCH, TextField.class);
        this.groupSummonButton = findPaneOfTypeByID(ID_GROUP_SUM, Button.class);
        this.groupSendButton   = findPaneOfTypeByID(ID_GROUP_SEND, Button.class);
        this.selCountText      = findPaneOfTypeByID(ID_SELCOUNT, Text.class);
        this.list              = findPaneOfTypeByID(ID_LIST, ScrollingList.class);

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
        bindHeader();
        applyEmptyText();
        refreshFooter();
    }

    // ------------------------------------------------------------------
    // Header strip
    // ------------------------------------------------------------------

    private void bindHeader() {
        setText(ID_COLONY_NAME,
                header.colonyName().isEmpty() ? "[No Colony]" : header.colonyName(),
                COLOR_COLONY_NAME);
        setText(ID_AWAKENING, header.awakeningTitle(), COLOR_AWAKEN); // "" when not awakened
        setText(ID_PLAYER_NAME, localPlayerName(), COLOR_PLAYER);
        setText(ID_TOWNHALL,
                "Town Hall Level: " + (header.townHallLevel() > 0 ? header.townHallLevel() : "-"),
                COLOR_INFO);
        setText(ID_POPULATION,
                "Population: " + header.population() + "/" + header.maxPopulation(),
                COLOR_INFO);
        setText(ID_MAGCOUNT, "Magicules: " + formatBig(playerMagicule), COLOR_MAGICULE);
        setText(ID_RACES,
                "Races: " + (header.racesText().isEmpty() ? "Colonist" : header.racesText()),
                COLOR_INFO);
    }

    private void setText(String id, String text, int color) {
        Text pane = findPaneOfTypeByID(id, Text.class);
        if (pane != null) {
            pane.setText(Component.literal(text));
            pane.setColors(color);
        }
    }

    private static String localPlayerName() {
        try {
            var p = Minecraft.getInstance().player;
            return p != null ? p.getGameProfile().getName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

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
        Text loc = rowPane.findPaneOfTypeByID(ROW_LOC, Text.class);
        if (loc != null) {
            loc.setText(Component.literal(inColony ? "At The Colony" : "By Your Side"));
            loc.setColors(inColony ? COLOR_LOC_COLONY : COLOR_LOC_SIDE);
        }
        Text ep = rowPane.findPaneOfTypeByID(ROW_EP, Text.class);
        if (ep != null) {
            ep.setText(Component.literal(formatComma(e.ep())));
            ep.setColors(COLOR_EP);
        }
        // Checkbox: a clickable ButtonImage; swap the checked / unchecked texture.
        ButtonImage sel = rowPane.findPaneOfTypeByID(ROW_SEL, ButtonImage.class);
        if (sel != null) {
            sel.setImage(selectedIds.contains(e.identityId()) ? SEL_CHECKED : SEL_UNCHECKED);
        }
        Button act = rowPane.findPaneOfTypeByID(ROW_ACT, Button.class);
        if (act != null) {
            // Keep existing semantics: At The Colony -> Summon (back); By Your Side -> Send.
            act.setText(Component.literal(inColony ? "Summon" : "Send"));
        }
    }

    /** Per-row immediate action (single summon/send). */
    private void onRowAction(Button button) {
        int idx = rowIndexOf(button);
        if (idx < 0) return;
        UUID id = displayed.get(idx).identityId();
        PacketDistributor.sendToServer(new Networking.ActOnIdentityPayload(id));
    }

    /** Per-row checkbox click — toggle this identity in the selection. */
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

    // ------------------------------------------------------------------
    // Footer group actions
    // ------------------------------------------------------------------

    /** Fire the bulk action for {@code mode} (1 = summon at-colony, 0 = send
     *  at-side) on the current selection — but only if the selection is locked
     *  to that mode (it always is, since selection is mode-locked). */
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

    private void refreshFooter() {
        // Both group buttons always visible (concept layout). Only the one
        // matching the current selection mode is "live"; we surface that via the
        // selection-count line. Mode-lock keeps a batch to a single payload.
        if (selCountText != null) {
            boolean any = !selectedIds.isEmpty();
            if (any) {
                String which = batchMode == 1 ? "Summon" : "Send";
                String cap = selectedIds.size() >= SELECTION_CAP ? "  (max)" : "";
                selCountText.setText(Component.literal(
                        selectedIds.size() + "/" + SELECTION_CAP + " selected — Group "
                                + which + cap));
            } else {
                selCountText.setText(Component.literal("Tick rows to group, or use a row's button."));
            }
            selCountText.setColors(COLOR_INFO);
        }
    }

    // ------------------------------------------------------------------
    // Search + data refresh
    // ------------------------------------------------------------------

    private void onSearchChanged(String text) {
        this.searchText = text == null ? "" : text;
        this.displayed = filterAndSort(this.entries, this.searchText);
        applyEmptyText();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    /** Apply a fresh server roster, preserving search text + live selection. */
    public void setEntries(List<Networking.RosterEntry> newEntries, double magicule,
                           Networking.RosterHeader newHeader) {
        this.entries = newEntries;
        this.playerMagicule = magicule;
        if (newHeader != null) this.header = newHeader;
        this.displayed = filterAndSort(newEntries, this.searchText);
        // Drop selections that no longer exist server-side.
        Set<UUID> live = new HashSet<>(newEntries.size());
        for (Networking.RosterEntry e : newEntries) live.add(e.identityId());
        selectedIds.retainAll(live);
        if (selectedIds.isEmpty()) batchMode = -1;
        bindHeader();
        applyEmptyText();
        if (list != null) list.refreshElementPanes();
        refreshFooter();
    }

    private void applyEmptyText() {
        if (list == null) return;
        String msg = entries.isEmpty()
                ? "No named subordinates yet."
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

    /** Full comma-grouped number (e.g. 100000 -> "100,000") for the EP column. */
    private static String formatComma(double v) {
        return String.format(Locale.ROOT, "%,d", Math.round(v));
    }

    /** Compact formatting for the magicule counter (e.g. 5.0M). */
    private static String formatBig(double v) {
        if (v >= 1_000_000.0) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
        if (v >= 1_000.0)     return String.format(Locale.ROOT, "%.1fk", v / 1_000.0);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    // ------------------------------------------------------------------
    // Open + live-refresh routing
    // ------------------------------------------------------------------

    /**
     * Route an incoming roster: refresh the open window in place (BlockUI or the
     * vanilla fallback, including while a collapse-confirm dialog is layered over
     * it), or open a fresh window if none is showing. Vanilla {@link RosterScreen}
     * is the fail-closed fallback if the BlockUI window can't be opened.
     */
    public static void route(List<Networking.RosterEntry> entries, double playerMagicule,
                             Networking.RosterHeader header) {
        Minecraft mc = Minecraft.getInstance();

        BOScreen rosterScreen = (instance != null) ? safeScreen(instance) : null;

        boolean blockUiConfirm = mc.screen instanceof BOScreen bo
                && bo.getWindow() instanceof WindowCollapseConfirm;
        boolean vanillaConfirmOverBlockUiRoster = rosterScreen != null
                && mc.screen instanceof ConfirmCollapseScreen c && c.getParent() == rosterScreen;

        if (rosterScreen != null
                && (mc.screen == rosterScreen || blockUiConfirm || vanillaConfirmOverBlockUiRoster)) {
            instance.setEntries(entries, playerMagicule, header);
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

        if (blockUiConfirm) return;

        // Nothing open → open fresh (BlockUI primary, vanilla fail-closed).
        try {
            new WindowRoster(entries, playerMagicule, header).open();
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
