package com.example.examplemod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * The barrier's wall visual — a faceted SPHERE per concentric layer, divided
 * into {@link BarrierBlockEntity#SECTION_COUNT} quad-sphere patches that match
 * the collision/state sectioning exactly. Each section is drawn at an opacity
 * set by its health stage (full / fade1 / fade2 / fade3); a BROKEN section is
 * skipped entirely — that empty patch IS the visible hole and the collision
 * gap. The sphere clips into terrain (the buried lower hemisphere is intended).
 *
 * <ul>
 *   <li><b>Tessellation:</b> 6 cube faces × 2×2 cells = 24 logical sections,
 *       each rendered as a {@link #SUBDIV}×{@link #SUBDIV} grid of quads
 *       projected onto the sphere for roundness.</li>
 *   <li><b>Per-tier tint</b> (blue/green/magenta/gold) so each barrier reads as
 *       its own colour; both windings emitted so it's visible inside and out.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class BarrierFieldRenderer implements BlockEntityRenderer<BarrierBlockEntity> {

    private static final ResourceLocation WALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExampleMod.MODID, "textures/block/barrier_field.png");
    /** Depth-write-OFF translucent type so overlapping panels blend evenly
     *  (see {@link BarrierRenderType}). Built once. */
    private static final net.minecraft.client.renderer.RenderType WALL_RENDER_TYPE =
            BarrierRenderType.barrier(WALL_TEXTURE);

    /** Base (full-health) vertex alpha. The texture itself is ~65% alpha, so
     *  on-screen opacity tops out around 0.65 × this. */
    private static final float ALPHA_FULL = 0.40f;
    /** Per-stage opacity multipliers: 0 full, 1 fade1, 2 fade2, 3 fade3.
     *  (Stage 4 = broken is never drawn.) */
    private static final float[] STAGE_ALPHA = { 1.00f, 0.60f, 0.35f, 0.18f };

    /** Texture tiling: one repeat per this many blocks. */
    private static final float TILE_SIZE = 0.5f;
    /** Sub-grid per logical section, for sphere roundness (state stays 24). */
    private static final int SUBDIV = 3;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Per-tier wall tint (index = tier − 1): T1 blue, T2 green, T3 magenta,
     *  T4 gold. */
    private static final float[][] TIER_TINTS = {
            {0.45f, 0.65f, 1.00f},
            {0.45f, 1.00f, 0.55f},
            {0.85f, 0.45f, 1.00f},
            {1.00f, 0.85f, 0.35f},
    };
    private static float tintR = 1f, tintG = 1f, tintB = 1f;

    public BarrierFieldRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(BarrierBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!be.isWallVisible()) return;          // player toggled the render off
        if (be.getFillRatio() <= 0f) return;       // whole barrier down (pool 0)

        int tintIndex = Math.max(0, Math.min(TIER_TINTS.length - 1, be.getTier() - 1));
        tintR = TIER_TINTS[tintIndex][0];
        tintG = TIER_TINTS[tintIndex][1];
        tintB = TIER_TINTS[tintIndex][2];

        VertexConsumer vc = bufferSource.getBuffer(WALL_RENDER_TYPE);
        Matrix4f pose = poseStack.last().pose();

        // PoseStack origin = block corner; the sphere centres on the block.
        final float cx = 0.5f, cy = 0.5f, cz = 0.5f;

        // One full sphere per CONFIGURED layer (layers don't shed from drain;
        // only individual sections break, and only the whole barrier falls at
        // pool 0).
        for (int layer = 0; layer < be.getActiveLayers(); layer++) {
            float r = (float) be.getLayerRadius(layer);

            for (int face = 0; face < BarrierBlockEntity.SECTION_FACES; face++) {
                for (int cu = 0; cu < BarrierBlockEntity.SECTION_GRID; cu++) {
                    for (int cv = 0; cv < BarrierBlockEntity.SECTION_GRID; cv++) {
                        int section = BarrierBlockEntity.cellSection(face, cu, cv);
                        int stage = be.getSectionStage(layer, section);
                        if (stage >= 4) continue;          // broken → the hole
                        float alpha = ALPHA_FULL * STAGE_ALPHA[Math.max(0, Math.min(3, stage))];

                        // Cell tangent-param range within [-1,1].
                        float a0 = cu == 0 ? -1f : 0f, a1 = cu == 0 ? 0f : 1f;
                        float b0 = cv == 0 ? -1f : 0f, b1 = cv == 0 ? 0f : 1f;
                        renderPatch(vc, pose, face, r, cx, cy, cz, a0, a1, b0, b1, alpha);
                    }
                }
            }
        }
    }

    /** Render one logical section (cube-face cell) as a SUBDIV×SUBDIV grid of
     *  sphere-projected quads, both windings. */
    private static void renderPatch(VertexConsumer vc, Matrix4f pose, int face, float r,
                                    float cx, float cy, float cz,
                                    float a0, float a1, float b0, float b1, float alpha) {
        for (int i = 0; i < SUBDIV; i++) {
            float sa0 = a0 + (a1 - a0) * (i / (float) SUBDIV);
            float sa1 = a0 + (a1 - a0) * ((i + 1) / (float) SUBDIV);
            for (int j = 0; j < SUBDIV; j++) {
                float sb0 = b0 + (b1 - b0) * (j / (float) SUBDIV);
                float sb1 = b0 + (b1 - b0) * ((j + 1) / (float) SUBDIV);

                Vec3 p00 = onSphere(face, sa0, sb0, r, cx, cy, cz);
                Vec3 p10 = onSphere(face, sa1, sb0, r, cx, cy, cz);
                Vec3 p11 = onSphere(face, sa1, sb1, r, cx, cy, cz);
                Vec3 p01 = onSphere(face, sa0, sb1, r, cx, cy, cz);

                // UVs from tangent params so the lattice doesn't stretch.
                float u0 = sa0 * r / TILE_SIZE, u1 = sa1 * r / TILE_SIZE;
                float v0 = sb0 * r / TILE_SIZE, v1 = sb1 * r / TILE_SIZE;

                // Outward normal ≈ direction to the quad centre.
                Vec3 nrm = BarrierBlockEntity.cubePoint(face, (sa0 + sa1) * 0.5, (sb0 + sb1) * 0.5)
                        .normalize();
                float nx = (float) nrm.x, ny = (float) nrm.y, nz = (float) nrm.z;

                // Front winding.
                emit(vc, pose, p00, u0, v0, alpha, nx, ny, nz);
                emit(vc, pose, p10, u1, v0, alpha, nx, ny, nz);
                emit(vc, pose, p11, u1, v1, alpha, nx, ny, nz);
                emit(vc, pose, p01, u0, v1, alpha, nx, ny, nz);
                // Back winding (visible from inside).
                emit(vc, pose, p01, u0, v1, alpha, -nx, -ny, -nz);
                emit(vc, pose, p11, u1, v1, alpha, -nx, -ny, -nz);
                emit(vc, pose, p10, u1, v0, alpha, -nx, -ny, -nz);
                emit(vc, pose, p00, u0, v0, alpha, -nx, -ny, -nz);
            }
        }
    }

    /** Project a cube-face param point onto the sphere and offset by the block
     *  centre (local PoseStack coords). */
    private static Vec3 onSphere(int face, float a, float b, float r,
                                 float cx, float cy, float cz) {
        Vec3 dir = BarrierBlockEntity.cubePoint(face, a, b).normalize();
        return new Vec3(cx + dir.x * r, cy + dir.y * r, cz + dir.z * r);
    }

    private static void emit(VertexConsumer vc, Matrix4f pose, Vec3 p,
                             float u, float v, float alpha, float nx, float ny, float nz) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(tintR, tintG, tintB, alpha)
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(nx, ny, nz);
    }

    @Override
    public boolean shouldRenderOffScreen(BarrierBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256; // tier-4 spheres reach ~70 blocks out
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(BarrierBlockEntity be) {
        double r = be.getEffectiveRadius() + 2; // outermost sphere
        return new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(r, r, r);
    }
}
