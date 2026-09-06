package com.example.examplemod;

import com.github.hvnbael.trnightmare.event.OwnerChangeEvent;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * TR: Nightmare (`trnightmare`) integration — OPTIONAL dependency.
 *
 * ⚠ CLASS-ISOLATION RULE: this is the ONLY class allowed to import
 * `com.github.hvnbael.trnightmare.*`. It is loaded exclusively via
 * {@link #init()} behind {@code ModList.get().isLoaded("trnightmare")} in
 * {@link ExampleMod#commonSetup} — the JVM never links it (or the TR:N
 * classes it references) when the mod is absent. Never reference this class
 * from a static initializer or an always-loaded code path.
 *
 * What it does: subscribes to TR:N's {@code OwnerChangeEvent} — a cancellable
 * NeoForge event TR:N fires at the HEAD of Tensura's
 * {@code ExistenceStorage.setPermanentOwner/setTemporaryOwner} for EVERY
 * entity and caller — and defends/serves our identity-registered mobs:
 *
 *  - PERMANENT write, tamer = a DIFFERENT live player  → the Greed-ego steal
 *    (or any equivalent). We let the write land, then hand the identity to
 *    {@link MindControlTracker#transferPermanent} next tick: citizen moves to
 *    the thief's colony (or their pending pool), both players notified.
 *  - PERMANENT write, tamer = null or not a live player → VETO. This is the
 *    Pure-Heart-liberation / deal-`make_owner=false` shape: nothing may clear
 *    or non-player-own a citizen's mob silently. (Pure Heart itself is
 *    unregistered dead code in TR:N 1.0.3.2.8; the veto future-proofs. The
 *    Pure Heart "defection" feature is designed but DEFERRED —
 *    docs/tr-nightmare-integration.md §2.)
 *  - PERMANENT write, tamer = current owner → allowed (naming re-stamps etc.).
 *  - TEMPORARY writes → ignored here. Mind control is mirrored by
 *    {@link MindControlTracker#tick}'s per-second poll of the live mob, which
 *    also covers base-Tensura charm skills that exist without TR:N.
 */
public final class TrNightmareCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrNightmareCompat() {}

    public static void init() {
        NeoForge.EVENT_BUS.register(new TrNightmareCompat());
        LOGGER.info("[TM] TR:Nightmare detected — owner-change guard active "
                + "(citizen mobs protected from silent owner clears; permanent "
                + "steals transfer the whole identity)");
        // Operator advisory — TR:N's `EntityAwakening` gamerule defaults ON:
        // any NON-player majin that kills something at ≥200k EP becomes a
        // Demon Lord Seed (and a True Demon Lord at 10M soul points). Our
        // EP-buffed garrison bosses, assassin bodies, and 10k-EP raid
        // defenders are exactly such killers, and the flag shifts Tensura
        // stat scaling + this mod's majin/human race-side classification.
        // deps/tr-nightmare.md §5. Disable with /gamerule EntityAwakening
        // false if faction balance drifts.
        LOGGER.info("[TM] TR:Nightmare note: gamerule 'EntityAwakening' (TR:N, default ON) "
                + "can turn this mod's buffed mobs into Demon Lord Seeds on their first "
                + "high-EP kill — '/gamerule EntityAwakening false' to opt out");
    }

    @SubscribeEvent
    public void onOwnerChange(OwnerChangeEvent event) {
        if (MindControlTracker.internalOwnerWrite) return; // our own Release writing owners
        if (!event.isPermanent()) return; // temp control handled by the tracker poll
        LivingEntity tamed = event.getTamed();
        if (tamed == null || tamed.level().isClientSide()) return;
        if (!(tamed.level() instanceof ServerLevel level)) return;

        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = saved.getByMobUUID(tamed.getUUID());
        if (identity == null || identity.ownerPlayerUUID == null) {
            // Not one of ours (yet) — this may be TR:N DEAL-NAMING a race mob
            // (contracts name mobs WITHOUT firing Tensura's NAMING_EVENT, so
            // our onRaceNamed never runs — docs/tr-nightmare-integration.md
            // §5). Adopt it into the citizen pipeline. Option 2, user decision
            // 2026-09-05.
            maybeAdoptDealNamed(level, tamed, event.getTamer());
            return;
        }

        UUID tamer = event.getTamer();
        if (identity.ownerPlayerUUID.equals(tamer)) return; // owner re-stamp — fine

        MinecraftServer server = level.getServer();
        boolean tamerIsLivePlayer = tamer != null
                && server.getPlayerList().getPlayer(tamer) != null;
        // Also accept a loaded non-player-list Player entity, defensively.
        if (!tamerIsLivePlayer && tamer != null) {
            tamerIsLivePlayer = level.getEntity(tamer) instanceof Player;
        }

        if (!tamerIsLivePlayer) {
            // Owner CLEAR or a non-player owner — veto. A citizen's mob may
            // not be silently freed or re-owned by something we can't route.
            event.setCanceled(true);
            LOGGER.info("[TM] trnightmare: VETOED permanent owner write on identity {} "
                    + "(tamer={} — null or not a live player)", identity.identityId, tamer);
            return;
        }

        // A live player is permanently stealing a registered mob (Greed ego —
        // or anything with the same shape). Let the Tensura-side write land;
        // move the identity + citizen at the END of this tick, outside the
        // skill handler we're nested in.
        final UUID thief = tamer;
        final UUID identityId = identity.identityId;
        server.execute(() -> {
            RaceIdentitySavedData savedNow = RaceIdentitySavedData.get(server.overworld());
            RaceIdentitySavedData.RaceIdentity current = savedNow.getById(identityId);
            if (current == null) return;                       // died/removed meanwhile
            if (thief.equals(current.ownerPlayerUUID)) return; // already transferred
            MindControlTracker.transferPermanent(server, savedNow, current, thief);
        });
    }

    /**
     * Deal-naming adoption. A permanent owner write on an UNREGISTERED race
     * mob by a live player is either (a) Tensura's normal naming pipeline —
     * in which case onRaceNamed already ran this tick and created the
     * identity — or (b) a TR:N Deal-Maker naming contract, which set the
     * existence name directly and fired no NAMING_EVENT. Deferring to the
     * end of the tick and re-checking lets (a) fall through harmlessly and
     * catches exactly (b): a still-unregistered, named, race-typed mob.
     *
     * NOT adopted (deliberate): mobs with no existence name (a Greed-ego
     * escalation can permanently own a never-named tame — no name, no
     * citizen), envoys, blocked types (orc lord / disaster), and
     * `make_owner=false` naming deals (they CLEAR owners — vetoed upstream
     * for registered mobs, and ownerless mobs give adoption nothing to key
     * on).
     */
    private static void maybeAdoptDealNamed(ServerLevel level, LivingEntity tamed, UUID tamer) {
        if (tamer == null) return;
        Race race = Races.of(tamed.getType());
        if (race == null || Races.isBlocked(tamed.getType())) return;
        if (tamed.hasData(Attachments.ENVOY_TAG.get())) return; // envoys never become citizens
        MinecraftServer server = level.getServer();
        if (server.getPlayerList().getPlayer(tamer) == null
                && !(level.getEntity(tamer) instanceof Player)) {
            return; // non-player owner — nothing to adopt for
        }

        final UUID mobUUID = tamed.getUUID();
        final UUID ownerUUID = tamer;
        server.execute(() -> {
            if (!tamed.isAlive() || tamed.isRemoved()) return;
            RaceIdentitySavedData savedNow = RaceIdentitySavedData.get(server.overworld());
            if (savedNow.getByMobUUID(mobUUID) != null) return; // normal naming got here first
            for (RaceIdentitySavedData.PendingRaceMob queued : savedNow.getPending()) {
                if (mobUUID.equals(queued.mobEntityUUID)) return; // already queued pre-colony
            }
            // Only a NAMED mob is a naming (the deal writes the existence name
            // before the owner). Owner unchanged-or-cleared by now → skip too.
            var existence = ExampleMod.readExistence(tamed);
            if (existence == null) return;
            String name = existence.getName();
            if (name == null || name.isBlank()) return;
            if (!ownerUUID.equals(existence.getPermanentOwner())) return;

            ExampleMod.intakeNamedRaceMob((ServerLevel) tamed.level(), tamed, name, ownerUUID, race);
            LOGGER.info("[TM] trnightmare: ADOPTED deal-named {} '{}' for owner {}",
                    race, name, ownerUUID);
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerUUID);
            if (owner != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        name + " is bound by contract — they may now serve your colony.")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            }
        });
    }
}
