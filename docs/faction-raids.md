# Investigation: Faction raids — world-rep-triggered themed raids

**Status:** investigation only, no code written (2026-06-10).

**Concept:** anger a boss-faction (world-rep standing drops) → that
faction raids the player's colony with a FACTION-THEMED roster —
distinct from the generic raids triggered by COLONY reputation. This is
the first real CONSUMER of the world-reputation backbone.

References: docs/raid-system.md (the engine), docs/world-reputation.md
(the spine being consumed).

---

## #1 PIVOTAL — Reuse: the existing engine extends CLEANLY. No new event type.

A faction raid is the existing `TensuraRaidEvent` plus ONE field.
Audit of what the event/driver actually owns:

- `TensuraRaidEvent` already persists `rosterTier` and delegates roster
  lookups to `TensuraRaids.rosterType(tier, slot)`. Add an optional
  `factionId` STRING field (NBT key absent = generic raid — v1 saves
  rehydrate untouched; string id = the world-rep addon-door discipline).
- The registry entry (`tensura_raid` in `colonyeventtypes`) needs no
  change — the deserializer just reads the extra key. `isRaided()`,
  citizen flee/hide, persistence: all inherited as today.
- Wave spawning, RAID_TAG, glowing outline, steering (barrier-then-
  center), target assist, boss bar mechanics, victory/timeout
  resolution, barrier drain: ALL faction-agnostic already. The themed
  bits are exactly: roster array, boss-bar title/color, start/victory
  messages, and the victory reward (below).
- Victory reward: generic raids pay the colony +8 `RAID_REPELLED`.
  A faction raid should ALSO pay the player's standing with that
  faction: **+10 `RAID_SURVIVED`** (the reserved WorldRepReason) —
  Tensura factions respect strength; repelling Clayman's puppets earns
  grudging respect and naturally limits raid-chaining (standing climbs
  back toward the threshold with each repelled raid).

**Verdict: parameterize, don't fork.** A second `IColonyRaidEvent`
implementation would duplicate ~all of the event for three strings and
an array.

## #2 PIVOTAL — Per-player standing → which colony, and the trigger

**The mismatch:** faction standing is PER-PLAYER; raids hit a COLONY.

**Recommendation: a RANDOM eligible colony OWNED by that player.**
- Eligible = owned by the player (the `getPermissions().getOwner()`
  filter idiom) AND no active raid (the existing `findActiveRaid` scan)
  AND past the colony's existing 3-day raid-resolve cooldown.
