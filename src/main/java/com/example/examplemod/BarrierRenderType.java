package com.example.examplemod;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Render type for the barrier sphere. Identical to {@code entityTranslucent}
 * EXCEPT depth-write is OFF (COLOR_WRITE) and quads are distance-sorted on
 * upload.
 *
 * <p>Why: the sphere draws many overlapping translucent panels (two windings
 * each, plus the near + far hemisphere). With the default translucent type
 * (depth-write ON), coincident panels z-fight — sometimes both windings blend,
 * sometimes one is depth-culled — so a panel reads brighter or fainter than its
 * neighbour depending on view distance/angle (the "far panels look lower
 * capacity" artifact). With depth-write off + sort-on-upload, every panel
 * blends consistently regardless of order/distance.
 *
 * <p>Extends {@link RenderStateShard} only to reach its {@code protected
 * static} state constants; never instantiated.
 */
@OnlyIn(Dist.CLIENT)
public final class BarrierRenderType extends RenderStateShard {
    private BarrierRenderType() { super("noop", () -> {}, () -> {}); }

    public static RenderType barrier(ResourceLocation texture) {
        return RenderType.create(
                "tensura_minecolonies_barrier_field",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                false,   // affectsCrumbling
                true,    // sortOnUpload — distance-sort translucent quads
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)        // depth-write OFF
                        .setDepthTestState(LEQUAL_DEPTH_TEST)  // still occluded by opaque world
                        .createCompositeState(false));
    }
}
