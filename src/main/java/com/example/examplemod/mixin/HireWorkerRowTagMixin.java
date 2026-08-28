package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateClientStore;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.api.colony.ICitizenDataView;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a small gray "subordinate" tag to a citizen's row in the hiring window
 * when that citizen is away with the player, so the grayed-out hire button has a
 * visible reason next to it rather than looking broken.
 *
 * <p><b>This is best-effort and deliberately non-fatal.</b> The target is
 * MineColonies' ANONYMOUS row-adapter class ({@code WindowHireWorker$1}), which
 * is identified by its compiler-assigned index — a MineColonies update that adds
 * another anonymous class to that file would silently shift it. Every injector
 * here is therefore {@code require = 0}: if the target moves, the tag quietly
 * stops appearing and nothing else changes. The behaviour that matters — the
 * citizen not being hireable — lives in {@code WindowHireWorkerGrayOutMixin} and
 * {@code WorkerModuleAssignJobMixin}, both of which target stable NAMED methods
 * and will fail loudly if they ever break.</p>
 *
 * <p>The tag is written into the row's existing {@code distance} label (the one
 * that otherwise reads "lives here" / "lives at work"), so no layout or XML
 * change is needed.</p>
 */
@Mixin(targets = "com.minecolonies.core.client.gui.WindowHireWorker$1", remap = false)
public abstract class HireWorkerRowTagMixin {

    @Inject(
            method = "updateElement(ILcom/ldtteam/blockui/Pane;)V",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void tensura_minecolonies$tagAwaySubordinate(int index, Pane rowPane, CallbackInfo ci) {
        try {
            Text label = rowPane.findPaneOfTypeByID("distance", Text.class);
            if (label == null) return;

            ICitizenDataView citizen = tensura_minecolonies$citizenAt(index);
            if (citizen == null) return;
            if (!SubordinateClientStore.isAway(citizen.getColonyId(), citizen.getId())) return;

            label.setText(Component.translatable("tensura_minecolonies.hiring.subordinate")
                    .withStyle(ChatFormatting.GRAY));
        } catch (Throwable ignored) {
            // Cosmetic only — never let a label tweak break the hiring window.
        }
    }

    /**
     * The row index maps into the window's citizen list. Reached reflectively
     * rather than through a {@code @Shadow} on the synthetic {@code this$0}
     * field, whose name is also compiler-assigned.
     */
    @SuppressWarnings("unchecked")
    private ICitizenDataView tensura_minecolonies$citizenAt(int index) {
        try {
            java.lang.reflect.Field outer = null;
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (f.getName().startsWith("this$")) { outer = f; break; }
            }
            if (outer == null) return null;
            outer.setAccessible(true);
            Object window = outer.get(this);

            java.lang.reflect.Field citizensField =
                    window.getClass().getDeclaredField("citizens");
            citizensField.setAccessible(true);
            java.util.List<ICitizenDataView> citizens =
                    (java.util.List<ICitizenDataView>) citizensField.get(window);
            if (citizens == null || index < 0 || index >= citizens.size()) return null;
            return citizens.get(index);
        } catch (Throwable t) {
            return null;
        }
    }
}
