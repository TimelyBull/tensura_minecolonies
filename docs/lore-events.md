# Investigation: Lore events — the Orc Disaster (the pattern-setter)

**Status:** investigation only, no code written (2026-06-11).

**THE MODEL (supersedes faction-raids.md's generic-faction-wave design):**
raids are BOSS-SPECIFIC LORE EVENTS — bespoke encounters tied to a
faction, each with its own trigger, scaling, and ripple consequences.
The Orc Disaster (Clayman's orc-horde march) is v1: the most wave-like
lore event, built end-to-end as the TEMPLATE. Charybdis (flying
set-piece) and Ifrit (named-boss confrontation) are DEFERRED and more
bespoke — the pattern must not force them into a wave model.

faction-raids.md remains useful for its trigger findings (per-player →
random owned colony, nightfall phase, cooldowns); its generic-wave
roster model is superseded.

---

## #1 PIVOTAL — Engine reuse: ~80%. The horde IS a parameterized TensuraRaidEvent + a LEAD BOSS.

**What reuses unchanged:** wave spawning (create + finalizeSpawn +
RAID_TAG + glow), WALK_TARGET steering + citizen target-assist, the
barrier field/drain, EP-budget scaling, the one-active-raid gate,
timeout machinery, event persistence via the `colonyeventtypes` entry,
citizen flee/hide via `isRaided()`.

**What's bespoke (the lore-event layer):**
1. **The lead boss:** ONE real `OrcDisasterEntity` spawned at the wave
   head — RAID-tagged like the horde, dark-red custom name ("Geld, the
   Orc Disaster"), its UUID stored on the event as `leadBossUuid`
   (new NBT-optional field, like the faction id).
2. **The horde roster is lore-exact:** plain `OrcEntity` fodder +
   `OrcLordEntity` heavies. KEY FINDING: roster mobs do NOT need to be
   innately hostile — RAID_TAG drives the barrier + steering, and the
   target-assist forces citizen aggression, so passive-ish wild orcs
   genuinely work as horde members (lore-accurate: a marching horde,
   not a monster zoo).
3. **Bespoke resolution rule:** killing the LEAD BOSS ends the event —
   the remaining horde breaks and flees (the existing poof-withdraw
   treatment). Lore: the Disaster IS the horde's will. Generic raids
   keep their all-dead rule; this is a per-event override.
4. **Boss bar binds to the LEAD BOSS HP** (not alive/total), titled for
   the event ("Geld, the Orc Disaster — Clayman's Calamity", purple).
5. Flavor strings (march announcement, broken-horde, withdrawal).

Mechanically: `TensuraRaidEvent` gains two optional fields
(`loreEventId` string + `leadBossUuid`) — NBT-absent = generic raid,
existing saves untouched. The per-event bespoke logic (resolution rule,
bar binding) keys off `loreEventId`.

## #2 Trigger — Clayman standing + offense, on the existing nightfall pass

Per the faction-raids trigger findings (kept): a per-ONLINE-player
phase of the nightfall pass, targeting a RANDOM eligible colony OWNED
by the player (no active raid, past the colony's 3-day cooldown).

Lore events gate HARDER than generic raids — recommend BOTH:
- `isBelow(player, CLAYMAN, WARY)` (standing < 20 — truly offended,
  not merely disliked), AND
- offense score ≥ a minimum (`ORC_DISASTER_MIN_OFFENSE`, suggest 10 —
  at least one grave act; see #3).
- Chance/night when gated: suggest 30%. Per-(player, event) cooldown
  on TIMEOUT only (suggest 8 days — a lore march regrouping).
- **Recurrence rule (lore-consistent):** if the event ends by LEAD-BOSS
  DEATH, it NEVER recurs for that player — the Disaster is dead (this
  mirrors the existing permanent `orcDisasterDefeated` envoy flag set
  by the same kill). Timeout/withdrawal → it can march again.

## #3 PIVOTAL — Degree of offense: a per-(player, faction) ledger

Standing is the wrong carrier for "what you DID" — it recovers
(RAID_SURVIVED, future diplomacy) while acts should be remembered. So:

**An offense LEDGER beside the standings:** a second string-keyed map in
`WorldReputationSavedData` — `player → (faction id → double offense)` —
written by the SAME mover hooks that already shift standing (one extra
line each), exposed through the manager
(`getOffense/addOffense/clearOffense`, sole-door discipline):

| Act (v1 — all computable today) | Standing | Offense |
|---|---|---|
| Attacking a faction-anchored boss (deduped hit) | −3 | +1 |
| Killing a faction-anchored boss | −20 | +10 |
| (Territory encroachment / raiding their holdings) | DEFERRED | DEFERRED — rival-colony arc adds writers |

- No decay — it's a ledger of acts, not a mood.
- **Consumed by the event:** when an Orc Disaster march RESOLVES
  (either way), the player's Clayman offense RESETS — the faction has
  spent its retribution. Offend again → it can build again (until the
  Disaster is slain, after which the event is gone for good).

**Offense scales the event** (third signal, alongside the two existing):
```
budget = colonyStrength × (BASE 1.15
         + 0.15 if standing tier is HOSTILE        // standing severity
         + min(0.20, offense × 0.01))              // degree of offense
heavies = 1 OrcLord per 25 offense (cap 3)         // composition, not just size
```
Colony strength keeps it winnable; standing severity and the offense
ledger make HOW MUCH you provoked them visible in the horde itself.
All constants named for tuning.

## #4 PIVOTAL — The ripple: defeat → −standing → forced HOSTILE → diplomacy closed

- **−20 standing on the kill: ALREADY INTEGRATES.** The lead boss is a
  real `OrcDisasterEntity`; the existing `BOSS_KILLED` mover fires from
  the same death pass with zero new code. (The colony's +10 boss-kill
  and the permanent `orcDisasterDefeated` envoy flag also fire — by
  design: the colony cheers, the orc envoy unlocks, Clayman declares
  eternal war. A genuinely interesting fork.)
- **Force HOSTILE:** after the kill mover, clamp:
  `setStanding(min(current, 19))` (HOSTILE band ceiling) via a
  dedicated reason — so even a high-standing player who slays the
  Disaster lands HOSTILE with Clayman.
- **Diplomacy closed:** a per-(player, faction) flag set —
  `diplomacyClosed: player → Set<faction id>` in
  `WorldReputationSavedData`, manager API `isDiplomacyClosed(player,
  faction)` / `closeDiplomacy(...)`. Diplomacy isn't built yet, so v1 =
  the flag + dark flavor messaging ("Clayman will never treat with
  you."); the future diplomacy system's FIRST check is this flag.
- **Permanence: PERMANENT** — ⚠ defaulted to the recommended option
  (the confirmation question went unanswered): you killed his calamity;
  Clayman never treats with you. Mirrors the permanent envoy flag from
  the same kill and makes slaying the boss a real fork. If recoverable
  is preferred (e.g. reopens at ALLIED 80+), it's a one-line change in
  the future diplomacy check — the FLAG design is identical either way.

## #5 The pattern — how this generalizes to Charybdis / Ifrit

Split the lore-event concept into a SHARED SPINE and a PLUGGABLE
ENCOUNTER, so non-wave events never get forced into the raid engine:

**Shared spine (built once, with the Orc Disaster):**
- The trigger phase (nightfall, per online player, standing + offense
  gates, per-event cooldown/recurrence rules, target-colony selection).
- The offense ledger + its mover writers + event-consumption reset.
- The ripple helpers (standing change, forced-tier clamp,
  diplomacy-closed flag, flavor dispatch).
- A small `LoreEvent` descriptor per event: faction, gates, scaling
  inputs, recurrence rule, ripple spec, and an `EncounterFactory`.

**Pluggable encounter (per event):**
- ORC DISASTER → the parameterized `TensuraRaidEvent` horde (this doc).
- CHARYBDIS (deferred) → its own encounter class — a flying set-piece
  (single world-boss entity, no wave, likely its own movement/phase
  logic; can still implement `IColonyRaidEvent` for the citizen-hide
  behavior, or skip the colony-event system entirely if it's not
  colony-bound).
- IFRIT (deferred) → a named-boss confrontation (single arena-style
  spawn, maybe Shizu-linked story beats).

The descriptor's `EncounterFactory` is the seam: the spine never
assumes "encounter = wave". The Orc Disaster simply happens to plug the
raid engine in; Charybdis plugs in something else and inherits the
trigger/offense/ripple machinery unchanged.

---

## Recommended bounded v1 SCOPE (build later from this)

1. Offense ledger in `WorldReputationSavedData` (+ manager API), written
   by the existing boss-attack/kill movers (+1 / +10), reset on event
   resolution.
2. `TensuraRaidEvent` gains `loreEventId` + `leadBossUuid`
   (NBT-optional); lead-boss-death resolution rule + boss-HP bar
   binding keyed off the lore id.
3. The Orc Disaster descriptor: CLAYMAN; gates = below WARY + offense
   ≥ 10; 30%/night; horde = OrcEntity fodder + OrcLord heavies
   (offense-scaled count) + one OrcDisasterEntity lead; budget
   coefficient 1.15 + 0.15 (HOSTILE) + min(0.20, offense × 0.01);
   timeout → 8-day cooldown, can return; lead-boss death → event gone
   forever for that player.
4. Ripple on defeat: existing −20 (automatic) + forced-HOSTILE clamp +
   PERMANENT diplomacy-closed flag (pending user confirmation) +
   flavor.
5. The `LoreEvent` descriptor + trigger-phase spine, shaped so
   Charybdis/Ifrit add descriptors with NON-wave encounter factories
   later.

PIVOTAL locks: (#1) ~80% engine reuse — the horde is a parameterized
TensuraRaidEvent with a lead-boss field and a bespoke resolution rule;
(#3) offense = a no-decay per-(player, faction) ledger written by the
existing movers, consumed/reset by the event, scaling budget AND
composition; (#4) defeat ripples through the EXISTING kill mover plus a
forced-HOSTILE clamp and a permanent (default; confirm) diplomacy-closed
flag that future diplomacy checks first.
