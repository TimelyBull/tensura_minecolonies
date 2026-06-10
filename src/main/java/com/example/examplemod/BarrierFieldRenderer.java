package com.example.examplemod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * The barrier's wall visual — a lightly-visible SQUARE wall (four
 * translucent textured quads) around the barrier block, replacing the
 * v1 particle shell.
 *
 * <ul>
 *   <li><b>Footprint matches the field:</b> walls sit exactly at the
 *       {@link BarrierBlockEntity#BARRIER_RADIUS} half-extent square the
 *       pushback/drain field and the hostile-spawn prevention use.</li>
 *   <li><b>Opacity = stored magicule:</b> vertex alpha scales with
 *       {@link BarrierBlockEntity#getFillRatio()} (synced from the
 *       server) — full tank ≈ clearly visible, near-empty ≈ almost
 *       invisible. Nothing renders at exactly 0.</li>
 *   <li>Rendered fullbright through {@code RenderType.entityTranslucent}
 *       with the player-supplied energy-field texture, tiled per block.
 *       Quads are emitted in both windings so the wall is visible from
 *       inside and outside.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class BarrierFieldRenderer implements BlockEntityRenderer<BarrierBlockEntity> {

    private static final ResourceLocation WALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExampleMod.MODID, "textures/block/barrier_field.png");

    /** Wall vertical extent relative to the barrier block's Y. */
    private static final float WALL_BOTTOM = -4.0f;
    private static final float WALL_TOP    = 12.0f;
    /** Vertex alpha range across 0..100% fill. The texture itself is
     *  ~65% alpha, so the effective on-screen opacity tops out around
     *  0.65 × MAX. */
    private static final float ALPHA_MIN = 0.10f;
    private static final float ALPHA_MAX = 0.85f;
    /** Texture tiling: one texture repeat per this many blocks. */
    private static final float TILE_SIZE = 4.0f;
    private static final int FULL_BRIGHT = 0xF000F0;

    public BarrierFieldRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(BarrierBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!be.isWallVisible()) return; // player toggled the render off
        float fill = be.getFillRatio();
        if (fill <= 0f) return;
        float alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * fill;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(WALL_TEXTURE));
        Matrix4f pose = poseStack.last().pose();
        float y0 = WALL_BOTTOM, y1 = WALL_TOP;
        float vLen = (y1 - y0) / TILE_SIZE;        // vertical repeats

        // One square shell per ACTIVE layer — layer 0 at the tier radius,
        // each further ring +LAYER_SPACING out (concentric).
        for (int layer = 0; layer < be.getActiveLayers(); layer++) {
            float r = (float) be.getLayerRadius(layer);
            // PoseStack origin = the block's corner; walls centre on the block.
            float cx = 0.5f, cz = 0.5f;
            float x0 = cx - r, x1 = cx + r;
            float z0 = cz - r, z1 = cz + r;
            float uLen = (2 * r) / TILE_SIZE;      // horizontal repeats per wall

            // North wall (z = z0), South (z = z1), West (x = x0), East (x = x1).
            wall(vc, pose, x0, y0, z0, x1, y1, z0, uLen, vLen, alpha, 0, 0, -1);
            wall(vc, pose, x0, y0, z1, x1, y1, z1, uLen, vLen, alpha, 0, 0, 1);
            wallX(vc, pose, x0, y0, z0, x0, y1, z1, uLen, vLen, alpha, -1, 0, 0);
            wallX(vc, pose, x1, y0, z0, x1, y1, z1, uLen, vLen, alpha, 1, 0, 0);
        }
    }

    /** A wall quad spanning X at fixed Z, emitted in both windings so it
     *  renders from either side. */
    private static void wall(VertexConsumer vc, Matrix4f pose,
                             float xa, float yb, float z, float xb, float yt, float z2,
                             float uLen, float vLen, float alpha,
                             float nx, float ny, float nz) {
        quad(vc, pose,
                xa, yb, z,  xb, yb, z,  xb, yt, z,  xa, yt, z,
                uLen, vLen, alpha, nx, ny, nz);
        quad(vc, pose,
                xa, yt, z,  xb, yt, z,  xb, yb, z,  xa, yb, z,
                uLen, vLen, alpha, -nx, -ny, -nz);
    }

    /** A wall quad spanning Z at fixed X, both windings. */
    private static void wallX(VertexConsumer vc, Matrix4f pose,
                              float x, float yb, float za, float x2, float yt, float zb,
                              float uLen, float vLen, float alpha,
                              float nx, float ny, float nz) {
        quad(vc, pose,
                x, yb, za,  x, yb, zb,  x, yt, zb,  x, yt, za,
                uLen, vLen, alpha, nx, ny, nz);
        quad(vc, pose,
                x, yt, za,  x, yt, zb,  x, yb, zb,  x, yb, za,
                uLen, vLen, alpha, -nx, -ny, -nz);
    }

    private static void quad(VertexConsumer vc, Matrix4f pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float uLen, float vLen, float alpha,
                             float nx, float ny, float nz) {
        vc.addVertex(pose, x1, y1, z1).setColor(1f, 1f, 1f, alpha)
                .setUv(0, vLen).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x2, y2, z2).setColor(1f, 1f, 1f, alpha)
                .setUv(uLen, vLen).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x3, y3, z3).setColor(1f, 1f, 1f, alpha)
                .setUv(uLen, 0).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x4, y4, z4).setColor(1f, 1f, 1f, alpha)
                .setUv(0, 0).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
    }

    /** The walls extend ~16 blocks past the block — keep rendering while
     *  the block itself is off-screen, and from further away. */
    @Override
    public boolean shouldRenderOffScreen(BarrierBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 192; // tier-4 walls reach 60 blocks out
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(BarrierBlockEntity be) {
        double r = be.getEffectiveRadius() + 1; // outermost active ring
        return new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(r, 16, r);
    }
}
