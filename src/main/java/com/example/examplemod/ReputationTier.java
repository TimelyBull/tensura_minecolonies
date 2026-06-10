package com.example.examplemod;

import net.minecraft.ChatFormatting;

/**
 * Derived reputation tiers — the queryable representation downstream
 * features gate on ("below WARY → crime", "low tier → raid risk").
 *
 * The numeric reputation value (0–100, see {@link ReputationManager})
 * is the stored truth; tiers are DERIVED from it here and nowhere else.
 * All band thresholds live in this one enum — if the bands ever change,
 * this is the only file to touch.
 *
 * Ordered ascending so {@code compareTo} expresses "at least as good as":
 * {@code tier.compareTo(ReputationTier.WARY) < 0} means "below WARY".
 *
 * Bands (locked in the v1 design confirmation):
 * <pre>
 *    0– 9   HOSTILE
 *   10–19   PASSIVEAGGRESSIVE
 *   20–39   WARY
 *   40–59   NEUTRAL   (default 50 lands here)
 *   60–79   LOYAL
 *   80–100  DEVOTED
 * </pre>
 */
public enum ReputationTier {

    HOSTILE(0, "Hostile", ChatFormatting.DARK_RED),
    PASSIVEAGGRESSIVE(10, "Passive-Aggressive", ChatFormatting.RED),
    WARY(20, "Wary", ChatFormatting.GOLD),
    NEUTRAL(40, "Neutral", ChatFormatting.GRAY),
    LOYAL(60, "Loyal", ChatFormatting.GREEN),
    DEVOTED(80, "Devoted", ChatFormatting.AQUA);

    /** Inclusive lower bound of this tier's band. The upper bound is the
     *  next tier's lower bound (DEVOTED runs to the 100 cap). */
    private final int minInclusive;
    /** Player-facing tier name (roster header, /reputation output). */
    private final String displayName;
    /** Display colour — mirrors the envoy nameplate-colour convention of
     *  "tell the standing apart at a glance". */
    private final ChatFormatting color;

    ReputationTier(int minInclusive, String displayName, ChatFormatting color) {
        this.minInclusive = minInclusive;
        this.displayName = displayName;
        this.color = color;
    }

    public int minInclusive() { return minInclusive; }

    public String displayName() { return displayName; }

    public ChatFormatting color() { return color; }

    /** Display colour as opaque ARGB — for BlockUI text panes which take
     *  raw ints rather than ChatFormatting. */
    public int argb() {
        Integer rgb = color.getColor();
        return 0xFF000000 | (rgb == null ? 0xFFFFFF : rgb);
    }

    /** Map a clamped reputation value to its tier. Values outside 0–100
     *  are tolerated (clamped semantics: below 0 → HOSTILE, above 100 →
     *  DEVOTED) so a caller with a stale unclamped value can't crash. */
    public static ReputationTier forValue(double value) {
        ReputationTier[] tiers = values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (value >= tiers[i].minInclusive) return tiers[i];
        }
        return HOSTILE;
    }

    /** Stable wire id (ordinal is fine here — the enum order IS the band
     *  order and appending new tiers at the ends would change bands anyway,
     *  a design-level event). Used by the envoy-dialogue payload. */
    public byte id() { return (byte) ordinal(); }

    public static ReputationTier byId(int id) {
        ReputationTier[] tiers = values();
        return (id < 0 || id >= tiers.length) ? NEUTRAL : tiers[id];
    }
}
