package com.example.examplemod;

/**
 * Why a per-faction world standing changed — the {@link ReputationReason}
 * discipline repeated for the world-reputation spine. v1 uses the first
 * three; the rest are RESERVED for the deferred consumers (rival
 * colonies, lore raids, diplomacy, reclaim) so future features extend
 * this enum instead of inventing parallel entry points.
 */
public enum WorldRepReason {
    /** Player damaged a faction's anchored boss/figure. */
    BOSS_ATTACKED,
    /** Player killed a faction's anchored boss/figure. */
    BOSS_KILLED,
    /** Admin/debug adjustment (the /worldrep set command). */
    ADMIN,
    /** RESERVED — survived/repelled a faction's raid (rival-colony arc). */
    RAID_SURVIVED,
    /** RESERVED — conquest/encroachment on faction territory. */
    CONQUEST,
    /** RESERVED — diplomacy outcomes. */
    DIPLOMACY
}
