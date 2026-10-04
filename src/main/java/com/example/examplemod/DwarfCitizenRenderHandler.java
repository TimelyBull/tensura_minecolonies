package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.slf4j.Logger;

/**
 * Render-path interception for DWARF-tagged citizens. Structural twin of
 * {@link GoblinCitizenRenderHandler} — same lazy-renderer build, same
 * recursion guard on our own renderer, same cancellation-before-render
 * for fail-safe behaviour.
 */
@OnlyIn(Dist.CLIENT)
public final class DwarfCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static DwarfCitizenRenderer renderer;
    /** Count of consecutive render failures. We tolerate a small number
     *  of transient failures (e.g. resource-pack reload race) before
     *  giving up and invalidating. */
    private static int consecutiveFailures = 0;
    private static final int FAILURE_THRESHOLD = 5;
    /** Permanent latches. The threshold above USED to call {@link #invalidate()},
     *  which reset {@code consecutiveFailures} to 0 — so the next frame rebuilt
     *  the renderer and re-entered the same loop, five failures at a time,
     *  forever. The threshold looked like a bound but never actually stopped
     *  anything. These latches are what stop it; only {@link #invalidate()}
     *  (logout) clears them. */
    private static boolean disabled = false;
    private static boolean buildFailed = false;

    private DwarfCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (disabled) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null) return;
        if (tag.race() != Race.DWARF) return;

        // Recursion guard — our renderer extends LivingEntityRenderer.render
        // which fires RenderLivingEvent.Pre again for the same entity.
        if (event.getRenderer() instanceof DwarfCitizenRenderer) return;

        DwarfCitizenRenderer r = renderer();
        if (r == null) {
            // Our renderer could not be built. Fall back to MineColonies' own
            // render for the rest of the session — cancelling the event and
            // drawing nothing left every such citizen invisible.
            disabled = true;
            return;
        }
        event.setCanceled(true);

        float partialTick = event.getPartialTick();
        float entityYaw = Mth.rotLerp(partialTick, citizen.yBodyRotO, citizen.yBodyRot);

        try {
            r.render(citizen, entityYaw, partialTick,
                    event.getPoseStack(),
                    event.getMultiBufferSource(),
                    event.getPackedLight());
            consecutiveFailures = 0;
        } catch (Throwable t) {
            consecutiveFailures++;
            // Stack trace ONCE — a persistent failure must not write gigabytes
            // to latest.log at frame rate.
            if (consecutiveFailures == 1) {
                LOGGER.error("[TM] dwarf render failed for entity {} — will retry {} more times before disabling",
                        citizen.getUUID(), FAILURE_THRESHOLD - 1, t);
            } else {
                LOGGER.error("[TM] dwarf render failed for entity {} (failure {}/{})",
                        citizen.getUUID(), consecutiveFailures, FAILURE_THRESHOLD);
            }
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                LOGGER.error("[TM] dwarf renderer failed {} times — disabling for this session; "
                        + "dwarf citizens will render as plain colonists", FAILURE_THRESHOLD);
                // NOT invalidate(): that clears the counter and the latch, which
                // is what made this threshold ineffective.
                disabled = true;
                renderer = null;
                DwarfTextures.invalidate();
            }
        }
    }

    /** Full reset for a session boundary (logout). Clears the cached renderer
     *  AND the failure latches. The render failure path deliberately does not
     *  call this. */
    public static void invalidate() {
        renderer = null;
        consecutiveFailures = 0;
        disabled = false;
        buildFailed = false;
        DwarfTextures.invalidate();
    }

    private static DwarfCitizenRenderer renderer() {
        if (renderer != null) return renderer;
        if (buildFailed) return null; // already tried and failed; don't loop-build
        try {
            Minecraft mc = Minecraft.getInstance();
            EntityRendererProvider.Context ctx = new EntityRendererProvider.Context(
                    mc.getEntityRenderDispatcher(),
                    mc.getItemRenderer(),
                    mc.getBlockRenderer(),
                    mc.getEntityRenderDispatcher().getItemInHandRenderer(),
                    mc.getResourceManager(),
                    mc.getEntityModels(),
                    mc.font
            );
            renderer = new DwarfCitizenRenderer(ctx);
            LOGGER.info("[TM] dwarf renderer built");
        } catch (Throwable t) {
            // Latch it, or the next frame re-attempts the build and re-logs
            // the stack trace — every frame, for every dwarf citizen.
            buildFailed = true;
            LOGGER.error("[TM] failed to build dwarf renderer — tagged citizens fall back to the plain colonist look this session", t);
        }
        return renderer;
    }
}
