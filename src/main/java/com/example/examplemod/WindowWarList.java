package com.example.examplemod;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.Box;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The rival-colony Wars window (Stage C) — opens from the roster's Wars
 * button. Lists the settlements the player has DISCOVERED; each row's
 * button is "Declare War" (idle settlement) or "Retreat" (one the player
 * is currently assaulting) — the snapshot-flag button-swap idiom. Same
 * paper styling + row idiom as {@link WindowLendPicker}/{@link WindowRoster}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowWarList extends AbstractWindowSkeleton {

    private static final ResourceLocation XML = ResourceLocation
            .fromNamespaceAndPath("tensura_minecolonies", "gui/windowwarlist.xml");

    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_GRAY = 0xFF7A6E58;
    private static final int[] BORDER_CARD = {0x9A, 0x80, 0x55};
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};

    private record Row(int id, String faction, String where, String state,
                       boolean canDeclare, boolean canRetreat) {}

    private final List<Row> rows = new ArrayList<>();
    private ScrollingList list;

    public WindowWarList(CompoundTag data) {
        super(XML);
        ListTag listTag = data.getList("settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag e = listTag.getCompound(i);
            rows.add(new Row(e.getInt("id"), e.getString("faction"), e.getString("where"),
                    e.getString("state"), e.getBoolean("canDeclare"), e.getBoolean("canRetreat")));
        }
        registerButton("close", (Button b) -> close());
        registerButton("wact", this::onRowAction);
    }

    @Override
    public void onOpened() {
        super.onOpened();
        Box divider = findPaneOfTypeByID("divider", Box.class);
        if (divider != null) divider.setColor(BORDER_DIVIDER[0], BORDER_DIVIDER[1], BORDER_DIVIDER[2]);
        Text subtitle = findPaneOfTypeByID("subtitle", Text.class);
        if (subtitle != null) {
            subtitle.setText(Component.literal(rows.isEmpty()
                    ? "No settlements discovered yet — go exploring."
                    : "Discovered settlements (" + rows.size() + ")."));
            subtitle.setColors(TXT_GRAY);
        }
        this.list = findPaneOfTypeByID("settlements", ScrollingList.class);
        if (this.list != null) {
            this.list.setDataProvider(new ScrollingList.DataProvider() {
                @Override public int getElementCount() { return rows.size(); }
                @Override public void updateElement(int index, Pane rowPane) { bindRow(index, rowPane); }
            });
        }
    }

    private void bindRow(int index, Pane rowPane) {
        if (index < 0 || index >= rows.size()) return;
        Row r = rows.get(index);
        Box card = rowPane.findPaneOfTypeByID("scard", Box.class);
        if (card != null) card.setColor(BORDER_CARD[0], BORDER_CARD[1], BORDER_CARD[2]);
        Text faction = rowPane.findPaneOfTypeByID("sfaction", Text.class);
        if (faction != null) {
            faction.setText(Component.literal("§l#" + r.id() + " " + r.faction() + "§r"));
            faction.setColors(TXT_DARK);
        }
        Text where = rowPane.findPaneOfTypeByID("swhere", Text.class);
        if (where != null) { where.setText(Component.literal(r.where())); where.setColors(TXT_GRAY); }
        Text state = rowPane.findPaneOfTypeByID("sstate", Text.class);
        if (state != null) { state.setText(Component.literal(r.state())); state.setColors(TXT_GRAY); }
        Button act = rowPane.findPaneOfTypeByID("wact", Button.class);
        if (act != null) {
            if (r.canRetreat()) {
                act.setText(Component.literal("Retreat"));
                act.setEnabled(true);
            } else if (r.canDeclare()) {
                act.setText(Component.literal("Declare"));
                act.setEnabled(true);
            } else {
                act.setText(Component.literal("—"));
                act.setEnabled(false);
            }
        }
    }

    private void onRowAction(Button button) {
        if (list == null) return;
        int idx = list.getListElementIndexByPane(button);
        if (idx < 0 || idx >= rows.size()) return;
        Row r = rows.get(idx);
        if (r.canRetreat()) {
            PacketDistributor.sendToServer(new Networking.WarActionPayload(
                    Networking.WarActionPayload.RETREAT, r.id(), new ArrayList<>()));
            close();
        } else if (r.canDeclare()) {
            // Ask the server for the war-party picker for this settlement.
            PacketDistributor.sendToServer(new Networking.WarActionPayload(
                    Networking.WarActionPayload.PICKER, r.id(), new ArrayList<>()));
            close();
        }
    }
}
