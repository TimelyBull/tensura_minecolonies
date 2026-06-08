package com.example.examplemod;

import io.github.manasmods.tensura.entity.variant.GoblinVariant;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Resolves per-variant goblin texture paths from {@link GoblinVariantData}
 * without needing a live {@code GoblinEntity}.
 *
 * Tensura's own {@code GoblinVariant.*.getTextureLocation(GoblinEntity)}
 * methods all require a live goblin to read its synced data — which we
 * don't have on the citizen body. We replicate the path-building logic
 * by calling each enum's value-resolution helpers (every variant enum
 * exposes a {@code byId(int)} static returning the enum instance, plus
 * either an instance {@code getTextureLocation()} or a {@code getTextures()}
 * EnumMap by Gender). This is the same data Tensura's own layers consult
 * — we just feed it the IDs we carried over from the server instead of
 * reading them off a goblin entity.
 *
 * Texture-path formulas (decompiled from {@code GoblinVariant.*} static init):
 * <pre>
 *   Skin     : textures/entity/goblin/{gender}/skin/{skinPrefix}{gender}.png
 *   Hair     : textures/entity/goblin/{gender}/hair/{hairPrefix}{gender}.png
 *   Face     : per-enum constant — instance.getTextureLocation()
 *   Clothing : textures/entity/goblin/{gender}/clothing/loin_{gender}.png  (unisex has none)
 *   Bandages : textures/entity/goblin/unisex/bandages.png  (literal)
 * </pre>
 *
 * The {@code GoblinVariant.<X>.getTextures()} EnumMap accessor returns the
 * exact ResourceLocations Tensura built at static init, so we use those
 * directly for Skin and Hair instead of re-formatting strings — that
 * avoids drift if Tensura ever moves a texture file.
 */
@OnlyIn(Dist.CLIENT)
public final class GoblinTextures {

    private static final ResourceLocation BANDAGES = ResourceLocation.fromNamespaceAndPath(
            "tensura", "textures/entity/goblin/unisex/bandages.png");

    private GoblinTextures() {}

    /** Base body texture (replaces the F3 hardcoded dark_male). */
    public static ResourceLocation skin(GoblinVariantData v) {
        GoblinVariant.Skin skin = GoblinVariant.Skin.byId(v.skin());
        GoblinVariant.Gender gender = GoblinVariant.Gender.byId(v.gender());
        ResourceLocation rl = skin.getTextures().get(gender);
        return rl != null ? rl : BANDAGES; // unreachable in practice — every (skin,gender) pair populated
    }

    /** Face overlay. Face enum stores a complete subpath per value and
     *  exposes an instance {@code getTextureLocation()} that doesn't need
     *  a goblin entity. */
    public static ResourceLocation face(GoblinVariantData v) {
        return GoblinVariant.Face.byId(v.face()).getTextureLocation();
    }

    /** Hair overlay (also used by the HairBody layer — same texture, the
     *  HairBody layer just uses a different model geometry). */
    public static ResourceLocation hair(GoblinVariantData v) {
        GoblinVariant.Hair hair = GoblinVariant.Hair.byId(v.hair());
        GoblinVariant.Gender gender = GoblinVariant.Gender.byId(v.gender());
        return hair.getTextures().get(gender);
    }

    /** Loincloth — the base "Clothing" layer. Returns null for unisex,
     *  matching Tensura's {@code CLOTHING_TEXTURES} EnumMap which only
     *  has male and female entries (the unisex gender has no loincloth
     *  asset). Callers must skip rendering when null. */
    public static ResourceLocation clothing(GoblinVariantData v) {
        GoblinVariant.Gender gender = GoblinVariant.Gender.byId(v.gender());
        if (gender == GoblinVariant.Gender.OTHER) return null;
        return ResourceLocation.fromNamespaceAndPath(
                "tensura",
                "textures/entity/goblin/" + gender.getLocation()
                        + "/clothing/loin_" + gender.getLocation() + ".png");
    }

    public static ResourceLocation bandages() {
        return BANDAGES;
    }

    /** Hobgoblin head accessory (bandana / bandana_full). Returns null for
     *  the "-1" sentinel id meaning no head accessory — matches Tensura's
     *  own {@code GoblinLayer$Head} which bails on {@code DATA_HEAD == -1}. */
    public static ResourceLocation head(GoblinVariantData v) {
        if (v.head() < 0) return null;
        try {
            return ResourceLocation.fromNamespaceAndPath(
                    "tensura",
                    "textures/entity/goblin/unisex/head/head_"
                            + GoblinVariant.Head.byId(v.head()).getLocation()
                            + ".png");
        } catch (RuntimeException e) {
            return null; // unknown id — skip the layer rather than crash
        }
    }

    /** Hobgoblin chest clothing (t-shirt / vest). */
    public static ResourceLocation top(GoblinVariantData v) {
        if (v.top() < 0) return null;
        try {
            return ResourceLocation.fromNamespaceAndPath(
                    "tensura",
                    "textures/entity/goblin/unisex/top/chest_"
                            + GoblinVariant.Top.byId(v.top()).getLocation()
                            + ".png");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Hobgoblin leg clothing (shorts / pants). */
    public static ResourceLocation bottom(GoblinVariantData v) {
        if (v.bottom() < 0) return null;
        try {
            return ResourceLocation.fromNamespaceAndPath(
                    "tensura",
                    "textures/entity/goblin/unisex/bottom/legs_"
                            + GoblinVariant.Bottom.byId(v.bottom()).getLocation()
                            + ".png");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tensura's layer rule: if the per-variant color int is non-zero use
     *  it as the ARGB tint, otherwise pass -1 (white / no tint) to
     *  {@code renderToBuffer}. */
    public static int tintOrWhite(int color) {
        return color != 0 ? color : -1;
    }
}
