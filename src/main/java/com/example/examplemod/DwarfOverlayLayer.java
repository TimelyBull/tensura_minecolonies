package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Generic overlay layer for {@link DwarfCitizenRenderer}. Structural
 * twin of {@link GoblinOverlayLayer} — same {@link PlayerModel}-based
 * overlay-parts rendering, parameterised by a per-overlay
 * {@link ModelLayerLocation} (e.g. {@code tensura:dwarf_face main}), a
 * variant-to-texture function, a variant-to-color function, and a
 * should-render predicate.
 *
 * <p>The PlayerModel slim=false matches Tensura's
 * {@code DwarfLayer.<clinit>} construction
 * ({@code PlayerModel.createMesh(deformation, false)}); the same
 * lesson learned for goblin Top/Bottom overlay-parts (jacket /
 * sleeves / pant-legs) applies here for dwarf clothing.
 */
@OnlyIn(Dist.CLIENT)
public class DwarfOverlayLayer
        extends RenderLayer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Null when the underlying model layer failed to bake (e.g. Tensura's
     *  layer definition isn't registered in this dev env). {@link #render}
     *  early-outs when null so a single bad overlay doesn't disable the
     *  entire dwarf renderer. */
    private final PlayerModel<AbstractEntityCitizen> overlayModel;
    private final Function<DwarfVariantData, ResourceLocation> textureFn;
    private final ToIntFunction<DwarfVariantData> colorFn;
    private final Predicate<DwarfVariantData> shouldRender;

    public DwarfOverlayLayer(
            RenderLayerParent<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> parent,
            ModelLayerLocation modelLayer,
            Function<DwarfVariantData, ResourceLocation> textureFn,
            ToIntFunction<DwarfVariantData> colorFn,
            Predicate<DwarfVariantData> shouldRender) {
        super(parent);
        PlayerModel<AbstractEntityCitizen> baked;
        try {
            baked = new PlayerModel<>(
                    Minecraft.getInstance().getEntityModels().bakeLayer(modelLayer),
                    false);
        } catch (Throwable t) {
            LOGGER.warn("[TM] dwarf overlay '{}' failed to bake — overlay will be skipped",
                    modelLayer, t);
            baked = null;
        }
        this.overlayModel = baked;
        this.textureFn = textureFn;
        this.colorFn = colorFn;
        this.shouldRender = shouldRender;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractEntityCitizen citizen, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        if (overlayModel == null) return;
        DwarfVariantData v = RaceTagClientStore.getDwarfVariant(citizen.getUUID());
        if (v == null) return;
        if (!shouldRender.test(v)) return;

        ResourceLocation texture = textureFn.apply(v);
        if (texture == null) return;

        int color = colorFn.applyAsInt(v);

        overlayModel.prepareMobModel(citizen, limbSwing, limbSwingAmount, partialTick);
        getParentModel().copyPropertiesTo(overlayModel);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(texture));
        overlayModel.setupAnim(citizen, limbSwing, limbSwingAmount,
                ageInTicks, netHeadYaw, headPitch);

        poseStack.pushPose();
        overlayModel.renderToBuffer(poseStack, vc, packedLight,
                OverlayTexture.NO_OVERLAY, color);
        poseStack.popPose();
    }
}
