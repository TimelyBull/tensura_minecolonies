package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renders OTHERWORLDER race citizens as the Tensura character they are.
 *
 * <p>Same shadow-entity idea as {@link LizardmanCitizenRenderHandler}: cancel
 * the citizen's normal render and instead draw a hidden, never-ticked copy of
 * the real mob posed like the citizen. The difference: otherworlders are EIGHT
 * entity types (one per character, each with its own fixed skin and slim/wide
 * arms), so instead of constructing one renderer we ask Minecraft for the
 * renderer Tensura already REGISTERED for that character's type
 * ({@code getEntityRenderDispatcher().getRenderer(shadow)}). The character
 * comes from the citizen's {@link OtherworlderVariantData}.
 *
 * <p>A render failure disables only that CITIZEN's shadow (it falls back to the
 * plain MineColonies look), never the whole handler.
 */
@OnlyIn(Dist.CLIENT)
public final class OtherworlderCitizenRenderHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Shadow {
        final LivingEntity entity;
        final String typeId;
        int lastSyncedTick = Integer.MIN_VALUE;
        Shadow(LivingEntity entity, String typeId) { this.entity = entity; this.typeId = typeId; }
    }

    private static final Map<UUID, Shadow> SHADOWS = new HashMap<>();
    /** Citizens whose shadow failed to create or render — drawn vanilla from then on. */
    private static final java.util.Set<UUID> FAILED = new java.util.HashSet<>();

    private OtherworlderCitizenRenderHandler() {}

    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        RaceTag tag = RaceTagClientStore.get(citizen.getUUID());
        if (tag == null || tag.race() != Race.OTHERWORLDER) return;
        if (FAILED.contains(citizen.getUUID())) return;
        String typeId = tag.variant() instanceof OtherworlderVariantData w
                ? w.typeId() : OtherworlderVariantData.DEFAULT.typeId();

        Shadow shadow = getOrCreateShadow(citizen.getUUID(), typeId);
        if (shadow == null) return; // vanilla render continues
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> renderer = (EntityRenderer<Entity>)
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(shadow.entity);
        if (renderer == null) return;

        event.setCanceled(true);
        float partialTick = event.getPartialTick();
        syncShadowFromCitizen(shadow, citizen);
        float entityYaw = Mth.rotLerp(partialTick, citizen.yBodyRotO, citizen.yBodyRot);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            renderer.render(shadow.entity, entityYaw, partialTick, pose,
                    event.getMultiBufferSource(), event.getPackedLight());
        } catch (Throwable t) {
            LOGGER.error("[TM] otherworlder render FAILED for citizen {} ({}) — using the plain citizen look",
                    citizen.getUUID(), typeId, t);
            FAILED.add(citizen.getUUID());
            SHADOWS.remove(citizen.getUUID());
        }
        pose.popPose();
    }

    private static void syncShadowFromCitizen(Shadow shadow, AbstractEntityCitizen citizen) {
        LivingEntity e = shadow.entity;
        e.setPos(citizen.getX(), citizen.getY(), citizen.getZ());
        e.setYRot(citizen.getYRot());
        e.yRotO = citizen.yRotO;
        e.setXRot(citizen.getXRot());
        e.xRotO = citizen.xRotO;
        e.yBodyRot = citizen.yBodyRot;
        e.yBodyRotO = citizen.yBodyRotO;
        e.yHeadRot = citizen.yHeadRot;
        e.yHeadRotO = citizen.yHeadRotO;
        e.setDeltaMovement(citizen.getDeltaMovement());
        e.setPose(citizen.getPose());
        e.setSprinting(citizen.isSprinting());
        if (shadow.lastSyncedTick != citizen.tickCount) {
            e.walkAnimation.update(citizen.walkAnimation.speed(), 1.0f);
            shadow.lastSyncedTick = citizen.tickCount;
        }
        e.tickCount = citizen.tickCount;
        e.setCustomName(citizen.getCustomName() != null ? citizen.getCustomName() : citizen.getName());
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            e.setItemSlot(slot, citizen.getItemBySlot(slot));
        }
    }

    private static Shadow getOrCreateShadow(UUID citizenUuid, String typeId) {
        Shadow existing = SHADOWS.get(citizenUuid);
        if (existing != null && existing.typeId.equals(typeId)) return existing;

        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.parse(typeId)).orElse(null);
        Entity created = type != null ? type.create(level) : null;
        if (!(created instanceof LivingEntity living)) {
            LOGGER.error("[TM] otherworlder shadow create: '{}' unavailable — citizen {} uses the plain look",
                    typeId, citizenUuid);
            FAILED.add(citizenUuid);
            return null;
        }
        Shadow s = new Shadow(living, typeId);
        SHADOWS.put(citizenUuid, s);
        return s;
    }

    public static void removeForEntity(UUID citizenUuid) {
        SHADOWS.remove(citizenUuid);
        FAILED.remove(citizenUuid);
    }

    public static void invalidate() {
        SHADOWS.clear();
        FAILED.clear();
    }
}
