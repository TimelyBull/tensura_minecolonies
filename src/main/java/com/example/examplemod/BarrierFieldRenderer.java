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
     *  0.65 × MAX. Tuned DOWN by request — the wall should read as a
     *  light shimmer, not a curtain. */
    private static final float ALPHA_MIN = 0.05f;
    private static final float ALPHA_MAX = 0.40f;
    /** Texture tiling: one texture repeat per this many blocks.
     *  0.5 = the lattice repeats every half block (1/8 of the former
     *  4-block tile) — a dense net across the whole wall. */
    private static final float TILE_SIZE = 0.5f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Per-tier wall tint (index = tier − 1): T1 blue, T2 green,
     *  T3 magenta, T4 gold — each barrier reads as its own color. */
    private static final float[][] TIER_TINTS = {
            {0.45f, 0.65f, 1.00f},
            {0.45f, 1.00f, 0.55f},
            {0.85f, 0.45f, 1.00f},
            {1.00f, 0.85f, 0.35f},
    };
    /** Tint applied to the quads currently being emitted (render is
     *  single-threaded; set once per render call). */
    private static float tintR = 1f, tintG = 1f, tintB = 1f;

    public BarrierFieldRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(BarrierBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!be.isWallVisible()) return; // player toggled the render off
        float fill = be.getFillRatio();
        if (fill <= 0f) return;
        float alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * fill;

        int tintIndex = Math.max(0, Math.min(TIER_TINTS.length - 1, be.getTier() - 1));
        tintR = TIER_TINTS[tintIndex][0];
        tintG = TIER_TINTS[tintIndex][1];
        tintB = TIER_TINTS[tintIndex][2];

        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(WALL_TEXTURE));
        Matrix4f pose = poseStack.last().pose();

        // The PoseStack origin is the barrier block's corner; we need world
        // coords to sample the per-column ground heightmap and to keep the
        // texture lattice aligned to the world (so strips of different height
        // don't stretch).
        int blockX = be.getBlockPos().getX();
        int blockY = be.getBlockPos().getY();
        int blockZ = be.getBlockPos().getZ();
        int fallbackBottomWorldY = blockY + (int) WALL_BOTTOM;
        be.maybeRefreshContour();

        float topY = WALL_TOP;

        // One square shell per STANDING layer (outer = top magicule slice).
        // Each wall side is split into per-block vertical strips whose BOTTOM
        // follows the world surface; the TOP stays flat at WALL_TOP.
        int standing = be.getStandingLayers();
        for (int layer = 0; layer < standing; layer++) {
            float r = (float) be.getLayerRadius(layer);
            float cx = 0.5f, cz = 0.5f;
            float x0 = cx - r, x1 = cx + r;
            float z0 = cz - r, z1 = cz + r;

            // North (z = z0) + South (z = z1): iterate X columns.
            renderColumnsAlongX(vc, pose, be, blockX, blockY, blockZ, x0, x1, z0,
                    topY, alpha, fallbackBottomWorldY, 0, 0, -1);
            renderColumnsAlongX(vc, pose, be, blockX, blockY, blockZ, x0, x1, z1,
                    topY, alpha, fallbackBottomWorldY, 0, 0, 1);
            // West (x = x0) + East (x = x1): iterate Z columns.
            renderColumnsAlongZ(vc, pose, be, blockX, blockY, blockZ, z0, z1, x0,
                    topY, alpha, fallbackBottomWorldY, -1, 0, 0);
            renderColumnsAlongZ(vc, pose, be, blockX, blockY, blockZ, z0, z1, x1,
                    topY, alpha, fallbackBottomWorldY, 1, 0, 0);

            // Roof — flat cap at the wall top (both windings).
            float uLen = (2 * r) / TILE_SIZE;
            roof(vc, pose, x0, topY, z0, x1, z1, uLen, alpha);
        }
    }

    /** Render one wall face running along X at a fixed local Z, split into
     *  per-block vertical strips whose bottom follows the world surface. */
    private void renderColumnsAlongX(VertexConsumer vc, Matrix4f pose, BarrierBlockEntity be,
            int blockX, int blockY, int blockZ,
            float x0, float x1, float localZ, float topY, float alpha,
            int fallbackBottomWorldY, float nx, float ny, float nz) {
        int wz = net.minecraft.util.Mth.floor(blockZ + localZ);
        int wxStart = net.minecraft.util.Mth.floor(blockX + x0);
        int wxEnd = net.minecraft.util.Mth.floor(blockX + x1 - 1.0e-4f);
        for (int wx = wxStart; wx <= wxEnd; wx++) {
            float la = Math.max(x0, wx - blockX);
            float lb = Math.min(x1, (wx + 1) - blockX);
            if (lb <= la) continue;
            int bottomWorldY = be.bottomWorldYAt(wx, wz, fallbackBottomWorldY);
            float bottomLocalY = bottomWorldY - blockY;
            if (bottomLocalY >= topY) continue; // surface above the wall top
            float u0 = (blockX + la) / TILE_SIZE;
            float u1 = (blockX + lb) / TILE_SIZE;
            float vBottom = bottomWorldY / TILE_SIZE;
            float vTop = (blockY + topY) / TILE_SIZE;
            verticalStrip(vc, pose, la, localZ, lb, localZ,
                    bottomLocalY, topY, u0, u1, vBottom, vTop, alpha, nx, ny, nz);
        }
    }

    /** Render one wall face running along Z at a fixed local X, split into
     *  per-block vertical strips whose bottom follows the world surface. */
    private void renderColumnsAlongZ(VertexConsumer vc, Matrix4f pose, BarrierBlockEntity be,
            int blockX, int blockY, int blockZ,
            float z0, float z1, float localX, float topY, float alpha,
            int fallbackBottomWorldY, float nx, float ny, float nz) {
        int wx = net.minecraft.util.Mth.floor(blockX + localX);
        int wzStart = net.minecraft.util.Mth.floor(blockZ + z0);
        int wzEnd = net.minecraft.util.Mth.floor(blockZ + z1 - 1.0e-4f);
        for (int wz = wzStart; wz <= wzEnd; wz++) {
            float la = Math.max(z0, wz - blockZ);
            float lb = Math.min(z1, (wz + 1) - blockZ);
            if (lb <= la) continue;
            int bottomWorldY = be.bottomWorldYAt(wx, wz, fallbackBottomWorldY);
            float bottomLocalY = bottomWorldY - blockY;
            if (bottomLocalY >= topY) continue;
            float u0 = (blockZ + la) / TILE_SIZE;
            float u1 = (blockZ + lb) / TILE_SIZE;
            float vBottom = bottomWorldY / TILE_SIZE;
            float vTop = (blockY + topY) / TILE_SIZE;
            verticalStrip(vc, pose, localX, la, localX, lb,
                    bottomLocalY, topY, u0, u1, vBottom, vTop, alpha, nx, ny, nz);
        }
    }

    /** A vertical strip quad from (ax,az)→(bx,bz) spanning bottomY→topY with
     *  explicit world-derived UVs, emitted in both windings (visible from
     *  either side). */
    private void verticalStrip(VertexConsumer vc, Matrix4f pose,
            float ax, float az, float bx, float bz,
            float bottomY, float topY,
            float u0, float u1, float vBottom, float vTop,
            float alpha, float nx, float ny, float nz) {
        emit(vc, pose, ax, bottomY, az, u0, vBottom, alpha, nx, ny, nz);
        emit(vc, pose, bx, bottomY, bz, u1, vBottom, alpha, nx, ny, nz);
        emit(vc, pose, bx, topY,    bz, u1, vTop,    alpha, nx, ny, nz);
        emit(vc, pose, ax, topY,    az, u0, vTop,    alpha, nx, ny, nz);
        emit(vc, pose, ax, topY,    az, u0, vTop,    alpha, -nx, -ny, -nz);
        emit(vc, pose, bx, topY,    bz, u1, vTop,    alpha, -nx, -ny, -nz);
        emit(vc, pose, bx, bottomY, bz, u1, vBottom, alpha, -nx, -ny, -nz);
        emit(vc, pose, ax, bottomY, az, u0, vBottom, alpha, -nx, -ny, -nz);
    }

    private void emit(VertexConsumer vc, Matrix4f pose, float x, float y, float z,
            float u, float v, float alpha, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(tintR, tintG, tintB, alpha)
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
    }

    /** Horizontal roof quad at height {@code y}, both windings. */
    private static void roof(VertexConsumer vc, Matrix4f pose,
                             float x0, float y, float z0, float x1, float z1,
                             float uvLen, float alpha) {
        quad(vc, pose,
                x0, y, z0,  x1, y, z0,  x1, y, z1,  x0, y, z1,
                uvLen, uvLen, alpha, 0, 1, 0);
        quad(vc, pose,
                x0, y, z1,  x1, y, z1,  x1, y, z0,  x0, y, z0,
                uvLen, uvLen, alpha, 0, -1, 0);
    }

    private static void quad(VertexConsumer vc, Matrix4f pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float uLen, float vLen, float alpha,
                             float nx, float ny, float nz) {
        vc.addVertex(pose, x1, y1, z1).setColor(tintR, tintG, tintB, alpha)
                .setUv(0, vLen).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x2, y2, z2).setColor(tintR, tintG, tintB, alpha)
                .setUv(uLen, vLen).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x3, y3, z3).setColor(tintR, tintG, tintB, alpha)
                .setUv(uLen, 0).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        vc.addVertex(pose, x4, y4, z4).setColor(tintR, tintG, tintB, alpha)
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
        double r = be.getEffectiveRadius() + 1; // outermost STANDING ring
        // The wall bottom follows terrain and can drop far below the block
        // (cliffs/valleys), so extend the cull box well downward — otherwise
        // the wall is culled when the block scrolls off the top of view.
        return new net.minecraft.world.phys.AABB(be.getBlockPos())
                .inflate(r, 16, r)
                .expandTowards(0, -128, 0);
    }
}
