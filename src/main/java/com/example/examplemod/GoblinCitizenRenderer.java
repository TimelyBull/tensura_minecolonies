package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.manasmods.tensura.client.entity.layer.GoblinLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Stage F3/F4 — render a goblin-tagged MineColonies citizen as a goblin
 * with its own per-instance appearance.
 *
 * Base body uses {@link PlayerModel} (matching Tensura's own
 * {@code PlayerLikeModel<GoblinEntity>}) baked from {@link ModelLayers#PLAYER}.
 * That makes layer typing consistent: every overlay we stack on top is a
 * {@code RenderLayer<…, PlayerModel<…>>}, and PlayerModel's
 * {@code copyPropertiesTo(HumanoidModel)} propagates pose to overlays
 * uniformly.
 *
 * Texture selection (F4) reads the per-citizen {@link GoblinVariantData}
 * from {@link RaceTagClientStore} and routes through {@link GoblinTextures}.
 * If the tag is somehow missing at render time (race condition between
 * payload and first draw — shouldn't happen given the eager StartTracking
 * unicast, but defensible), we fall back to {@link GoblinVariantData#DEFAULT}
 * rather than crashing.
 *
 * Overlay layers added (BASE GOBLIN ONLY — Top/Bottom/Head are
 * {@code isHobgoblin()}-gated in Tensura and deferred to Stage G):
 * <ul>
 *   <li>{@code goblin_face}    — Face texture, no tint</li>
 *   <li>{@code goblin_hair}    — Hair texture, tinted by hairColor</li>
 *   <li>{@code goblin_hair_body} — same Hair texture, body-hair geometry, tinted</li>
 *   <li>{@code goblin_clothing} — loincloth (skips for unisex), tinted by bottomColor</li>
 *   <li>{@code goblin_bandages} — conditional on the bandages flag</li>
 * </ul>
 *
 * Scale 0.7× matches Tensura's {@code GoblinRenderer.scale()} for base
 * (non-hobgoblin) goblins.
 */
@OnlyIn(Dist.CLIENT)
public class GoblinCitizenRenderer
        extends LivingEntityRenderer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    // Tensura's GoblinRenderer.scale() uses 0.9375f for hobgoblin and
    // 0.7f for base goblin. Switched per-render via isHobgoblin().
    private static final float BASE_GOBLIN_SCALE = 0.7f;
    private static final float HOBGOBLIN_SCALE = 0.9375f;
    private static final float SHADOW_RADIUS = 0.35f;

    public GoblinCitizenRenderer(EntityRendererProvider.Context context) {
        super(context,
                // slim=false matches Tensura's PlayerLikeModel construction
                new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false),
                SHADOW_RADIUS);

        // Vanilla equipment layers — added in the same order Tensura's
        // GoblinRenderer adds them. Without these, armor and held items
        // are invisible on the citizen even when the entity has them set
        // on its equipment slots. Inner/outer armor models bake from
        // ModelLayers.PLAYER_INNER_ARMOR / PLAYER_OUTER_ARMOR (the same
        // layer locations vanilla and Tensura use for player-shaped mobs).
        addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));

        // Overlay layers — same ModelLayerLocation constants Tensura's own
        // GoblinRenderer feeds into its GoblinLayer.* sub-classes. Each
        // layer's texture function takes the decoded variant and returns
        // the right ResourceLocation; color function returns -1 (white)
        // or the variant's stored tint.
        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Face.FACE,
                GoblinTextures::face,
                v -> -1,
                v -> true));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Hair.HAIR,
                GoblinTextures::hair,
                v -> GoblinTextures.tintOrWhite(v.hairColor()),
                v -> true));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.HairBody.HAIR_BODY,
                GoblinTextures::hair,
                v -> GoblinTextures.tintOrWhite(v.hairColor()),
                v -> true));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Clothing.CLOTHING,
                GoblinTextures::clothing,
                v -> GoblinTextures.tintOrWhite(v.bottomColor()),
                v -> GoblinTextures.clothing(v) != null));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Bandages.BANDAGES,
                v -> GoblinTextures.bandages(),
                v -> -1,
                GoblinVariantData::bandages));

        // Hobgoblin-only overlays — Tensura's own Top/Bottom/Head layers
        // bail immediately when !isHobgoblin(); we replicate that gate
        // in the should-render predicate so base goblins skip them.
        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Head.HEAD,
                GoblinTextures::head,
                v -> GoblinTextures.tintOrWhite(v.headColor()),
                v -> v.isHobgoblin() && GoblinTextures.head(v) != null));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Top.TOP,
                GoblinTextures::top,
                v -> GoblinTextures.tintOrWhite(v.topColor()),
                v -> v.isHobgoblin() && GoblinTextures.top(v) != null));

        addLayer(new GoblinOverlayLayer(this,
                GoblinLayer.Bottom.BOTTOM,
                GoblinTextures::bottom,
                v -> GoblinTextures.tintOrWhite(v.bottomColor()),
                v -> v.isHobgoblin() && GoblinTextures.bottom(v) != null));
    }

    @Override
    public ResourceLocation getTextureLocation(AbstractEntityCitizen entity) {
        GoblinVariantData v = RaceTagClientStore.getGoblinVariant(entity.getUUID());
        if (v == null) v = GoblinVariantData.DEFAULT;
        return GoblinTextures.skin(v);
    }

    @Override
    protected void scale(AbstractEntityCitizen entity, PoseStack poseStack, float partialTick) {
        GoblinVariantData v = RaceTagClientStore.getGoblinVariant(entity.getUUID());
        float s = (v != null && v.isHobgoblin()) ? HOBGOBLIN_SCALE : BASE_GOBLIN_SCALE;
        poseStack.scale(s, s, s);
    }
}
