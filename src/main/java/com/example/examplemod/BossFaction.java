package com.example.examplemod;

import net.minecraft.ChatFormatting;

/**
 * The boss-faction cast the player holds PER-FACTION standing with
 * (world reputation — see docs/world-reputation.md). A fixed enum by
 * design: the cast is known and each faction will grow bespoke logic
 * (rival colonies, raid flavors, diplomacy) in later arcs.
 *
 * <p><b>Addon door:</b> storage keys standings by {@link #id()} STRING
 * (never ordinal), and unknown ids in saves round-trip untouched — a
 * future data-driven faction overlay can add factions without spine
 * rework. v1 logic uses this enum everywhere.
 *
 * <p>Boss anchoring lives in {@code WorldReputationManager.bossProfileOf}
 * (faction + lore importance); per-faction dispositions, the
 * relationship web, swing multipliers and provocation thresholds live
 * in {@link FactionProfile#PROFILES}. TEMPEST, CARRION and MILIM have
 * no anchored boss — their standings move only via the two-sided
 * RIPPLE legs (e.g. a marked Clayman boss kill raises them).
 */
public enum BossFaction {

    TEMPEST("tempest", "Tempest", ChatFormatting.AQUA),
    DWARGON("dwargon", "Dwargon", ChatFormatting.GOLD),
    LUMINOUS("luminous", "Luminous", ChatFormatting.WHITE),
    FALMUTH("falmuth", "Falmuth", ChatFormatting.RED),
    CLAYMAN("clayman", "Clayman", ChatFormatting.DARK_PURPLE),
    LEON("leon", "Leon", ChatFormatting.YELLOW),
    SHIZU("shizu", "Shizu", ChatFormatting.DARK_AQUA),
    JURA_ALLIANCE("jura_alliance", "Jura Alliance", ChatFormatting.GREEN),
    OTHERWORLDERS("otherworlders", "Otherworlders", ChatFormatting.LIGHT_PURPLE),
    CARRION("carrion", "Carrion", ChatFormatting.DARK_GREEN),
    MILIM("milim", "Milim", ChatFormatting.LIGHT_PURPLE);

    private final String id;
    private final String displayName;
    private final ChatFormatting color;

    BossFaction(String id, String displayName, ChatFormatting color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    /** Stable storage/command key. NEVER the ordinal. */
    public String id() { return id; }

    public String displayName() { return displayName; }

    public ChatFormatting color() { return color; }

    /** Lookup by storage/command id; null for unknown (e.g. a future
     *  addon faction's id found in a save — preserved, not ours). */
    public static BossFaction byId(String id) {
        for (BossFaction f : values()) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }
}
