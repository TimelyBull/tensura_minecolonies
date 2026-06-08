package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.client.entity.monster.OrcRenderer;
import io.github.manasmods.tensura.entity.monster.OrcEntity;
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
 * Race-system Stage 2 (orc) — render an ORC-tagged citizen as a Tensura
 * orc with its GeckoLib animation driven by the citizen's actual movement.
 *
 * Stage 2 changes over Stage 1:
 * <ul>
 *   <li><b>Per-citizen shadow pool</b> — replaces the single shared shadow.
 *       Each tagged citizen gets its own {@link OrcEntity} instance so it
 *       gets its own slot in GeckoLib's {@code AnimatableInstanceCache}
 *       (which keys by {@code entity.getId()}). Without this, two
 *       simultaneously-visible orc-citizens flicker between each other's
 *       animation states every frame.</li>
 *   <li><b>Movement state mirroring</b> — copies the citizen's
 *       {@code deltaMovement}, {@code pose}, and {@code isSprinting}
 *       onto its shadow each frame. This makes
 *       {@code GeoEntityRenderer.isMoving} (which checks {@code
 *       (|delta.x| + |delta.z|)/2 > 0.015}) true when the citizen is
 *       actually walking, so the loopController picks walk/run instead
 *       of idle.</li>
 *   <li><b>Per-tick walkAnimation advance</b> — when the citizen's
 *       {@code tickCount} changes, mirror that ONE call to
 *       {@code walkAnimation.update(citizenSpeed, 1.0f)}. That sets
 *       {@code speedOld←speed, speed←target, position+=speed} the same
 *       way {@code LivingEntity.aiStep} would each tick, so
 *       {@code walkAnimation.speed(partialTick)} returns the correct
 *       interpolated value on every render frame.</li>
 * </ul>
 *
 * Hard rule: we NEVER call {@code shadow.tick()} or {@code shadow.aiStep()}.
 * Those would run brain/AI/navigation/collision against off-world state
 * (NPEs on missing nav target, off-world death events if the shadow
 * "walked into lava" while at the citizen's position, etc.). Only direct
 * field writes and public setters are used.
 *
 * Deferred (NOT implemented this stage):
 * <ul>
 *   <li>Swim animation — needs reflection on private {@code Entity.wasInWater}
 *       since there's no public setter; orc-citizens won't play
 *       {@code animation.orc.swim} until then.</li>
 *   <li>Triggerable one-shots (attack / shield / crossbow) — needs
 *       citizen-side attack detection + {@code triggerAnim()} plumbing.
 *       The miscController stays at {@code PlayState.STOP}.</li>
 *   <li>Variants (neck / top / bottom / colors) — Stage 3.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class OrcCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-citizen shadow + last-synced-tick. The tick value is the
     *  citizen's {@code tickCount} the last time we mirrored
     *  walkAnimation onto this shadow — we only do that work when the
     *  citizen has actually advanced a tick, not every render frame. */
    private static final class Shadow {
        final OrcEntity entity;
        int lastSyncedTick = Integer.MIN_VALUE;
        Shadow(OrcEntity entity) { this.entity = entity; }
    }

    private static final Map<UUID, Shadow> SHADOWS = new HashMap<>();
    private static OrcRenderer renderer;
    private static boolean disabled = false;

    private OrcCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (disabled) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null || tag.race() != Race.ORC) return;

        // Cancel BEFORE rendering — if our render below throws, we still
        // skip MineColonies' default render. (Same pattern as the goblin
        // handler.)
        event.setCanceled(true);

        if (renderer == null) {
            try { initRenderer(); } catch (Throwable t) {
                LOGGER.error("[TM] orc renderer INIT failed — disabling handler", t);
                disabled = true;
                return;
            }
            if (renderer == null) return;
        }

        Shadow shadow = getOrCreateShadow(citizen.getUUID());
        if (shadow == null) return; // create failed; logged once inside

        float partialTick = event.getPartialTick();
        syncShadowFromCitizen(shadow, citizen, partialTick);

        // entityYaw — same formula vanilla EntityRenderDispatcher uses for
        // living entities. RenderLivingEvent.Pre doesn't carry it.
        float entityYaw = Mth.rotLerp(partialTick, citizen.yBodyRotO, citizen.yBodyRot);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // SCALE retrofit — orc model scales in lockstep with citizen SCALE
        // attribute. Default 1.0 → orc at intrinsic 0.8×2.5 type size.
        float scale = citizen.getScale();
        pose.scale(scale, scale, scale);

        try {
            renderer.render(shadow.entity, entityYaw, partialTick, pose,
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (Throwable t) {
            LOGGER.error("[TM] orc render FAILED for citizen {} — disabling handler",
                    citizen.getUUID(), t);
            disabled = true;
        }
        pose.popPose();
    }

    /**
     * Mirror citizen state → shadow. Per-frame work; the per-tick
     * walkAnimation advance is conditional on
     * {@code citizen.tickCount != shadow.lastSyncedTick}.
     *
     * NEVER call {@code shadow.entity.tick()} or {@code aiStep()} —
     * see class doc. Only direct field writes and public setters.
     */
    private static void syncShadowFromCitizen(Shadow shadow,
                                              AbstractEntityCitizen citizen,
                                              float partialTick) {
        OrcEntity orc = shadow.entity;

        // Position — informational; the actual draw position comes from
        // the PoseStack (the dispatcher pre-translated to citizen's
        // interpolated world position before firing RenderLivingEvent.Pre).
        // Setting it correctly keeps blockPosition()-dependent reads sane
        // (e.g. ambient light at the right block).
        orc.setPos(citizen.getX(), citizen.getY(), citizen.getZ());

        // Orientation — all four rotation pairs so the renderer's lerp
        // between current and previous works correctly between frames.
        orc.setYRot(citizen.getYRot());
        orc.yRotO = citizen.yRotO;
        orc.setXRot(citizen.getXRot());
        orc.xRotO = citizen.xRotO;
        orc.yBodyRot = citizen.yBodyRot;
        orc.yBodyRotO = citizen.yBodyRotO;
        orc.yHeadRot = citizen.yHeadRot;
        orc.yHeadRotO = citizen.yHeadRotO;

        // Stage 2 — the missing pieces that make isMoving true.
        // GeoEntityRenderer reads getDeltaMovement() to derive the
        // (|x|+|z|)/2 > 0.015 condition; without this it's always 0,
        // so loopController always picks idle.
        orc.setDeltaMovement(citizen.getDeltaMovement());
        // Pose unlocks the sleep branch in loopController
        // (isSleeping() → Pose.SLEEPING).
        orc.setPose(citizen.getPose());
        // Sprint flag selects run vs walk in loopController.
        orc.setSprinting(citizen.isSprinting());

        // walkAnimation — only advance ONCE per detected tick change.
        // update(target, 1.0) is the exact call vanilla makes each tick
        // in LivingEntity.aiStep: speedOld←speed, speed←target,
        // position+=speed. Doing this per frame instead of per tick
        // would cause position to accumulate ~3× faster than real, so
        // the gate matters.
        if (shadow.lastSyncedTick != citizen.tickCount) {
            orc.walkAnimation.update(citizen.walkAnimation.speed(), 1.0f);
            shadow.lastSyncedTick = citizen.tickCount;
        }
        // Bump tickCount so GeckoLib's animation clock advances
        // (GeoEntity.getTick default returns tickCount directly).
        orc.tickCount = citizen.tickCount;

        // Nameplate — citizen's display name copied onto the shadow each
        // frame; GeckoLib's default name-tag rendering picks it up.
        orc.setCustomName(citizen.getCustomName() != null
                ? citizen.getCustomName()
                : citizen.getName());

        // Equipment slots — Tensura's OrcRenderer$1 is a GeckoLib
        // ItemArmorGeoLayer that reads from the entity's HEAD/CHEST/LEGS/FEET
        // slots, and OrcRenderer$2 is a BlockAndItemGeoLayer that reads
        // mainHand/offHand (cached by OrcRenderer.preRender from
        // entity.getMainHandItem()/getOffhandItem()). Without mirroring these
        // onto the shadow, the orc citizen renders with no armor or weapon.
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            orc.setItemSlot(slot, citizen.getItemBySlot(slot));
        }

        // Stage 3 — variant fields. Tensura's OrcRenderer adds 7
        // OrcLayer accessory layers (Neck, Top, Necklace, Bottom, Belt,
        // Boots, Bandage) that each read state directly off the entity
        // (orc.getNeck(), orc.hasBandage(), orc.getBootsColor(), …) per
        // the F5 javap. So driving accessories is just "write these
        // setters before render". No new layer classes needed.
        //
        // Force evolutionState/evolving to 0 — base-orc render only.
        // Orc lord and orc disaster are separate Tensura EntityTypes
        // and are blocked from the citizen pipeline by Races.isBlocked.
        OrcVariantData v = RaceTagClientStore.getOrcVariant(citizen.getUUID());
        if (v != null) {
            try {
                orc.setVariant(io.github.manasmods.tensura.entity.variant.OrcVariant.byId(v.variantId()));
            } catch (Throwable t) {
                // Defensive — invalid stored id falls back to OrcVariant.byId(0) = HAM
                orc.setVariant(io.github.manasmods.tensura.entity.variant.OrcVariant.byId(0));
            }
            orc.setNeck(v.neckId());
            orc.setNeckColor(v.neckColor());
            orc.setTop(v.topId());
            orc.setTopColor(v.topColor());
            orc.setBottomColor(v.bottomColor());
            orc.setBeltColor(v.beltColor());
            orc.setBootsColor(v.bootsColor());
            orc.setBandage(v.bandage());
            orc.setNecklace(v.necklace());
            orc.setCurrentEvolutionState(0);
            orc.setEvolving(0);
        }
    }

    private static Shadow getOrCreateShadow(UUID citizenUuid) {
        Shadow existing = SHADOWS.get(citizenUuid);
        if (existing != null) return existing;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;

        // Resolve through the central race registry rather than a local
        // hardcoded ResourceLocation — adding a new race only touches Races.
        ResourceLocation orcId = Races.idFor(Race.ORC);
        EntityType<?> orcType = BuiltInRegistries.ENTITY_TYPE.get(orcId);
        if (orcType == null) {
            LOGGER.error("[TM] orc shadow create: 'tensura:orc' not in entity registry");
            disabled = true;
            return null;
        }
        Entity created = orcType.create(level);
        if (!(created instanceof OrcEntity orc)) {
            LOGGER.error("[TM] orc shadow create: factory returned {}",
                    created != null ? created.getClass().getName() : "null");
            disabled = true;
            return null;
        }
        Shadow s = new Shadow(orc);
        SHADOWS.put(citizenUuid, s);
        LOGGER.info("[TM] orc shadow created for citizen {} (shadow uuid={}, pool size {})",
                citizenUuid, orc.getUUID(), SHADOWS.size());
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
        renderer = new OrcRenderer(ctx);
        LOGGER.info("[TM] orc renderer constructed");
    }

    /** Drop a single citizen's shadow — wired to
     *  {@code EntityLeaveLevelEvent} in {@link ClientEvents}, mirroring
     *  how {@link RaceTagClientStore#removeForEntity(UUID)} cleans up. */
    public static void removeForEntity(UUID citizenUuid) {
        Shadow removed = SHADOWS.remove(citizenUuid);
        if (removed != null) {
            LOGGER.info("[TM] orc shadow dropped for citizen {} (pool size {})",
                    citizenUuid, SHADOWS.size());
        }
    }

    /** Wipe everything — called on logout. Next session re-tests
     *  cleanly against fresh baked models / resources. */
    public static void invalidate() {
        if (!SHADOWS.isEmpty()) {
            LOGGER.info("[TM] orc shadow pool cleared ({} entries)", SHADOWS.size());
        }
        SHADOWS.clear();
        renderer = null;
        disabled = false;
    }
}
