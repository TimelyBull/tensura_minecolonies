package com.example.examplemod;

import java.util.EnumSet;

/**
 * The seven unlock-condition flavours an envoy can have been "earned by"
 * at the moment it spawned. A single envoy may carry multiple — they're
 * a {@link EnumSet} stored on {@link EnvoyTag} as a bitmask byte and
 * surfaced to the dialogue composer in {@link EnvoyDialogue#body}.
 *
 * <p>Not every (race, condition) pair has a flavour snippet — the
 * composer skips silently when no snippet is defined. The set captured
 * at spawn is the GROUND TRUTH; the dialogue presentation is the view.
 *
 * <p>Bit layout (low to high). The mask is a single byte → seven bits
 * used + one spare. Stable across saves; never renumber existing values.
 */
public enum EnvoyCondition {
    /** Race-specific citizen / named-count threshold (per-race COUNT
     *  alternative). For GOBLIN it's the cumulative-named count, for
     *  ORC / LIZARDMAN / DWARF it's the current-citizen count, for
     *  COLONIST it's not used (timer is used instead). */
    COUNT(0),
    /** Time-based condition. COLONIST: 3 in-game days since anchor.
     *  DWARF: 20 in-game days since the owning player last died. */
    TIMER(1),
    /** LIZARDMAN: player has defeated Ifrit (per-player flag). */
    IFRIT_DEFEATED(2),
    /** ORC: player has defeated the Orc Disaster (per-player permanent
     *  flag — immune to all resets, including character reset). */
    ORC_DISASTER_DEFEATED(3),
    /** DWARF: player has physically entered a {@code tensura:dwarf_village}
     *  jigsaw structure at least once (per-player flag). */
    DWARVEN_VILLAGE(4),
    /** DWARF: player is currently a true demon lord (live
     *  {@code IExistence} read at spawn-time, gated by the per-player
     *  demon-lord-path-disabled flag). */
    TRUE_DEMON_LORD(5),
    /** DWARF: player is currently a true hero (live
     *  {@code IExistence} read at spawn-time, gated by the per-player
     *  hero-path-disabled flag). */
    TRUE_HERO(6);

    private final int bit;

    EnvoyCondition(int bit) { this.bit = bit; }

    public int bit() { return bit; }
    public int mask() { return 1 << bit; }

    public static byte toMask(EnumSet<EnvoyCondition> set) {
        int m = 0;
        for (EnvoyCondition c : set) m |= c.mask();
        return (byte) m;
    }

    public static EnumSet<EnvoyCondition> fromMask(byte mask) {
        EnumSet<EnvoyCondition> out = EnumSet.noneOf(EnvoyCondition.class);
        int m = mask & 0xFF;
        for (EnvoyCondition c : values()) {
            if ((m & c.mask()) != 0) out.add(c);
        }
        return out;
    }
}
