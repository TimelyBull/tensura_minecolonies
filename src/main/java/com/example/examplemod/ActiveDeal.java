package com.example.examplemod;

import net.minecraft.nbt.CompoundTag;

/**
 * One ACCEPTED deal's persisted progress (docs/diplomacy.md #3) — the
 * instance half of the deal framework, stored per (player, faction) in
 * {@link DiplomacySavedData} so % progress, deadlines and payoff timers
 * survive save/reload by construction.
 *
 * <p>State machine: ACTIVE → (requirement met) → AWAITING_PAYOFF (when
 * the spec has a payoff delay — the caravan travels) → READY → removed
 * on COLLECT. Deadline expiry while ACTIVE → removed with the standing
 * penalty. The standing REWARD is paid at fulfillment; the ITEM reward
 * at collect.
 */
class ActiveDeal {

    static final byte STATE_ACTIVE = 0;
    static final byte STATE_AWAITING_PAYOFF = 1;
    static final byte STATE_READY = 2;

    String dealId;
    /** The colony the requirement is bound to (chosen at accept time —
     *  the player's primary colony in Stage 1). */
    int colonyId;
    long acceptedTick;
    long deadlineTick;
    /** SupplyItems: items delivered so far. Milestone requirements
     *  don't use it (their progress is read live). */
    int progress;
    /** Game tick at which AWAITING_PAYOFF flips to READY. */
    long payoffAtTick;
    byte state = STATE_ACTIVE;

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dealId", dealId);
        tag.putInt("colonyId", colonyId);
        tag.putLong("acceptedTick", acceptedTick);
        tag.putLong("deadlineTick", deadlineTick);
        tag.putInt("progress", progress);
        tag.putLong("payoffAtTick", payoffAtTick);
        tag.putByte("state", state);
        return tag;
    }

    static ActiveDeal load(CompoundTag tag) {
        ActiveDeal deal = new ActiveDeal();
        deal.dealId = tag.getString("dealId");
        deal.colonyId = tag.getInt("colonyId");
        deal.acceptedTick = tag.getLong("acceptedTick");
        deal.deadlineTick = tag.getLong("deadlineTick");
        deal.progress = tag.getInt("progress");
        deal.payoffAtTick = tag.getLong("payoffAtTick");
        deal.state = tag.getByte("state");
        return deal;
    }
}
