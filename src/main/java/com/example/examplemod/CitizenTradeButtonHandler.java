package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.core.client.gui.citizen.MainWindowCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

/**
 * Citizen-side trade button. Injects a "Trade" button into MineColonies'
 * {@link MainWindowCitizen} (right-click a citizen → that window opens).
 *
 * <p><b>Why this uses BlockUI's {@link ButtonImage}, not a vanilla
 * {@code Button}.</b> The original implementation added a vanilla
 * {@code net.minecraft.client.gui.components.Button} via
 * {@code event.addListener(button)} → that calls
 * {@code Screen.addRenderableWidget}, which only draws the widget when
 * the screen's {@code render()} iterates its renderables list.
 * Disassembly of {@link BOScreen#render} confirms it does NOT call
 * {@code super.render(...)} — BOScreen is a full override that draws
 * only the BlockUI window contents. Result: vanilla widgets added via
 * the event were registered but never drawn. The button was invisible.
 *
 * <p>Fix: use BlockUI's own widget hierarchy. Construct a
 * {@link ButtonImage} with {@code setVanillaButton()} for the vanilla
 * look, position it inside the {@link BOWindow} via
 * {@code putInside(window)}, set a {@link com.ldtteam.blockui.controls.ButtonHandler}
 * for click routing → C2S {@link Networking.OpenCitizenTradePayload}.
 *
 * <p>The button shows only on citizens with a {@link RaceTag} for a
 * merchant-capable race (GOBLIN / LIZARDMAN / DWARF — orc excluded
 * since orcs don't trade).
 *
 * <p>Position chosen inside the window: x=55 y=222 size 100×18. The
 * window is 210×244 per {@code main.xml}; the skills scrollgroup
 * occupies y=117..217 at x=33..138; the right-column ornaments (status
 * icon, ribbon, gender wax) sit at x≥150. y=222..240 is the only clear
 * area visible in every tab state, and x=55..155 avoids the
 * decorative ribbon at x=157+.
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenTradeButtonHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CitizenTradeButtonHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;

        BOWindow window;
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
        // If the entity isn't loaded (opening the citizen info from the
        // colony overview while standing far away), skip silently.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity entity = mc.level.getEntity(citizen.getEntityId());
        if (entity == null) {
            LOGGER.debug("[TM] citizen trade button: entity {} not loaded — skipping", citizen.getEntityId());
            return;
        }
        RaceTag tag = RaceTagClientStore.get(entity.getUUID());
        if (tag == null) return;
        if (tag.race() == Race.ORC) return;

        // Idempotency — if we've already injected the button on a previous
        // init pass (BlockUI can re-run init on resize / tab change), do
        // not duplicate it. Locate by the unique ID we set below.
        if (window.findPaneByID("tm_trade") != null) return;

        final int citizenEntityId = citizen.getEntityId();

        ButtonImage trade = new ButtonImage();
        trade.setID("tm_trade");
        trade.setVanillaButton();
        trade.setSize(100, 18);
        trade.setPosition(55, 222);
        trade.setText(List.of(Component.literal("Trade")));
        trade.setHandler(button -> sendOpenTrade(citizenEntityId));
        trade.putInside(window);

        LOGGER.debug("[TM] citizen trade button injected for citizen entity {} (race={})",
                citizenEntityId, tag.race());
    }

    private static void sendOpenTrade(int citizenEntityId) {
        PacketDistributor.sendToServer(
                new Networking.OpenCitizenTradePayload(citizenEntityId));
    }
}
