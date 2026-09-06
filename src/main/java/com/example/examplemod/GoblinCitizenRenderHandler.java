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

    /** Failure bounding. Without these, a renderer that throws every frame
     *  re-logs a full stack trace AND rebuilds the whole renderer (8 overlay
     *  layers + armor + item-in-hand, with model baking) on the NEXT frame,
     *  for every goblin citizen on screen. That death spiral floods the log
     *  and churns the heap until the game freezes — the shape of the
     *  2026-09-05 "progressive FPS drain" report. Mirrors the permanent
     *  {@code disabled} latch the orc/lizardman handlers already use.
     *
     *  Both latches are cleared ONLY by {@link #invalidate()} (logout), never
     *  by the failure path itself — resetting them on failure is exactly what
     *  defeated the dwarf handler's threshold. */
    private static boolean disabled = false;
    private static boolean buildFailed = false;
    private static int consecutiveFailures = 0;
    private static final int FAILURE_THRESHOLD = 5;

    private GoblinCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (disabled) return;
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
            consecutiveFailures = 0;
        } catch (Throwable t) {
            consecutiveFailures++;
            // Stack trace ONCE — after that the counter alone, so a persistent
            // failure can't write gigabytes to latest.log.
            if (consecutiveFailures == 1) {
                LOGGER.error("[TM] goblin render failed for entity {} — will retry {} more times before disabling",
                        citizen.getUUID(), FAILURE_THRESHOLD - 1, t);
            } else {
                LOGGER.error("[TM] goblin render failed for entity {} (failure {}/{})",
                        citizen.getUUID(), consecutiveFailures, FAILURE_THRESHOLD);
            }
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                LOGGER.error("[TM] goblin renderer failed {} times — disabling for this session; "
                        + "goblin citizens will render as plain colonists", FAILURE_THRESHOLD);
                // NOT invalidate(): this must NOT clear the latch, or the next
                // frame rebuilds and re-enters the spiral.
                disabled = true;
                renderer = null;
            }
        }
    }

    /** Full reset for a session boundary. Called from
     *  {@code ClientEvents.onClientLoggingOut} — drops the cached renderer
     *  (its Context references the outgoing world's resource manager) AND
     *  clears the failure latches, so a new session starts clean. The render
     *  failure path deliberately does NOT call this. */
    public static void invalidate() {
        renderer = null;
        disabled = false;
        buildFailed = false;
        consecutiveFailures = 0;
    }

    private static GoblinCitizenRenderer renderer() {
        if (renderer != null) return renderer;
        if (buildFailed) return null; // already tried and failed; don't loop-build
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
            // Latch it. Previously this only left `renderer` null, so the very
            // next frame re-attempted the build and re-logged the stack trace —
            // every frame, for every goblin citizen. The comment claimed "log
            // once"; nothing enforced it.
            buildFailed = true;
            LOGGER.error("[TM] failed to build goblin renderer — tagged citizens will not render this session", t);
        }
        return renderer;
    }
}
