package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.client.entity.monster.KnightSpiderRenderer;
import io.github.manasmods.tensura.entity.monster.KnightSpiderEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Render a {@link Beast#KNIGHT_SPIDER}-tagged citizen as a Tensura
 * knight spider via the shadow-entity pattern.
 *
 * <p>Mirrors {@link LizardmanCitizenRenderHandler} structurally — both
 * are GeckoLib-rendered (`GeoEntityRenderer`), both use a per-citizen
 * shadow that's never `tick()`'d. Differences are typed:
 * {@link KnightSpiderEntity} as the shadow type, {@link KnightSpiderRenderer}
 * as the renderer, no per-citizen variant data (Stage 1 — the spider has
 * no per-instance appearance fields). Gating uses
 * {@link BeastTagClientStore} instead of {@link RaceTagClientStore}.
 *
 * <p><b>Hard rule:</b> shadow.tick() / aiStep() never called.
 *
 * <p><b>Visual / hitbox mismatch is accepted</b> per the investigation —
 * the citizen body keeps SCALE 1.0 (humanoid hitbox, MC pathfinding
 * works), the spider visual is 5.0w × 3.75h and will visibly clip
 * through narrow doorways. Combat reach uses the citizen hitbox, not
 * the visible spider geometry.
 */
@OnlyIn(Dist.CLIENT)
public final class KnightSpiderCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Shadow {
        final KnightSpiderEntity entity;
        int lastSyncedTick = Integer.MIN_VALUE;
        /** Last observed citizen.swinging flag. Used to detect the 0→1
         *  edge so we fire the spider's native bite animation exactly
         *  once per citizen swing (rather than every frame the swing is
         *  active). */
        boolean lastSwinging = false;
        Shadow(KnightSpiderEntity entity) { this.entity = entity; }
    }

    private static final Map<UUID, Shadow> SHADOWS = new HashMap<>();
    private static KnightSpiderRenderer renderer;
    private static boolean disabled = false;

    private KnightSpiderCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (disabled) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        BeastTag tag = BeastTagClientStore.get(citizen.getUUID());
        if (tag == null || tag.beast() != Beast.KNIGHT_SPIDER) return;

        event.setCanceled(true);

        if (renderer == null) {
            try { initRenderer(); } catch (Throwable t) {
                LOGGER.error("[TM] knight spider renderer INIT failed — disabling handler", t);
                disabled = true;
                return;
            }
            if (renderer == null) return;
        }

        Shadow shadow = getOrCreateShadow(citizen.getUUID());
        if (shadow == null) return;

        float partialTick = event.getPartialTick();
        syncShadowFromCitizen(shadow, citizen, partialTick);

        float entityYaw = Mth.rotLerp(partialTick, citizen.yBodyRotO, citizen.yBodyRot);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Apply the spider's fixed render scale — DECOUPLED from
        // citizen.getScale(). The citizen's actual SCALE attribute
        // (BEAST_BODY_SCALE) drives the hitbox dimensions, and we
        // intentionally do NOT mirror it into the visual: hitbox and
        // render are independently tunable so we can grow the
        // collision box (for pathfinding / combat reach) without
        // bloating the apparent spider, or vice versa.
        //
        // BEAST_RENDER_SCALE is the value the previous "coupled"
        // setup was rendering at — preserved so this change is
        // visual-neutral while the hitbox grows underneath.
        pose.scale(ExampleMod.BEAST_RENDER_SCALE,
                ExampleMod.BEAST_RENDER_SCALE,
                ExampleMod.BEAST_RENDER_SCALE);

        try {
            renderer.render(shadow.entity, entityYaw, partialTick, pose,
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (Throwable t) {
            LOGGER.error("[TM] knight spider render FAILED for citizen {} — disabling handler",
                    citizen.getUUID(), t);
            disabled = true;
        }
        pose.popPose();
    }

    /** Mirror citizen → shadow each frame. Same pattern as lizardman.
     *  Knight spider has no per-citizen variant data (Stage 1), so this
     *  method is shorter than the lizardman / orc equivalents. */
    private static void syncShadowFromCitizen(Shadow shadow,
                                              AbstractEntityCitizen citizen,
                                              float partialTick) {
        KnightSpiderEntity s = shadow.entity;

        s.setPos(citizen.getX(), citizen.getY(), citizen.getZ());

        s.setYRot(citizen.getYRot());
        s.yRotO = citizen.yRotO;
        s.setXRot(citizen.getXRot());
        s.xRotO = citizen.xRotO;
        s.yBodyRot = citizen.yBodyRot;
        s.yBodyRotO = citizen.yBodyRotO;
        s.yHeadRot = citizen.yHeadRot;
        s.yHeadRotO = citizen.yHeadRotO;

        s.setDeltaMovement(citizen.getDeltaMovement());
        s.setPose(citizen.getPose());
        s.setSprinting(citizen.isSprinting());

        // CRITICAL — mirror onGround.
        //
        // Disassembly of {@code KnightSpiderEntity.loopController} shows
        // the predicate checks {@code !onGround()} BEFORE
        // {@code AnimationState.isMoving()}. A freshly-constructed
        // shadow defaults onGround=false, which means the predicate
        // unconditionally selected {@code animation.knight_spider.falling}
        // every frame — visible symptom: "legs spread, barely moving."
        // (the falling pose is splayed because the spider's leg IK
        // reaches downward in mid-air).
        //
        // Mirroring citizen.onGround() (true while standing/walking on
        // a block) lets the predicate fall through to the walk/idle
        // branches as intended.
        s.setOnGround(citizen.onGround());

        // Mirror the hit-react fields so the spider visual flashes red
        // and wobbles when the citizen takes damage. These are public
        // fields on LivingEntity that GeckoLib's GeoEntityRenderer reads
        // (via inherited LivingEntityRenderer.getOverlayCoords + the
        // rotation wobble in render). Without these, the spider visual
        // is stoic regardless of how much damage the citizen takes —
        // knockback velocity moves the spider through the world (already
        // mirrored via setDeltaMovement above) but there's no hit-react
        // animation at all.
        s.hurtTime = citizen.hurtTime;
        s.hurtDuration = citizen.hurtDuration;
        s.deathTime = citizen.deathTime;

        // Stage L3 polish — mirror the swing-arm timer so the spider
        // visually attacks. GeckoLib's loopController on KnightSpiderEntity
        // has triggerableAnims (bite/dash) but it also reads vanilla
        // LivingEntity.attackAnim / attackAnimO for the default
        // attack-pose advance. Without this, the spider never appears
        // to swing — visible symptom is "legs spread, barely moving."
        s.attackAnim = citizen.attackAnim;
        s.oAttackAnim = citizen.oAttackAnim;
        s.swinging = citizen.swinging;
        s.swingingArm = citizen.swingingArm;
        s.swingTime = citizen.swingTime;

        // Stage L3 polish (problem #2) — port the knight spider's
        // NATIVE attack animation onto the beast-guard. The wild
        // KnightSpiderEntity's combat behaviour fires
        // {@code triggerAnim("miscController", "bite")} via
        // SmartBrainLib's AnimatableMeleeAttack — we can't run that
        // behaviour on a MineColonies citizen, but we CAN detect the
        // citizen's swing-start (false→true edge on {@code swinging})
        // and fire the same trigger on the shadow.
        //
        // Controller name "miscController" and trigger name "bite"
        // confirmed by disassembly of
        // {@code KnightSpiderEntity.registerControllers} —
        // {@code triggerableAnim("bite", animation.knight_spider.bite)}
        // is registered on the controller at ldc constant #430
        // ("miscController").
        //
        // Edge-detect rather than firing every frame: triggerAnim
        // restarts the animation from t=0, which on a continuously-
        // held swing would mean the bite never visually completes.
        boolean swinging = citizen.swinging;
        if (swinging && !shadow.lastSwinging) {
            try {
                s.triggerAnim("miscController", "bite");
            } catch (Throwable t) {
                // GeckoLib triggerAnim is dispatched via the cached
                // animatable instance — should not throw on a shadow
                // built from EntityType.create, but defensive: a
                // failed trigger should not break the whole render.
            }
        }
        shadow.lastSwinging = swinging;

        // Spider-specific synched data — explicitly clear so the
        // animation predicates don't get stale values from a previous
        // shadow lookup. Citizen body never climbs / slam-falls.
        try {
            s.setClimbing(false);
            s.setSlammingFall(false);
        } catch (Throwable t) {
            // Defensive — shouldn't fail on a freshly-created shadow.
        }
        // Note: `hurtDir` is not a public field in 1.21.1 — driven via
        // knockback(...) parameter rather than a mirror-able field.
        // The red-flash + wobble fires off hurtTime alone, which is the
        // visually-prominent piece.

        if (shadow.lastSyncedTick != citizen.tickCount) {
            s.walkAnimation.update(citizen.walkAnimation.speed(), 1.0f);
            shadow.lastSyncedTick = citizen.tickCount;
        }
        s.tickCount = citizen.tickCount;

        s.setCustomName(citizen.getCustomName() != null
                ? citizen.getCustomName()
                : citizen.getName());

        // Equipment slots — beast guards can hold weapons (the guard AI
        // pulls a sword from the inventory). Mirror equipment so the
        // spider visually carries any held items.
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            s.setItemSlot(slot, citizen.getItemBySlot(slot));
        }
    }

    private static Shadow getOrCreateShadow(UUID citizenUuid) {
        Shadow existing = SHADOWS.get(citizenUuid);
        if (existing != null) return existing;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;

        ResourceLocation id = Beasts.idFor(Beast.KNIGHT_SPIDER);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) {
            LOGGER.error("[TM] knight spider shadow create: 'tensura:knight_spider' not in entity registry");
            disabled = true;
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof KnightSpiderEntity ks)) {
            LOGGER.error("[TM] knight spider shadow create: factory returned {}",
                    created != null ? created.getClass().getName() : "null");
            disabled = true;
            return null;
        }
        Shadow s = new Shadow(ks);
        SHADOWS.put(citizenUuid, s);
        LOGGER.info("[TM] knight spider shadow created for citizen {} (shadow uuid={}, pool size {})",
                citizenUuid, ks.getUUID(), SHADOWS.size());
        return s;
    }

    private static void initRenderer() {
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
        renderer = new KnightSpiderRenderer(ctx);
        LOGGER.info("[TM] knight spider renderer constructed");
    }

    public static void removeForEntity(UUID citizenUuid) {
        Shadow removed = SHADOWS.remove(citizenUuid);
        if (removed != null) {
            LOGGER.info("[TM] knight spider shadow dropped for citizen {} (pool size {})",
                    citizenUuid, SHADOWS.size());
        }
    }

    /**
     * Trigger a one-shot GeckoLib animation on a citizen's shadow
     * spider. Called from the {@code TriggerSpiderAnimPayload} handler
     * installed by ClientEvents.
     *
     * <p>Server-side: the beast-guard combat AI decides to leap and
     * sends the payload. Client-side: we look up the shadow we
     * already created for this citizen and call
     * {@code triggerAnim(controllerName, animName)} on it. GeckoLib
     * fires the animation on its next render tick.
     *
     * <p>If the shadow doesn't exist yet (player hasn't rendered the
     * citizen yet — out of range, just joined), the trigger is
     * silently dropped. The next leap will play, and missing one is
     * cosmetic only.
     */
    public static void onTriggerAnimPayload(Networking.TriggerSpiderAnimPayload payload) {
        if (disabled) return;
        Shadow s = SHADOWS.get(payload.entityUuid());
        if (s == null) return;
        try {
            s.entity.triggerAnim(payload.controllerName(), payload.animName());
        } catch (Throwable t) {
            LOGGER.warn("[TM] knight spider triggerAnim threw — ctrl={} anim={}",
                    payload.controllerName(), payload.animName(), t);
        }
    }

    public static void invalidate() {
        if (!SHADOWS.isEmpty()) {
            LOGGER.info("[TM] knight spider shadow pool cleared ({} entries)", SHADOWS.size());
        }
        SHADOWS.clear();
        renderer = null;
        disabled = false;
    }
}
