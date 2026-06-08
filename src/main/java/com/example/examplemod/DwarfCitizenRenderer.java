package com.example.examplemod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.manasmods.tensura.client.entity.layer.DwarfLayer;
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
 * Render a DWARF-tagged MineColonies citizen as a Tensura dwarf — biped
 * path, structurally a twin of {@link GoblinCitizenRenderer}.
 *
 * <p>Base body uses {@link PlayerModel} baked from
 * {@link ModelLayers#PLAYER}, matching Tensura's own
 * {@code PlayerLikeModel<DwarfEntity>}.
 *
 * <p>Overlay layers — reusing Tensura's per-overlay
 * {@code ModelLayerLocation}s via {@link DwarfOverlayLayer}:
 * <ul>
 *   <li>Face (no tint)</li>
 *   <li>Hair (tinted by hairColor)</li>
 *   <li>HairBody (body-hair geometry, same hair texture / tint)</li>
 *   <li>FacialHair (male dwarves only — Tensura asset set is male-only;
 *       the texture resolver returns null for female, so the layer is
 *       inert in that case)</li>
 *   <li>Top (clothing — tinted by topColor)</li>
 *   <li>Bottom (clothing — tinted by bottomColor)</li>
 *   <li>Feet (clothing — tinted by feetColor)</li>
 * </ul>
 *
 * <p>Chest, RoyalGuardArmor, and ProfessionClothes overlays from
 * Tensura's own renderer are NOT included — Chest uses
 * {@code HumanoidChestModel} which is not PlayerModel-compatible
 * (different mesh structure), and RoyalGuardArmor / ProfessionClothes
 * are quest / job specific cosmetics that don't apply to colony citizens.
 *
 * <p><b>Wrinkle 2 — SCALE.</b> {@link #scale} multiplies by
 * {@code entity.getScale()}. The vanilla {@link net.minecraft.world.entity.ai.attributes.Attributes#SCALE}
 * attribute is set to 0.5 server-side on dwarf citizens during
 * materialisation ({@code applyRaceScaleAttribute} in ExampleMod), so
 * {@code citizen.getScale()} returns 0.5 here automatically. The
 * vanilla {@link net.minecraft.world.entity.LivingEntity#getDimensions}
 * also multiplies by SCALE so hitbox shrinks in lockstep.
 */
@OnlyIn(Dist.CLIENT)
public class DwarfCitizenRenderer
        extends LivingEntityRenderer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    /** Base scale factor — Tensura's {@code PlayerLikeRenderer.scale()} value.
     *  Multiplied by the per-citizen captured SCALE (from
     *  {@code DwarfVariantData.scale()}) so each citizen dwarf renders at
     *  the same size as the wild dwarf it was named from. Tensura's
     *  {@code DwarfEntity} randomises SCALE in {@code [0.7, 1.0]} (biased
     *  low), so the final per-citizen render scale is
     *  {@code (0.7..1.0) × 0.9375 ≈ (0.656..0.9375)}. */
    private static final float DWARF_BASE_SCALE = 0.9375f;
    private static final float SHADOW_RADIUS = 0.5f;

    public DwarfCitizenRenderer(EntityRendererProvider.Context context) {
        super(context,
                new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false),
                SHADOW_RADIUS);

        // Vanilla equipment layers — same as goblin, so citizens render
        // armor + held items.
        addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));

        // Per-citizen overlays, each Tensura ModelLayerLocation reused.
        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.Face.FACE,
                DwarfTextures::face,
                v -> -1,
                v -> DwarfTextures.face(v) != null));

        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.Hair.HAIR,
                DwarfTextures::hair,
                v -> DwarfTextures.tintOrWhite(v.hairColor()),
                v -> DwarfTextures.hair(v) != null));

        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.HairBody.HAIR_BODY,
                DwarfTextures::hair,
                v -> DwarfTextures.tintOrWhite(v.hairColor()),
                v -> DwarfTextures.hair(v) != null));

        // Facial hair uses Tensura's HumanoidModel-baked FACIAL_HAIR_LAYER,
        // NOT the PlayerModel mesh every other dwarf overlay uses. Wrapping
        // it in PlayerModel throws during bake and silently disables the
        // overlay, which is why citizens were losing their beards on the
        // subordinate→citizen swap. Dedicated layer handles the model-shape
        // mismatch; same texture / male-only gate as before.
        addLayer(new DwarfFacialHairLayer(this));

        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.Top.TOP,
                DwarfTextures::top,
                v -> DwarfTextures.tintOrWhite(v.topColor()),
                v -> DwarfTextures.top(v) != null));

        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.Bottom.BOTTOM,
                DwarfTextures::bottom,
                v -> DwarfTextures.tintOrWhite(v.bottomColor()),
                v -> DwarfTextures.bottom(v) != null));

        addLayer(new DwarfOverlayLayer(this,
                DwarfLayer.Feet.FEET,
                DwarfTextures::feet,
                v -> DwarfTextures.tintOrWhite(v.feetColor()),
                v -> DwarfTextures.feet(v) != null));
    }

    @Override
    public ResourceLocation getTextureLocation(AbstractEntityCitizen entity) {
        DwarfVariantData v = RaceTagClientStore.getDwarfVariant(entity.getUUID());
        if (v == null) v = DwarfVariantData.DEFAULT;
        ResourceLocation skin = DwarfTextures.skin(v);
        if (skin != null) return skin;
        // Defensive: if texture proxy isn't ready or default lookup somehow
        // returns null, fall back to vanilla steve skin so the entity renders
        // SOMETHING instead of being invisible (or fed a null path that
        // crashes deeper in the pipeline).
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    }

    @Override
    protected void scale(AbstractEntityCitizen entity, PoseStack poseStack, float partialTick) {
        // Per-citizen scale: read the captured wild-dwarf SCALE from the
        // variant data and multiply by Tensura's PlayerLikeRenderer base.
        // Falling back to the base scale when the variant isn't available
        // keeps the entity visible during the rare frame between citizen
        // spawn and race-tag client sync.
        DwarfVariantData v = RaceTagClientStore.getDwarfVariant(entity.getUUID());
        float captured = (v != null && v.scale() > 0.01f) ? v.scale() : 1.0f;
        float s = captured * DWARF_BASE_SCALE;
        poseStack.scale(s, s, s);
    }
}