- Random (not "nearest to player") because the faction strikes the
  player's HOLDINGS, not their person — and nearest-to-player makes
  raids follow the player around in degenerate ways (e.g. visiting a
  friend's area). Random across holdings spreads the threat and stays
  one line of code. "Largest colony" was considered (the seat of
  power) but punishes the flagship colony repeatedly; random is fairer
  and simpler. Easy to revisit.

**Trigger hook — a second phase in the SAME nightfall pass:**
`TensuraRaids.tick` already detects the nightfall edge per dimension.
After the existing colony-rep phase, a faction phase runs PER ONLINE
PLAYER (faction anger targets the player; requiring them online also
sidesteps the raid-while-unloaded leak documented for generic raids):

1. For each raid-capable faction (those with a raid profile, #3):
   `isBelow(player, faction, NEUTRAL)` → chance roll by tier —
   recommend **WARY 20%/night, HOSTILE 40%/night** per faction
   (slightly above the colony-raid 15/30/50 curve is NOT wanted here;
   these stack across factions, so keep individual chances modest).
2. Per-(player, faction) cooldown — recommend **4 in-game days**,
   persisted in `RaidSavedData` (a new `Map<UUID, Map<String, Long>>`
   beside the colony cooldowns) so one angry faction doesn't chain
   nightly even across different target colonies.
3. Roll passes → pick the random eligible owned colony → start the
   themed raid via the existing `startRaid` path with the faction
   profile. At most ONE faction raid triggers per player per night
   (first roll wins) — avoids the all-factions-at-once pile-up when a
   player has angered several.

## #3 Faction theming — a profile record, one map entry per faction

What makes it FEEL like the faction: roster + boss-bar identity +
flavor lines. Everything else stays generic. Recommended structure:

```java
record FactionRaidProfile(
    BossFaction faction,
    EntityType<? extends Mob>[] roster,   // themed wave composition
    String barTitle,                      // "Clayman's Puppets"
    BossEvent.BossBarColor barColor,
    String startMessage, String repelledMessage, String withdrawMessage)
// static Map<BossFaction, FactionRaidProfile> PROFILES — adding a
// faction's raid later = ONE map entry (data-shaped, not deep code).
```

**v1 rosters (entity-verified candidates):**

| Faction | Roster | Theme |
|---|---|---|
| CLAYMAN — "Clayman's Puppets" | Orc Lord (heavy), Evil Centipede, Knight Spider | his orc-plot mobs; Charybdis itself EXCLUDED for v1 (a flying world-boss breaks wave steering/barrier assumptions — it's a later set-piece, not a wave mob) |
| LEON — "Leon's Flames" | **Ifrit Clone** (exists in the monster registry — purpose-built for this), Salamander, Hell Caterpillar | fire roster |

Mechanics note: roster mobs need NOT be in the `hostile_monster` tag —
the RAID_TAG covers the barrier field and the target assist forces
citizen aggression regardless; guard auto-engagement covers
MONSTER-category types (all candidates are monster-registry entries).

## #4 Coexistence with generic raids — one raid at a time, automatic

Because a faction raid IS a `TensuraRaidEvent`, the existing
one-active-raid-per-colony gate (`findActiveRaid` is type-agnostic)
already enforces "a colony is raided by one thing at a time" — zero new
code. Recommended interaction (keep): the colony-rep phase runs first
in the nightfall pass; the faction phase skips colonies that just got a
generic raid. No stacking — a barrier siege by two armies sounds fun
and plays terribly (two boss bars, doubled drain, mixed rosters in one
victory check). Both raid kinds share the colony's 3-day resolve
cooldown, so back-to-back nights of alternating raid types can't happen
either.

## #5 v1 factions — moveable-standing only. Confirmed.

Only factions whose standing can actually DROP can trigger raids:
the entity-anchored ones (Clayman, Leon, Luminous, Dwargon, Falmuth,
Otherworlders, Shizu, Jura Alliance — standing moves via the
boss-attack/kill movers). The unanchored three (Carrion, Milim,
Tempest) sit at 50 forever in v1 → `isBelow(..., NEUTRAL)` is never
true → structurally cannot raid until the rival-colony arc gives them
movers. **The slot-in path for later is exactly two steps:** their
standing starts moving (new movers) + a `FactionRaidProfile` map entry.
Nothing in the trigger hard-codes which factions raid.

**v1 scope: CLAYMAN + LEON only** (profiles exist only for them) —
the two factions whose standing moves most naturally today (Orc
Lord/Orc Disaster/Charybdis kills; Ifrit/Shizu kills are Leon-adjacent
via Ifrit).

## #6 Scaling — colony-strength budget × standing severity

Use BOTH signals, separated by role:
- **Wave size/strength: the SAME colony-strength EP budget** (the
  tiered-raid metric — total citizen EP + secondaries, spawn until
  spawned EP ≥ budget). This keeps every raid winnable-but-challenging
  regardless of which faction sends it; a themed raid that ignores
  colony strength would one-shot small colonies.
- **Standing severity scales the BUDGET COEFFICIENT:** generic raids
  use ×1.15; recommend faction raids use **×1.15 at WARY, ×1.30 at
  HOSTILE** (a named per-tier constant pair). Angrier faction → harder
  raid, same fairness floor.
- Wave caps: reuse the colony-strength level caps (6/10/14) unchanged.

---

## Recommended bounded v1 SCOPE (build later from this)

1. `factionId` field on `TensuraRaidEvent` (NBT-optional, string id).
2. `FactionRaidProfile` record + `PROFILES` map with CLAYMAN + LEON.
3. Faction phase in the nightfall pass: per online player × profiled
   faction → `isBelow(player, faction, NEUTRAL)` → 20/40% by tier →
   per-(player, faction) 4-day cooldown (RaidSavedData extension) →
   random eligible owned colony → themed `startRaid` (themed roster,
   bar title/color, messages, severity coefficient). One faction raid
   per player per night.
4. Victory: colony +8 RAID_REPELLED (unchanged) AND player +10
   RAID_SURVIVED with that faction (the reserved reason goes live).
5. Everything else — steering, barrier, glow, scaling caps, timeout,
   one-active-raid gate, persistence — inherited untouched.

PIVOTAL locks: (#1) parameterize the existing event, no new
IColonyRaidEvent; (#2) per-player standing resolves to a RANDOM
eligible colony OWNED by that player, triggered in a per-online-player
phase of the existing nightfall pass, gated by tier + per-(player,
faction) cooldown + the colony's existing gates.
