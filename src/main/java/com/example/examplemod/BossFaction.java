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
 * in {@link FactionProfile#PROFILES}. EURAZANIA and MILIM have no anchored
 * boss — their standings move only via the two-sided RIPPLE legs (e.g. a
 * marked Clayman boss kill raises them).
 *
 * <p><b>Jura-Tempest Federation:</b> the former {@code tempest} and
 * {@code jura_alliance} factions were MERGED (they are the same canon
 * power — the Jura Forest Grand Alliance became the Jura Tempest
 * Federation). The surviving id is {@code tempest}; it carries the
 * combined diplomacy catalog plus the old Jura settlement/garrison body.
 * Old-save {@code jura_alliance} standing/relations migrate into
 * {@code tempest} on load (see the SavedData load methods). The faction
 * stays EXTERNAL to the player's colony.
 */
public enum BossFaction {

    TEMPEST("tempest", "Jura-Tempest Federation", ChatFormatting.AQUA),
    DWARGON("dwargon", "Dwargon", ChatFormatting.GOLD),
    LUMINOUS("luminous", "Luminous", ChatFormatting.WHITE),
    FALMUTH("falmuth", "Falmuth", ChatFormatting.RED),
    CLAYMAN("clayman", "Moderate Harlequin Alliance", ChatFormatting.DARK_PURPLE),
    LEON("leon", "Leon", ChatFormatting.YELLOW),
    // DEPRECATED — Shizu was soft-retired from play (consolidation step 3).
    // The enum value is KEPT so existing saves referencing "shizu" still load
    // (standing/relations remain valid but INERT). She is gated OUT of every
    // active system via isActive() (no settlement/garrison/event/diplomacy/
    // UI). Scheduled for HARD REMOVAL in a future version once old saves age
    // out. See DEPRECATED_IDS below + docs/faction-model.md.
    SHIZU("shizu", "Shizu", ChatFormatting.DARK_AQUA),
    OTHERWORLDERS("otherworlders", "Otherworlders", ChatFormatting.LIGHT_PURPLE),
    // Eurazania — the Beast Kingdom (Carrion/Calion's nation). Renamed from
    // the old "carrion" id; stays BODILESS (diplomacy/rep only). Old-save
    // `carrion` standing/relations migrate to `eurazania` on load.
    EURAZANIA("eurazania", "Eurazania", ChatFormatting.DARK_GREEN),
    MILIM("milim", "Milim", ChatFormatting.LIGHT_PURPLE);

    private final String id;
    private final String displayName;
    private final ChatFormatting color;

    BossFaction(String id, String displayName, ChatFormatting color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    /** Faction ids SOFT-RETIRED from active play but kept in the enum so old
     *  saves still load (their standing/relations stay valid but inert).
     *  Every active gameplay system — settlement gen, garrison, lore events,
     *  diplomacy offers/envoys, UI listings, raid ally-support — must skip
     *  these via {@link #isActive()} / {@link #isActiveId(String)}. Slated
     *  for hard removal once old saves age out. */
    private static final java.util.Set<String> DEPRECATED_IDS = java.util.Set.of("shizu");

    /** Stable storage/command key. NEVER the ordinal. */
    public String id() { return id; }

    public String displayName() { return displayName; }

    public ChatFormatting color() { return color; }

    /** True for factions in ACTIVE play. False for soft-retired (deprecated)
     *  factions — active systems must skip these. */
    public boolean isActive() { return !DEPRECATED_IDS.contains(id); }

    /** True for soft-retired factions kept only for save compatibility. */
    public boolean isDeprecated() { return DEPRECATED_IDS.contains(id); }

    /** String-keyed twin of {@link #isActive()} for the systems that work
     *  with raw faction-id strings (settlements, switch cases). Unknown ids
     *  count as active (an addon faction is not ours to retire). */
    public static boolean isActiveId(String id) { return !DEPRECATED_IDS.contains(id); }

    /** Lookup by storage/command id; null for unknown (e.g. a future
     *  addon faction's id found in a save — preserved, not ours). */
    public static BossFaction byId(String id) {
        for (BossFaction f : values()) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }
}
