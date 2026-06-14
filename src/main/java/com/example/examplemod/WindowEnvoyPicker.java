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
 * The envoy subordinate picker (diplomacy). Opens after Send Envoy. Lists
 * the player's at-your-side subordinates eligible to envoy the faction
 * (EP ≥ the faction's danger threshold). Pick ONE; Send dispatches it on
 * the envoy mission. Single-select sibling of {@link WindowWarPicker}.
 */
@OnlyIn(Dist.CLIENT)
public class WindowEnvoyPicker extends AbstractWindowSkeleton {

    private static final ResourceLocation XML = ResourceLocation
            .fromNamespaceAndPath("tensura_minecolonies", "gui/windowenvoypicker.xml");

    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_GRAY = 0xFF7A6E58;
    private static final int[] BORDER_CARD = {0x9A, 0x80, 0x55};
    private static final int[] BORDER_SELECTED = {0x4A, 0x6E, 0xB5};
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};
    private static final int ROW_PIXEL_HEIGHT = 22;

    private record Candidate(int id, String name, int ep) {}

    private final String factionId;
    private final String factionName;
    private final int threshold;
    private final boolean withGift;
    private final List<Candidate> candidates = new ArrayList<>();
    private int selectedId = -1;

    private ScrollingList list;
    private Button confirmButton;

    public WindowEnvoyPicker(CompoundTag data) {
        super(XML);
        this.factionId = data.getString("factionId");
        this.factionName = data.getString("factionName");
        this.threshold = data.getInt("threshold");
        this.withGift = data.getBoolean("withGift");
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
            titlePane.setText(Component.literal("§lEnvoy to " + factionName
                    + (withGift ? " (with gift)" : "") + "§r"));
            titlePane.setColors(TXT_DARK);
        }
        Text subtitle = findPaneOfTypeByID("subtitle", Text.class);
        if (subtitle != null) {
            subtitle.setText(Component.literal(candidates.isEmpty()
                    ? "No subordinate at your side has EP ≥ " + threshold + "."
                    : "Pick a subordinate (EP ≥ " + threshold + ") to send. They return when relations resolve."));
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
        boolean isSelected = candidate.id() == selectedId;
        Box card = rowPane.findPaneOfTypeByID("ccard", Box.class);
        if (card != null) {
            int[] rgb = isSelected ? BORDER_SELECTED : BORDER_CARD;
            card.setColor(rgb[0], rgb[1], rgb[2]);
        }
        Text name = rowPane.findPaneOfTypeByID("cname", Text.class);
        if (name != null) {
            name.setText(Component.literal((isSelected ? "✓ " : "") + candidate.name()));
            name.setColors(isSelected ? 0xFF274A6B : TXT_DARK);
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
                selectedId = (selectedId == id) ? -1 : id; // toggle / single-select
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
        confirmButton.setEnabled(selectedId >= 0);
    }

    private void confirm() {
        if (selectedId < 0) return;
        PacketDistributor.sendToServer(new Networking.EnvoyConfirmPayload(
                factionId, selectedId, withGift));
        close();
    }
}
