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
 * <p>Six factions are entity-anchorable today (see
 * {@code WorldReputationManager.factionOf}); TEMPEST, CARRION and MILIM
 * are pure-lore until the rival-colony system — their standings exist
 * (default 50) but no v1 mover touches them.
 */
public enum BossFaction {

    TEMPEST("tempest", "Tempest", ChatFormatting.AQUA),
    DWARGON("dwargon", "Dwargon", ChatFormatting.GOLD),
    LUMINOUS("luminous", "Luminous", ChatFormatting.WHITE),
    FALMUTH("falmuth", "Falmuth", ChatFormatting.RED),
    CLAYMAN("clayman", "Clayman", ChatFormatting.DARK_PURPLE),
    LEON("leon", "Leon", ChatFormatting.YELLOW),
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
