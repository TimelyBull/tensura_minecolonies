package com.example.examplemod.mixin;

import com.example.examplemod.SubordinateClientStore;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.api.colony.ICitizenDataView;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grays out an away subordinate's row in the hiring window, disables its hire
 * button, and tags it "subordinate" so the reason is visible.
 *
 * <p><b>Why this is done per-row and NOT via {@code WindowHireWorker.canAssign}.</b>
 * Hooking {@code canAssign} looks like the clean seam — the row adapter calls it
 * to decide whether to enable the hire button. But that same method is ALSO the
 * predicate {@code updateCitizens()} filters the list with
 * (verified in 1.1.1368: the {@code Stream.filter} bootstrap resolves to
 * {@code WindowHireWorker.canAssign}). Returning false there doesn't gray the
 * row, it DELETES it — the citizen vanishes from the hiring list entirely. That
 * shipped briefly and produced a confusing bug where the row rendered correctly
 * on first open (the list had been built before the id set synced) and then
 * disappeared on reopen. So the disable is applied to the row itself, which is
 * the only place that affects presentation without affecting membership.</p>
 *
 * <p><b>Best-effort by construction.</b> The target is MineColonies' ANONYMOUS
 * row-adapter class ({@code WindowHireWorker$1}), identified by its
 * compiler-assigned index, so the injector is {@code require = 0} /
 * {@code expect = 0}: a MineColonies update that shifts that index drops the
 * graying silently rather than crashing on load. That is an acceptable
 * degradation because this is presentation only — {@code WorkerModuleAssignJobMixin}
 * and {@code AssignedCitizenModuleJobMixin} refuse the hire SERVER-side on stable
 * named methods with {@code require = 1}, so the worst case if this mixin ever
 * stops applying is a row that looks hireable and then quietly declines.</p>
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
            ICitizenDataView citizen = tensura_minecolonies$citizenAt(index);
            if (citizen == null) return;

            int colonyId = citizen.getColonyId();
            int citizenId = citizen.getId();
            boolean away = SubordinateClientStore.isAway(colonyId, citizenId);

            // Ordinary MineColonies colonists are left completely alone, keeping
            // whatever vanilla puts in the row (lives here / lives at work /
            // homeless / distance). Only OUR race citizens get a mode label.
            if (!away && !SubordinateClientStore.isRaceCitizen(colonyId, citizenId)) return;

            Text label = rowPane.findPaneOfTypeByID("distance", Text.class);
            if (label != null) {
                label.setText(Component.translatable(away
                                ? "tensura_minecolonies.hiring.subordinate"
                                : "tensura_minecolonies.hiring.colonist")
                        .withStyle(ChatFormatting.GRAY));
            }

            // A race citizen serving IN the colony is an ordinary hire — only the
            // mode label changes. Everything below marks the row unavailable and
            // applies to away subordinates only.
            if (!away) return;

            // Un-clickable: same thing MineColonies itself does to this button
            // when a citizen can't take the job.
            Button hire = rowPane.findPaneOfTypeByID("done", Button.class);
            if (hire != null) hire.off();

            // Re-enable FIRE — but ONLY in the exact case MineColonies hid it
            // because of travelling, and nowhere else.
            //
            // `updateElement` touches the fire button in four places
            // (1.1.1368, bytecode offsets 96 / 190 / 274 / 291):
            //   96  fire.off()  canAssign && !full && !assignedHere  — nothing to fire
            //   190 fire.off()  full && !assignedHere                — nothing to fire
            //   274 fire.off()  assigned-branch AND isTravelling     — THE ONE WE OVERRIDE
            //   291 fire.on()   assigned-branch AND !isTravelling    — already on
            // Both `off` cases at 96 and 190 require !assignedHere, so the
            // assignedHere test alone already excludes them; the isTravelling
            // test then pins us to 274 specifically. If MineColonies ever adds a
            // NEW reason to hide Fire, this will not override it.
            //
            // Why 274 needs overriding at all: our subordinates are marked
            // travelling PERMANENTLY (that flag is the mechanism that stops a
            // body spawning for them), so MineColonies hid Fire on every one of
            // them and the hut offered no way to dismiss a worker who was out
            // with the player. MineColonies' own genuinely-travelling citizens
            // never reach this code — they are not in our race set.
            if (tensura_minecolonies$isAssignedHere(citizenId)
                    && tensura_minecolonies$isTravelling(citizen)) {
                Button fire = rowPane.findPaneOfTypeByID("fire", Button.class);
                if (fire != null) fire.on();
            }

            // Grayed: dim the name so the row reads as unavailable at a glance.
            Text name = rowPane.findPaneOfTypeByID("citizen", Text.class);
            if (name != null) name.setColors(TAG_GRAY);
        } catch (Throwable ignored) {
            // Never let a row tweak break the hiring window. The SERVER refuses
            // the hire regardless (WorkerModuleAssignJobMixin), so the worst case
            // here is a row that looks hireable and then declines.
        }
    }

    /** Dimmed row text. Matches the gray MineColonies uses for inactive rows. */
    private static final int TAG_GRAY = 0xFF808080;

    /**
     * Whether this citizen is assigned to the job module the hiring window is
     * currently showing — i.e. whether "Fire" is a meaningful action on this row.
     *
     * <p>Read reflectively from the window's {@code selectedModule}, the same
     * field MineColonies' own row logic consults. Returns false on any problem,
     * which simply leaves the Fire button exactly as MineColonies set it.</p>
     */
    private boolean tensura_minecolonies$isAssignedHere(int citizenId) {
        try {
            Object window = tensura_minecolonies$outerWindow();
            if (window == null) return false;
            java.lang.reflect.Field f = window.getClass().getDeclaredField("selectedModule");
            f.setAccessible(true);
            Object module = f.get(window);
            if (!(module instanceof com.minecolonies.api.colony.buildings.modules.IAssignmentModuleView view)) {
                return false;
            }
            return view.getAssignedCitizens().contains(citizenId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether MineColonies considers this citizen travelling — the single
     *  condition it hides the Fire button on for an assigned worker. Mirrors the
     *  exact call its own row logic makes. False on any problem, which leaves the
     *  button as MineColonies set it. */
    private boolean tensura_minecolonies$isTravelling(ICitizenDataView citizen) {
        try {
            return citizen.getColony().getTravellingManager().isTravelling(citizen);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The enclosing {@code WindowHireWorker}, via the synthetic outer-instance
     *  field (whose name is compiler-assigned, hence the scan). */
    private Object tensura_minecolonies$outerWindow() {
        try {
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (f.getName().startsWith("this$")) {
                    f.setAccessible(true);
                    return f.get(this);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * The row index maps into the window's citizen list. Reached reflectively
     * rather than through a {@code @Shadow} on the synthetic {@code this$0}
     * field, whose name is also compiler-assigned.
     */
    @SuppressWarnings("unchecked")
    private ICitizenDataView tensura_minecolonies$citizenAt(int index) {
        try {
            Object window = tensura_minecolonies$outerWindow();
            if (window == null) return null;

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
