package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.citizen.MainWindowCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Citizen-side trade button. Replaces the subordinate-side trade tab
 * ({@code SubordinateTradeButtonHandler}, which is no longer registered).
 *
 * <p>Injects a "Trade" button into MineColonies' {@link MainWindowCitizen}
 * (the citizen info screen opened when you right-click a citizen). The
 * button shows only on citizens with a {@link RaceTag} for a
 * merchant-capable race (GOBLIN / LIZARDMAN / DWARF — orc is excluded).
 *
 * <p>Hook shape:
 * <ul>
 *   <li>{@code BOScreen extends Screen} (BlockUI's container), so
 *       NeoForge's {@link ScreenEvent.Init.Post} fires for it.</li>
 *   <li>{@code BOScreen.getWindow()} returns the wrapped
 *       {@link com.ldtteam.blockui.views.BOWindow} — we instance-check
 *       {@link MainWindowCitizen}.</li>
 *   <li>{@code MainWindowCitizen.getCitizen()} → {@link ICitizenDataView},
 *       from which we pull the entity id and look up the client-side
 *       race tag.</li>
 * </ul>
 *
 * <p>The Trade button is a vanilla {@code Button} added through
 * {@code event.addListener} — positioned over the BOScreen panel.
 * BlockUI scaling isn't inherited, so the button looks like a vanilla
 * widget overlaid on top; that's intentional (consistent with how the
 * subordinate tab worked).
 *
 * <p>Click → C2S {@link Networking.OpenCitizenTradePayload(int)}.
 * Server validates ownership and opens trade (see
 * {@link ExampleMod#handleOpenCitizenTrade}).
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenTradeButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean disabled = false;

    private CitizenTradeButtonHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (disabled) return;
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;

        com.ldtteam.blockui.views.BOWindow window;
        try {
            window = boScreen.getWindow();
        } catch (Throwable t) {
            return;
        }
        if (!(window instanceof MainWindowCitizen mwc)) return;

        ICitizenDataView citizen;
        try {
            citizen = mwc.getCitizen();
        } catch (Throwable t) {
            LOGGER.warn("[TM] citizen trade button: MainWindowCitizen.getCitizen threw", t);
            return;
        }
        if (citizen == null) return;

        // Resolve the citizen entity client-side to look up the RaceTag.
        // The entity may not be loaded (citizen window can be opened from
        // colony overview when the citizen is far away) — in that case
        // skip silently.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) return;
        RaceTag tag = RaceTagClientStore.get(entity.getUUID());
        if (tag == null) return;
        // Merchant-capable races only. Orc has no trade pipeline.
        Race race = tag.race();
        if (race == Race.ORC) return;

        // Add a vanilla button overlaid on the BOScreen. Coords place it
        // just inside the top-right corner of the BlockUI panel; this is
        // an empty area on MainWindowCitizen (the BlockUI XML uses
        // 210x244 panel, top-right around (160, 8) is clear).
        int btnX = boScreen.width / 2 + 36;
        int btnY = boScreen.height / 2 - 110;
        int citizenEntityId = citizen.getEntityId();

        Button trade = Button.builder(
                Component.literal("Trade").withStyle(ChatFormatting.YELLOW),
                b -> sendOpenTrade(citizenEntityId))
                .bounds(btnX, btnY, 60, 20)
                .build();
        event.addListener(trade);
    }

    private static void sendOpenTrade(int citizenEntityId) {
        PacketDistributor.sendToServer(
                new Networking.OpenCitizenTradePayload(citizenEntityId));
    }
}
