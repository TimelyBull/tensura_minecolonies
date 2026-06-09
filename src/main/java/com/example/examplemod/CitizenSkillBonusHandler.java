package com.example.examplemod;

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.View;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.client.gui.citizen.AbstractWindowCitizen;
import com.minecolonies.core.client.gui.citizen.MainWindowCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the blue "+X" Harvest-Festival bonus next to each boosted skill on the
 * MineColonies citizen window's main page. Hooks {@code ScreenEvent.Init.Post}
 * (the same pattern as the trade tab), reflects the window's
 * {@link ICitizenDataView}, looks up the citizen's bonuses from
 * {@link FestivalBonusClientStore}, and adds a blue {@code +X} text pane beside
 * the skill number (whose pane id is the lowercase skill name).
 */
@OnlyIn(Dist.CLIENT)
public final class CitizenSkillBonusHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Blue, matching MC's "buffed" colour family. */
    private static final int BLUE = 0xFF4FA3FF;

    private static Field citizenField;

    private CitizenSkillBonusHandler() {}

    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BOScreen boScreen)) return;
        BOWindow window;
        try {
            window = boScreen.getWindow();
        } catch (Throwable t) {
            return;
        }
        // The skill list only exists on the main citizen page.
        if (!(window instanceof MainWindowCitizen mainWindow)) return;

        ICitizenDataView citizen = readCitizen(mainWindow);
        if (citizen == null) return;
        Map<Skill, Integer> bonuses = FestivalBonusClientStore.get(citizen.getColonyId(), citizen.getId());
        if (bonuses.isEmpty()) return;

        for (Map.Entry<Skill, Integer> e : bonuses.entrySet()) {
            int bonus = e.getValue();
            if (bonus <= 0) continue;
            String skillId = e.getKey().name().toLowerCase(Locale.ROOT);
            String bonusId = "tm_bonus_" + skillId;
            if (mainWindow.findPaneByID(bonusId) != null) continue; // re-init guard

            Pane numberPane = mainWindow.findPaneByID(skillId);
            if (numberPane == null) continue;
            View parent = numberPane.getParent();
            if (parent == null) continue;

            Text plus = new Text();
            plus.setID(bonusId);
            plus.setText(Component.literal("+" + bonus));
            plus.setColors(BLUE);
            plus.setSize(20, numberPane.getHeight());
            plus.setPosition(numberPane.getX() + numberPane.getWidth() + 1, numberPane.getY());
            parent.addChild(plus);
        }
    }

    /** Read the protected {@code citizen} field (shared by the citizen windows). */
    private static ICitizenDataView readCitizen(AbstractWindowCitizen window) {
        try {
            if (citizenField == null) {
                citizenField = AbstractWindowCitizen.class.getDeclaredField("citizen");
                citizenField.setAccessible(true);
            }
            Object v = citizenField.get(window);
            return v instanceof ICitizenDataView c ? c : null;
        } catch (Throwable t) {
            LOGGER.warn("[TM] festival UI: could not read AbstractWindowCitizen.citizen", t);
            return null;
        }
    }
}
