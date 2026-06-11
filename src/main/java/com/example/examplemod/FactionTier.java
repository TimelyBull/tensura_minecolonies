package com.example.examplemod;

import net.minecraft.ChatFormatting;

/**
 * Derived per-faction disposition tiers — what deferred consumers gate
 * on in BOTH directions ("HOSTILE → this faction raids you",
 * "ALLIED → diplomacy opens"). Mirror of {@link ReputationTier}:
 * the standing number (0–100) is the stored truth; ALL band thresholds
 * live here. Ordered ascending so compareTo expresses "at least".
 *
 * Bands: 0–19 HOSTILE | 20–39 WARY | 40–59 NEUTRAL (default 50)
 *        | 60–79 FRIENDLY | 80–100 ALLIED
 */
public enum FactionTier {

    HOSTILE(0, "Hostile", ChatFormatting.DARK_RED),
    WARY(20, "Wary", ChatFormatting.GOLD),
    NEUTRAL(40, "Neutral", ChatFormatting.GRAY),
    FRIENDLY(60, "Friendly", ChatFormatting.GREEN),
    ALLIED(80, "Allied", ChatFormatting.AQUA);

    private final int minInclusive;
    private final String displayName;
    private final ChatFormatting color;

    FactionTier(int minInclusive, String displayName, ChatFormatting color) {
        this.minInclusive = minInclusive;
        this.displayName = displayName;
        this.color = color;
    }

    public int minInclusive() { return minInclusive; }

    public String displayName() { return displayName; }

    public ChatFormatting color() { return color; }

    public static FactionTier forValue(double value) {
        FactionTier[] tiers = values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (value >= tiers[i].minInclusive) return tiers[i];
        }
        return HOSTILE;
    }
}
