package com.example.examplemod;

/**
 * Why a per-faction world standing changed — the {@link ReputationReason}
 * discipline repeated for the world-reputation spine. v1 uses the first
 * three; the rest are RESERVED for the deferred consumers (rival
 * colonies, lore raids, diplomacy, reclaim) so future features extend
 * this enum instead of inventing parallel entry points.
 */
public enum WorldRepReason {
    /** Player damaged a MARKED faction boss (unmarked attacks are free). */
    MARKED_BOSS_ATTACKED,
    /** Player killed a MARKED faction boss — an act of war against
     *  the boss's own faction. */
    MARKED_BOSS_KILLED,
    /** Secondary leg of a marked kill: the victim faction's allies
     *  mourn / its enemies celebrate (the two-sided fan-out). */
    MARKED_BOSS_RIPPLE,
    /** Admin/debug adjustment (the /worldrep set command). */
    ADMIN,
    /** RESERVED — survived/repelled a faction's raid (rival-colony arc). */
    RAID_SURVIVED,
    /** RESERVED — conquest/encroachment on faction territory. */
    CONQUEST,
    /** RESERVED — diplomacy outcomes. */
    DIPLOMACY
}
