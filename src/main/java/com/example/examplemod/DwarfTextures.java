package com.example.examplemod;

import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.entity.human.DwarfEntity;
import io.github.manasmods.tensura.entity.variant.DwarfVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * Per-citizen dwarf texture resolution for {@link DwarfCitizenRenderer} and
 * {@link DwarfOverlayLayer}.
 *
 * <p>Tensura's {@code DwarfVariant.*} enums each carry their {@code texture}
 * field as {@code private final ResourceLocation} and only expose it
 * through static {@code getTextureLocation(DwarfEntity)} helpers — there's
 * no public per-instance accessor. The helpers read fields off a real
 * {@code DwarfEntity}, which we don't have at the citizen-render call site
 * (the entity is an {@code AbstractEntityCitizen}).
 *
 * <p>Solution: a single lazy "texture proxy" {@link DwarfEntity}, held
 * statically and reused. Each lookup sets the proxy's variant fields from
 * our {@link DwarfVariantData} and then calls Tensura's static
 * {@code getTextureLocation(proxy)} — Tensura computes the same texture
 * path it would compute for a real dwarf. The proxy is never added to the
 * world and never ticks; it's a passive data holder. This is structurally
 * the same "shadow entity for read-only state" trick the orc / lizardman
 * renderers use for rendering, just cheaper (one proxy total, not
 * per-citizen) because dwarf texture lookup doesn't depend on
 * animation / per-entity state.
 *
 * <p>For lookups where Tensura's helper uses a public per-instance
 * accessor ({@code Skin.getTextures()}, {@code Hair.getTextures()}) we
 * skip the proxy and call the enum directly — slightly faster, no
 * proxy mutation needed.
 */
@OnlyIn(Dist.CLIENT)
public final class DwarfTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Single proxy entity reused across all texture lookups.
     *  Built lazily on first access (when {@link Minecraft#level} is set). */
    private static DwarfEntity proxy;

    private DwarfTextures() {}

    public static ResourceLocation skin(DwarfVariantData v) {
        try {
            DwarfVariant.Skin skin = DwarfVariant.Skin.byId(v.skin());
            DwarfVariant.Gender gender = DwarfVariant.Gender.byId(v.gender());
            return skin.getTextures().get(gender);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation face(DwarfVariantData v) {
        DwarfEntity p = proxy();
        if (p == null) return null;
        try {
            applyVariantTo(p, v);
            return DwarfVariant.Face.getTextureLocation(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation hair(DwarfVariantData v) {
        try {
            DwarfVariant.Hair hair = DwarfVariant.Hair.byId(v.hair());
            DwarfVariant.Gender gender = DwarfVariant.Gender.byId(v.gender());
            return hair.getTextures().get(gender);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation facialHair(DwarfVariantData v) {
        // Facial hair only renders for male dwarves in Tensura's asset set.
        if (!isMale(v)) return null;
        DwarfEntity p = proxy();
        if (p == null) return null;
        try {
            applyVariantTo(p, v);
            return DwarfVariant.FacialHair.getTextureLocation(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation top(DwarfVariantData v) {
        DwarfEntity p = proxy();
        if (p == null) return null;
        try {
            applyVariantTo(p, v);
            return DwarfVariant.Top.getTextureLocation(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation bottom(DwarfVariantData v) {
        DwarfEntity p = proxy();
        if (p == null) return null;
        try {
            applyVariantTo(p, v);
            return DwarfVariant.Bottom.getTextureLocation(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ResourceLocation feet(DwarfVariantData v) {
        DwarfEntity p = proxy();
        if (p == null) return null;
        try {
            applyVariantTo(p, v);
            return DwarfVariant.Feet.getTextureLocation(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Convert ARGB to renderable tint; 0 means "no tint" → -1 (white). */
    public static int tintOrWhite(int argb) {
        return argb == 0 ? -1 : argb;
    }

    private static boolean isMale(DwarfVariantData v) {
        try {
            return DwarfVariant.Gender.byId(v.gender()) == DwarfVariant.Gender.MALE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyVariantTo(DwarfEntity p, DwarfVariantData v) {
        p.setGender(v.gender());
        p.setSkin(v.skin());
        p.setFace(v.face());
        p.setScar(v.scar());
        p.setHair(v.hair());
        p.setFacialHair(v.facialHair());
        p.setTop(v.top());
        p.setTopColor(v.topColor());
        p.setBottom(v.bottom());
        p.setBottomColor(v.bottomColor());
        p.setFeet(v.feet());
        p.setFeetColor(v.feetColor());
        p.setHairColor(v.hairColor());
    }

    /** Build (or return cached) proxy entity. Returns null if {@link Minecraft#level}
     *  isn't available yet (very early init). */
    private static DwarfEntity proxy() {
        if (proxy != null) return proxy;
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        ResourceLocation id = Races.idFor(Race.DWARF);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) {
            LOGGER.error("[TM] dwarf texture proxy: 'tensura:dwarf' not in entity registry");
            return null;
        }
        Entity created = type.create(level);
        if (!(created instanceof DwarfEntity d)) {
            LOGGER.error("[TM] dwarf texture proxy: factory returned {}",
                    created != null ? created.getClass().getName() : "null");
            return null;
        }
        proxy = d;
        LOGGER.info("[TM] dwarf texture proxy created (uuid={})", d.getUUID());
        return proxy;
    }

    /** Drop the proxy on logout — re-created against the next session's
     *  fresh resources / world. */
    public static void invalidate() {
        proxy = null;
    }
}
