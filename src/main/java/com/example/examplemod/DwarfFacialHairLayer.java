package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * Dwarf facial-hair overlay. {@link DwarfOverlayLayer} can't be reused here
 * because Tensura's {@code DwarfLayer.FACIAL_HAIR_LAYER} is built from
 * {@code HumanoidModel.createMesh(...)} — every other dwarf overlay uses
 * {@code PlayerModel.createMesh(...)}. Trying to wrap the FacialHair model
 * part in a {@link PlayerModel} throws during bake (the PlayerModel
 * constructor reaches for slim-arm / cloak / ear children that the
 * HumanoidModel mesh doesn't provide), so the overlay falls back to
 * disabled and the dwarf renders with no beard at all.
 *
 * <p>Wrapping it in a vanilla {@link HumanoidModel} is the matching shape
 * Tensura's own {@code DwarfLayer.FacialHair} uses, and it bakes cleanly.
 * Only males render facial hair (the predicate hides the layer for
 * females, mirroring Tensura's own gate).
 */
@OnlyIn(Dist.CLIENT)
public class DwarfFacialHairLayer
        extends RenderLayer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final HumanoidModel<AbstractEntityCitizen> overlayModel;

    public DwarfFacialHairLayer(
            RenderLayerParent<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> parent) {
        super(parent);
        HumanoidModel<AbstractEntityCitizen> baked;
        try {
            baked = new HumanoidModel<>(
                    Minecraft.getInstance().getEntityModels().bakeLayer(
                            io.github.manasmods.tensura.client.entity.layer.DwarfLayer.FacialHair.FACIAL_HAIR));
        } catch (Throwable t) {
            LOGGER.warn("[TM] dwarf facial-hair overlay failed to bake — beards will not render", t);
            baked = null;
        }
        this.overlayModel = baked;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractEntityCitizen citizen, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        if (overlayModel == null) return;
        DwarfVariantData v = RaceTagClientStore.getDwarfVariant(citizen.getUUID());
        if (v == null) return;

        ResourceLocation texture = DwarfTextures.facialHair(v);
        if (texture == null) return;

        overlayModel.prepareMobModel(citizen, limbSwing, limbSwingAmount, partialTick);
        // Parent is a PlayerModel; HumanoidModel.copyPropertiesTo from a
        // PlayerModel parent is fine because PlayerModel extends HumanoidModel
        // and Tensura's own DwarfLayer.FacialHair.render does the same copy.
        getParentModel().copyPropertiesTo(overlayModel);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(texture));
        overlayModel.setupAnim(citizen, limbSwing, limbSwingAmount,
                ageInTicks, netHeadYaw, headPitch);

        poseStack.pushPose();
        overlayModel.renderToBuffer(poseStack, vc, packedLight,
                OverlayTexture.NO_OVERLAY, -1);
        poseStack.popPose();
    }
}
