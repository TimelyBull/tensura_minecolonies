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

    private DwarfCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null) return;
        if (tag.race() != Race.DWARF) return;

        // Recursion guard — our renderer extends LivingEntityRenderer.render
        // which fires RenderLivingEvent.Pre again for the same entity.
        if (event.getRenderer() instanceof DwarfCitizenRenderer) return;

        event.setCanceled(true);

        DwarfCitizenRenderer r = renderer();
        if (r == null) return;

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
            LOGGER.error("[TM] dwarf render failed for entity {} (failure {}/{})",
                    citizen.getUUID(), consecutiveFailures, FAILURE_THRESHOLD, t);
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                LOGGER.error("[TM] dwarf renderer failure threshold reached — invalidating");
                invalidate();
            }
        }
    }

    public static void invalidate() {
        renderer = null;
        consecutiveFailures = 0;
        DwarfTextures.invalidate();
    }

    private static DwarfCitizenRenderer renderer() {
        if (renderer != null) return renderer;
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
            LOGGER.error("[TM] failed to build dwarf renderer — tagged citizens will not render this session", t);
        }
        return renderer;
    }
}
