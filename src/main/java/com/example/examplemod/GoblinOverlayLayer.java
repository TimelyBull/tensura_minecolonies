package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Generic overlay layer for {@link GoblinCitizenRenderer}. One instance
 * per Tensura overlay kind (face, hair, hair-body, clothing, bandages).
 *
 * Each instance:
 *   - holds its own {@link HumanoidModel} baked from Tensura's per-overlay
 *     {@code ModelLayerLocation} (e.g. {@code tensura:goblin_face main}),
 *     so we reuse the exact geometry Tensura's own renderer would draw;
 *   - has a {@code textureFn} that picks the right per-citizen
 *     ResourceLocation from {@link GoblinVariantData};
 *   - has a {@code colorFn} that returns either the variant's stored
 *     ARGB tint (e.g. hair color) or -1 for no tint;
 *   - has a {@code shouldRender} predicate so the bandages layer can
 *     skip entirely when the flag is false.
 *
 * Render flow mirrors Tensura's own layer pattern (decompiled from
 * {@code GoblinLayer$Face.render} / {@code GoblinLayer$Hair.render}):
 *   prepareMobModel → copyPropertiesTo → resolve texture → setupAnim →
 *   renderToBuffer with {@code RenderType.entityTranslucent}.
 *
 * Overlay model class: {@link PlayerModel} with {@code slim=false}. This
 * matches the template Tensura uses to build every {@code GoblinLayer.*}
 * {@code LayerDefinition} ({@code PlayerModel.createMesh(deformation,
 * false)} in {@code GoblinLayer.<clinit>}). Six of the eight goblin
 * layers (Hair, HairBody, Clothing, Bandages, Top, Bottom) are
 * {@code RenderLayer<…, PlayerModel<T>>} in Tensura — the cubes for
 * shirts (Top) and pant-legs (Bottom) live on PlayerModel's overlay
 * parts (jacket, sleeves, left_pants, right_pants) which a plain
 * HumanoidModel cannot resolve. The earlier HumanoidModel wrap silently
 * dropped that geometry, so Top and Bottom were invisible on citizens
 * even with correct variant data. PlayerModel extends HumanoidModel,
 * so the Face and Head layers (which Tensura builds as
 * {@code HumanoidModel}-based) continue to render the same basic parts
 * unchanged.
 */
@OnlyIn(Dist.CLIENT)
public class GoblinOverlayLayer
        extends RenderLayer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    private final PlayerModel<AbstractEntityCitizen> overlayModel;
    private final Function<GoblinVariantData, ResourceLocation> textureFn;
    private final ToIntFunction<GoblinVariantData> colorFn;
    private final Predicate<GoblinVariantData> shouldRender;

    public GoblinOverlayLayer(
            RenderLayerParent<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> parent,
            ModelLayerLocation modelLayer,
            Function<GoblinVariantData, ResourceLocation> textureFn,
            ToIntFunction<GoblinVariantData> colorFn,
            Predicate<GoblinVariantData> shouldRender) {
        super(parent);
        // slim=false matches Tensura's GoblinLayer template — every Tensura
        // goblin layer is baked from PlayerModel.createMesh(deformation, false).
        this.overlayModel = new PlayerModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(modelLayer),
                false);
        this.textureFn = textureFn;
        this.colorFn = colorFn;
        this.shouldRender = shouldRender;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractEntityCitizen citizen, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        GoblinVariantData v = RaceTagClientStore.getGoblinVariant(citizen.getUUID());
        if (v == null) return;
        if (!shouldRender.test(v)) return;

        ResourceLocation texture = textureFn.apply(v);
        if (texture == null) return;

        int color = colorFn.applyAsInt(v);

        overlayModel.prepareMobModel(citizen, limbSwing, limbSwingAmount, partialTick);
        // PlayerModel.copyPropertiesTo(HumanoidModel) — PlayerModel IS-A
        // HumanoidModel, so the parent (also PlayerModel) propagates pose /
        // arm position / crouch / sneak flags through the HumanoidModel
        // surface and the overlay tracks the citizen's animation exactly.
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
