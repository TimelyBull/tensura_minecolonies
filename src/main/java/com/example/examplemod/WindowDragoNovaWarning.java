package com.example.examplemod;

import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.Box;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Drago Nova foresight warning (Covenant — Milim) — shown only to
 * Sage / Great Sage holders before a detonation commits (the
 * collapse-confirm screen pattern). "Unleash It" confirms (server
 * detonates); "Hold" cancels. If the user is unworthy (not a true
 * demon lord / hero), the warning spells out that it will KILL them.
 */
@OnlyIn(Dist.CLIENT)
public class WindowDragoNovaWarning extends AbstractWindowSkeleton {

    private static final ResourceLocation XML = ResourceLocation
            .fromNamespaceAndPath("tensura_minecolonies", "gui/windowdragonovawarning.xml");

    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_RED = 0xFF8A2E2E;
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};

    private final boolean lethal;

    public WindowDragoNovaWarning(boolean lethal) {
        super(XML);
        this.lethal = lethal;
        registerButton("confirm", (Button b) -> {
            PacketDistributor.sendToServer(new Networking.DragoNovaConfirmPayload());
            close();
        });
        registerButton("cancel", (Button b) -> close());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        Box divider = findPaneOfTypeByID("divider", Box.class);
        if (divider != null) divider.setColor(BORDER_DIVIDER[0], BORDER_DIVIDER[1], BORDER_DIVIDER[2]);
        setText("line1", "Milim's Drago Nova will devastate the area.", TXT_DARK);
        if (lethal) {
            setText("line2", "You are NEITHER true demon lord NOR hero —", TXT_RED);
            setText("line3", "unleashing it will KILL YOU. The Sage is certain.", TXT_RED);
        } else {
            setText("line2", "It will not harm you, but spares little else.", TXT_DARK);
            setText("line3", "The Sage foresees the blast — proceed?", TXT_DARK);
        }
    }

    private void setText(String id, String text, int color) {
        Text pane = findPaneOfTypeByID(id, Text.class);
        if (pane != null) {
            pane.setText(Component.literal(text));
            pane.setColors(color);
        }
    }
}
