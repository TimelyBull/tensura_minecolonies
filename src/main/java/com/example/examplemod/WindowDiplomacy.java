package com.example.examplemod;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.Box;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The Diplomacy tab — native BlockUI window styled to match
 * {@link WindowRoster} (same paper panel, bordered cards, colour
 * palette). Server-snapshot-driven: every action round-trips a
 * {@code DiplomacyActionPayload} and the server re-sends the snapshot,
 * which {@link #openOrRefresh} applies in place.
 *
 * <p><b>No-overlap rule:</b> the right-hand detail pane shows EITHER
 * the offer cards OR the active-deal card (you can't accept while a
 * deal runs anyway), and EITHER the send-envoy buttons OR the
 * deliver/collect button — every section is toggled via visibility,
 * so text never draws over text.
 *
 * <p>The vanilla {@link DiplomacyScreen} is kept as a fail-closed
 * fallback, mirroring the WindowRoster / RosterScreen split.
 */
@OnlyIn(Dist.CLIENT)
public class WindowDiplomacy extends AbstractWindowSkeleton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation XML =
            ResourceLocation.fromNamespaceAndPath("tensura_minecolonies", "gui/windowdiplomacy.xml");

    // Colours — identical palette to WindowRoster.
    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_GRAY = 0xFF7A6E58;
    private static final int[] BORDER_CARD = {0x9A, 0x80, 0x55};
    private static final int[] BORDER_SELECTED = {0x4A, 0x6E, 0xB5};
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};

    /** Row pitch of the faction list (matches the XML row template). */
    private static final int ROW_PIXEL_HEIGHT = 24;
    /** Progress-bar fill width at 100% (matches progressTrack − border). */
    private static final int PROGRESS_FILL_MAX = 142;

    // ---- parsed snapshot ----
    private record OfferRow(String dealId, String title, String req, String reward, int daysLeft) {}
    private record ActiveRow(String title, String req, String reward, byte state, int pct,
                             int hoursLeft, boolean lend, int returnHours, boolean rite,
                             boolean canDeliver, boolean canCollect) {}
    private record FactionRow(String id, String name, double standing, String tier,
                              int tierColor, RelationsState state, boolean closed,
                              boolean pendingReply, boolean canSend, boolean hasNew,
                              boolean canCaravan, boolean giftAvailable,
                              List<OfferRow> offers, ActiveRow active) {}

    /** Most recently constructed window — {@link #openOrRefresh} refreshes
     *  it in place when it is the current screen. */
    private static WindowDiplomacy instance;

    private boolean enabled;
    private boolean canTravel;
    private List<FactionRow> factions = new ArrayList<>();
    private int selected = 0;

    private ScrollingList list;

    public WindowDiplomacy(CompoundTag snapshot) {
        super(XML);
        instance = this;
        parse(snapshot);

        registerButton("roster", (Button b) ->
                PacketDistributor.sendToServer(new Networking.RequestRosterPayload()));
        registerButton("sendEnvoy", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_SEND_ENVOY, "", false));
        registerButton("sendGift", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_SEND_ENVOY, "", true));
        registerButton("deliver", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_DELIVER, "", false));
        registerButton("collect", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_COLLECT, "", false));
        registerButton("o1Accept", (Button b) -> acceptOffer(0));
        registerButton("o2Accept", (Button b) -> acceptOffer(1));
        // Stage 3 — relationship rewards.
        registerButton("travel", (Button b) -> PacketDistributor.sendToServer(
                new Networking.DiplomacyActionPayload(
                        Networking.DiplomacyActionPayload.ACTION_TRAVEL_HOME, "", "", false)));
        registerButton("caravan", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_CLAIM_CARAVAN, "", false));
        registerButton("gift", (Button b) -> sendAction(
                Networking.DiplomacyActionPayload.ACTION_CLAIM_GIFT, "", false));
    }

    /** Open the window, or refresh the already-open one in place (the
     *  server re-sends the snapshot after every action). Falls back to
     *  the vanilla DiplomacyScreen if BlockUI fails. */
    public static void openOrRefresh(CompoundTag snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (instance != null && mc.screen != null && mc.screen == safeScreen(instance)) {
            instance.parse(snapshot);
            instance.refreshAll();
            return;
        }
        try {
            new WindowDiplomacy(snapshot).open();
        } catch (Throwable t) {
            LOGGER.error("[TM] diplomacy: native BlockUI window failed to open; "
                    + "falling back to the vanilla screen", t);
            DiplomacyScreen.openOrRefresh(snapshot);
        }
    }

    private static com.ldtteam.blockui.BOScreen safeScreen(WindowDiplomacy w) {
        try {
            return w.getScreen();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();
        instance = this;
        setBoxColor("divider", BORDER_DIVIDER);
        setBoxColor("detailBox", BORDER_CARD);
        this.list = findPaneOfTypeByID("factions", ScrollingList.class);
        if (this.list != null) {
            this.list.setDataProvider(new ScrollingList.DataProvider() {
                @Override public int getElementCount() { return enabled ? factions.size() : 0; }
                @Override public void updateElement(int index, Pane rowPane) { bindRow(index, rowPane); }
            });
        }
        refreshAll();
    }

    // ------------------------------------------------------------------
    // Snapshot parse
    // ------------------------------------------------------------------

    private void parse(CompoundTag snapshot) {
        this.enabled = snapshot.getBoolean("enabled");
        this.canTravel = snapshot.getBoolean("canTravel");
        List<FactionRow> rows = new ArrayList<>();
        ListTag factionList = snapshot.getList("factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < factionList.size(); i++) {
            CompoundTag f = factionList.getCompound(i);
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
                        d.getInt("hoursLeft"), d.getBoolean("lend"), d.getInt("returnHours"),
                        d.getBoolean("rite"), d.getBoolean("canDeliver"), d.getBoolean("canCollect"));
            }
            rows.add(new FactionRow(f.getString("id"), f.getString("name"),
                    f.getDouble("standing"), f.getString("tier"), f.getInt("tierColor"),
                    RelationsState.byId(f.getByte("state")), f.getBoolean("closed"),
                    f.getBoolean("pendingReply"), f.getBoolean("canSend"),
                    f.getBoolean("hasNew"), f.getBoolean("canCaravan"),
                    f.getBoolean("giftAvailable"), offers, active));
        }
        this.factions = rows;
        if (selected >= rows.size()) selected = 0;
    }

    // ------------------------------------------------------------------
    // Faction list rows (left)
    // ------------------------------------------------------------------

    private void bindRow(int index, Pane rowPane) {
        if (index < 0 || index >= factions.size()) return;
        FactionRow row = factions.get(index);
        boolean isSelected = index == selected;

        Box card = rowPane.findPaneOfTypeByID("fcard", Box.class);
        if (card != null) {
            int[] rgb = isSelected ? BORDER_SELECTED : BORDER_CARD;
            card.setColor(rgb[0], rgb[1], rgb[2]);
        }
        Text name = rowPane.findPaneOfTypeByID("fname", Text.class);
        if (name != null) {
            // "!" badge — this faction has offers the player hasn't
            // looked at yet; clears when the tab is clicked.
            name.setText(Component.literal((row.hasNew() ? "! " : "") + row.name()));
            name.setColors(row.hasNew() ? 0xFF8A2E2E
                    : isSelected ? 0xFF274A6B : TXT_DARK);
        }
        Text state = rowPane.findPaneOfTypeByID("fstate", Text.class);
        if (state != null) {
            // Multi-deal tracking at a glance: a running deal shows its
            // % right in the faction list.
            String label;
            int color;
            if (row.active() != null) {
                label = "deal " + row.active().pct() + "%";
                color = 0xFF274A6B;
            } else {
                label = switch (row.state()) {
                    case OPEN -> "Diplomacy";
                    case PACT -> "Alliance";
                    default -> row.closed() ? "Closed" : "";
                };
                color = row.state() == RelationsState.PACT ? 0xFF274A6B
                        : row.closed() ? 0xFF8A2E2E : 0xFF2F5A28;
            }
            state.setText(Component.literal(label));
            state.setColors(color);
        }
    }

    /** Row click → select (the WindowRoster rowIndexAt idiom). */
    @Override
    public boolean click(double mx, double my) {
        boolean consumed = super.click(mx, my);
        if (!consumed && enabled && list != null) {
            int row = rowIndexAt(mx, my);
            if (row >= 0) {
                boolean changed = row != selected;
                selected = row;
                // Clicking a tab clears its "!" badge (server marks the
                // current offers seen and re-sends the snapshot).
                if (factions.get(row).hasNew()) {
                    PacketDistributor.sendToServer(new Networking.DiplomacyActionPayload(
                            Networking.DiplomacyActionPayload.ACTION_MARK_SEEN,
                            factions.get(row).id(), "", false));
                }
                if (changed) refreshAll();
                return true;
            }
        }
        return consumed;
    }

    private int rowIndexAt(double mx, double my) {
        if (list == null) return -1;
        int lx = list.getX(), ly = list.getY(), lw = list.getWidth(), lh = list.getHeight();
        if (mx < lx || mx >= lx + lw || my < ly || my >= ly + lh) return -1;
        double localY = (my - ly) + list.getScrollY();
        int idx = (int) Math.floor(localY / ROW_PIXEL_HEIGHT);
        return (idx >= 0 && idx < factions.size()) ? idx : -1;
    }

    // ------------------------------------------------------------------
    // Detail pane (right) — visibility-toggled sections, no overlap
    // ------------------------------------------------------------------

    private void refreshAll() {
        boolean hasSelection = enabled && !factions.isEmpty();
        setVisible("disabled", !enabled);
        Text disabledPane = findPaneOfTypeByID("disabled", Text.class);
        if (disabledPane != null) disabledPane.setColors(TXT_GRAY);
        setVisible("detailBox", hasSelection);
        setVisible("dName", hasSelection);
        setVisible("dTier", hasSelection);
        setVisible("dState", hasSelection);
        setVisible("dStatus", hasSelection);

        // Default-hide every conditional section, then re-show below.
        for (String id : new String[] {
                "o1Card", "o1Title", "o1Req", "o1Reward", "o1Accept",
                "o2Card", "o2Title", "o2Req", "o2Reward", "o2Accept",
                "aCard", "aTitle", "aReq", "aReward", "aState",
                "progressTrack", "progressFill", "aPct",
                "sendEnvoy", "sendGift", "deliver", "collect",
                "caravan", "gift"}) {
            setVisible(id, false);
        }
        // The caravan-network travel perk (header) — shown while the
        // layer is live; enabled when any PACT exists + off cooldown.
        Button travel = findPaneOfTypeByID("travel", Button.class);
        if (travel != null) {
            travel.setVisible(enabled);
            travel.setEnabled(canTravel);
        }
        if (list != null) list.refreshElementPanes();
        if (!hasSelection) return;

        FactionRow row = factions.get(selected);
        setTextColor("dName", row.name(), TXT_DARK);
        setTextColor("dTier", row.tier() + " · " + Math.round(row.standing()),
                0xFF000000 | row.tierColor());
        Text statePane = findPaneOfTypeByID("dState", Text.class);
        if (statePane != null) {
            statePane.setText(Component.literal(row.state().displayName()));
            statePane.setColors(switch (row.state()) {
                case OPEN -> 0xFF2F5A28;
                case PACT -> 0xFF274A6B;
                default -> TXT_GRAY;
            });
        }

        // Status line — one sentence, gray.
        String status;
        if (row.closed()) {
            status = row.name() + " will not treat with you"
                    + (!row.offers().isEmpty() || row.active() != null
                            ? " — save through grave atonement." : ".");
        } else if (row.pendingReply()) {
            status = "Your envoy awaits a reply…";
        } else if (row.state() == RelationsState.NONE) {
            status = "No relations. Send an envoy to open diplomacy.";
        } else if (row.active() != null) {
            status = "";
        } else if (row.offers().isEmpty()) {
            status = "No offers on the table today.";
        } else {
            status = "Their offers:";
        }
        setTextColor("dStatus", status, TXT_GRAY);

        // Section: send-envoy buttons (NONE + not closed).
        if (row.state() == RelationsState.NONE && !row.closed()) {
            setButton("sendEnvoy", row.canSend());
            setButton("sendGift", row.canSend());
        }

        // Stage 3 — rewards: the PACT caravan + the standing gift.
        if (row.state() == RelationsState.PACT) {
            setButton("caravan", row.canCaravan());
        }
        if (row.giftAvailable()) {
            setButton("gift", true);
        }

        // Section: the active deal OR the offers — never both. A
        // FORECLOSED faction shows its sections too (the mending rite).
        if (row.state() != RelationsState.NONE || row.closed()) {
            ActiveRow active = row.active();
            if (active != null) {
                setVisible("aCard", true);
                setBoxColor("aCard", BORDER_CARD);
                setVisible("progressTrack", true);
                setBoxColor("progressTrack", BORDER_CARD);
                setTextColor("aTitle", "Active: " + active.title(), TXT_DARK);
                setTextColor("aReq", active.req(), TXT_GRAY);
                setTextColor("aReward", "Pays: " + active.reward(), TXT_GRAY);
                String stateText = switch (active.state()) {
                    case ActiveDeal.STATE_AWAITING_PAYOFF -> active.lend()
                            ? "Citizens away — back in ~" + active.returnHours() + "h"
                            : "Payment is on its way…";
                    case ActiveDeal.STATE_READY -> "Ready to collect!";
                    default -> active.hoursLeft() + "h left to fulfil";
                };
                setTextColor("aState", stateText, TXT_GRAY);
                Image fill = findPaneOfTypeByID("progressFill", Image.class);
                if (fill != null) {
                    int width = Math.max(1, PROGRESS_FILL_MAX * active.pct() / 100);
                    fill.setVisible(active.pct() > 0);
                    fill.setSize(width, 8);
                }
                setTextColor("aPct", active.pct() + "%", TXT_DARK);
                setVisible("aPct", true);
                if (active.canDeliver()) {
                    setButton("deliver", true);
                    Button deliver = findPaneOfTypeByID("deliver", Button.class);
                    if (deliver != null) {
                        deliver.setText(Component.literal(
                                active.rite() ? "Perform Rite" : "Deliver"));
                    }
                }
                if (active.canCollect()) setButton("collect", true);
            } else {
                List<OfferRow> offers = row.offers();
                if (offers.size() > 0) showOffer("o1", offers.get(0));
                if (offers.size() > 1) showOffer("o2", offers.get(1));
            }
        }
    }

    private void showOffer(String prefix, OfferRow offer) {
        setVisible(prefix + "Card", true);
        setBoxColor(prefix + "Card", BORDER_CARD);
        setTextColor(prefix + "Title", offer.title(), TXT_DARK);
        setTextColor(prefix + "Req", offer.req(), TXT_GRAY);
        setTextColor(prefix + "Reward",
                "Pays: " + offer.reward() + "  ·  lapses in " + offer.daysLeft() + "d", TXT_GRAY);
        setButton(prefix + "Accept", true);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void acceptOffer(int offerIndex) {
        if (factions.isEmpty()) return;
        FactionRow row = factions.get(selected);
        if (offerIndex >= row.offers().size()) return;
        PacketDistributor.sendToServer(new Networking.DiplomacyActionPayload(
                Networking.DiplomacyActionPayload.ACTION_ACCEPT_DEAL,
                row.id(), row.offers().get(offerIndex).dealId(), false));
    }

    private void sendAction(byte action, String dealId, boolean flag) {
        if (factions.isEmpty()) return;
        PacketDistributor.sendToServer(new Networking.DiplomacyActionPayload(
                action, factions.get(selected).id(), dealId, flag));
    }

    // ---- small helpers (the WindowRoster idiom) ----

    private void setVisible(String id, boolean visible) {
        Pane pane = findPaneByID(id);
        if (pane != null) pane.setVisible(visible);
    }

    private void setButton(String id, boolean activeEnabled) {
        Button button = findPaneOfTypeByID(id, Button.class);
        if (button != null) {
            button.setVisible(true);
            button.setEnabled(activeEnabled);
        }
    }

    private void setTextColor(String id, String text, int color) {
        Text pane = findPaneOfTypeByID(id, Text.class);
        if (pane != null) {
            pane.setVisible(true);
            pane.setText(Component.literal(text));
            pane.setColors(color);
        }
    }

    private void setBoxColor(String id, int[] rgb) {
        Box box = findPaneOfTypeByID(id, Box.class);
        if (box != null) box.setColor(rgb[0], rgb[1], rgb[2]);
    }
}
