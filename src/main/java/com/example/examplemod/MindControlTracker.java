package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.EntityUtils;
import com.mojang.logging.LogUtils;
import io.github.manasmods.tensura.ability.SkillUtils;
import io.github.manasmods.tensura.registry.skill.UniqueSkills;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Subordinate ownership under OTHER PLAYERS' skills — mind control (temporary
 * owner) and permanent steals.
 *
 * Two jobs, both keyed on {@link RaceIdentitySavedData.RaceIdentity}:
 *
 * 1. MIND CONTROL (temporary owner ≠ our owner). Tensura's charm skills — and
 *    TR:Nightmare's Mammon / Yog / Temptation / Charisma / Lemegeton /
 *    King's-Authority family — write {@code IExistence.temporaryOwner} on a
 *    wild subordinate body and tame it to the controller. The per-second
 *    {@link #tick} mirrors that live state onto
 *    {@code identity.controlledByUUID} (persisted, so a reload mid-control
 *    reconciles). While controlled:
 *      - the CONTROLLER gains the roster entry and may summon (to their side)
 *        or send (to THEIR colony — the citizen half is temporarily moved
 *        there, {@code controlParked=true});
 *      - the ORIGINAL owner's roster still lists the subordinate (silent — the
 *        deceit is the point) but menu actions return "doesn't respond";
 *      - when control ends (Tensura's MindControlEffect auto-clears the
 *        temporary owner on expiry, or a release skill fires) the mirror
 *        clears and, if the citizen was parked, it is SILENTLY moved back to
 *        the owner's colony.
 *    NOTE a parked citizen whose wild body was consumed by the send has no
 *    live entity ticking the control effect down — control is FROZEN until
 *    someone summons the body again. Documented behavior (sleeper-agent
 *    seam), not a bug.
 *
 * 2. PERMANENT TRANSFER ({@link #transferPermanent}) — the Greed-ego steal
 *    (and any future permanent-steal source TrNightmareCompat routes here):
 *    the WHOLE identity changes hands. Citizen moves to the thief's colony
 *    (or the pending pool if they have none), the identity record is rebuilt
 *    under the same identityId with the new ownerPlayerUUID, both players are
 *    notified.
 *
 * This class has NO TR:Nightmare imports — it works against base Tensura's
 * storage and is always active. TR:Nightmare-specific detection lives in
 * {@link TrNightmareCompat}, which is only loaded when that mod is present.
 */
final class MindControlTracker {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ------------------------------------------------------------------
    // Colonist-mode control budget (user decision 2026-09-05):
    // SUBORDINATE mode keeps the skill's own natural duration (the effect
    // ticks on the wild body — nothing of ours involved). COLONIST mode
    // (citizen parked in the controller's colony) gets AT MOST one hour,
    // scaled by the strength of the control skill — its effect duration is
    // the only strength signal the skills expose. Both clocks conceptually
    // start at control time; each is frozen while the other body is active
    // (a natural side effect: the effect only ticks on a live wild body, and
    // the budget below only ticks while parked).
    // ------------------------------------------------------------------

    /** Hard cap on parked-citizen service: one hour of real time (72 000 t).
     *  Only applies to TIMED controls — the indefinite skills (Lemegeton
     *  seal, King's Authority, permanent Charisma) stay indefinite in BOTH
     *  forms (user decision 2026-09-05). */
    private static final long COLONIST_CONTROL_MAX_TICKS = 72_000L;
    /** Budget = effect duration × this, capped. A max-strength 10-min control
     *  (12 000 t — Mammon / mastered Yog) maps exactly to the full hour; the
     *  weakest ladder rung (600 t) yields 3 min. ⚠ BALANCE GUESS. */
    private static final double COLONIST_BUDGET_PER_EFFECT_TICK = 6.0;
    /** Budget sentinel: no countdown — control lasts until something else
     *  ends it. */
    private static final long BUDGET_INDEFINITE = -1L;

    // ---- Deceit tuning ------------------------------------------------

    /** How long a launched sleeper strike lasts before standing down (60 s). */
    private static final long STRIKE_DURATION_TICKS = 1_200L;
    /** Delayed plant tell: min/max delay before the owner's info skill
     *  notices "something seems off" (2–5 min). */
    private static final long TELL_DELAY_MIN_TICKS = 2_400L;
    private static final long TELL_DELAY_MAX_TICKS = 6_000L;
    /** Happiness malus on a controlled citizen — same modifier shape as the
     *  named-acquisition penalty; the low static value drags the citizen's
     *  mood ("something feels wrong"), a skill-less soft detector. */
    private static final String CONTROL_HAPPINESS_ID = "tensura_minecolonies_controlled";
    private static final double CONTROL_HAPPINESS_WEIGHT = 1.0;
    private static final double CONTROL_HAPPINESS_VALUE = 2.0;

    /** Active strikes: identityId → end gameTime. Transient — a reload during
     *  the 60 s strike stands the striker down via the scrub/mirror path. */
    private static final java.util.Map<UUID, Long> ACTIVE_STRIKES = new java.util.HashMap<>();
    /** Delayed plant tells: identityId → due gameTime. Transient. */
    private static final java.util.Map<UUID, Long> PENDING_TELLS = new java.util.HashMap<>();
    /** Plant tells already delivered this session (avoid repeats). Transient. */
    private static final java.util.Set<UUID> TELLS_SENT = new java.util.HashSet<>();
    /** Suspicion flags last synced per owner (player → citizen entity ids). */
    private static final java.util.Map<UUID, java.util.Set<UUID>> SUSPICION_SENT = new java.util.HashMap<>();

    // ---- Skill ranks (user design 2026-09-05) -------------------------
    //
    // Control rank: read at control start from the MIND_CONTROL effect's
    // SOURCE ABILITY (Tensura stamps the casting skill on the effect
    // instance). NO effect = the indefinite controls = SUPREME.
    // Info rank: the strongest information skill the player holds.
    // Unmask rule: infoRank >= RANK_ULTIMATE (Raphael-and-above; Great Sage
    // never unmasks) AND infoRank >= controlRank. Supreme is unmaskable by
    // nothing — its only counter is the layer-3 barrier cleanse.

    static final byte RANK_NONE = 0;
    static final byte RANK_UNIQUE = 1;    // Tempter / base charm tier; Great Sage / Investigator
    static final byte RANK_ULTIMATE = 2;  // Azazel, Mammon, Yog-Sothoth, Charisma; Raphael / Faust
    static final byte RANK_GOD = 3;       // Yog-Sotohort; Nodens / Akashic Records
    static final byte RANK_SUPREME = 4;   // effect-less indefinite controls — unmaskable

    private static final java.util.Map<String, Byte> CONTROL_SKILL_RANKS = java.util.Map.of(
            "trnightmare:tempter", RANK_UNIQUE,
            "trnightmare:azazel", RANK_ULTIMATE,
            "trnightmare:mammon", RANK_ULTIMATE,
            "trnightmare:yog_sothoth", RANK_ULTIMATE,
            "trnightmare:true_hero", RANK_ULTIMATE,
            "trnightmare:yog-sotohort", RANK_GOD);

    /** (rank, display name, voice) of the strongest info skill; rank 0 =
     *  none. {@code wisdomVoice} = the canon Wisdom line (Great Sage /
     *  Raphael): the skill SPEAKS to its holder in «Notice. …»-style
     *  announcements (source canon + TR:N's own Raphael voice lines). Every
     *  other info skill is a silent tool — its findings render as
     *  "[Skill] …" output (TR:N's own house style: "[Zagan] …",
     *  "Investigated X: …"). */
    record InfoSkill(byte rank, String name, boolean wisdomVoice) {}
    private static final InfoSkill NO_INFO = new InfoSkill(RANK_NONE, null, false);
    private static final List<java.util.Map.Entry<net.minecraft.resources.ResourceLocation, InfoSkill>> INFO_SKILLS = List.of(
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:akashic_records"), new InfoSkill(RANK_GOD, "Akashic Records", false)),
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:nodens"), new InfoSkill(RANK_GOD, "Nodens", false)),
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:raphael_wisdom"), new InfoSkill(RANK_ULTIMATE, "Raphael", true)),
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:raphael_knowledge"), new InfoSkill(RANK_ULTIMATE, "Raphael", true)),
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:faust"), new InfoSkill(RANK_ULTIMATE, "Faust", false)),
            java.util.Map.entry(net.minecraft.resources.ResourceLocation.parse("trnightmare:investigator"), new InfoSkill(RANK_UNIQUE, "Investigator", false)));

    /**
     * Canon-formatted info-skill message. Wisdom line → the guillemet
     * announcement «Opener. wisdomText» (the skill speaking to its holder);
     * silent tools → "[Skill] toolText" (skill output, no persona).
     */
    static String speak(InfoSkill info, String opener, String wisdomText, String toolText) {
        return info.wisdomVoice()
                ? "«" + opener + ". " + wisdomText + "»"
                : "[" + info.name() + "] " + toolText;
    }

    // ---- Steal / interrogation tuning ---------------------------------

    /** One skim every 30 s while [Steal] is active. */
    private static final long STEAL_INTERVAL_TICKS = 600L;
    /** Items taken per skim, and the loot-bag stack cap. ⚠ BALANCE GUESSES. */
    private static final int STEAL_MAX_PER_SKIM = 8;
    private static final int STEAL_LOOT_CAP_STACKS = 27;
    /** Interrogation magicule cost = this × the control skill's rank. */
    private static final double INTERROGATE_COST_PER_RANK = 2_500.0;
    /** Last skim gameTime per identity (transient). */
    private static final java.util.Map<UUID, Long> LAST_STEAL = new java.util.HashMap<>();
    /** Reconcile cadence — must match the scheduler block calling tick(). */
    private static final long TICKS_PER_RECONCILE = 20L;

    private MindControlTracker() {}

    // ------------------------------------------------------------------
    // Action gating
    // ------------------------------------------------------------------

    /**
     * May this player act on this identity through the roster/menu paths?
     * Controller (while control lasts) beats owner; owner regains the moment
     * the mirror clears.
     */
    static boolean canActOn(ServerPlayer player, RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.ownerPlayerUUID == null) return false;
        UUID actor = player.getUUID();
        if (identity.controlledByUUID != null) {
            // Gated: nobody acts on a charmed subordinate through our menus
            // (the owner still gets "They don't respond"). Ungated: the
            // controller does — the espionage suite's entry point.
            return TrnGate.espionage() && actor.equals(identity.controlledByUUID);
        }
        return actor.equals(identity.ownerPlayerUUID);
    }

    /** True when the identity is currently mind-controlled by someone other
     *  than its owner. */
    static boolean isControlled(RaceIdentitySavedData.RaceIdentity identity) {
        return identity.controlledByUUID != null;
    }

    /**
     * Pre-action hook for the send/summon chokepoints. When the ACTOR is the
     * CONTROLLER and the action is a send (mode SUBORDINATE), the citizen
     * half must first be parked in the controller's colony so the normal send
     * path (which reads {@code identity.colonyId}) delivers there. Returns
     * the (possibly rebuilt) identity to continue with, or null if the action
     * must abort (advisory already sent).
     */
    static RaceIdentitySavedData.RaceIdentity prepareForAction(ServerPlayer player,
                                                               RaceIdentitySavedData saved,
                                                               RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.controlledByUUID == null
                || !player.getUUID().equals(identity.controlledByUUID)) {
            return identity; // not a controller action — nothing to prepare
        }
        if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE) {
            return identity; // summon direction — no citizen move needed
        }
        if (identity.planted) {
            // Plant intent: the send must deliver to the OWNER's colony (the
            // identity's own colonyId), NOT park in the controller's. Skip.
            return identity;
        }
        ServerLevel level = player.serverLevel();
        IColony controllerColony = IColonyManager.getInstance()
                .getIColonyByOwner(level, player.getUUID());
        if (controllerColony == null) {
            ExampleMod.sendAdvisoryNotice(player,
                    "You have no colony to send them to.");
            return null;
        }
        if (controllerColony.getID() == identity.colonyId) {
            return identity; // already parked (send retry after a collapse prompt)
        }
        RaceIdentitySavedData.RaceIdentity parked = moveCitizen(player.getServer(), saved,
                identity, controllerColony, identity.ownerPlayerUUID, /*park=*/true,
                "controlled-send by " + player.getGameProfile().getName());
        if (parked == null) {
            ExampleMod.sendAdvisoryNotice(player,
                    "They resist — their colony record couldn't be moved.");
            return null;
        }
        return parked;
    }

    // ------------------------------------------------------------------
    // Per-second reconcile (called from ExampleMod.onServerTickPost, 1 s)
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(server.overworld());
        // Copy — the home-return pass rebuilds records (replaceIdentity mutates
        // the backing map).
        List<RaceIdentitySavedData.RaceIdentity> snapshot = new ArrayList<>(saved.all());
        for (RaceIdentitySavedData.RaceIdentity identity : snapshot) {
            if (identity.ownerPlayerUUID == null) continue;
            try {
                reconcileOne(server, saved, identity);
            } catch (Throwable t) {
                LOGGER.error("[TM] mind-control: reconcile threw for identity {}",
                        identity.identityId, t);
            }
        }
    }

    private static void reconcileOne(MinecraftServer server, RaceIdentitySavedData saved,
                                     RaceIdentitySavedData.RaceIdentity identity) {
        // A. Mirror the live mob's temporary owner (only a WILD body can carry
        //    the ticking control effect — a parked IN_COLONY identity's
        //    effect state is frozen inside the snapshot).
        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                && identity.mobEntityUUID != null) {
            LivingEntity mob = ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID);
            if (mob != null) {
                // A0. Stale-control scrub: the colonist budget expired while
                //     the control NBT was frozen in the snapshot; this is the
                //     first live body since. Strip it — removing the effect
                //     makes Tensura's own MindControlEffect.onAttributeRemoved
                //     clear the temp owner and reset the mob to its permanent
                //     owner; the manual clear covers the effect-less skills
                //     (Lemegeton seal / King's Authority).
                if (identity.scrubControl) {
                    try {
                        mob.removeEffect(io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL);
                        ExistenceStorage ex = ExampleMod.readExistence(mob);
                        if (ex != null && ex.getTemporaryOwner() != null
                                && !ex.getTemporaryOwner().equals(identity.ownerPlayerUUID)) {
                            ex.setTemporaryOwner(null);
                            ex.markDirty();
                        }
                        LOGGER.info("[TM] mind-control: identity {} stale control scrubbed off live body",
                                identity.identityId);
                    } catch (Throwable t) {
                        LOGGER.warn("[TM] mind-control: scrub failed for identity {}",
                                identity.identityId, t);
                    }
                    saved.setScrubControl(identity, false);
                }
                ExistenceStorage existence = ExampleMod.readExistence(mob);
                if (existence != null) {
                    UUID temp = existence.getTemporaryOwner();
                    boolean controlled = temp != null && !temp.equals(identity.ownerPlayerUUID);
                    if (controlled && !temp.equals(identity.controlledByUUID)) {
                        saved.setControlledBy(identity, temp);
                        // CONTROL START — capture the colonist budget from the
                        // control effect's duration (the strength signal) and
                        // the control skill's RANK from the effect's source
                        // ability. Effect-less / permanent controls get the
                        // full hour budget-wise but SUPREME rank.
                        saved.setControlBudget(identity, computeColonistBudget(mob));
                        saved.setControlRank(identity, computeControlRank(mob));
                        LOGGER.info("[TM] mind-control: identity {} now controlled by {} "
                                + "(colonist budget {} ticks, control rank {})",
                                identity.identityId, temp, identity.controlBudgetTicks,
                                identity.controlRank);
                    } else if (!controlled && identity.controlledByUUID != null) {
                        saved.setControlledBy(identity, null);
                        saved.setControlBudget(identity, -1L);
                        saved.setControlRank(identity, RANK_NONE);
                        clearDeceitState(server, saved, identity);
                        LOGGER.info("[TM] mind-control: identity {} control ended",
                                identity.identityId);
                    }
                }
            }
        }

        // A2. Parked-citizen countdown: only while the citizen half actively
        //     serves the controller's colony does the colonist budget burn
        //     (it is frozen in every other state — the "freeze based on which
        //     is active" rule falls out of this gate). At zero, control is
        //     force-ended: mirror cleared, stale snapshot control flagged for
        //     scrubbing, and the home-return pass below brings the citizen
        //     back this same tick.
        if (identity.mode == RaceIdentitySavedData.Mode.IN_COLONY
                && (identity.controlParked || identity.planted)
                && identity.controlledByUUID != null
                && identity.controlBudgetTicks >= 0) { // < 0 = indefinite control
            // LOYALTY: a happy citizen shakes control off faster; a miserable
            // one succumbs longer. Happiness ≥ 7 → ×2 burn, ≤ 3 → ×0.5.
            // (Consistent with the mod's happiness-as-loyalty language:
            // Pure Heart defection, assassin determination.) Irrelevant for
            // indefinite controls by construction.
            long burn = TICKS_PER_RECONCILE;
            double happiness = citizenHappinessOf(server, identity);
            if (happiness >= 7.0) burn *= 2;
            else if (happiness >= 0.0 && happiness <= 3.0) burn = Math.max(1, burn / 2);
            long budget = identity.controlBudgetTicks - burn;
            if (budget <= 0) {
                saved.setControlledBy(identity, null);
                saved.setControlBudget(identity, -1L);
                saved.setControlRank(identity, RANK_NONE);
                saved.setScrubControl(identity, true);
                clearDeceitState(server, saved, identity);
                // A parked citizen goes home via pass B below; a PLANTED one
                // is already home — control simply dissolves in place (an
                // armed-but-unfired strike fizzles silently, by design).
                LOGGER.info("[TM] mind-control: identity {} colonist budget exhausted — "
                        + "control force-ended in place", identity.identityId);
            } else {
                saved.setControlBudget(identity, budget);
            }
        }

        // A3. Deceit upkeep — controlled + IN_COLONY citizens carry the
        //     unhappiness malus and (planted ones) the delayed info-skill
        //     tell; armed strikes watch for the owner's vulnerability window.
        if (identity.controlledByUUID != null
                && identity.mode == RaceIdentitySavedData.Mode.IN_COLONY) {
            applyControlHappiness(server, identity);
            if (identity.planted) {
                tickPlantTell(server, identity);
                if (identity.stealing) {
                    tickSteal(server, saved, identity);
                }
                if (identity.strikeArmed) {
                    maybeLaunchStrike(server, saved, identity);
                }
            }
        }
        tickActiveStrike(server, saved, identity);
        tickLootDelivery(server, saved, identity);

        // B. Silent home-return: control has ended but the citizen half is
        //    still parked in the (ex-)controller's colony. Retry each second
        //    until the owner's colony exists and the move succeeds.
        if (identity.controlledByUUID == null && identity.controlParked) {
            IColony home = findOwnerColony(server, identity.ownerPlayerUUID);
            if (home == null) return; // owner colonyless right now — keep waiting
            if (home.getID() == identity.colonyId) {
                saved.setControlParked(identity, false); // already home somehow
                return;
            }
            RaceIdentitySavedData.RaceIdentity moved = moveCitizen(server, saved, identity,
                    home, identity.ownerPlayerUUID, /*park=*/false, "control-ended home return");
            if (moved != null) {
                LOGGER.info("[TM] mind-control: identity {} citizen silently returned to colony {}",
                        moved.identityId, home.getID());
                // Counterplay tell — an information skill notices the citizen
                // came back from an unexplained absence. Vague on purpose (no
                // who / where / how long); the return itself stays silent for
                // everyone else.
                ServerPlayer owner = server.getPlayerList().getPlayer(moved.ownerPlayerUUID);
                if (owner != null) {
                    InfoSkill info = infoSkillOf(owner);
                    if (info.rank() >= RANK_UNIQUE) {
                        String subject = citizenNameOf(server, moved);
                        owner.sendSystemMessage(Component.literal(speak(info, "Notice",
                                subject + " was absent from the colony for an extended period.",
                                subject + " was absent for an extended period."))
                                .withStyle(ChatFormatting.YELLOW));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Permanent transfer — the Greed steal (called by TrNightmareCompat)
    // ------------------------------------------------------------------

    /**
     * Move the ENTIRE identity to a new owner: citizen into the thief's
     * colony (or the pending pool when they have none), identity rebuilt with
     * the new ownerPlayerUUID, notifications to both sides. The Tensura-side
     * permanentOwner is assumed already written by the stealing skill.
     */
    static void transferPermanent(MinecraftServer server, RaceIdentitySavedData saved,
                                  RaceIdentitySavedData.RaceIdentity identity, UUID newOwner) {
        transferPermanent(server, saved, identity, newOwner, false);
    }

    static void transferPermanent(MinecraftServer server, RaceIdentitySavedData saved,
                                  RaceIdentitySavedData.RaceIdentity identity, UUID newOwner,
                                  boolean isRelease) {
        UUID oldOwner = identity.ownerPlayerUUID;
        String name = citizenNameOf(server, identity);

        IColony destColony = findOwnerColony(server, newOwner);
        if (destColony != null) {
            RaceIdentitySavedData.RaceIdentity moved = moveCitizen(server, saved, identity,
                    destColony, newOwner, /*park=*/false,
                    isRelease ? "release" : "permanent steal");
            if (moved == null) {
                // Citizen record unreachable (colony gone / data missing).
                // Fall through to the pending-pool shape so ownership still
                // transfers instead of desyncing.
                dropToPending(server, saved, identity, newOwner);
            } else {
                saved.setControlledBy(moved, null); // perm owner IS the ex-controller now
                saved.setControlBudget(moved, -1L);
                saved.setPlanted(moved, false);     // deceit state dies with the theft
                saved.setStrikeArmed(moved, false);
                // Provenance: record who it was taken from — grants the
                // ex-owner the "(stolen)" roster row, the ask-permission
                // summon/send, and the Release attempt. A steal's release
                // difficulty is ULTIMATE tier (the Greed escalation).
                saved.setPreviousOwner(moved, oldOwner);
                saved.setControlRank(moved, isRelease ? RANK_NONE : RANK_ULTIMATE);
            }
        } else {
            dropToPending(server, saved, identity, newOwner);
        }

        ServerPlayer thief = server.getPlayerList().getPlayer(newOwner);
        if (thief != null) {
            thief.sendSystemMessage(Component.literal(
                    name + (isRelease ? " returns to your side" : " now serves you")
                            + (destColony != null
                            ? " — their citizen record joined " + destColony.getName() + "."
                            : ". They will join your first colony."))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (isRelease && oldOwner != null) {
            ServerPlayer exThief = server.getPlayerList().getPlayer(oldOwner);
            if (exThief != null) {
                exThief.sendSystemMessage(Component.literal(
                        name + " has been released from your grasp.")
                        .withStyle(ChatFormatting.RED));
            }
            return; // no theft warning on a release
        }
        // The VICTIM only learns of the theft if an information-type skill
        // tips them off (Great Sage, or a TR:N analysis skill) — delivered in
        // canon format (announcement vs skill-output; see speak()). Everyone
        // else just finds the subordinate missing from their roster — the
        // deceit is the point, and the missing entry is the organic tell.
        if (oldOwner != null) {
            ServerPlayer victim = server.getPlayerList().getPlayer(oldOwner);
            if (victim != null) {
                InfoSkill info = infoSkillOf(victim);
                if (info.rank() >= RANK_UNIQUE) {
                    String by = thief != null ? " by " + thief.getGameProfile().getName() : "";
                    victim.sendSystemMessage(Component.literal(speak(info, "Warning",
                            name + " has been stolen" + by + ".",
                            name + " has been stolen" + by + "."))
                            .withStyle(ChatFormatting.RED));
                }
            }
        }
        LOGGER.info("[TM] steal: identity {} ('{}') transferred {} -> {} (destColony={})",
                identity.identityId, name, oldOwner, newOwner,
                destColony != null ? destColony.getID() : "none/pending");
    }

    /** New owner has no colony: remove the citizen record entirely and queue
     *  the mob in the pending pool under the new owner — it promotes to a
     *  fresh citizen when they found a colony (skills re-roll; logged). */
    private static void dropToPending(MinecraftServer server, RaceIdentitySavedData saved,
                                      RaceIdentitySavedData.RaceIdentity identity, UUID newOwner) {
        String name = citizenNameOf(server, identity);
        ColonyRef src = findColony(server, identity.colonyId);
        ICitizenData cd = src == null ? null
                : src.colony.getCitizenManager().getCivilian(identity.citizenId);

        // The pending pool only holds a MONSTER (it is re-promoted by mob UUID
        // when the new owner founds a colony). An IN_COLONY identity has no
        // monster — its body is the citizen — so first give it one: the plain
        // summon path rebuilds the monster from the snapshot on the citizen's
        // spot. If that cannot be done the record is left untouched rather than
        // deleted; previously the citizen was simply lost.
        if (identity.mode == RaceIdentitySavedData.Mode.IN_COLONY) {
            if (src == null || cd == null || identity.entitySnapshot == null) {
                LOGGER.warn("[TM] steal: identity {} is IN_COLONY with no way to rebuild a body "
                        + "(colony {} citizen {} snapshot {}) — left in place",
                        identity.identityId, identity.colonyId, identity.citizenId,
                        identity.entitySnapshot != null);
                return;
            }
            net.minecraft.world.phys.Vec3 at = cd.getEntity()
                    .map(net.minecraft.world.entity.Entity::position)
                    .orElseGet(() -> {
                        BlockPos th = src.colony.getServerBuildingManager().hasTownHall()
                                ? src.colony.getServerBuildingManager().getTownHall().getPosition()
                                : src.colony.getCenter();
                        return new net.minecraft.world.phys.Vec3(th.getX() + 0.5, th.getY(), th.getZ() + 0.5);
                    });
            ExampleMod.summonGoblin(null, src.level, saved, identity, src.colony, cd, at, false);
            if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE || identity.mobEntityUUID == null) {
                LOGGER.warn("[TM] steal: could not materialize a body for identity {} — left in place",
                        identity.identityId);
                return;
            }
        }

        if (cd != null) {
            cd.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard);
            // Travelling entries are keyed by citizen number and outlive the
            // citizen unless cleared; the next citizen with this number would
            // inherit "travelling forever". See ExampleMod death(A).
            src.colony.getTravellingManager().finishTravellingFor(cd);
            src.colony.getCitizenManager().removeCivilian(cd);
        }
        saved.removeIdentity(identity);
        if (identity.mobEntityUUID != null) {
            saved.addPending(new RaceIdentitySavedData.PendingRaceMob(
                    identity.identityId, name, identity.mobEntityUUID, newOwner, identity.race));
        }
        LOGGER.info("[TM] steal: identity {} dropped to pending pool for colonyless owner {} "
                + "(citizen skills will re-roll at promotion)", identity.identityId, newOwner);
    }

    // ------------------------------------------------------------------
    // The citizen mover — the lending idiom (serialize → remove → resurrect)
    // ------------------------------------------------------------------

    /**
     * Move the CITIZEN half of an identity into {@code destColony} and rebuild
     * the identity record (same identityId) with the given owner. Only valid
     * while the identity has no live citizen body of consequence — i.e. mode
     * SUBORDINATE (travelling-suppressed, bodyless) or a defense swap; the
     * rebuilt record is always SUBORDINATE with travelling re-suppressed at
     * the destination. Returns the new record, or null on failure (state
     * unchanged).
     */
    private static RaceIdentitySavedData.RaceIdentity moveCitizen(MinecraftServer server,
                                                                  RaceIdentitySavedData saved,
                                                                  RaceIdentitySavedData.RaceIdentity identity,
                                                                  IColony destColony,
                                                                  UUID newOwnerUUID,
                                                                  boolean park,
                                                                  String reasonLog) {
        ColonyRef src = findColony(server, identity.colonyId);
        if (src == null) {
            LOGGER.warn("[TM] moveCitizen: source colony {} not found (identity {})",
                    identity.colonyId, identity.identityId);
            return null;
        }
        ICitizenData cd = src.colony.getCitizenManager().getCivilian(identity.citizenId);
        if (cd == null) {
            LOGGER.warn("[TM] moveCitizen: citizen {} missing from colony {} (identity {})",
                    identity.citizenId, identity.colonyId, identity.identityId);
            return null;
        }
        ServerLevel destLevel = destColonyLevel(server, destColony);
        if (destLevel == null || !destColony.getServerBuildingManager().hasTownHall()) {
            LOGGER.warn("[TM] moveCitizen: destination colony {} has no town hall / level",
                    destColony.getID());
            return null;
        }
        BlockPos th = destColony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos spawnAt = EntityUtils.getSpawnPoint(destLevel, th);
        if (spawnAt == null) spawnAt = th;

        // Serialize → remove → resurrect (the lending idiom). resetId=true —
        // citizen ids are only unique per colony.
        CompoundTag snapshot = cd.serializeNBT(destLevel.registryAccess());
        cd.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard);
        // Clear the source colony's travelling entry for this number (see
        // ExampleMod death(A) for why a leftover entry is dangerous).
        src.colony.getTravellingManager().finishTravellingFor(cd);
        src.colony.getCitizenManager().removeCivilian(cd);

        ICitizenData moved;
        try {
            moved = destColony.getCitizenManager()
                    .resurrectCivilianData(snapshot, true, destLevel, spawnAt);
        } catch (Throwable t) {
            LOGGER.error("[TM] moveCitizen: resurrect threw — citizen LOST from colony {}, "
                    + "identity {} left pointing at the old record", identity.colonyId,
                    identity.identityId, t);
            return null;
        }
        if (moved == null) {
            LOGGER.error("[TM] moveCitizen: resurrect returned null (identity {})",
                    identity.identityId);
            return null;
        }
        // The wild body is the active one — keep the citizen bodyless.
        // Keep the SOURCE mode. A SUBORDINATE identity has a live monster out in
        // the world, so its new citizen record waits (travelling forever) until
        // it is sent home, exactly like a freshly named one. An IN_COLONY
        // identity has NO monster — its body is the citizen — so it must get a
        // body in the destination right now. Marking that one travelling used to
        // leave a citizen that could never be recalled (nothing would ever send
        // it home): a permanent ghost in the town hall.
        boolean wasSubordinate = identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE;
        if (wasSubordinate) {
            destColony.getTravellingManager().startTravellingTo(moved, th, Integer.MAX_VALUE);
        } else {
            destColony.getTravellingManager().finishTravellingFor(moved);
            if (moved.getEntity().isEmpty()) {
                destColony.getCitizenManager().spawnOrCreateCivilian(
                        moved, destLevel, java.util.List.of(spawnAt), true);
            }
        }

        RaceIdentitySavedData.RaceIdentity rebuilt = new RaceIdentitySavedData.RaceIdentity(
                identity.identityId, moved.getId(), destColony.getID(),
                wasSubordinate ? identity.mobEntityUUID : null,
                wasSubordinate ? RaceIdentitySavedData.Mode.SUBORDINATE
                               : RaceIdentitySavedData.Mode.IN_COLONY,
                identity.entitySnapshot, newOwnerUUID, identity.race);
        rebuilt.raceTagSnapshot = identity.raceTagSnapshot;
        rebuilt.controlledByUUID = identity.controlledByUUID;
        rebuilt.controlParked = park;
        rebuilt.controlBudgetTicks = identity.controlBudgetTicks; // survive the rebuild
        rebuilt.scrubControl = identity.scrubControl;
        rebuilt.planted = identity.planted;
        rebuilt.strikeArmed = identity.strikeArmed;
        // jobSitePos deliberately dropped (the job-site block was in the old
        // colony); defendingColony deliberately false (colony changed).
        saved.replaceIdentity(identity, rebuilt);
        LOGGER.info("[TM] moveCitizen: identity {} citizen {}@{} -> {}@{} ({})",
                rebuilt.identityId, identity.citizenId, identity.colonyId,
                rebuilt.citizenId, rebuilt.colonyId, reasonLog);
        return rebuilt;
    }

    // ------------------------------------------------------------------
    // Deceit — plant, strike, tells, suspicion (design:
    // docs/tr-nightmare-integration.md §6)
    // ------------------------------------------------------------------

    /** C2S entry for the roster [Plant] / [Strike] buttons. Server-authoritative. */
    static void handleDeceitAction(ServerPlayer player, UUID identityId, byte action) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = saved.getById(identityId);
        if (identity == null) {
            ExampleMod.sendAdvisoryNotice(player, "That citizen no longer exists.");
            return;
        }
        if (identity.controlledByUUID == null
                || !player.getUUID().equals(identity.controlledByUUID)) {
            ExampleMod.sendAdvisoryNotice(player, "They are not under your control.");
            return;
        }

        if (action == Networking.DeceitActionPayload.ACTION_PLANT) {
            if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE) {
                ExampleMod.sendAdvisoryNotice(player,
                        "Summon them to your side first — a plant starts from the wild form.");
                return;
            }
            // If a previous park moved the citizen record into OUR colony, it
            // must go back to the owner's colony record-side first so the
            // send below delivers there.
            IColony ownerColony = findOwnerColony(server, identity.ownerPlayerUUID);
            if (ownerColony == null) {
                ExampleMod.sendAdvisoryNotice(player, "Their master has no colony to infiltrate.");
                return;
            }
            if (identity.colonyId != ownerColony.getID()) {
                RaceIdentitySavedData.RaceIdentity moved = moveCitizen(server, saved, identity,
                        ownerColony, identity.ownerPlayerUUID, /*park=*/false,
                        "plant: record returned to owner colony first");
                if (moved == null) {
                    ExampleMod.sendAdvisoryNotice(player, "The infiltration failed — try again.");
                    return;
                }
                identity = moved;
            }
            saved.setPlanted(identity, true);
            ExampleMod.sendAdvisoryNotice(player,
                    "You will return them to their master's colony — none the wiser.");
            // Route through the normal send chokepoint (cost gate + swap);
            // prepareForAction sees `planted` and skips the controller park.
            ExampleMod.handleMenuAction(player, identityId);
            return;
        }

        if (action == Networking.DeceitActionPayload.ACTION_STRIKE) {
            if (!identity.planted || identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) {
                ExampleMod.sendAdvisoryNotice(player, "Only a planted sleeper can strike.");
                return;
            }
            if (identity.strikeArmed) {
                ExampleMod.sendAdvisoryNotice(player, "The knife already waits for an opening.");
                return;
            }
            saved.setStrikeArmed(identity, true);
            ExampleMod.sendAdvisoryNotice(player,
                    "The knife waits for an opening.");
            LOGGER.info("[TM] deceit: strike ARMED on identity {} (controller {})",
                    identity.identityId, player.getGameProfile().getName());
            return;
        }

        if (action == Networking.DeceitActionPayload.ACTION_STEAL) {
            if (!identity.planted || identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) {
                ExampleMod.sendAdvisoryNotice(player, "Only a planted sleeper can steal.");
                return;
            }
            boolean now = !identity.stealing;
            saved.setStealing(identity, now);
            ExampleMod.sendAdvisoryNotice(player, now
                    ? "Light fingers. The goods come with them when you next summon them out."
                    : "The skimming stops.");
            LOGGER.info("[TM] deceit: steal {} on identity {} (controller {})",
                    now ? "ON" : "OFF", identity.identityId, player.getGameProfile().getName());
            return;
        }

        if (action == Networking.DeceitActionPayload.ACTION_DEBRIEF) {
            if (!identity.planted || identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) {
                ExampleMod.sendAdvisoryNotice(player, "Only a planted sleeper can report.");
                return;
            }
            sendDebrief(player, server, identity);
        }
    }

    /** [Debrief] — the sleeper's report on the victim colony. Plain server
     *  reads; no state change. */
    private static void sendDebrief(ServerPlayer controller, MinecraftServer server,
                                    RaceIdentitySavedData.RaceIdentity identity) {
        ColonyRef ref = findColony(server, identity.colonyId);
        if (ref == null) {
            ExampleMod.sendAdvisoryNotice(controller, "No word comes back.");
            return;
        }
        IColony colony = ref.colony;
        int count = colony.getCitizenManager().getCurrentCitizenCount();
        int max = colony.getCitizenManager().getMaxCitizens();
        double avgHappiness = 0.0;
        int n = 0;
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            try {
                avgHappiness += cd.getCitizenHappinessHandler().getHappiness(colony, cd);
                n++;
            } catch (Throwable ignored) { }
        }
        if (n > 0) avgHappiness /= n;
        double reputation = ReputationManager.getReputation(colony);
        String tier = ReputationTier.forValue(reputation).displayName();
        String barrier = BarrierBlockEntity.describeColonyBarrier(ref.level, colony.getID());
        controller.sendSystemMessage(Component.literal(
                "Whispers from " + colony.getName() + ": "
                        + count + "/" + max + " citizens, mood "
                        + String.format(java.util.Locale.ROOT, "%.1f", avgHappiness)
                        + ", standing " + tier + " ("
                        + String.format(java.util.Locale.ROOT, "%.0f", reputation) + "). "
                        + barrier)
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** [Steal] skim — every 30 s pull a few items from a random warehouse
     *  rack into the identity's loot bag (capped). */
    private static void tickSteal(MinecraftServer server, RaceIdentitySavedData saved,
                                  RaceIdentitySavedData.RaceIdentity identity) {
        ColonyRef ref = findColony(server, identity.colonyId);
        if (ref == null) return;
        long now = ref.level.getGameTime();
        Long last = LAST_STEAL.get(identity.identityId);
        if (last != null && now - last < STEAL_INTERVAL_TICKS) return;
        LAST_STEAL.put(identity.identityId, now);

        if (identity.stolenLoot != null && identity.stolenLoot.size() >= STEAL_LOOT_CAP_STACKS) {
            return; // bag full — keep quiet
        }
        try {
            var warehouses = ref.colony.getServerBuildingManager().getWareHouses();
            if (warehouses.isEmpty()) return;
            var wh = warehouses.get(ref.level.getRandom().nextInt(warehouses.size()));
            java.util.List<net.minecraft.core.BlockPos> racks = wh.getContainers();
            if (racks.isEmpty()) return;
            // Try a few random racks for a non-empty slot.
            for (int attempt = 0; attempt < 4; attempt++) {
                net.minecraft.core.BlockPos rackPos = racks.get(ref.level.getRandom().nextInt(racks.size()));
                if (!(ref.level.getBlockEntity(rackPos)
                        instanceof com.minecolonies.api.tileentities.AbstractTileEntityRack rack)) continue;
                net.neoforged.neoforge.items.IItemHandler inv =
                        rack.getItemHandlerCap((net.minecraft.core.Direction) null);
                if (inv == null) continue;
                int slots = inv.getSlots();
                if (slots == 0) continue;
                int start = ref.level.getRandom().nextInt(slots);
                for (int i = 0; i < slots; i++) {
                    int slot = (start + i) % slots;
                    if (inv.getStackInSlot(slot).isEmpty()) continue;
                    net.minecraft.world.item.ItemStack taken =
                            inv.extractItem(slot, STEAL_MAX_PER_SKIM, false);
                    if (taken.isEmpty()) continue;
                    net.minecraft.nbt.ListTag loot = identity.stolenLoot != null
                            ? identity.stolenLoot : new net.minecraft.nbt.ListTag();
                    loot.add(taken.save(ref.level.registryAccess(), new CompoundTag()));
                    saved.setStolenLoot(identity, loot);
                    LOGGER.info("[TM] deceit: identity {} skimmed {}x {} from colony {}",
                            identity.identityId, taken.getCount(),
                            taken.getItem(), identity.colonyId);
                    return;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] deceit: steal skim failed for identity {}", identity.identityId, t);
        }
    }

    /** Hand the loot bag to the controller once the sleeper is summoned out
     *  (wild body live, controller nearby). Lost if control ends first. */
    private static void tickLootDelivery(MinecraftServer server, RaceIdentitySavedData saved,
                                         RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.stolenLoot == null || identity.stolenLoot.isEmpty()) return;
        if (identity.controlledByUUID == null) {
            saved.setStolenLoot(identity, null); // scheme died with the control
            LOGGER.info("[TM] deceit: identity {} loot bag lost (control ended)",
                    identity.identityId);
            return;
        }
        if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE
                || identity.mobEntityUUID == null) return;
        LivingEntity mob = ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID);
        if (mob == null) return;
        // Delivery happens as soon as the sleeper's wild form exists and the
        // controller is online — no distance requirement (user decision):
        // the goods go straight into the controller's INVENTORY; anything
        // that doesn't fit drops at the controller's feet.
        ServerPlayer controller = server.getPlayerList().getPlayer(identity.controlledByUUID);
        if (controller == null) return;
        int stacks = 0;
        for (int i = 0; i < identity.stolenLoot.size(); i++) {
            try {
                net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack
                        .parse(mob.level().registryAccess(), identity.stolenLoot.getCompound(i))
                        .orElse(net.minecraft.world.item.ItemStack.EMPTY);
                if (stack.isEmpty()) continue;
                if (!controller.getInventory().add(stack)) {
                    controller.drop(stack, false);
                }
                stacks++;
            } catch (Throwable ignored) { }
        }
        saved.setStolenLoot(identity, null);
        if (stacks > 0) {
            controller.sendSystemMessage(Component.literal(
                    citizenNameOf(server, identity) + " hands over the goods.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Armed sleeper: launch when the owner hits a vulnerability window
     *  (Assassins detector, reused as-is). One shot — planted/armed are
     *  cleared at launch; control ends at stand-down. */
    private static void maybeLaunchStrike(MinecraftServer server, RaceIdentitySavedData saved,
                                          RaceIdentitySavedData.RaceIdentity identity) {
        if (ACTIVE_STRIKES.containsKey(identity.identityId)) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(identity.ownerPlayerUUID);
        if (owner == null || !owner.isAlive()) return;
        if (!Assassins.isVulnerable(owner)) return;

        ColonyRef ref = findColony(server, identity.colonyId);
        if (ref == null) return;
        ICitizenData cd = ref.colony.getCitizenManager().getCivilian(identity.citizenId);
        if (cd == null || cd.getEntity().isEmpty()) return; // body unloaded — next window

        var body = cd.getEntity().get();
        // Place-swap to the Tensura body, silently, in place (the raw summon
        // primitive — NOT defenseSwapToSubordinate, whose defendingColony flag
        // would make ColonyThreatResponse immediately swap the striker back).
        boolean swapped = ExampleMod.summonGoblin(null, ref.level, saved, identity,
                ref.colony, cd, body.position(), false);
        if (!swapped) {
            LOGGER.warn("[TM] deceit: strike swap failed for identity {} — will retry next window",
                    identity.identityId);
            return;
        }
        LivingEntity striker = identity.mobEntityUUID != null
                ? ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID) : null;
        if (striker != null) {
            ExampleMod.grantSentient(striker);
            if (striker instanceof net.minecraft.world.entity.Mob mob) {
                mob.setTarget(owner);
                mob.setAggressive(true);
            }
        }
        saved.setStrikeArmed(identity, false); // spent
        saved.setPlanted(identity, false);
        ACTIVE_STRIKES.put(identity.identityId,
                ref.level.getGameTime() + STRIKE_DURATION_TICKS);
        owner.sendSystemMessage(Component.literal(
                citizenNameOf(server, identity) + " turns on you!")
                .withStyle(ChatFormatting.DARK_RED));
        // UNMASKING — Raphael-and-above, and only when the info skill matches
        // or outranks the control skill. Supreme controls stay anonymous.
        InfoSkill ownerInfo = infoSkillOf(owner);
        if (canUnmask(ownerInfo, identity.controlRank)) {
            String who = controllerNameOf(server, identity.controlledByUUID);
            String subject = citizenNameOf(server, identity);
            owner.sendSystemMessage(Component.literal(speak(ownerInfo, "Report",
                    who + " was controlling " + subject + ".",
                    who + " was controlling " + subject + "."))
                    .withStyle(ChatFormatting.RED));
        }
        ServerPlayer controller = identity.controlledByUUID != null
                ? server.getPlayerList().getPlayer(identity.controlledByUUID) : null;
        if (controller != null) {
            controller.sendSystemMessage(Component.literal("The knife strikes.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        LOGGER.info("[TM] deceit: strike LAUNCHED — identity {} vs owner {}",
                identity.identityId, owner.getGameProfile().getName());
    }

    /** Per-second upkeep for a launched strike: re-assert the target; stand
     *  down on timeout, owner death/logout, or striker gone. */
    private static void tickActiveStrike(MinecraftServer server, RaceIdentitySavedData saved,
                                         RaceIdentitySavedData.RaceIdentity identity) {
        Long endTick = ACTIVE_STRIKES.get(identity.identityId);
        if (endTick == null) return;
        LivingEntity striker = identity.mobEntityUUID != null
                ? ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID) : null;
        if (striker == null || !striker.isAlive()) {
            // Striker died — the death hooks already handled the identity.
            ACTIVE_STRIKES.remove(identity.identityId);
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(identity.ownerPlayerUUID);
        long now = striker.level().getGameTime();
        if (owner == null || !owner.isAlive() || now >= endTick) {
            standDownStriker(server, saved, identity, striker);
            return;
        }
        if (striker instanceof net.minecraft.world.entity.Mob mob) {
            mob.setTarget(owner);
            mob.setAggressive(true);
        }
    }

    /** The strike is over (timeout / owner down): the control is SPENT.
     *  Free the mob back to its real owner on the spot. */
    private static void standDownStriker(MinecraftServer server, RaceIdentitySavedData saved,
                                         RaceIdentitySavedData.RaceIdentity identity,
                                         LivingEntity striker) {
        ACTIVE_STRIKES.remove(identity.identityId);
        try {
            ExampleMod.removeSentient(striker);
            if (striker instanceof net.minecraft.world.entity.Mob mob) {
                mob.setTarget(null);
                mob.setAggressive(false);
            }
            striker.removeEffect(io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL);
            ExistenceStorage ex = ExampleMod.readExistence(striker);
            if (ex != null && ex.getTemporaryOwner() != null
                    && !ex.getTemporaryOwner().equals(identity.ownerPlayerUUID)) {
                ex.setTemporaryOwner(null);
                ex.markDirty();
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] deceit: stand-down cleanup failed for identity {}",
                    identity.identityId, t);
        }
        saved.setControlledBy(identity, null);
        saved.setControlBudget(identity, -1L);
        clearDeceitState(server, saved, identity);
        ServerPlayer owner = server.getPlayerList().getPlayer(identity.ownerPlayerUUID);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(
                    citizenNameOf(server, identity) + " comes to their senses.")
                    .withStyle(ChatFormatting.GRAY));
        }
        LOGGER.info("[TM] deceit: strike ended — identity {} freed", identity.identityId);
    }

    /** Clear every deceit flag + transient state + the happiness malus.
     *  Called on every control-end path. */
    private static void clearDeceitState(MinecraftServer server, RaceIdentitySavedData saved,
                                         RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.planted) saved.setPlanted(identity, false);
        if (identity.strikeArmed) saved.setStrikeArmed(identity, false);
        if (identity.stealing) saved.setStealing(identity, false);
        if (identity.controlRank != RANK_NONE) saved.setControlRank(identity, RANK_NONE);
        PENDING_TELLS.remove(identity.identityId);
        TELLS_SENT.remove(identity.identityId);
        LAST_STEAL.remove(identity.identityId);
        try {
            ColonyRef ref = findColony(server, identity.colonyId);
            ICitizenData cd = ref == null ? null
                    : ref.colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd != null && cd.getCitizenHappinessHandler().getModifier(CONTROL_HAPPINESS_ID) != null) {
                cd.getCitizenHappinessHandler().resetModifier(CONTROL_HAPPINESS_ID);
            }
        } catch (Throwable ignored) { }
    }

    /** Apply the "something feels wrong" mood malus once per controlled
     *  IN_COLONY citizen (parked or planted). Removed by clearDeceitState. */
    private static void applyControlHappiness(MinecraftServer server,
                                              RaceIdentitySavedData.RaceIdentity identity) {
        try {
            ColonyRef ref = findColony(server, identity.colonyId);
            ICitizenData cd = ref == null ? null
                    : ref.colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd == null) return;
            if (cd.getCitizenHappinessHandler().getModifier(CONTROL_HAPPINESS_ID) != null) return;
            cd.getCitizenHappinessHandler().addModifier(
                    new com.minecolonies.api.entity.citizen.happiness.StaticHappinessModifier(
                            CONTROL_HAPPINESS_ID, CONTROL_HAPPINESS_WEIGHT,
                            new com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier(
                                    CONTROL_HAPPINESS_VALUE)));
        } catch (Throwable t) {
            LOGGER.warn("[TM] deceit: control-happiness modifier failed", t);
        }
    }

    /** Delayed plant tell — a few minutes after the plant lands, the OWNER
     *  (if online with an information skill) gets one vague warning. */
    private static void tickPlantTell(MinecraftServer server,
                                      RaceIdentitySavedData.RaceIdentity identity) {
        if (TELLS_SENT.contains(identity.identityId)) return;
        long now = server.overworld().getGameTime();
        Long due = PENDING_TELLS.get(identity.identityId);
        if (due == null) {
            long delay = TELL_DELAY_MIN_TICKS + (long) (Math.random()
                    * (TELL_DELAY_MAX_TICKS - TELL_DELAY_MIN_TICKS));
            PENDING_TELLS.put(identity.identityId, now + delay);
            return;
        }
        if (now < due) return;
        PENDING_TELLS.remove(identity.identityId);
        ServerPlayer owner = server.getPlayerList().getPlayer(identity.ownerPlayerUUID);
        if (owner == null) return; // offline — retry scheduling next tick
        InfoSkill info = infoSkillOf(owner);
        if (info.rank() < RANK_UNIQUE) { TELLS_SENT.add(identity.identityId); return; }
        String subject = citizenNameOf(server, identity);
        owner.sendSystemMessage(Component.literal(speak(info, "Notice",
                subject + "'s behavior contains irregularities.",
                "Irregularity detected in " + subject + "'s behavior."))
                .withStyle(ChatFormatting.YELLOW));
        // A Raphael-tier sight that outranks the control ALSO names the hand.
        if (canUnmask(info, identity.controlRank)) {
            String who = controllerNameOf(server, identity.controlledByUUID);
            owner.sendSystemMessage(Component.literal(speak(info, "Report",
                    who + " was controlling " + subject + ".",
                    who + " was controlling " + subject + "."))
                    .withStyle(ChatFormatting.RED));
        } else if (info.rank() >= RANK_GOD && identity.controlRank >= RANK_SUPREME) {
            // God-tier sight can't name a SUPREME hand, but it KNOWS what
            // kind of power it's looking at (user decision 2026-09-05).
            // God-tier info skills are all silent tools — output format.
            owner.sendSystemMessage(Component.literal(speak(info, "Warning",
                    "The will binding " + subject + " exceeds analysis.",
                    "The will binding " + subject + " exceeds analysis."))
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
        TELLS_SENT.add(identity.identityId);
    }

    /**
     * 5 s pass (AMBIENT block): sync suspicion nameplate flags to each OWNER
     * for their controlled/planted IN_COLONY citizens' bodies. Diff-based;
     * the client additionally gates rendering on its own info skill.
     */
    static void tickSuspicionSync(MinecraftServer server) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(server.overworld());
        java.util.Map<UUID, java.util.Set<UUID>> current = new java.util.HashMap<>();
        for (RaceIdentitySavedData.RaceIdentity identity : saved.all()) {
            if (identity.controlledByUUID == null || identity.ownerPlayerUUID == null) continue;
            if (identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
            ColonyRef ref = findColony(server, identity.colonyId);
            ICitizenData cd = ref == null ? null
                    : ref.colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd == null || cd.getEntity().isEmpty()) continue;
            current.computeIfAbsent(identity.ownerPlayerUUID, k -> new java.util.HashSet<>())
                    .add(cd.getEntity().get().getUUID());
        }
        java.util.Set<UUID> players = new java.util.HashSet<>(current.keySet());
        players.addAll(SUSPICION_SENT.keySet());
        for (UUID playerId : players) {
            ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
            java.util.Set<UUID> now = current.getOrDefault(playerId, java.util.Set.of());
            java.util.Set<UUID> before = SUSPICION_SENT.getOrDefault(playerId, java.util.Set.of());
            if (sp == null) { SUSPICION_SENT.remove(playerId); continue; } // client cleared on logout
            for (UUID e : now) {
                if (!before.contains(e)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                            new Networking.SyncSuspicionFlagPayload(e, true));
                }
            }
            for (UUID e : before) {
                if (!now.contains(e)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                            new Networking.SyncSuspicionFlagPayload(e, false));
                }
            }
            if (now.isEmpty()) SUSPICION_SENT.remove(playerId);
            else SUSPICION_SENT.put(playerId, new java.util.HashSet<>(now));
        }
    }

    /**
     * The colonist-mode budget for a control that just landed on this mob:
     * MIND_CONTROL effect duration × {@link #COLONIST_BUDGET_PER_EFFECT_TICK},
     * capped at {@link #COLONIST_CONTROL_MAX_TICKS}. No effect (Lemegeton
     * seal, King's Authority, permanent Charisma) or an unreadable one →
     * {@link #BUDGET_INDEFINITE}: those controls stay indefinite in both
     * forms, per the user's 2026-09-05 decision ("if that needs to change we
     * can do that in the future").
     */
    private static long computeColonistBudget(LivingEntity mob) {
        try {
            var effect = mob.getEffect(
                    io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL);
            if (effect != null && effect.getDuration() > 0) {
                return Math.min(COLONIST_CONTROL_MAX_TICKS,
                        (long) (effect.getDuration() * COLONIST_BUDGET_PER_EFFECT_TICK));
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] mind-control: could not read control effect duration", t);
        }
        return BUDGET_INDEFINITE;
    }

    /** Rank of the skill controlling this mob, read from the MIND_CONTROL
     *  effect's source ability. No effect = the indefinite controls =
     *  SUPREME. Unknown-but-effect-based defaults to ultimate tier. */
    private static byte computeControlRank(LivingEntity mob) {
        try {
            var effect = mob.getEffect(
                    io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL);
            if (effect == null) return RANK_SUPREME;
            if (effect instanceof io.github.manasmods.tensura.effect.template.TensuraMobEffectInstance duck
                    && duck.tensura$hasAbility()) {
                var skill = duck.tensura$getSourceAbility().getSkill();
                if (skill != null && skill.getRegistryName() != null) {
                    String id = skill.getRegistryName().toString();
                    Byte mapped = CONTROL_SKILL_RANKS.get(id);
                    if (mapped != null) return mapped;
                    if (id.contains("charm") || id.contains("tempt")) return RANK_UNIQUE;
                }
            }
            return RANK_ULTIMATE; // effect present, source unknown — middle tier
        } catch (Throwable t) {
            LOGGER.warn("[TM] mind-control: control-rank read failed", t);
            return RANK_ULTIMATE;
        }
    }

    /** The strongest information skill this player holds (rank + speaker
     *  name), or {@link #NO_INFO}. Great Sage is rank 1 — it detects and
     *  warns but NEVER unmasks (user rule: Raphael and above). */
    static InfoSkill infoSkillOf(ServerPlayer player) {
        try {
            var storage = io.github.manasmods.manascore.skill.api.SkillAPI.getSkillsFrom(player);
            InfoSkill best = NO_INFO;
            // INFO_SKILLS are all TR:N skills — hidden behind the dev gate.
            if (TrnGate.trnActive()) for (var entry : INFO_SKILLS) {
                if (storage.getSkill(entry.getKey()).isPresent()
                        && entry.getValue().rank() > best.rank()) {
                    best = entry.getValue();
                }
            }
            if (best.rank() < RANK_UNIQUE
                    && SkillUtils.hasSkill(player, UniqueSkills.GREAT_SAGE.get())) {
                best = new InfoSkill(RANK_UNIQUE, "Great Sage", true);
            } else if (best.rank() == RANK_UNIQUE
                    && SkillUtils.hasSkill(player, UniqueSkills.GREAT_SAGE.get())
                    && "Investigator".equals(best.name())) {
                best = new InfoSkill(RANK_UNIQUE, "Great Sage", true); // prefer the sage voice
            }
            return best;
        } catch (Throwable t) {
            LOGGER.warn("[TM] info-skill query failed for {}", player.getGameProfile().getName(), t);
            return NO_INFO;
        }
    }

    /** Unmask rule: Raphael-and-above, and the info skill must match or
     *  outrank the control skill. Legacy identities with no recorded rank
     *  count as ultimate. Supreme is never unmasked. */
    static boolean canUnmask(InfoSkill info, byte controlRank) {
        byte effective = controlRank == RANK_NONE ? RANK_ULTIMATE : controlRank;
        return info.rank() >= RANK_ULTIMATE && info.rank() >= effective;
    }

    /** The controller's display name, online or not ("an unseen hand" when
     *  unresolvable). */
    private static String controllerNameOf(MinecraftServer server, UUID controller) {
        if (controller == null) return "an unseen hand";
        ServerPlayer online = server.getPlayerList().getPlayer(controller);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache() != null
                ? server.getProfileCache().get(controller)
                        .map(com.mojang.authlib.GameProfile::getName).orElse("an unseen hand")
                : "an unseen hand";
    }

    /** Citizen happiness (0–10) or -1 when unreadable. */
    private static double citizenHappinessOf(MinecraftServer server,
                                             RaceIdentitySavedData.RaceIdentity identity) {
        try {
            ColonyRef ref = findColony(server, identity.colonyId);
            ICitizenData cd = ref == null ? null
                    : ref.colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd == null) return -1.0;
            return cd.getCitizenHappinessHandler().getHappiness(ref.colony, cd);
        } catch (Throwable t) {
            return -1.0;
        }
    }

    // ------------------------------------------------------------------
    // Interrogation — the citizen-window [?] button (design: user spec
    // 2026-09-05). Truth follows the unmasking ranks; truth costs magicule
    // and BREAKS the control.
    // ------------------------------------------------------------------

    static void handleInterrogate(ServerPlayer player, int citizenEntityId) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        if (!(level.getEntity(citizenEntityId)
                instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen)) {
            return;
        }
        ICitizenData cd = citizen.getCitizenData();
        if (cd == null || cd.getColony() == null) return;
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity =
                saved.getByColonyAndCitizen(cd.getColony().getID(), cd.getId());
        String name = cd.getName();

        // Only the citizen's owner questions their own people.
        if (identity == null || identity.ownerPlayerUUID == null
                || !player.getUUID().equals(identity.ownerPlayerUUID)
                || identity.controlledByUUID == null) {
            sendCitizenLine(player, name, dismissiveLine(level));
            return;
        }

        InfoSkill info = infoSkillOf(player);
        if (!canUnmask(info, identity.controlRank)) {
            // The control outranks the questioner's sight (or they lack
            // Raphael-tier insight) — the citizen holds the line. The
            // dismissal itself IS the intel: something is shielding them.
            sendCitizenLine(player, name, dismissiveLine(level));
            if (info.rank() >= RANK_GOD && identity.controlRank >= RANK_SUPREME) {
                player.sendSystemMessage(Component.literal(speak(info, "Warning",
                        "The will binding " + name + " exceeds analysis.",
                        "The will binding " + name + " exceeds analysis."))
                        .withStyle(ChatFormatting.DARK_PURPLE));
            }
            return;
        }

        byte effectiveRank = identity.controlRank == RANK_NONE
                ? RANK_ULTIMATE : identity.controlRank;
        double cost = INTERROGATE_COST_PER_RANK * effectiveRank;
        ExistenceStorage playerExist = ExampleMod.readExistence(player);
        if (playerExist == null || playerExist.getMagicule() < cost) {
            sendCitizenLine(player, name, dismissiveLine(level));
            if (info.name() != null) {
                String needed = String.format(java.util.Locale.ROOT, "%.0f", cost);
                player.sendSystemMessage(Component.literal(speak(info, "Answer",
                        "They are concealing something. Required magicule: " + needed + ".",
                        "Concealment detected. Required magicule: " + needed + "."))
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }
        playerExist.setMagicule(playerExist.getMagicule() - cost);
        playerExist.markDirty();

        String controllerName = controllerNameOf(server, identity.controlledByUUID);
        sendCitizenLine(player, name,
                "I— I couldn't stop myself… it was " + controllerName + "! Their will held my strings!");
        if (info.name() != null) {
            player.sendSystemMessage(Component.literal(speak(info, "Report",
                    "External control over " + name + " has been severed.",
                    "External control over " + name + " severed."))
                    .withStyle(ChatFormatting.AQUA));
        }
        // Break the control: end it exactly like a budget expiry — scrub the
        // stale snapshot control, restore the mood, drop any deceit orders.
        saved.setControlledBy(identity, null);
        saved.setControlBudget(identity, -1L);
        saved.setControlRank(identity, RANK_NONE);
        saved.setScrubControl(identity, true);
        clearDeceitState(server, saved, identity);
        LOGGER.info("[TM] interrogation: identity {} freed by {} (cost {})",
                identity.identityId, player.getGameProfile().getName(), cost);
    }

    private static void sendCitizenLine(ServerPlayer player, String citizenName, String line) {
        player.sendSystemMessage(Component.literal(citizenName + ": " + line)
                .withStyle(ChatFormatting.WHITE));
    }

    private static final String[] DISMISSIVE_LINES = {
            "Everything is fine! Truly. May I get back to work now?",
            "Hm? I have been here all day, I promise!",
            "You worry too much. The colony keeps me busy, that is all.",
            "Strange? Me? I simply did not sleep well." };

    private static String dismissiveLine(ServerLevel level) {
        return DISMISSIVE_LINES[level.getRandom().nextInt(DISMISSIVE_LINES.length)];
    }

    // ------------------------------------------------------------------
    // The stolen-subordinate suite: request/approval, Release, and the
    // barrier Cleanse button (user redesign 2026-09-05).
    // ------------------------------------------------------------------

    /** True while OUR code is writing Tensura permanent owners (a release) —
     *  TrNightmareCompat's owner-change guard must not treat it as a steal. */
    static volatile boolean internalOwnerWrite = false;

    /** Pending summon/send requests from an EX-owner, awaiting the current
     *  owner's leave. Transient: id → request. */
    record OwnerRequest(UUID identityId, UUID requester, long expiresAtTick) {}
    private static final java.util.Map<Integer, OwnerRequest> PENDING_REQUESTS = new java.util.HashMap<>();
    private static int nextRequestId = 1;
    private static final long REQUEST_TIMEOUT_TICKS = 1_200L; // 60 s

    /** The Release attempt is 3× an interrogation of equal rank (user
     *  decision 2026-09-05 — reclaiming a whole subordinate should hurt). */
    private static final double RELEASE_COST_PER_RANK = INTERROGATE_COST_PER_RANK * 3.0;

    /**
     * The EX-owner clicked a "(stolen)" roster row: don't act — ask. The
     * current owner gets a clickable Allow/Deny prompt; on Allow the action
     * runs AS the requester through the normal chokepoint.
     */
    static void requestActOnStolen(ServerPlayer requester, RaceIdentitySavedData.RaceIdentity identity) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        MinecraftServer server = requester.getServer();
        ServerPlayer owner = identity.ownerPlayerUUID != null
                ? server.getPlayerList().getPlayer(identity.ownerPlayerUUID) : null;
        if (owner == null) {
            ExampleMod.sendAdvisoryNotice(requester,
                    "Their current master is beyond reach — try again later.");
            return;
        }
        long now = requester.serverLevel().getGameTime();
        PENDING_REQUESTS.values().removeIf(r -> now >= r.expiresAtTick());
        // One live request per identity.
        for (OwnerRequest r : PENDING_REQUESTS.values()) {
            if (r.identityId().equals(identity.identityId)) {
                ExampleMod.sendAdvisoryNotice(requester, "You already await their answer.");
                return;
            }
        }
        int id = nextRequestId++;
        PENDING_REQUESTS.put(id, new OwnerRequest(identity.identityId,
                requester.getUUID(), now + REQUEST_TIMEOUT_TICKS));
        String action = identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE ? "send" : "summon";
        String name = citizenNameOf(server, identity);
        Component prompt = Component.literal(
                requester.getGameProfile().getName() + " is attempting to " + action
                        + " " + name + ". Allow? ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("[Allow]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tmrequest allow " + id))))
                .append(Component.literal(" "))
                .append(Component.literal("[Deny]").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tmrequest deny " + id))));
        owner.sendSystemMessage(prompt);
        ExampleMod.sendAdvisoryNotice(requester,
                name + " looks to their master for leave…");
    }

    /** /tmrequest allow|deny <id> — only the identity's CURRENT owner may
     *  answer. On allow, the action executes as the requester with the
     *  ownership gate bypassed. */
    static void answerRequest(ServerPlayer answering, int requestId, boolean allow) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        MinecraftServer server = answering.getServer();
        OwnerRequest request = PENDING_REQUESTS.get(requestId);
        long now = answering.serverLevel().getGameTime();
        if (request == null || now >= request.expiresAtTick()) {
            PENDING_REQUESTS.remove(requestId);
            ExampleMod.sendAdvisoryNotice(answering, "That request has lapsed.");
            return;
        }
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(server.overworld());
        RaceIdentitySavedData.RaceIdentity identity = saved.getById(request.identityId());
        if (identity == null || identity.ownerPlayerUUID == null
                || !answering.getUUID().equals(identity.ownerPlayerUUID)) {
            ExampleMod.sendAdvisoryNotice(answering, "That is not yours to answer.");
            return;
        }
        PENDING_REQUESTS.remove(requestId);
        ServerPlayer requester = server.getPlayerList().getPlayer(request.requester());
        if (!allow) {
            if (requester != null) {
                ExampleMod.sendAdvisoryNotice(requester,
                        answering.getGameProfile().getName() + " denies you.");
            }
            return;
        }
        if (requester == null) return; // asked, then logged off
        ExampleMod.handleMenuAction(requester, identity.identityId, /*approvedByOwner=*/true);
    }

    /**
     * The [Release] attempt — the EX-owner tries to break a stolen
     * subordinate free. Same sight rules as unmasking (Raphael-and-above,
     * info ≥ steal rank), costs magicule on success, and transfers the whole
     * identity back — including the Tensura-side permanent owner.
     */
    static void handleRelease(ServerPlayer player, int entityId) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = null;
        net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen) {
            ICitizenData cd = citizen.getCitizenData();
            if (cd != null && cd.getColony() != null) {
                identity = saved.getByColonyAndCitizen(cd.getColony().getID(), cd.getId());
            }
        } else if (entity != null) {
            identity = saved.getByMobUUID(entity.getUUID());
        }
        if (identity == null || identity.previousOwnerUUID == null
                || !player.getUUID().equals(identity.previousOwnerUUID)
                || player.getUUID().equals(identity.ownerPlayerUUID)) {
            ExampleMod.sendAdvisoryNotice(player, "They were never yours to free.");
            return;
        }
        InfoSkill info = infoSkillOf(player);
        byte effectiveRank = identity.controlRank == RANK_NONE
                ? RANK_ULTIMATE : identity.controlRank;
        if (!canUnmask(info, effectiveRank)) {
            ExampleMod.sendAdvisoryNotice(player,
                    "They do not answer — a higher will binds them.");
            return;
        }
        double cost = RELEASE_COST_PER_RANK * effectiveRank;
        ExistenceStorage playerExist = ExampleMod.readExistence(player);
        if (playerExist == null || playerExist.getMagicule() < cost) {
            ExampleMod.sendAdvisoryNotice(player,
                    "The bond resists — you need "
                            + String.format(java.util.Locale.ROOT, "%.0f", cost)
                            + " magicule to break it.");
            return;
        }
        playerExist.setMagicule(playerExist.getMagicule() - cost);
        playerExist.markDirty();

        // Tensura-side reclaim on the live wild body (if any) — guarded so
        // TrNightmareCompat's steal detector ignores our own write.
        if (identity.mobEntityUUID != null) {
            LivingEntity mob = ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID);
            if (mob != null) {
                internalOwnerWrite = true;
                try {
                    ExistenceStorage ex = ExampleMod.readExistence(mob);
                    if (ex != null) {
                        ex.setPermanentOwner(player.getUUID());
                        ex.setTemporaryOwner(null);
                        ex.markDirty();
                    }
                    if (mob instanceof io.github.manasmods.tensura.entity.template.subclass.ISubordinate sub) {
                        sub.resetOwner(player.getUUID());
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[TM] release: Tensura-side reclaim failed for identity {}",
                            identity.identityId, t);
                } finally {
                    internalOwnerWrite = false;
                }
            }
        }
        transferPermanent(server, saved, identity, player.getUUID(), /*isRelease=*/true);
        LOGGER.info("[TM] release: identity {} reclaimed by {} (cost {})",
                identity.identityId, player.getGameProfile().getName(), cost);
    }

    // ------------------------------------------------------------------
    // The barrier CLEANSE button (replaces the passive layer-3 cleanse)
    // ------------------------------------------------------------------

    /** Per-effect cleanse pricing — doubled 2026-09-05 (user: "it should be
     *  costly", may rise again). ⚠ BALANCE GUESSES. */
    private static final double CLEANSE_COST_MILD = 500.0;     // slowness, weakness, …
    private static final double CLEANSE_COST_SEVERE = 1_500.0; // wither, poison, modded debuffs
    private static final double CLEANSE_COST_CONTROL_PER_RANK = INTERROGATE_COST_PER_RANK * 2.0;

    private static double debuffCost(net.minecraft.world.effect.MobEffectInstance effect) {
        var holder = effect.getEffect();
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(holder.value());
        if (id == null) return CLEANSE_COST_SEVERE;
        if (!"minecraft".equals(id.getNamespace())) return CLEANSE_COST_SEVERE; // Tensura/TRN debuffs bite harder
        return switch (id.getPath()) {
            case "wither", "poison", "darkness", "levitation" -> CLEANSE_COST_SEVERE;
            default -> CLEANSE_COST_MILD;
        };
    }

    /**
     * The core-menu [Cleanse]: purge ALL debuffs from friendlies inside the
     * field, each priced by severity; mind control is purged ONLY where the
     * clicking player's information skill can see it (the unmasking rules —
     * so SUPREME controls pass through untouched). All-or-nothing: the total
     * is charged up front from the clicker's magicule.
     */
    static void handleBarrierCleanse(ServerPlayer player, ServerLevel level,
                                     net.minecraft.world.phys.Vec3 center, double radius,
                                     int colonyId) {
        if (!TrnGate.espionage()) return; // hidden dev gate — see TrnGate
        MinecraftServer server = level.getServer();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        InfoSkill info = infoSkillOf(player);

        // Pass 1 — price everything the cleanse WOULD do.
        record MobWork(LivingEntity mob, List<net.minecraft.world.effect.MobEffectInstance> debuffs,
                       RaceIdentitySavedData.RaceIdentity controlled) {}
        List<MobWork> work = new ArrayList<>();
        double total = 0.0;
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class,
                net.minecraft.world.phys.AABB.ofSize(center, radius * 2, radius * 2, radius * 2))) {
            double dx = living.getX() - center.x;
            double dy = (living.getY() + living.getBbHeight() * 0.5) - center.y;
            double dz = living.getZ() - center.z;
            if (dx * dx + dy * dy + dz * dz >= radius * radius) continue;
            if (living.hasData(Attachments.RAID_TAG.get())) continue; // never heal raiders
            List<net.minecraft.world.effect.MobEffectInstance> debuffs = new ArrayList<>();
            for (net.minecraft.world.effect.MobEffectInstance effect : living.getActiveEffects()) {
                if (effect.getEffect().value().getCategory()
                        != net.minecraft.world.effect.MobEffectCategory.HARMFUL) continue;
                debuffs.add(effect);
            }
            RaceIdentitySavedData.RaceIdentity controlled = saved.getByMobUUID(living.getUUID());
            if (controlled != null && controlled.controlledByUUID != null) {
                byte rank = controlled.controlRank == RANK_NONE ? RANK_ULTIMATE : controlled.controlRank;
                if (canUnmask(info, rank)) {
                    total += CLEANSE_COST_CONTROL_PER_RANK * rank;
                } else {
                    controlled = null; // cannot see it — passes through
                }
            } else {
                controlled = null;
            }
            if (debuffs.isEmpty() && controlled == null) continue;
            for (var effect : debuffs) {
                // MIND_CONTROL is priced through the control path, not per-effect.
                total += isMindControl(effect) ? 0.0 : debuffCost(effect);
            }
            work.add(new MobWork(living, debuffs, controlled));
        }
        // The colony's own PLANTED sleepers (no live body to scan).
        List<RaceIdentitySavedData.RaceIdentity> plantedFrees = new ArrayList<>();
        if (colonyId >= 0) {
            for (RaceIdentitySavedData.RaceIdentity identity : new ArrayList<>(saved.all())) {
                if (identity.controlledByUUID == null) continue;
                if (identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
                if (identity.colonyId != colonyId || identity.controlParked) continue;
                byte rank = identity.controlRank == RANK_NONE ? RANK_ULTIMATE : identity.controlRank;
                if (!canUnmask(info, rank)) continue; // unseen — stays hidden
                total += CLEANSE_COST_CONTROL_PER_RANK * rank;
                plantedFrees.add(identity);
            }
        }
        if (work.isEmpty() && plantedFrees.isEmpty()) {
            ExampleMod.sendAdvisoryNotice(player, "The field finds nothing to cleanse.");
            return;
        }
        ExistenceStorage playerExist = ExampleMod.readExistence(player);
        if (playerExist == null || playerExist.getMagicule() < total) {
            ExampleMod.sendAdvisoryNotice(player, "Cleansing this would take "
                    + String.format(java.util.Locale.ROOT, "%.0f", total)
                    + " magicule — more than you can channel right now.");
            return;
        }
        playerExist.setMagicule(playerExist.getMagicule() - total);
        playerExist.markDirty();

        // Pass 2 — do it.
        int purged = 0;
        for (MobWork w : work) {
            for (var effect : w.debuffs()) {
                if (isMindControl(effect) && w.controlled() == null) continue; // unseen control
                w.mob().removeEffect(effect.getEffect());
                purged++;
            }
            if (w.controlled() != null) {
                try {
                    ExistenceStorage ex = ExampleMod.readExistence(w.mob());
                    if (ex != null && ex.getTemporaryOwner() != null
                            && !ex.getTemporaryOwner().equals(w.controlled().ownerPlayerUUID)) {
                        ex.setTemporaryOwner(null);
                        ex.markDirty();
                    }
                } catch (Throwable ignored) { }
                saved.setControlledBy(w.controlled(), null);
                saved.setControlBudget(w.controlled(), -1L);
                saved.setControlRank(w.controlled(), RANK_NONE);
                clearDeceitState(server, saved, w.controlled());
                purged++;
            }
        }
        for (RaceIdentitySavedData.RaceIdentity identity : plantedFrees) {
            saved.setControlledBy(identity, null);
            saved.setControlBudget(identity, -1L);
            saved.setControlRank(identity, RANK_NONE);
            saved.setScrubControl(identity, true);
            clearDeceitState(server, saved, identity);
            purged++;
        }
        ExampleMod.sendAdvisoryNotice(player, "The field pulses clean — "
                + purged + " affliction(s) purged ("
                + String.format(java.util.Locale.ROOT, "%.0f", total) + " magicule).");
        LOGGER.info("[TM] cleanse: {} purged {} afflictions for {} magicule (colony {})",
                player.getGameProfile().getName(), purged, total, colonyId);
    }

    private static boolean isMindControl(net.minecraft.world.effect.MobEffectInstance effect) {
        try {
            return effect.getEffect().value()
                    == io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Roster-name suffix for an entry the viewer CONTROLS: " (controlled)"
     * or " (controlled, 43m)" with the remaining time of whichever clock is
     * active — the parked colonist budget, or the live wild body's control
     * effect. Indefinite controls (and unreadable states) show no time.
     */
    static String controlSuffix(MinecraftServer server,
                                RaceIdentitySavedData.RaceIdentity identity) {
        long remaining = -1L;
        if (identity.mode == RaceIdentitySavedData.Mode.IN_COLONY && identity.controlParked) {
            remaining = identity.controlBudgetTicks; // -1 = indefinite
        } else if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                && identity.mobEntityUUID != null) {
            try {
                LivingEntity mob = ExampleMod.findLivingEntityAcrossLevels(server, identity.mobEntityUUID);
                if (mob != null) {
                    var effect = mob.getEffect(
                            io.github.manasmods.tensura.registry.effect.TensuraMobEffects.MIND_CONTROL);
                    if (effect != null && effect.getDuration() > 0) {
                        remaining = effect.getDuration();
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[TM] mind-control: could not read control time for roster", t);
            }
        }
        if (remaining < 0) return " (controlled)";
        long seconds = remaining / 20L;
        String time = seconds >= 60 ? ((seconds + 59) / 60) + "m" : seconds + "s";
        return " (controlled, " + time + ")";
    }

    // ------------------------------------------------------------------
    // Information-skill gate
    // ------------------------------------------------------------------

    /**
     * The name of the information-centred ability that would warn this player
     * about hostile manipulation of their subordinates — used as the warning's
     * speaker prefix — or null when they hold none (no warning). Plain Sage
     * deliberately does NOT qualify (user decision — the warning is a
     * high-tier perk). Detection/warning needs ANY rank ≥ 1; UNMASKING
     * additionally needs {@link #canUnmask}.
     */
    static String infoSkillName(ServerPlayer player) {
        InfoSkill info = infoSkillOf(player);
        return info.rank() >= RANK_UNIQUE ? info.name() : null;
    }

    // ------------------------------------------------------------------
    // Small lookups
    // ------------------------------------------------------------------

    private record ColonyRef(IColony colony, ServerLevel level) {}

    private static ColonyRef findColony(MinecraftServer server, int colonyId) {
        for (ServerLevel level : server.getAllLevels()) {
            IColony c = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (c != null) return new ColonyRef(c, level);
        }
        return null;
    }

    private static IColony findOwnerColony(MinecraftServer server, UUID owner) {
        if (owner == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            IColony c = IColonyManager.getInstance().getIColonyByOwner(level, owner);
            if (c != null) return c;
        }
        return null;
    }

    private static ServerLevel destColonyLevel(MinecraftServer server, IColony colony) {
        for (ServerLevel level : server.getAllLevels()) {
            if (IColonyManager.getInstance().getColonyByWorld(colony.getID(), level) == colony) {
                return level;
            }
        }
        // Fallback — MC colonies live in the overworld in practice.
        return server.overworld();
    }

    private static String citizenNameOf(MinecraftServer server, RaceIdentitySavedData.RaceIdentity identity) {
        ColonyRef ref = findColony(server, identity.colonyId);
        if (ref != null) {
            ICitizenData cd = ref.colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd != null) return cd.getName();
        }
        return "Your subordinate";
    }
}
