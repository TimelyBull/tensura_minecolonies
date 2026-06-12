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
 * The alliance prompt (diplomacy Stage 1) — pops when OPEN relations
 * reach the ALLIED band (80+). Same BlockUI paper styling as
 * {@link WindowRoster}/{@link WindowDiplomacy}. Accept → PACT, standing
 * untouched; Decline → standing drops to just below ALLIED (server
 * authority — this window only routes the choice). ESC simply closes;
 * the server re-prompts after a minute while the conditions hold.
 */
@OnlyIn(Dist.CLIENT)
public class WindowAlliancePrompt extends AbstractWindowSkeleton {

    private static final ResourceLocation XML = ResourceLocation
            .fromNamespaceAndPath("tensura_minecolonies", "gui/windowallianceprompt.xml");

    private static final int TXT_DARK = 0xFF2E2616;
    private static final int TXT_GRAY = 0xFF7A6E58;
    private static final int[] BORDER_DIVIDER = {0x8A, 0x75, 0x4A};

    private final String factionId;
    private final String factionName;
    private final double standing;

    public WindowAlliancePrompt(String factionId, String factionName, double standing) {
        super(XML);
        this.factionId = factionId;
        this.factionName = factionName;
        this.standing = standing;
        registerButton("accept", (Button b) -> respond(true));
        registerButton("decline", (Button b) -> respond(false));
    }

    @Override
    public void onOpened() {
        super.onOpened();
        Box divider = findPaneOfTypeByID("divider", Box.class);
        if (divider != null) divider.setColor(BORDER_DIVIDER[0], BORDER_DIVIDER[1], BORDER_DIVIDER[2]);
        setText("line1", factionName + " counts you among its closest friends ("
                + Math.round(standing) + ").", TXT_DARK);
        setText("line2", "Seal a formal alliance pact?", TXT_DARK);
        setText("line3", "Declining will cool the relationship.", TXT_GRAY);
    }

    private void respond(boolean accepted) {
        PacketDistributor.sendToServer(
                new Networking.AllianceResponsePayload(factionId, accepted));
        close();
    }

    private void setText(String id, String text, int color) {
        Text pane = findPaneOfTypeByID(id, Text.class);
        if (pane != null) {
            pane.setText(Component.literal(text));
            pane.setColors(color);
        }
    }
}
