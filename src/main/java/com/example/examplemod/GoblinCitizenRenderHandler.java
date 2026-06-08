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
 * Stage F3 — render-path interception.
 *
 * Subscribes to {@link RenderLivingEvent.Pre}; for any
 * {@code AbstractEntityCitizen} that has an entry in
 * {@link RaceTagClientStore}, cancels the event (skipping
 * MineColonies' default {@code RenderBipedCitizen}) and renders the
 * entity through {@link GoblinCitizenRenderer} instead.
 *
 * Untagged citizens pass through untouched — we early-out before
 * cancelling so MineColonies' renderer continues to handle every
 * normal colonist.
 *
 * Renderer instantiation: {@link GoblinCitizenRenderer} needs an
 * {@link EntityRendererProvider.Context}, which is normally only handed
 * out during the {@code RegisterRenderers} event. We build one on
 * first use from {@link Minecraft} fields — all seven Context inputs
 * are public getters on {@code Minecraft.getInstance()} in 1.21.1.
 *
 * Invalidation: {@link #invalidate()} drops the cached renderer, called
 * from {@code ClientEvents} on logout. (A resource-pack reload also
 * invalidates baked models, but for F3 we rely on the next logout to
 * pick that up; F4+ can subscribe to {@code EntityRenderersEvent.AddLayers}
 * for a tighter invalidation if pack-switch artifacts appear.)
 *
 * Name tag: {@code LivingEntityRenderer.render} draws the nameplate
 * internally via {@code renderNameTag}, so cancelling MC's render path
 * and calling ours preserves the citizen's name above the goblin —
 * no extra work needed.
 */
@OnlyIn(Dist.CLIENT)
public final class GoblinCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static GoblinCitizenRenderer renderer;

    private GoblinCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null) return;
        // Race gate — only GOBLIN-tagged citizens go through this renderer.
        // ORC-tagged citizens are handled by OrcCitizenRenderHandler.
        if (tag.race() != Race.GOBLIN) return;

        // Recursion guard. Our renderer extends LivingEntityRenderer, whose
        // render() fires RenderLivingEvent.Pre as well — so when we call
        // r.render(...) below, the SAME handler fires for the same entity,
        // would cancel again, and our render() would return without drawing
        // anything. Result: invisible citizen. If the event's renderer is
        // already ours, this is the inner fire — let it proceed normally.
        if (event.getRenderer() instanceof GoblinCitizenRenderer) return;

        // Cancel BEFORE rendering — if our render below throws, we still
        // skip MC's default render. Better to flash an invisible body for
        // one frame than to double-render or NPE during MC's pipeline.
        event.setCanceled(true);

        GoblinCitizenRenderer r = renderer();
        if (r == null) return; // cache build failed (logged once); skip frame

        float partialTick = event.getPartialTick();

        // RenderLivingEvent.Pre carries partialTick but not entityYaw —
        // EntityRenderDispatcher computes yaw before calling MobRenderer.render
        // and doesn't forward it into the event. Recompute with the same
        // formula vanilla uses for living entities.
        float entityYaw = Mth.rotLerp(partialTick, citizen.yBodyRotO, citizen.yBodyRot);

        try {
            r.render(citizen, entityYaw, partialTick,
                    event.getPoseStack(),
                    event.getMultiBufferSource(),
                    event.getPackedLight());
        } catch (Throwable t) {
            LOGGER.error("[TM] goblin render failed for entity {} — invalidating renderer",
                    citizen.getUUID(), t);
            invalidate();
        }
    }

    /** Drops the cached renderer. Called from {@code ClientEvents.onClientLoggingOut}. */
    public static void invalidate() {
        renderer = null;
    }

    private static GoblinCitizenRenderer renderer() {
        if (renderer != null) return renderer;
        try {
            Minecraft mc = Minecraft.getInstance();
            // In 1.21.1, ItemInHandRenderer is owned by EntityRenderDispatcher
            // (no direct Minecraft.getItemInHandRenderer accessor); pull it
            // through the dispatcher.
            EntityRendererProvider.Context ctx = new EntityRendererProvider.Context(
                    mc.getEntityRenderDispatcher(),
                    mc.getItemRenderer(),
                    mc.getBlockRenderer(),
                    mc.getEntityRenderDispatcher().getItemInHandRenderer(),
                    mc.getResourceManager(),
                    mc.getEntityModels(),
                    mc.font
            );
            renderer = new GoblinCitizenRenderer(ctx);
            LOGGER.info("[TM] goblin renderer built");
        } catch (Throwable t) {
            // Don't loop-build: leave null, log once.
            LOGGER.error("[TM] failed to build goblin renderer — tagged citizens will not render this session", t);
        }
        return renderer;
    }
}
