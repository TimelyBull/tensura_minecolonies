package com.example.examplemod;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.tensura.registry.skill.UniqueSkills;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the mind-control SUSPICION tell: a faint "…?" above a
 * controlled/planted citizen of the local player's OWN colony, visible only
 * when the local player holds an information skill (the same list as the
 * server-side theft/absence warnings — Great Sage or a TR:N analysis lord;
 * plain Sage excluded).
 *
 * Mirror of {@link AssassinClientHandler}: the server syncs flags per entity
 * UUID ({@link Networking.SyncSuspicionFlagPayload}, diff-based every 5 s,
 * sent only to the citizen's owner); the skill check is a cached client-side
 * self-check.
 */
@OnlyIn(Dist.CLIENT)
public final class SuspicionClientHandler {

    /** Entity UUIDs currently flagged suspicious for THIS client. */
    private static final Set<UUID> FLAGGED = ConcurrentHashMap.newKeySet();

    private static final Component TELL = Component.literal("…?");
    private static final int TELL_COLOR = 0xFFB080FF; // pale violet — subtle

    /** TR:N analysis-lord ids — registry lookups by id, safe without the mod
     *  (kept in sync with MindControlTracker.TRN_INFO_SKILLS). */
    private static final List<ResourceLocation> TRN_INFO_IDS = List.of(
            ResourceLocation.parse("trnightmare:akashic_records"),
            ResourceLocation.parse("trnightmare:nodens"),
            ResourceLocation.parse("trnightmare:raphael_wisdom"),
            ResourceLocation.parse("trnightmare:raphael_knowledge"),
            ResourceLocation.parse("trnightmare:faust"),
            ResourceLocation.parse("trnightmare:investigator"));

    private static boolean infoSkillCache = false;
    private static long infoSkillCacheTime = Long.MIN_VALUE;

    private SuspicionClientHandler() {}

    public static void onPayload(Networking.SyncSuspicionFlagPayload payload) {
        if (payload.flagged()) FLAGGED.add(payload.entityUuid());
        else FLAGGED.remove(payload.entityUuid());
    }

    /** Is this entity flagged suspicious for the local player? Drives the
     *  citizen-window [Interrogate] tab's visibility. */
    static boolean isFlagged(UUID entityUuid) {
        return FLAGGED.contains(entityUuid);
    }

    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FLAGGED.clear();
        infoSkillCache = false;
        infoSkillCacheTime = Long.MIN_VALUE;
    }

    private static boolean localPlayerHasInfoSkill() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return false;
        long now = player.level().getGameTime();
        if (now - infoSkillCacheTime >= 20) {
            infoSkillCacheTime = now;
            boolean has = false;
            try {
                var storage = SkillAPI.getSkillsFrom(player);
                has = storage.getSkill(UniqueSkills.GREAT_SAGE.getId()).isPresent();
                if (!has) {
                    for (ResourceLocation id : TRN_INFO_IDS) {
                        if (storage.getSkill(id).isPresent()) { has = true; break; }
                    }
                }
            } catch (Throwable ignored) { }
            infoSkillCache = has;
        }
        return infoSkillCache;
    }

    /** Post-render hook — same billboard math as the assassin tell, placed a
     *  little lower and dimmer (it is a hint, not an alarm). */
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!FLAGGED.contains(entity.getUUID())) return;
        if (!localPlayerHasInfoSkill()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Font font = mc.font;

        poseStack.pushPose();
        poseStack.translate(0.0, entity.getBbHeight() + 0.75, 0.0);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(0.02f, -0.02f, 0.02f);
        Matrix4f pose = poseStack.last().pose();
        float x = -font.width(TELL) / 2.0f;
        int bg = (int) (mc.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
        font.drawInBatch(TELL, x, 0, TELL_COLOR, false, pose,
                event.getMultiBufferSource(), Font.DisplayMode.SEE_THROUGH,
                bg, event.getPackedLight());
        poseStack.popPose();
    }
}
