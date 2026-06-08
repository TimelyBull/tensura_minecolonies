package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Envoy diplomacy dialogue — opened S2C when the player right-clicks an
 * envoy. Race-flavoured title + body (from {@link EnvoyDialogue}), Accept
 * / Decline buttons.
 *
 * Accept sends {@link Networking.EnvoyResponsePayload}{@code (entityId, true)};
 * Decline sends {@code (entityId, false)}. Server applies the resulting
 * state.
 *
 * ESC behaves as "think about it" — closes the Screen without sending a
 * packet. The envoy stays alive at the colony and can be re-clicked. Only
 * an explicit Decline button click commits the decline.
 *
 * Visual treatment mirrors {@link ConfirmCollapseScreen}: vanilla blur
 * backdrop, contained dialog panel with a 1px border + drop shadow.
 */
@OnlyIn(Dist.CLIENT)
public class EnvoyDialogueScreen extends Screen {

    private static final int DIALOG_WIDTH  = 420;
    /** Floor height — used when the wrapped body is short. The actual
     *  panel grows to fit the body when condition-flavoured snippets push
     *  the line count past what this would hold. */
    private static final int DIALOG_HEIGHT_MIN = 220;
    /** Fixed chrome (title band + button band) inside the panel. The body
     *  area is whatever's left between them. */
    private static final int CHROME_TOP    = 40;   // title + padding
    private static final int CHROME_BOTTOM = 48;   // button band + padding
    private static final int LINE_HEIGHT   = 12;

    /** Computed at {@link #init}-time once the body has been wrapped — used
     *  by {@link #renderBackground} so the dark panel matches the body
     *  height even on long multi-condition dialogues. */
    private int dialogHeight = DIALOG_HEIGHT_MIN;

    private final int entityId;
    private final ColonyMember member;
    /** Conditions satisfied at spawn-time (snapshot from the envoy's
     *  {@link EnvoyTag}). Drives condition-flavoured snippets appended
     *  to the base dialogue. */
    private final java.util.Set<EnvoyCondition> conditions;
    private List<FormattedCharSequence> wrappedBody;

    public EnvoyDialogueScreen(int entityId, ColonyMember member,
                               java.util.Set<EnvoyCondition> conditions) {
        super(Component.literal(EnvoyDialogue.title(member)));
        this.entityId = entityId;
        this.member = member;
        this.conditions = conditions == null
                ? java.util.EnumSet.noneOf(EnvoyCondition.class)
                : conditions;
    }

    @Override
    protected void init() {
        super.init();

        // Wrap the body text to fit inside the dialog panel with side
        // padding. Condition-aware body — appends per-condition snippets
        // in EnvoyCondition declaration order so multi-condition envoys
        // read as a stable sequence of observations.
        Component body = Component.literal(EnvoyDialogue.body(this.member, this.conditions));
        this.wrappedBody = this.font.split(body, DIALOG_WIDTH - 32);

        // Grow the panel to fit body line-count when condition snippets
        // push it past the floor. Clamp to the screen height so very
        // long dialogues still fit on small windows.
        int bodyPx = this.wrappedBody.size() * LINE_HEIGHT;
        int needed = CHROME_TOP + bodyPx + CHROME_BOTTOM;
        this.dialogHeight = Math.max(DIALOG_HEIGHT_MIN,
                Math.min(this.height - 20, needed));

        int dialogX = (this.width  - DIALOG_WIDTH)  / 2;
        int dialogY = (this.height - this.dialogHeight) / 2;
        int buttonY = dialogY + this.dialogHeight - 28;
        int cx = this.width / 2;

        // Decline (left) — gray text, like an opt-out.
        addRenderableWidget(Button.builder(
                Component.literal("Decline").withStyle(ChatFormatting.GRAY),
                b -> decline()
        ).bounds(cx - 130, buttonY, 120, 20).build());

        // Accept (right) — green text, like a commit action.
        addRenderableWidget(Button.builder(
                Component.literal("Accept").withStyle(ChatFormatting.GREEN),
                b -> accept()
        ).bounds(cx + 10, buttonY, 120, 20).build());
    }

    /** ESC closes without committing — envoy stays, can be re-talked-to. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    private void accept() {
        PacketDistributor.sendToServer(new Networking.EnvoyResponsePayload(this.entityId, true));
        Minecraft.getInstance().setScreen(null);
    }

    private void decline() {
        PacketDistributor.sendToServer(new Networking.EnvoyResponsePayload(this.entityId, false));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        int x0 = (this.width  - DIALOG_WIDTH)  / 2;
        int y0 = (this.height - this.dialogHeight) / 2;
        int x1 = x0 + DIALOG_WIDTH;
        int y1 = y0 + this.dialogHeight;

        // Drop shadow → outer border → solid dark interior.
        g.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0xA0000000);
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFFFFFFFF);
        g.fill(x0, y0, x1, y1, 0xFF181818);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int dialogTop = (this.height - this.dialogHeight) / 2;

        // Title in the race's nameplate colour — same hue as the envoy's
        // head label, ties the dialogue to the entity visually.
        Component title = Component.literal(EnvoyDialogue.title(this.member))
                .withStyle(EnvoyDialogue.NAMEPLATE_COLOR
                        .getOrDefault(this.member, ChatFormatting.WHITE),
                        ChatFormatting.BOLD);
        g.drawCenteredString(this.font, title, cx, dialogTop + 14, 0xFFFFFFFF);

        // Body lines, wrapped at init time.
        int y = dialogTop + CHROME_TOP;
        for (FormattedCharSequence line : this.wrappedBody) {
            g.drawCenteredString(this.font, line, cx, y, 0xFFE6E6E6);
            y += LINE_HEIGHT;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
