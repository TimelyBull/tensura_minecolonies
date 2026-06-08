package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.client.entity.monster.LizardmanRenderer;
import io.github.manasmods.tensura.entity.monster.LizardmanEntity;
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
 * Render a LIZARDMAN-tagged citizen as a Tensura lizardman with its
 * GeckoLib animation driven by the citizen's actual movement.
 *
 * <p>Mirrors {@link OrcCitizenRenderHandler} byte-for-byte in structure —
 * both Tensura races are GeckoLib-rendered and the shadow-entity pattern
 * is identical. The differences are typed: per-citizen shadow is a
 * {@link LizardmanEntity}, and the variant-applied fields come from
 * {@link LizardmanVariantData} instead of {@link OrcVariantData}.
 *
 * <p><b>Hard rule:</b> NEVER call {@code shadow.tick()} or
 * {@code shadow.aiStep()} — same justification as the orc handler
 * (off-world brain / nav / collision would NPE or fire spurious death
 * events). Only direct field writes and public setters.
 */
@OnlyIn(Dist.CLIENT)
public final class LizardmanCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Shadow {
        final LizardmanEntity entity;
        int lastSyncedTick = Integer.MIN_VALUE;
        Shadow(LizardmanEntity entity) { this.entity = entity; }
    }

    private static final Map<UUID, Shadow> SHADOWS = new HashMap<>();
    private static LizardmanRenderer renderer;
    private static boolean disabled = false;

    private LizardmanCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (disabled) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null || tag.race() != Race.LIZARDMAN) return;

        // Cancel BEFORE rendering — if our render below throws, we still
        // skip MineColonies' default render.
        event.setCanceled(true);

        if (renderer == null) {
            try { initRenderer(); } catch (Throwable t) {
                LOGGER.error("[TM] lizardman renderer INIT failed — disabling handler", t);
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
        float scale = citizen.getScale();
        pose.scale(scale, scale, scale);

        try {
            renderer.render(shadow.entity, entityYaw, partialTick, pose,
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (Throwable t) {
            LOGGER.error("[TM] lizardman render FAILED for citizen {} — disabling handler",
                    citizen.getUUID(), t);
            disabled = true;
        }
        pose.popPose();
    }

    /**
     * Mirror citizen state → shadow. Per-frame work; the per-tick
     * walkAnimation advance is conditional on
     * {@code citizen.tickCount != shadow.lastSyncedTick} so it fires
     * exactly once per game tick rather than per render frame.
     */
    private static void syncShadowFromCitizen(Shadow shadow,
                                              AbstractEntityCitizen citizen,
                                              float partialTick) {
        LizardmanEntity l = shadow.entity;

        l.setPos(citizen.getX(), citizen.getY(), citizen.getZ());

        l.setYRot(citizen.getYRot());
        l.yRotO = citizen.yRotO;
        l.setXRot(citizen.getXRot());
        l.xRotO = citizen.xRotO;
        l.yBodyRot = citizen.yBodyRot;
        l.yBodyRotO = citizen.yBodyRotO;
        l.yHeadRot = citizen.yHeadRot;
        l.yHeadRotO = citizen.yHeadRotO;

        l.setDeltaMovement(citizen.getDeltaMovement());
        l.setPose(citizen.getPose());
        l.setSprinting(citizen.isSprinting());

        if (shadow.lastSyncedTick != citizen.tickCount) {
            l.walkAnimation.update(citizen.walkAnimation.speed(), 1.0f);
            shadow.lastSyncedTick = citizen.tickCount;
        }
        l.tickCount = citizen.tickCount;

        l.setCustomName(citizen.getCustomName() != null
                ? citizen.getCustomName()
                : citizen.getName());

        // Equipment slots — Tensura's LizardmanRenderer reads slot items
        // for armor and held weapons just like OrcRenderer does. Mirror
        // them onto the shadow each frame.
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            l.setItemSlot(slot, citizen.getItemBySlot(slot));
        }

        // Variant fields. Force evolutionState/evolving to 0 — base
        // lizardman render only. Higher tiers (Dragonewt etc.) are
        // Tensura race-tier state on the same entity but we render
        // base form only at the citizen pipeline (consistent with how
        // we render base orc / base goblin only).
        LizardmanVariantData v = RaceTagClientStore.getLizardmanVariant(citizen.getUUID());
        if (v != null) {
            try {
                l.setVariant(io.github.manasmods.tensura.entity.variant.LizardmanVariant.byId(v.variantId()));
            } catch (Throwable t) {
                l.setVariant(io.github.manasmods.tensura.entity.variant.LizardmanVariant.byId(0));
            }
            l.setHair(v.hairId());
            l.setHairColor(v.hairColor());
            l.setTop(v.topId());
            l.setTopColor(v.topColor());
            l.setBottomColor(v.bottomColor());
            l.setBandage(v.bandage());
            l.setCurrentEvolutionState(0);
            l.setEvolving(0);
        }
    }

    private static Shadow getOrCreateShadow(UUID citizenUuid) {
        Shadow existing = SHADOWS.get(citizenUuid);
        if (existing != null) return existing;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;

        ResourceLocation lizardId = Races.idFor(Race.LIZARDMAN);
        EntityType<?> lizardType = BuiltInRegistries.ENTITY_TYPE.get(lizardId);
        if (lizardType == null) {
            LOGGER.error("[TM] lizardman shadow create: 'tensura:lizardman' not in entity registry");
            disabled = true;
            return null;
        }
        Entity created = lizardType.create(level);
        if (!(created instanceof LizardmanEntity l)) {
            LOGGER.error("[TM] lizardman shadow create: factory returned {}",
                    created != null ? created.getClass().getName() : "null");
            disabled = true;
            return null;
        }
        Shadow s = new Shadow(l);
        SHADOWS.put(citizenUuid, s);
        LOGGER.info("[TM] lizardman shadow created for citizen {} (shadow uuid={}, pool size {})",
                citizenUuid, l.getUUID(), SHADOWS.size());
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
        renderer = new LizardmanRenderer(ctx);
        LOGGER.info("[TM] lizardman renderer constructed");
    }

    /** Drop a single citizen's shadow — wired to
     *  {@code EntityLeaveLevelEvent} in {@link ClientEvents}, mirroring
     *  how {@link OrcCitizenRenderHandler#removeForEntity} cleans up. */
    public static void removeForEntity(UUID citizenUuid) {
        Shadow removed = SHADOWS.remove(citizenUuid);
        if (removed != null) {
            LOGGER.info("[TM] lizardman shadow dropped for citizen {} (pool size {})",
                    citizenUuid, SHADOWS.size());
        }
    }

    /** Wipe everything — called on logout. */
    public static void invalidate() {
        if (!SHADOWS.isEmpty()) {
            LOGGER.info("[TM] lizardman shadow pool cleared ({} entries)", SHADOWS.size());
        }
        SHADOWS.clear();
        renderer = null;
        disabled = false;
    }
}
