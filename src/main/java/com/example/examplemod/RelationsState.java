package com.example.examplemod;

import net.minecraft.ChatFormatting;

/**
 * Per-(player, faction) diplomatic relations state (docs/diplomacy.md
 * #2) — the ONLY new state the diplomacy tiers add. Everything else
 * derives from Layer-1 standing:
 *
 * <ul>
 *   <li>DIPLOMACY tier = {@link #OPEN} (deliberately NOT band-locked —
 *       deals are the ladder up from NEUTRAL).</li>
 *   <li>ALLIANCE tier = {@link #PACT}, entered by completing the
 *       milestone alliance-pact deal (offered at ALLIED 80+).</li>
 *   <li>Collapse: standing below WARY shatters relations to NONE —
 *       which is why the Orc Disaster's forced-HOSTILE clamp breaks
 *       Clayman relations with no event-specific code.</li>
 * </ul>
 */
public enum RelationsState {

    NONE((byte) 0, "No relations", ChatFormatting.GRAY),
    OPEN((byte) 1, "Diplomacy", ChatFormatting.YELLOW),
    PACT((byte) 2, "Alliance", ChatFormatting.AQUA),
    /** The top tier (above ALLIANCE): post-pact standing gain is
     *  damped, standing must crawl to the COVENANT threshold, then the
     *  faction's UNIQUE milestone deal unlocks — completing it forges
     *  the COVENANT (per-faction rewards + reduced deal costs). */
    COVENANT((byte) 3, "Covenant", ChatFormatting.LIGHT_PURPLE);

    private final byte id;
    private final String displayName;
    private final ChatFormatting color;

    RelationsState(byte id, String displayName, ChatFormatting color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    public byte id() { return id; }

    public String displayName() { return displayName; }

    public ChatFormatting color() { return color; }

    public static RelationsState byId(byte id) {
        for (RelationsState s : values()) {
            if (s.id == id) return s;
        }
        return NONE;
    }
}
