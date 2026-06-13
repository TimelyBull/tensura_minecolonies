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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The war-party picker (rival-colony Stage C) — opens after Declare War.
 * Lists the player's LOADED Tensura subordinates; click rows to toggle who
 * marches (up to the cap); March declares the war and teleports the player
 * + the chosen party to the settlement. Mirrors {@link WindowLendPicker}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowWarPicker extends AbstractWindowSkeleton {

    private static final ResourceLocation XML = ResourceLocation
            .fromNamespaceAndPath("tensura_minecolonies", "gui/windowwarpicker.xml");

    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_GRAY = 0xFF7A6E58;
    private static final int[] BORDER_CARD = {0x9A, 0x80, 0x55};
    private static final int[] BORDER_SELECTED = {0xB5, 0x4A, 0x4A};
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};
    private static final int ROW_PIXEL_HEIGHT = 22;

    private record Candidate(int id, String name, int ep) {}

    private final int settlementId;
    private final String faction;
    private final int cap;
    private final List<Candidate> candidates = new ArrayList<>();
    private final Set<Integer> selected = new LinkedHashSet<>();

    private ScrollingList list;
    private Button confirmButton;

    public WindowWarPicker(CompoundTag data) {
        super(XML);
        this.settlementId = data.getInt("settlementId");
        this.faction = data.getString("faction");
        this.cap = Math.max(1, data.getInt("cap"));
        ListTag listTag = data.getList("candidates", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag c = listTag.getCompound(i);
            candidates.add(new Candidate(c.getInt("id"), c.getString("name"), c.getInt("ep")));
        }
        registerButton("confirm", (Button b) -> confirm());
        registerButton("cancel", (Button b) -> close());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        Box divider = findPaneOfTypeByID("divider", Box.class);
        if (divider != null) divider.setColor(BORDER_DIVIDER[0], BORDER_DIVIDER[1], BORDER_DIVIDER[2]);
        Text titlePane = findPaneOfTypeByID("title", Text.class);
        if (titlePane != null) {
            titlePane.setText(Component.literal("§lMarch on " + faction + "§r"));
            titlePane.setColors(TXT_DARK);
        }
        Text subtitle = findPaneOfTypeByID("subtitle", Text.class);
        if (subtitle != null) {
            subtitle.setText(Component.literal(candidates.isEmpty()
                    ? "No subordinates at your side — summon some first."
                    : "Pick up to " + cap + " subordinates to take into battle."));
            subtitle.setColors(TXT_GRAY);
        }
        this.confirmButton = findPaneOfTypeByID("confirm", Button.class);
        this.list = findPaneOfTypeByID("candidates", ScrollingList.class);
        if (this.list != null) {
            this.list.setDataProvider(new ScrollingList.DataProvider() {
                @Override public int getElementCount() { return candidates.size(); }
                @Override public void updateElement(int index, Pane rowPane) { bindRow(index, rowPane); }
            });
        }
        refreshConfirm();
    }

    private void bindRow(int index, Pane rowPane) {
        if (index < 0 || index >= candidates.size()) return;
        Candidate candidate = candidates.get(index);
        boolean isSelected = selected.contains(candidate.id());
        Box card = rowPane.findPaneOfTypeByID("ccard", Box.class);
        if (card != null) {
            int[] rgb = isSelected ? BORDER_SELECTED : BORDER_CARD;
            card.setColor(rgb[0], rgb[1], rgb[2]);
        }
        Text name = rowPane.findPaneOfTypeByID("cname", Text.class);
        if (name != null) {
            name.setText(Component.literal((isSelected ? "⚔ " : "") + candidate.name()));
            name.setColors(isSelected ? 0xFF6B2727 : TXT_DARK);
        }
        Text ep = rowPane.findPaneOfTypeByID("cep", Text.class);
        if (ep != null) {
            ep.setText(Component.literal("EP " + candidate.ep()));
            ep.setColors(TXT_GRAY);
        }
    }

    @Override
    public boolean click(double mx, double my) {
        boolean consumed = super.click(mx, my);
        if (!consumed && list != null) {
            int row = rowIndexAt(mx, my);
            if (row >= 0) {
                int id = candidates.get(row).id();
                if (!selected.remove(id)) {
                    if (selected.size() < cap) selected.add(id);
                }
                list.refreshElementPanes();
                refreshConfirm();
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
        return (idx >= 0 && idx < candidates.size()) ? idx : -1;
    }

    private void refreshConfirm() {
        if (confirmButton == null) return;
        confirmButton.setText(Component.literal("March " + selected.size()));
        // You can march with zero subordinates (a solo assault), so always enabled.
        confirmButton.setEnabled(true);
    }

    private void confirm() {
        PacketDistributor.sendToServer(new Networking.WarActionPayload(
                Networking.WarActionPayload.DECLARE, settlementId, new ArrayList<>(selected)));
        close();
    }
}
