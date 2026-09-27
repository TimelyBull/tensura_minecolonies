package com.example.examplemod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Client side of the Orb of Domination: draws the orb hanging at the throat
 * of every dominated mob, so you can see who's wearing it.
 *
 * <p>The server tells us which mobs are puppets
 * ({@link Networking.SyncDominatedPayload}, plus a StartTracking resync). We
 * can't add a real "necklace" part to every mob's model, so the orb's item
 * sprite is drawn as a small pendant just under the head, turning with the
 * body — the same trick as a held item, placed at the neck.
 */
@OnlyIn(Dist.CLIENT)
public final class OrbOfDominationClientHandler {

    private static final Set<UUID> DOMINATED = ConcurrentHashMap.newKeySet();
    private static ItemStack pendant;

    private OrbOfDominationClientHandler() {}

    public static void onPayload(Networking.SyncDominatedPayload payload) {
        if (payload.dominated()) DOMINATED.add(payload.entityUuid());
        else DOMINATED.remove(payload.entityUuid());
    }

    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DOMINATED.clear();
    }

    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!DOMINATED.contains(entity.getUUID())) return;
        if (pendant == null) pendant = new ItemStack(ExampleMod.ORB_OF_DOMINATION.get());

        float bodyYaw = Mth.rotLerp(event.getPartialTick(), entity.yBodyRotO, entity.yBodyRot);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Just below the eyes, a little in front of the chest.
        pose.translate(0.0, entity.getEyeHeight() - 0.35 * entity.getScale(), 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        pose.translate(0.0, 0.0, entity.getBbWidth() * 0.5 + 0.02);
        float s = 0.3f * entity.getScale();
        pose.scale(s, s, s);
        Minecraft.getInstance().getItemRenderer().renderStatic(pendant, ItemDisplayContext.FIXED,
                event.getPackedLight(), OverlayTexture.NO_OVERLAY, pose, event.getMultiBufferSource(),
                entity.level(), 0);
        pose.popPose();
    }
}
