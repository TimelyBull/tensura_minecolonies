package com.example.examplemod;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.tensura.registry.skill.UniqueSkills;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the assassin LURKING tell: a red "Assassin" line above
 * the citizen's head, visible ONLY when the local player has the Great
 * Sage unique skill (the skill SEES the plot — without it, no tell).
 *
 * The server syncs lurk flags per entity UUID
 * ({@link Networking.SyncAssassinFlagPayload}, plus a StartTracking
 * resync); the Great Sage check is a client-side self-check against the
 * player's OWN ManasCore skill storage (synced by ManasCore), cached
 * for a second to avoid per-frame registry lookups.
 */
@OnlyIn(Dist.CLIENT)
public final class AssassinClientHandler {

    /** Entity UUIDs currently flagged LURKING. */
    private static final Set<UUID> FLAGGED = ConcurrentHashMap.newKeySet();

    private static final Component TELL = Component.literal("Assassin");
    private static final int TELL_COLOR = 0xFFFF4040;

    private static boolean greatSageCache = false;
    private static long greatSageCacheTime = Long.MIN_VALUE;

    private AssassinClientHandler() {}

    public static void onPayload(Networking.SyncAssassinFlagPayload payload) {
        if (payload.lurking()) FLAGGED.add(payload.entityUuid());
        else FLAGGED.remove(payload.entityUuid());
    }

    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FLAGGED.clear();
    }

    private static boolean localPlayerHasGreatSage() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return false;
        long now = player.level().getGameTime();
        if (now - greatSageCacheTime >= 20) {
            greatSageCacheTime = now;
            boolean has = false;
            try {
                has = SkillAPI.getSkillsFrom(player)
                        .getSkill(UniqueSkills.GREAT_SAGE.getId())
                        .isPresent();
            } catch (Throwable ignored) { }
            greatSageCache = has;
        }
        return greatSageCache;
    }

    /**
     * Post-render hook: draw the red tell above any flagged entity. Runs
     * AFTER our race renderers (which own the normal nameplate), so the
     * tell stacks above the name. Positioned/billboarded with the
     * vanilla nameplate math.
     */
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!FLAGGED.contains(entity.getUUID())) return;
        if (!localPlayerHasGreatSage()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Font font = mc.font;

        poseStack.pushPose();
        // Above the normal nameplate (which sits at height + 0.5).
        poseStack.translate(0.0, entity.getBbHeight() + 0.9, 0.0);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(0.025f, -0.025f, 0.025f);
        Matrix4f pose = poseStack.last().pose();
        float x = -font.width(TELL) / 2.0f;
        int bg = (int) (mc.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
        font.drawInBatch(TELL, x, 0, TELL_COLOR, false, pose,
                event.getMultiBufferSource(), Font.DisplayMode.SEE_THROUGH,
                bg, event.getPackedLight());
        poseStack.popPose();
    }
}
