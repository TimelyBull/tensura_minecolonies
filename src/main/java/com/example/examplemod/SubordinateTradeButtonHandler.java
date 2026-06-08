package com.example.examplemod;

import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.client.screen.HumanoidMainScreen;
import io.github.manasmods.tensura.entity.template.TensuraHumanoidEntity;
import io.github.manasmods.tensura.entity.template.TensuraMerchantEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Injects a "Trade" tab into Tensura's {@link HumanoidMainScreen} (the
 * main armor / weapon page of the subordinate inventory UI) for named
 * subordinates that are merchant-capable
 * ({@link TensuraMerchantEntity} — i.e. goblin, lizardman, dwarf; orc
 * extends a different parent chain and is intentionally skipped).
 *
 * <p>Tensura's subordinate inventory has two distinct Screen classes:
 * {@code HumanoidMainScreen} renders the armor + weapon slots (the
 * default page you land on when right-clicking with an empty hand) and
 * {@code HumanoidInventoryScreen} renders the chest-overflow pages
 * (navigated via the arrows). The trade tab anchors to the MAIN screen
 * so it's always visible on the page the player opens to.
 *
 * <p>The screen's {@code humanoid} field is private with no public getter,
 * so we reflect it once on first injection. This is the minimum-invasive
 * way to reach the entity reference without a mixin into Tensura.
 *
 * <p>Click handler sends a C2S {@link Networking.OpenSubordinateTradePayload}.
 * Server validates ownership through the identity store and opens the
 * standard merchant screen — preserving the subordinate's profession,
 * merchant level, gossips, and persisted offers (all round-tripped via
 * {@code TensuraMerchantEntity}'s own NBT, untouched by naming).
 */
@OnlyIn(Dist.CLIENT)
public final class SubordinateTradeButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cached reflective handle to {@code HumanoidMainScreen.humanoid}.
     *  Built lazily; null until first successful resolve. */
    private static Field HUMANOID_FIELD;
    /** True once we've tried-and-failed to reflect the field — short-circuits
     *  every subsequent call so we don't spam errors. */
    private static boolean reflectionFailed = false;

    private SubordinateTradeButtonHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (reflectionFailed) return;
        // Anchor to the MAIN page (armor + weapons), not the chest-scroll
        // page. That's the page the player lands on when they right-click
        // the subordinate, and the trade tab should be reachable from
        // there directly.
        if (!(event.getScreen() instanceof HumanoidMainScreen screen)) return;

        TensuraHumanoidEntity entity = readHumanoidField(screen);
        if (entity == null) return;
        // Only merchant-capable entities get the tab. Orc and other
        // non-merchant subordinates don't have offers/profession state.
        if (!(entity instanceof TensuraMerchantEntity)) return;

        int entityId = entity.getId();

        // Place the button OUTSIDE the right edge of the inventory texture
        // so it doesn't overlap any existing widget. AbstractContainerScreen
        // exposes leftPos / topPos / imageWidth which gives us the screen
        // origin in absolute coordinates.
        int x;
        int y;
        if (event.getScreen() instanceof AbstractContainerScreen<?> acs) {
            x = acs.getGuiLeft() + acs.getXSize() + 4;
            y = acs.getGuiTop() + 6;
        } else {
            // Defensive fallback; should never happen since HumanoidInventoryScreen
            // extends AbstractContainerScreen.
            x = event.getScreen().width - 76;
            y = 20;
        }

        Button tradeButton = Button.builder(
                Component.literal("Trade").withStyle(ChatFormatting.YELLOW),
                btn -> openTrade(entityId))
                .bounds(x, y, 60, 20)
                .build();
        event.addListener(tradeButton);
    }

    private static void openTrade(int entityId) {
        PacketDistributor.sendToServer(new Networking.OpenSubordinateTradePayload(entityId));
    }

    private static TensuraHumanoidEntity readHumanoidField(HumanoidMainScreen screen) {
        try {
            if (HUMANOID_FIELD == null) {
                Field f = HumanoidMainScreen.class.getDeclaredField("humanoid");
                f.setAccessible(true);
                HUMANOID_FIELD = f;
            }
            Object value = HUMANOID_FIELD.get(screen);
            if (value instanceof TensuraHumanoidEntity h) return h;
            return null;
        } catch (Throwable t) {
            // One-shot failure path: log once, then short-circuit so we
            // don't print the stack trace every time the inventory opens.
            LOGGER.error("[TM] subordinate trade tab: failed to reflect humanoid field — disabling", t);
            reflectionFailed = true;
            return null;
        }
    }
}
