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

import java.util.Set;

/**
 * Dwarf citizen profession-clothes overlay — the colony-side equivalent of
 * Tensura's {@code ProfessionClothesLayer}, so a dwarf citizen that has a
 * villager profession (Feature C) looks the same as its subordinate form.
 *
 * <p>Mirrors {@link DwarfFacialHairLayer}: Tensura's profession clothes are
 * baked from a {@code HumanoidModel} layer ({@code ProfessionClothesLayer.CLOTHES})
 * rather than a {@code PlayerModel}, so we wrap the baked part in a vanilla
 * {@link HumanoidModel} (the same shape Tensura uses) and render it over the
 * citizen with {@code RenderType.entityTranslucent}.
 *
 * <p>The profession is read from the citizen's {@link RaceTag} (synced as a
 * registry-name string, e.g. {@code "minecraft:butcher"}). The texture path
 * matches Tensura's: {@code tensura:textures/entity/dwarf/profession/{name}.png}.
 * Only professions Tensura actually ships a dwarf texture for render clothes —
 * the rest (and a jobless citizen) render nothing, matching Tensura's own
 * map (which falls back to EMPTY).
 */
@OnlyIn(Dist.CLIENT)
public class DwarfProfessionLayer
        extends RenderLayer<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Profession names Tensura ships a dwarf clothes texture for (the keys of
     *  Tensura's PROFESSION_TEXTURES map). A profession outside this set — or a
     *  jobless citizen — renders no clothes, matching Tensura's EMPTY fallback. */
    private static final Set<String> DWARF_PROFESSION_TEXTURES = Set.of(
            "alchemist", "armorer", "battlewill_trainer", "butcher", "cartographer",
            "farmer", "fisherman", "fletcher", "guard", "leatherworker", "librarian",
            "lumberjack", "magic_trainer", "mason", "merchant", "miner", "shepherd",
            "toolsmith", "weaponsmith");

    private final HumanoidModel<AbstractEntityCitizen> overlayModel;

    public DwarfProfessionLayer(
            RenderLayerParent<AbstractEntityCitizen, PlayerModel<AbstractEntityCitizen>> parent) {
        super(parent);
        HumanoidModel<AbstractEntityCitizen> baked;
        try {
            baked = new HumanoidModel<>(
                    Minecraft.getInstance().getEntityModels().bakeLayer(
                            io.github.manasmods.tensura.client.entity.layer.ProfessionClothesLayer.CLOTHES));
        } catch (Throwable t) {
            LOGGER.warn("[TM] dwarf profession overlay failed to bake — profession clothes won't render", t);
            baked = null;
        }
        this.overlayModel = baked;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractEntityCitizen citizen, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (overlayModel == null) return;

        String profId = RaceTagClientStore.getProfession(citizen.getUUID());
        if (profId == null || profId.isEmpty()) return;
        ResourceLocation profKey = ResourceLocation.tryParse(profId);
        if (profKey == null) return;
        String name = profKey.getPath();
        if (!DWARF_PROFESSION_TEXTURES.contains(name)) return;

        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                "tensura", "textures/entity/dwarf/profession/" + name + ".png");

        overlayModel.prepareMobModel(citizen, limbSwing, limbSwingAmount, partialTick);
        // Parent is a PlayerModel; copyPropertiesTo a HumanoidModel is fine
        // (PlayerModel extends HumanoidModel) — same copy Tensura's own layer does.
        getParentModel().copyPropertiesTo(overlayModel);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(texture));
        overlayModel.setupAnim(citizen, limbSwing, limbSwingAmount,
                ageInTicks, netHeadYaw, headPitch);

        poseStack.pushPose();
        overlayModel.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, -1);
        poseStack.popPose();
    }
}
