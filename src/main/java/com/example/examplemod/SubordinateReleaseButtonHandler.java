package com.example.examplemod;

import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.client.screen.HumanoidMainScreen;
import io.github.manasmods.tensura.entity.template.TensuraHumanoidEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * The wild-form "Release" button — deceit counterplay (user spec
 * 2026-09-05): an EX-owner who finds their STOLEN subordinate elsewhere can
 * open its inventory screen as normal and attempt a release. Mirror of
 * {@link SubordinateTradeButtonHandler} (same screen, same reflective
 * humanoid read, button stacked below where the trade button sits).
 *
 * Client-side gate is deliberately loose — the button shows whenever the
 * mob is NAMED and its Tensura permanent owner isn't the local player; the
 * SERVER enforces the real rules (must be the previous owner, unmasking
 * rank vs the steal, magicule cost) in
 * {@link MindControlTracker#handleRelease}.
 */
@OnlyIn(Dist.CLIENT)
public final class SubordinateReleaseButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Field humanoidField;
    private static boolean reflectionFailed = false;

    private SubordinateReleaseButtonHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (reflectionFailed) return;
        if (!(event.getScreen() instanceof HumanoidMainScreen screen)) return;

        TensuraHumanoidEntity entity = readHumanoidField(screen);
        if (entity == null || !entity.hasCustomName()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Only when someone ELSE holds the bond (Tensura existence is synced
        // to clients). Fails closed: unreadable → no button.
        try {
            var existence = ExampleMod.readExistence(entity);
            if (existence == null) return;
            UUID owner = existence.getPermanentOwner();
            if (owner == null || owner.equals(mc.player.getUUID())) return;
        } catch (Throwable t) {
            return;
        }

        int x;
        int y;
        if (event.getScreen() instanceof AbstractContainerScreen<?> acs) {
            x = acs.getGuiLeft() + acs.getXSize() + 4;
            y = acs.getGuiTop() + 30; // below the trade button's slot
        } else {
            x = event.getScreen().width - 76;
            y = 44;
        }

        final int entityId = entity.getId();
        Button releaseButton = Button.builder(
                Component.literal("Release").withStyle(ChatFormatting.AQUA),
                btn -> PacketDistributor.sendToServer(new Networking.ReleasePayload(entityId)))
                .bounds(x, y, 60, 20)
                .build();
        event.addListener(releaseButton);
    }

    private static TensuraHumanoidEntity readHumanoidField(HumanoidMainScreen screen) {
        try {
            if (humanoidField == null) {
                Field f = HumanoidMainScreen.class.getDeclaredField("humanoid");
                f.setAccessible(true);
                humanoidField = f;
            }
            Object v = humanoidField.get(screen);
            return v instanceof TensuraHumanoidEntity h ? h : null;
        } catch (Throwable t) {
            LOGGER.error("[TM] release button: failed to reflect humanoid field — disabling", t);
            reflectionFailed = true;
            return null;
        }
    }
}
