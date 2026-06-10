package com.example.examplemod;

/**
 * Why a reputation change happened. Passed to every
 * {@link ReputationManager#modifyReputation} call so:
 *
 * <ul>
 *   <li>call sites are self-documenting;</li>
 *   <li>the central mutator can log / message per-reason;</li>
 *   <li>a future per-reason policy layer (multipliers, diminishing
 *       returns, throttles) has exactly one place to live.</li>
 * </ul>
 *
 * v1 carries no per-reason logic — the enum is the extension seam, not a
 * behaviour switch. Future features add values here (CRIME, RAID_REPELLED,
 * TRADE, …) rather than inventing parallel entry points.
 */
public enum ReputationReason {
    /** Player killed a hostile boss (Orc Disaster, Ifrit, …) near the colony. */
    BOSS_KILL,
    /** A colony building finished construction or an upgrade. */
    BUILDING_COMPLETED,
    /** Player damaged one of the colony's citizens. */
    CITIZEN_ATTACKED,
    /** Player killed one of the colony's citizens. */
    CITIZEN_KILLED,
    /** Theft from the colony — reserved; no v1 hook drives it. */
    THEFT,
    /** Quest-driven adjustment — reserved for quest integration. */
    QUEST,
    /** A Tensura raid on the colony was repelled (all raiders defeated
     *  before the timer) — see {@link TensuraRaids}. */
    RAID_REPELLED,
    /** Admin/debug adjustment (the /reputation command). */
    ADMIN
}
