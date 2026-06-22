# Investigation: the Diplomacy system — Stage 1 (the spine)

> **FACTION MERGE (2026-06-21):** the former `tempest` and `jura_alliance`
> factions are now ONE faction, **"Tempest Jura Alliance"** (id `tempest`).
> Wherever this doc lists them separately or counts "8 diplomable factions,"
> read it as the merged faction and **7 full-catalogue factions**. The merged
> `tempest` deal table is the union of the old `tp_*` and `ja_*` deals minus
> the duplicate `ja_enlightened`; it keeps Tempest's `cov_tempest` Covenant
> and Warrior Training, while Jura's `cov_jura` and `cov_train_jura` ("Sage
> Training") were DROPPED (one Covenant + one training deal per faction). The
> Jura alliance buff (Luck) and caravan good were dropped; Tempest's
> (Regeneration; bread+emerald) survive. See `docs/faction-model.md` for the
> full as-built merge record.

**Status: STAGE 1 BUILT (2026-06-11).** As-built record (the
investigation below remains the design of record; everything landed as
specced unless noted):

- **Code:** `RelationsState` (NONE/OPEN/PACT), `DiplomacySavedData` +
  `DiplomacyManager` (sole door; every standing write via
  WorldReputationManager with the DIPLOMACY reason now LIVE),
  `DealSpec` (sealed `Requirement`: SupplyItems / BuildingLevel /
  Population / Happiness — the LendCitizens seam left for Stage 2) +
  `ActiveDeal` (persisted state machine ACTIVE → AWAITING_PAYOFF →
  READY; deadline expiry penalizes and removes), `FactionEnvoyTag` (+
  attachment), `FactionProfile` gained `sendsEnvoysToHuman/Majin` (the
  race gate: Holy bloc true/false, diplomats+swingables true/true,
  Clayman/aloof false/false = outbound-only), Networking
  (DiplomacySnapshotPayload NBT snapshot + DiplomacyActionPayload +
  faction-envoy dialogue pair), `DiplomacyScreen` (the [Roster |
  Diplomacy] tab strip — RosterScreen gained the Diplomacy button) +
  `FactionEnvoyScreen`, `/diplomacy` debug
  (show / `open <faction>` / `offers` / `reply <faction>`).
- **Mover/decay constants (tuning):** entry open +2; gift +2 (8 gold
  ingots); deal success +4 (supply) / +6 (building/population/
  happiness) / +10 pact (milestone → PACT); deal failure −5; offer
  ignored to expiry −1. Decay (daily pass, idle days only — no active
  deal and no activity within a day; only POSITIVE earned delta
  decays): OPEN 0.5/day, PACT 0.1/day, NONE none. Entry: reply delay
  1 day, accept floor standing ≥ 20, resend cooldown 1 day; inbound
  10%/day per faction, min standing 40, 3-day per-player cooldown,
  vanilla-Villager envoy ("Envoy of X") near the town hall, one at a
  time. Offers: 2 max per faction, 3-day expiry, refreshed daily.
- **Starter deals:** supply_iron (64 iron → 24 gold, +4, 3d, 1-day
  caravan payoff), supply_food (32 bread → 16 emeralds, +4, 3d,
  instant), raise_library (library L3 → 32 emeralds, +6, 12d),
  growing_town (15 citizens → 24 emeralds, +6, 20d), content_people
  (happiness ≥ 7 → 24 emeralds + 8 gold, +6, 12d, FRIENDLY-gated),
  alliance_pact (16 diamonds → 64 emeralds, +10, 6d, ALLIED-gated,
  milestone → PACT).
- **The Clayman-clamp freebie CONFIRMED:** the per-second collapse
  check derives purely from Layer-1 standing (`getStanding < 20` →
  state NONE, deals cancelled, offers cleared, message) — the Orc
  Disaster's forced-HOSTILE clamp (or `/worldrep set clayman 10`)
  shatters Clayman relations with zero event-specific code.
- **Gate CONFIRMED:** `DiplomacyManager.tick`, every mutator, the
  snapshot, the inbound roll and `/diplomacy` all no-op/report-dormant
  when `factionSystemEnabled=false` (on top of the Layer-1 manager
  gate).
- **Stage 2–4 seams left:** the sealed Requirement interface
  (LendCitizens), per-faction DealSpec tables (data swap over DEALS),
  reward kinds beyond items, and the mending ritual (a milestone deal
  offered only while `isDiplomacyClosed`; `reopenDiplomacy` already
  exists).

---

**Status: STAGE 2 BUILT (2026-06-11)** — citizen lending + faction-
flavored deal tables + the fuller UI. As-built record:

- **Citizen lending (`LendCitizens` Requirement variant — the sealed
  seam used as designed).** VANILLA COLONISTS ONLY (confirmed default):
  the eligibility filter excludes any citizen whose (colonyId,
  citizenId) pair has a `RaceIdentity` — matched on BOTH keys, since
  the bare citizenId lookup scans across colonies. Returned citizens
  resurrect with `resetId=true` (fresh ids; vanilla colonists carry no
  identity record, so nothing can collide). Race-citizen lending is a
  documented FUTURE follow-on (needs an identity remap on return).
- **The lend lifecycle:** accepting a lending offer opens the PICKER
  (`WindowLendPicker` — eligible citizens with their skill level,
  click-to-toggle, Send enables at the exact count); confirm →
  server re-validates → each citizen is `serializeNBT`-snapshotted
  into the ActiveDeal's NBT, the body poofs, `removeCivilian` drops
  them from the workforce; the deal sits in AWAITING_PAYOFF for the
  lend duration (time-based % bar); on payoff
  `resurrectCivilianData(snapshot, true, level, townHallSpawn)` +
  `incrementLevel(skill, boost)` + item/standing reward.
- **Edge cases (all handled):** (1) COLONY DELETED mid-lend → the
  return prefers the original colony, falls back to ANY colony the
  player owns, and if none exists the deal simply WAITS (citizens stay
  safe in the SavedData NBT — never lost, never duped). (2)
  SAVE/RELOAD mid-lend → snapshots ride `ActiveDeal.lentCitizens`
  inside DiplomacySavedData; survival is by construction. (3) FULL
  COLONY on return → resurrect registers the data regardless;
  MineColonies handles overcrowding through housing/happiness (a real
  but gentle cost). (4) RELATIONS COLLAPSE mid-lend (e.g. the Clayman
  clamp) → lent citizens are returned immediately, UNTRAINED and
  unpaid, before the deal is destroyed — collapse can't strand them.
  Lending deals cannot deadline-fail (the requirement is fulfilled by
  the act of lending; the timer IS the deal).
- **Faction deal tables (`DealSpec.FACTION_DEALS`, a data swap as
  designed):** Dwargon = craft/industry (iron tribute, blacksmith L3,
  smeltery L3, lend 3× Strength≥8 for 3d → +3); Tempest = community
  (provisions, population 15, happiness 7, lend 2× Adaptability);
  Jura = community/learning (wheat, school L3, lend 2× Knowledge≥8);
  Luminous = HARD (library L5, 32 diamonds, university L4); Falmuth =
  HARD war-materiel (32 iron blocks, barracks L3, lend 3× Stamina≥10);
  Milim = feasts/strength (64 cooked porkchop, population 20 — her
  1.5× swing amplifies the payouts); Carrion = beast offerings (48
  leather, happiness 8). Clayman/Leon/Otherworlders/Shizu offer
  NOTHING (hostility-oriented/aloof — relations can open, no deals).
  All schematic names verified against ModBuildings constants; note
  MineColonies has no "Mining" skill — miners key on STRENGTH, which
  Dwargon's lend uses.
- **Fuller UI:** the faction list now shows running deals at a glance
  ("deal 47%" on the row), lending deals show a time-based % bar +
  "Citizens away — back in ~Nh", and the picker window joins the
  paper-styled family. All behind `factionSystemEnabled` (the picker
  path routes through acceptDeal/handleLendConfirm, both gated).
- **Stages remaining:** Stage 3 = rewards (raid-support, buffs,
  exclusives); Stage 4 = the mending ritual (milestone deal offered
  only while `isDiplomacyClosed`; `reopenDiplomacy` exists). Plus the
  race-citizen lending follow-on.

---

**Status: STAGE 3 BUILT (2026-06-12)** — relationship rewards + ally
raid-support + action coupling. As-built record:

- **Pillar 1 — relationship rewards (the mechanisms + a representative
  set; more content authors into the maps):**
  - *No raids from allies:* a PACT faction's lore events never trigger
    against you (`LoreEvents.maybeTrigger` PACT check).
  - *Alliance buffs:* refresh-style `MobEffectInstance` re-applied each
    second while the PACT holds (ambient, icon-only; lapses by itself —
    no attribute-modifier bookkeeping). `ALLIANCE_BUFFS`: Dwargon Haste,
    Tempest Regeneration, Jura Luck, Luminous Resistance,
    Falmuth/Milim Strength, Carrion Speed.
  - *Trade access:* the daily PACT caravan (`FACTION_GOODS` map +
    Claim Caravan button, `CARAVAN_COOLDOWN_TICKS` = 1 day) — faction
    wares without a shop UI.
  - *QOL perk (feasibility CONFIRMED):* "Caravan Home" — teleport to
    your town hall via plain `ServerPlayer.teleportTo`; any PACT,
    `TRAVEL_COOLDOWN_TICKS` = half a day.
  - *Quest-reward content:* the SPARE Orc Disaster — Clayman standing
    ≥ 60 (`SPARE_BOSS_MIN_STANDING`), once ever, Claim Gift → an
    OrcDisasterEntity spawns near the player **WITHOUT FactionMarkTag**
    — unmarked = the Layer-1 movers ignore it entirely; kill it freely
    (boss loot + colony +10 + envoy unlock still apply, zero faction
    penalty). The `claimedGifts` set is the seam for more standing
    gifts (evolution assistance / exclusive materials are authoring
    work on the same mechanism).
- **Pillar 2 — ally raid-support (⚠ BALANCE-CRITICAL, UNPLAYED):** when
  ANY `TensuraRaidEvent` starts (generic colony-rep raids AND lore
  events — both call `spawnAllySupport`), each PACT faction sends
  friendly fighters: PASSIVE-category Tensura mobs (dwarf for Dwargon,
  lizardman for Milim/Carrion, goblin otherwise — guards don't
  auto-engage passive types; the orc horde proved passive mobs fight
  via target-assist), `AllyTag`-stamped, named "X Ally", steered onto
  the nearest raider each second by the inverted dual-write
  (`steerAllies`), poofed home at every resolution path. Magnitudes
  (ALL named on TensuraRaids, first guesses needing playtest):
  `ALLY_SUPPORT_PER_PACT` = 2, +1 per `ALLY_SUPPORT_STANDING_STEP` =
  10 standing above 80, `ALLY_SUPPORT_MAX_PER_FACTION` = 4,
  `ALLY_SUPPORT_TOTAL_CAP` = 8. Ally uuids persist on the event NBT
  ("allies", optional) so reloads keep them linked.
- **Pillar 3 — action coupling:**
  - *Majin downgrade (NEW):* a per-second side-watch persists the
    player's last race side; flipping to MAJIN drops every
    majin-sensitive faction (profile courts humans but never majin —
    the Holy bloc) from PACT → OPEN with a message. The Layer-1 live
    base already cooled the standing; this adds the relations-state
    consequence.
  - *Marked-boss kill → shatter (ALREADY EXISTED — confirmed):* the
    Layer-1 fan-out drops the faction's standing; the Stage-1
    per-second collapse check shatters relations below WARY; Stage 2's
    lend-guard returns lent citizens first. Chain verified end-to-end,
    no new code needed.
- Stage 4 (the mending ritual) remains; see docs/future-ideas.md for
  SIEGES (broken-alliance super-raids) and the 10+ per-faction QUEST
  CATALOG content pass.

---

**Status: STAGE 4 BUILT (2026-06-12) — the MENDING RITUAL. The
diplomacy system is COMPLETE (Stages 1–4).** As-built record:

- **The rite is a deal** (the framework reused as designed): a
  `MendingRite` Requirement variant + per-faction "Rite of Atonement"
  specs (`DealSpec.MENDING_DEALS`, ids `mend_<faction>`, generated for
  every faction so any future foreclosure source is covered).
- **Offered ONLY while foreclosed:** a daily pass replaces a
  diplomacy-closed faction's offer list with exactly the one rite
  (foreclosure collapsed relations to NONE, so the normal offer pass
  never touches them; accept-time validation requires the closed flag).
  The tab shows it under "X will not treat with you — save through
  grave atonement."; the active rite's button reads "Perform Rite".
- **The steep price (tunables on DealSpec):** `MENDING_TRIBUTE` =
  32 diamonds AND the SACRIFICE of the player's STRONGEST named
  subordinate with EP ≥ `MENDING_SACRIFICE_MIN_EP` = 10 000 — the
  body must be PRESENT (EP reads from the live body — the offering is
  brought to the rite). All-or-nothing validation, then: tribute
  consumed, both bodies poofed, the identity removed permanently (the
  death-hook removal).
- **On fulfilment:** the long-stubbed `reopenDiplomacy` finally fires
  — the closed flag clears and standing is SET to
  `MENDING_REOPEN_STANDING` = 25 (WARY — above the envoy-accept floor
  of 20, far below anything prior). Relations stay NONE: the player
  re-courts the faction normally (envoy + deals) and rebuilds from
  almost nothing. Forgiveness to TRY AGAIN, not restoration.
- **Repeatable (confirmed yes):** nothing is once-ever — re-foreclose
  and the rite is offered again, costing another champion each time.
- Follow-ons recorded in docs/future-ideas.md: sieges, the per-faction
  quest catalog, race-citizen lending.

---

**Status: COVENANT + BATCH BUILT (2026-06-12)** — a new top tier above
ALLIANCE plus a barrier/economy batch. As-built record:

- **COVENANT** (`RelationsState.COVENANT`, the tier above PACT): once
  ALLIED, deal-standing gain is DAMPED (`PACT_GAIN_DAMP` 0.25) so
  standing crawls; at standing ≥ `COVENANT_THRESHOLD` (95) the
  faction's UNIQUE milestone deal unlocks; completing it forges the
  Covenant. General perks: reduced supply-deal costs
  (`COVENANT_SUPPLY_DISCOUNT` 0.25), a Covenant travel perk (stub →
  routes to the town hall until the rival-colony settlement arc adds
  real targets), and stronger ally raid-support.
- **Per-faction Covenant rewards + milestones** (representative set —
  the 10+ catalog is the separate content pass in future-ideas.md):
  - **Dwargon** — reward: daily auto-GRINDER (industry: iron/coal/gold).
    Milestone "The Masterwork Commission": a Hihiirokane katana + 8
    pure magisteel + a new **Masterwork Forging Core** (recipe: pure
    magisteel centre, 4 diamond blocks edges, 4 high crystals corners).
  - **Tempest** — reward: Covenant TRAINING deal (PHYSICAL split:
    Strength primary +4, Stamina/Adaptability secondary +2). Milestone
    "A Thriving Metropolis": population 25.
  - **Jura** — reward: TRAINING deal (MENTAL split: Knowledge primary
    +4, Focus/Intelligence secondary +2). Milestone "The Grand
    Academy": university L5.
  - **Carrion** — reward: daily auto-GRINDER (beast/hunt: leather/bone/
    string — distinct from Dwargon's). Milestone "The Great Hunt": slay
    3 great beasts (Wither / Warden / Elder Guardian / Charybdis /
    Ifrit).
  - **Milim** — reward: **Drago Nova** (one-use AoE detonation, 1 per
    REAL-LIFE hour). Radius 12, magic damage 150. Configs
    `dragoNovaHarmAllies` / `dragoNovaBreakBlocks` (both default false);
    never harms the user UNLESS they are neither true demon lord nor
    hero → it KILLS them; Sage/Great-Sage holders get a foresight
    WARNING screen first (the collapse-confirm pattern). Milestone:
    deliver **Apito's Jelly** (craft: 8 honeycomb + 1 pure magisteel →
    Apito Nectar ×8, then 8 nectar around a slime core → the Jelly).
  - **Falmuth** — reward: stronger ally raid-support
    (`FALMUTH_COVENANT_SUPPORT_MULT` 2×). Milestone "Prove Your Might":
    slay the Wither.
  - **Luminous** — reward: grant 3 starter SPIRITS (Flame/Water/Wind
    at LESSER) IF the player has none, else does nothing (investigated:
    `TensuraStorages.getSpiritFrom` → `ISpiritWielder.setSpiritLevel`;
    "has none" = every element's `getSpiritLevelId == 0`). Milestone
    "The Grand Offering": 8 diamond blocks + 16 gold blocks.
  - **Clayman** (Covenant-able): reward: raid INTEL (next-march
    foresight via `LoreEvents.raidIntelFor`) + SUMMON a spare Orc
    Disaster (4-day cooldown, UNMARKED → no penalty). Milestone "Souls
    for the Core": slay 10 villagers (kill-tracked via `onPlayerKill`;
    the literal Charybdis-core EP-feed is simplified to kill tracking
    for v1).
- **#7 faction status label:** the Diplomacy row now shows the real
  DISPOSITION TIER (Hostile…Allied, tier-colored) when relations are
  NONE, or the relations STATE (Diplomacy/Alliance/Covenant) when open
  — never the static word "Diplomacy".
- **Quest REROLL button** (`ACTION_REROLL`): rerolls a faction's
  offers for `REROLL_CRYSTAL_COST` (4) high magic crystals, consumed,
  with a `REROLL_COOLDOWN_TICKS` (half-day) per-faction cooldown.
- **Named-citizen happiness:** investigated — acquisition method is NOT
  stored and NAMING is the only intake path today, so every named
  race-citizen gets a `StaticHappinessModifier` (factor 0.5) at
  creation; feeds colony rep → world rep via the existing chains.
- **New requirement variants:** `SupplyBundle` (all-or-nothing
  multi-item delivery), `SlayEntities` (kill-tracked hunts),
  `LendCitizens` gained `secondarySkills` for the training split.

---

**Status: FACTION QUEST CATALOG AUTHORED (2026-06-12).** The pending
content pass is done — the per-faction deal tables expanded from the
Stage-2 starter sets to a full catalog on the existing framework (no
new requirement mechanics):

- **7 full-catalogue factions** (Dwargon, the Tempest Jura Alliance,
  Luminous, Falmuth, Milim, Carrion, Clayman) — ~10+ quests each (the
  Tempest Jura Alliance carries the largest table, the union of the old
  Tempest + Jura deals), tier-gated into a
  NEUTRAL → FRIENDLY → ALLIED progression, varied across the five base
  Requirement types (supply / build / population / happiness / lend),
  faction-flavored requirements AND rewards. Existing Stage-2 deal ids
  preserved; ~60 new deals added.
- **Clayman INCLUDED** (user-approved): a 10-quest schemer table
  (magic crystals, dark goods, pawns) leading to its Covenant
  milestone — Clayman is Covenant-able and relations open via outbound
  envoy.
- **The 3 aloof factions get small sets** (user-approved, 4 each):
  Leon (fire/flame), Shizu (teaching/Ifrit-lore), Otherworlders
  (otherworld goods). They have no Covenant milestone but can now reach
  Alliance through their deals.
- **Building names all verified** against `ModBuildings` constants —
  archery / stable / enchanter / tavern / mysticalsite / hospital all
  exist; no substitutions were needed.
- The Covenant MILESTONE deal (built earlier) still sits above each
  faction's catalog; the offer pass draws ≤3 random eligible deals
  from the faction's table by standing tier, so the catalog forms the
  progression up to the milestone. Tensura items referenced via the
  `DealSpec.ten(path)` helper (silver/magisteel/crystals).

---

**Status: REWARDS DIVERSIFIED + SKILL CAPSTONES (2026-06-12).** A
content/balance pass on the catalog's rewards (no new requirement
mechanics) plus capstone skill grants:

- **De-emeralded.** Emerald-dominant rewards are replaced by
  faction-flavored useful payouts: **colony-loop resources** (building
  blocks, food, iron/gold/coal, leather, bone meal), **diamonds**
  (MC-useful, premium), and **Tensura materials** that feed our own
  systems. Emeralds survive only as small trader-flavor filler on
  Tempest "Meat for the Market" and Otherworlders "Settlers."
- **Tensura mats, conservatively** (they're currency in our own
  economy — barrier fuel, storage/Covenant crafts): magisteel
  (low common, high at FRIENDLY+, exactly ONE pure on Dwargon's top
  deal), magic crystals (low/medium common, **~8 high crystals across
  the ENTIRE catalog**, ALLIED/FRIENDLY only), `magic_stone` as a
  moderate craft-currency. Tiers scale: low deals → common goods,
  ALLIED → the richer mats.
- **Tensura flavor items:** typed `magic_tome_*` (earth/water/mental/
  recovery/enhancement/gravity/illusion/fire/summoning — 1 per
  magic-faction FRIENDLY/ALLIED deal, teaches a spell), `battlewill_
  manual` (war factions), all conservative.
- **MineColonies-useful items:** `ancienttome` (Jura/Luminous research),
  `scroll_tp`/`scroll_area_tp`/`scroll_guard_help`/`scroll_buff`
  (utility gifts), `compost` (Carrion farming), bulk building blocks.
- **Capstone SKILL rewards** (`DealSpec.SKILL_REWARDS`, granted on each
  faction's TOP ALLIED catalog quest; aloof factions on their highest
  quest): Dwargon Body Armor, Tempest Self-Regeneration, Jura Thought
  Communication, Luminous Holy-Attack Resistance, Falmuth Physical-
  Attack Resistance, Milim Strength, **Carrion Giantification** (the
  size-change giant intrinsic, per request), Clayman Charm, Leon
  Flame-Attack Resistance, Shizu Heat Resistance, Otherworlders Eye of
  Truth. **Grant rules** (`DiplomacyManager.grantSkillReward`): lack →
  learn; have + resistance → nothing; have + not-resistance + mastery
  not maxed → fully master it (the upgrade); have + no upgrade left →
  1 pure magisteel ingot instead. Idempotent; queued in
  `pendingSkillDeals` so offline completion grants on next login.

---

**Status: OFFER DRAW WEIGHTED BY TIER (2026-06-12).** Lower-tier deals
no longer dominate-or-vanish — they persist and surface rarer:

- **Eligibility was already correct** — `minTier` is a FLOOR
  (`tier.compareTo(spec.minTier()) < 0 → skip`), so a deal stays
  eligible at every tier at or above its gate; passing a tier never
  removed lower deals.
- **The draw is now WEIGHTED** (`weightedPick`): weight =
  `TIER_WEIGHT_FALLOFF (0.4) ^ (playerTierOrdinal − dealTierOrdinal)`.
  A deal at the player's tier = weight 1.0; one tier below = 0.4; two
  = 0.16; etc. Never zero — every eligible deal can still be offered,
  just progressively rarer the further below the player's tier it is.
  (Previously the draw was uniform, so a NEUTRAL deal at ALLIED was as
  likely as an ALLIED one.) For a typical 4/4/2 table an ALLIED player
  sees ≈47% ALLIED / 38% FRIENDLY / 15% NEUTRAL offers.
- **Covenant milestone + mending deals unaffected** — they enter the
  candidate pool (or the separate mending pass) by their own special
  gating; the weighting only reorders draw probability among already-
  eligible deals, and their ALLIED gate puts them at distance 0
  (full weight) anyway.

Original investigation follows.

---

**Status: ENVOY ENTRY REWORKED — colony-gated subordinate dispatch (2026-06-13).**
The outbound envoy is no longer an abstract "send + gift" button; it is a
colony-gated dispatch of a Tensura SUBORDINATE whose EP must meet the
faction's danger threshold. As-built:

- **Colony gate.** `sendEnvoy` and the gift both require the player to OWN
  a colony with a town hall (`DiplomacyManager.ownsColony`). The snapshot
  `canSend` now includes `hasColony`, so both buttons disable without a
  colony; the server-side gate returns "Found a colony first…" if invoked
  anyway. Behind `factionSystemEnabled` like the rest.
- **Envoy = subordinate dispatch (the picker).** Clicking Send Envoy
  (or Send Gift) validates, then opens a PICKER (`WindowEnvoyPicker`, the
  lend/war-picker shape) listing the player's at-your-side subordinates
  ELIGIBLE for that faction — owned Tensura `ISubordinate` bodies with a
  named identity whose `readExistence(mob).getEP()` ≥ the faction
  threshold, not already away. The player picks ONE; confirm →
  `EnvoyConfirmPayload` → `DiplomacyManager.dispatchEnvoy`. If no
  subordinate qualifies, Send Envoy fails with the EP requirement.
- **Per-faction EP threshold, danger-scaled** (`ENVOY_EP_THRESHOLD`,
  tunable BALANCE GUESSES): Tempest/Jura 500 (safe) · Shizu 1500 · Dwargon
  2000 (mid) · Carrion/Otherworlders 2500 · Falmuth/Leon 4000 ·
  Clayman/Luminous 5000 · Milim 6000 (most dangerous); default 2000. The
  threshold gates only PICKER ELIGIBILITY ("can the envoy survive the
  trip").
- **Model A — away then returns (never consumed, never at risk).** On
  dispatch: the chosen subordinate's snapshot is refreshed (`mob.save`),
  its body is despawned, its `mobEntityUUID` cleared, and it is recorded
  AWAY in `DiplomacySavedData.envoyAway` (identityId → factionId,
  persisted). While away it is unavailable — the roster action
  (`handleMenuAction`) blocks it ("away on an envoy mission"), and with no
  live body it never appears in loaded-subordinate scans (war party, etc.).
  When the mission RESOLVES (the existing reply pass — relations OPEN, or
  the faction rebuffs), `resolveEnvoyReturn` re-materializes the body from
  the snapshot at the owner's side and re-links the identity; if the owner
  is offline it's marked `envoyReturnPending` and re-materializes on next
  login (`onPlayerLogin`). Survives reload (both maps persist).
- **Race-gate composes, unchanged.** The EP/picker step only governs
  whether the envoy can be SENT; whether the faction RECEIVES it is still
  the existing reply-time check (`processPendingReplies`: accepted iff
  standing ≥ `ENTRY_ACCEPT_STANDING` (20) and not diplomacy-closed). A
  majin's base standing with the Holy bloc (Luminous 10 / Falmuth 15) is
  below 20, so a majin is rebuffed regardless of the envoy's EP — and the
  subordinate returns either way. The two gates are orthogonal: EP =
  survive the trip; standing/race = will they treat with you.

---

**Status: ENVOY PREREQS REVISED (2026-06-13, v0.1.0).**

- **INBOUND colony-development gate** (`colonyDevelopedEnough`, added to
  `rollInboundEnvoys` on top of the existing bars): a faction won't send an
  envoy until the player's colony has ≥ `INBOUND_BUILDING_COUNT_REQ` (4)
  buildings AND at least one at level ≥ `INBOUND_BUILDING_LEVEL_REQ` (2).
- **OUTBOUND EP rebalanced + danger-scaled** (`ENVOY_EP_THRESHOLD`): the
  dispatched subordinate's required EP is now inverse to faction
  friendliness — 5,000 floor (Tempest/Jura) · 6,000 (Dwargon/Shizu) ·
  8,000 (Carrion/Otherworlders) · 11,000 (Leon/Milim) · 12,000 (Clayman) ·
  13,000 (Falmuth) · 15,000 (Luminous); default 8,000. All tunable. The
  standing ≥ 20 + not-foreclosed accept checks at reply time are unchanged.

---

**Investigated:** 2026-06-11 (built the same day).

**What this is:** the builder's-path parallel to the hostility arc — a
TIERED, RECIPROCAL-EXCHANGE relationship system sitting on the Layer-1
faction model (docs/faction-model.md), gated by `factionSystemEnabled`.
Full arc: NEUTRAL → DIPLOMACY (fragile, decays) → ALLIANCE (durable);
entry via envoy exchange (race-gated); the core loop is faction DEALS
(reciprocal, deadline-bound, standing-driving) on a progressive
Diplomacy tab. This doc investigates STAGE 1 (entry + tiers + the deal
framework + a basic tab + movers/decay) plus the cross-cutting
feasibility unknowns. Stages 2–4 (citizen-lending depth, faction-
flavored deal sets, rewards/raid-support, mending) are deferred but the
framework is shaped for them.

Deferred entirely (needs the rival-colony/settlement arc): demands to
build at faction settlements / visiting faction towns.

---

## #1 Envoy-based entry — reuse the envoy MACHINERY, not its state machine

**What the existing envoy system provides (verified in code):**
`spawnEnvoy` (town-hall-adjacent spawn via `EntityUtils.getSpawnPoint`,
restrictTo roam, `EnvoyTag` attachment), the right-click → dialogue
screen flow (`EnvoyDialogueScreen` — a custom Screen, payload-driven),
the accept/decline server handlers, and the per-second scheduler
(`runEnvoyScheduler`) with cooldown discipline. All of it is
race-ENVOY-specific in its STATE (per-colony `acceptedEnvoys`,
spawn-set semantics) but generic in its MECHANICS.

**Recommendation: a parallel FACTION-envoy kind on the same machinery.**
- A new attachment (`FactionEnvoyTag(factionId, direction)` — the
  EnvoyTag/FactionMarkTag idiom) on a faction-appropriate entity
  (Falmuth Knight for the Holy bloc, a goblin/orc for forest factions,
  VisitorCitizen fallback) spawned by the SAME spawn helper near the
  player's town hall. Right-click opens an `EnvoyDialogueScreen`-pattern
  dialogue ("Open relations with Dwargon?"). Accept → relations OPEN.
  Do NOT overload `EnvoyTag`/ColonyMember — race envoys change colony
  SPAWN SETS; faction envoys change WORLD RELATIONS. Different state,
  same plumbing.
- **Inbound (faction → player):** a low-frequency roll on the existing
  scheduler cadence — eligible when the faction's diplomacy profile
  ACCEPTS the player's race side (below), standing ≥ NEUTRAL,
  relations not already OPEN, `!isDiplomacyClosed` (the Orc Disaster
  flag is THE first check — the door already exists in
  `WorldReputationManager.isDiplomacyClosed`).
- **Outbound (player → faction):** a "Send envoy" button per faction on
  the Diplomacy tab (no entity needed — the player IS the sender):
  optionally attach a GIFT (consumes the offered items from inventory →
  a small standing bump, the first deal in spirit), then a time-delayed
  reply (~1 in-game day, persisted) → relations OPEN if standing ≥ WARY
  and not diplomacy-closed; a rejection message otherwise (standing
  unharmed — asking costs nothing but the gift).

**The RACE-GATE (the Layer-1 classifier, zero new race code):** add a
per-faction `acceptsMajinEnvoys` / `acceptsHumanEnvoys` pair to the
diplomacy profile (one map, string-keyed like `FactionProfile` —
arguably ON FactionProfile itself as two booleans). Holy bloc: accepts
HUMAN inbound+outbound, but never SENDS to a majin — a majin player
must send their own envoy (and at a worse reception: their lower
disposition base already prices that in via the standing check, no
extra penalty needed). `isMajinSide(player)` is the only race read.

**Entry transition:** accept (either direction) →
`DiplomacyManager.openRelations(player, faction)` — sets the relations
STATE (below) and pays a small `+2` standing through
`WorldReputationManager.modifyStanding(..., DIPLOMACY)` (the reserved
reason goes live).

## #2 The tier system — a RELATIONS STATE beside the standing, not a parallel standing

**Recommendation: one new per-(player, faction) enum — `RelationsState
{ NONE, OPEN, PACT }` — stored string-keyed; everything else DERIVES
from Layer-1 standing.** No second 0–100 number anywhere.

| Diplomacy tier | Definition (derived) |
|---|---|
| (none) | state NONE — the faction is just a standing number |
| **DIPLOMACY** | state OPEN — deals available; quality scales with the standing TIER (NEUTRAL = basic deals, FRIENDLY = better terms) |
| **ALLIANCE** | state PACT — entered by completing the milestone ALLIANCE-PACT deal, offered only at standing ≥ ALLIED (80) while OPEN |

- **How it maps onto FactionTier:** DIPLOMACY is "FRIENDLY-band-ish
  with relations opened" in spirit, but deliberately NOT band-locked —
  it's the OPEN state plus whatever the standing currently is, so deals
  themselves can carry the player from NEUTRAL up through FRIENDLY
  (deals are the upward driver; locking entry to FRIENDLY would
  deadlock the loop). ALLIANCE = the ALLIED band + the pact milestone,
  exactly as proposed.
- **Collapse rules:** standing falling below WARY while OPEN → state
  reverts to NONE (relations break off, message); a PACT survives down
  to WARY (durable) but breaks below it. The Orc Disaster's
  forced-HOSTILE clamp therefore auto-shatters any Clayman relations —
  for free, via the standing it already writes.
- **Storage:** alongside the existing per-(player, faction) maps in
  `WorldReputationSavedData`… NO — recommend a separate
  `DiplomacySavedData` (relations state + active deals + entry
  cooldowns + pending envoy replies) behind a new sole-door
  `DiplomacyManager`, which performs every standing read/write through
  `WorldReputationManager`. Keeps the Layer-1 file pure standing/
  offense/flags, and diplomacy's churnier deal state in its own file.
  The `diplomacyClosed` flag STAYS in Layer 1 (already built — it's a
  standing-layer fact the Orc Disaster writes).

## #3 PIVOTAL — The deal framework: SPEC registry + ACTIVE instances + three detector kinds

**Data structure (two halves — static specs, persisted instances):**

```java
// STATIC, addon-extensible (string ids, the FactionProfile idiom):
record DealSpec(
    String id,                    // "supply_steel_dwargon"
    String factionId,
    Requirement requirement,      // sealed interface, below
    List<ItemStack> rewardItems,  // v1 rewards = goods (+ standing)
    double standingReward,        // on success (the upward driver)
    double standingPenalty,       // on failure/expiry
    long deadlineTicks,           // accept → due
    FactionTier minTier,          // offer gating (quality-with-standing)
    boolean milestone)            // the ALLIANCE-PACT marker

sealed interface Requirement permits
    SupplyItems,     // (Item or TagKey<Item>, int count)
    BuildingLevel,   // (String schematicName, int level)
    Population,      // (int citizens)
    Happiness        // (double average)
    // Stage 2 adds: LendCitizens(skill, level, count, durationTicks)

// PERSISTED per (player, faction) in DiplomacySavedData:
class ActiveDeal {
    String dealId; long acceptedTick; long deadlineTick;
    int progress;            // e.g. items delivered so far
    long completionDueTick;  // time-delayed deals: when the payoff lands
    State state;             // OFFERED, ACTIVE, AWAITING_PAYOFF, DONE, FAILED
}
```

**Fulfillment detection — the three kinds (all verified against the
APIs):**

1. **SupplyItems — PUSH, not poll: a "Deliver" button.** The player
   clicks Deliver on the tab → C2S payload → the SERVER walks the
   player's `Inventory`, consumes matching stacks up to the remaining
   count, bumps `progress`, syncs back. No deposit block, no chest UI,
   no warehouse coupling in Stage 1 (a warehouse-scan "deliver from
   stock" variant is a clean later addition — `IWareHouse` racks are
   reachable via `getBuildingManager().getWareHouses()`). This is the
   same consume-from-inventory idiom the barrier crystals and the
   magicule cost gate already use. Progress persists in `ActiveDeal`.
2. **BuildingLevel — EVENT-driven with a poll backstop.** The mod
   ALREADY listens to `BuildingConstructionModEvent` for the
   reputation building mover — the same hook reports
   `getSchematicName()` + level on every build/upgrade completion:
   one extra check against active BuildingLevel deals. Backstop: a
   lazy re-check on tab open / deal accept via
   `colony.getBuildingManager().getBuildings()` →
   `ISchematicProvider.getBuildingLevel()` + `getSchematicName()` —
   catches buildings that were already at level when the deal was
   accepted (decide: pre-met requirements either auto-complete or the
   offer is filtered out at offer time; recommend FILTERED OUT —
   a deal you've already met is no deal).
3. **Population / Happiness — polled milestones.** Read on the
   existing 1 s scheduler (or the daily pass — these move slowly):
   `getCitizenManager().getCurrentCitizenCount()` /
   `colony.getOverallHappiness()` — both already consumed elsewhere in
   the mod. Which colony? Stage 1: the deal binds to the colony CHOSEN
   at accept time (the player's colony from the roster context),
   stored on the ActiveDeal.

**Time-delayed deals + persistence:** `ActiveDeal` lives in
`DiplomacySavedData` (overworld SavedData, the established idiom) — so
% progress, deadlines, and AWAITING_PAYOFF timers all survive
save/reload by construction. The per-second scheduler (the same
`onServerTickPost` 20-tick cadence everything else rides) drives:
deadline expiry → FAILED (standing penalty through the sole door),
payoff timers → deliver rewards (items into the player's inventory,
overflow dropped at their feet — or held "ready to collect" on the tab;
recommend COLLECT button, no surprise drops). Offer rotation: per
(player, faction), a small offered-deals set refreshed daily from the
spec registry filtered by tier/state — persisted so the offer doesn't
reroll on relog.

**Starter your-colony deal seeds (Stage 1, faction-neutral terms):**
supply 64 iron/steel-ish goods → payment in faction goods + standing;
supply food bundle; reach building level (warehouse/library to L3);
reach population N; reach happiness ≥ 7. Faction FLAVOR (rosters of
faction-specific wares) is Stage 3's deal-set pass.

## #4 PIVOTAL FEASIBILITY — citizen lending: **FEASIBLE**, via MineColonies' own resurrection round-trip

The risky Stage-2 mechanic checks out cleanly — MineColonies itself
ships the exact primitive pair (verified against 1.1.1319):

- **Leave:** `ICitizenData implements INBTSerializable<CompoundTag>` →
  `data.serializeNBT()` captures the ENTIRE citizen (name, skills,
  job/home references, happiness, family links). Then
  `ICitizenManager.removeCivilian(data)` takes them out of the
  workforce (the death-path API — job/home slots free up, max-citizen
  count recalculates), and the live entity (if loaded) is discarded
  with a departure flourish (the envoy-poof treatment).
- **Return:** `ICitizenManager.resurrectCivilianData(CompoundTag,
  boolean resetId, Level, BlockPos)` — MineColonies' OWN grave-
  resurrection path: rebuilds the `ICitizenData` from the snapshot and
  respawns the citizen at a position. This is exactly "the citizen
  comes back," battle-tested by MC's graveyard feature.
- **Comes back TRAINED:** `ICitizenSkillHandler.incrementLevel(Skill,
  int)` (or `addXpToSkill`) — public API, applied right after
  resurrection. "Lend 5 Mining-X citizens → they return Mining-X+3" is
  a few lines.
- **The lent-out period:** the snapshot CompoundTags sit in the
  ActiveDeal's NBT (a `ListTag` of citizen snapshots + their citizen
  ids) — precisely the entity-snapshot idiom the race-identity system
  already uses for off-world bodies (and the trade-restock pass proves
  snapshot mutation works). The colony genuinely loses the workers
  (their jobs idle — the COST is real), and the deal's payoff timer
  brings them back.

**Verdict: FEASIBLE — no framework reshaping needed.** The deal
framework's `AWAITING_PAYOFF` + snapshot-carrying ActiveDeal already
fits lending; it's one more `Requirement` variant later. Two flagged
risks for the Stage-2 build (neither structural):
1. **Race-citizens:** our `RaceIdentity` records key on citizenId;
   `resetId=true` would orphan them. Stage-2 v1 should lend VANILLA
   colonists only (filter at selection), or resurrect with
   `resetId=false` and verify id stability — decide then.
2. **Mid-lend colony changes** (colony deleted, citizen cap shrunk):
   the return path must tolerate a full colony (resurrect anyway —
   MC handles overcrowding via happiness) and a deleted colony
   (snapshots held until any owned colony can receive; worst case the
   deal refunds as items). Edge handling, not architecture.

## #5 The basic UI tab — extend OUR RosterScreen with a tab strip (Stage-1 minimum)

Two candidate surfaces were checked:
- **The G-key `RosterScreen`** — our own vanilla Screen, server-
  snapshot-driven (`RequestRoster`/`RosterResponse` payloads), already
  the player's "my domain" surface (and already shows the colony
  reputation header).
- **The BlockUI tab-injection pattern** (`CitizenTradeButtonHandler`'s
  `Init.Post` + `ButtonImage` on MC's own windows) — proven, but it
  decorates MINECOLONIES' per-citizen/town-hall windows; faction
  relations are PLAYER-level, not colony-window-level.

**Recommendation: a tab strip on RosterScreen — [Roster | Diplomacy].**
We own every pixel (no BlockUI reflection), the data flow is the
established snapshot pattern (a `OpenDiplomacyPayload` carrying
per-faction: id, display name+color, effective standing + tier,
relations state, offered deals (id, title, requirement summary, reward
summary, deadline), active deal (progress %, time left)), and C2S
actions (`DiplomacyActionPayload`: SEND_ENVOY(faction, withGift),
ACCEPT_DEAL(dealId), DELIVER(dealId), COLLECT(dealId)) mirror
`BarrierMenuActionPayload`. The paper-styled rendering vocabulary from
`BarrierCoreScreen` (panel chrome in `renderBackground`, shadowless
text) carries over.

**Stage-1 minimum content:** one row per KNOWN faction (progressive:
factions with state NONE and no contact show as "Unknown" / greyed —
the tab fills in as relations open): tier chip (colored), relations
state, then per the state — [Send envoy] button, or the deal list
(≤2 offers + the active deal with a progress bar + Deliver/Collect).
Later stages add reward showcases, lending pickers, the mending ritual.

## #6 Standing movers + decay — all through the sole door, one daily pass

**Movers (every write `WorldReputationManager.modifyStanding(...,
WorldRepReason.DIPLOMACY)` — the reserved reason goes live):**

| Act | Standing |
|---|---|
| Relations opened (either direction) | +2 |
| Outbound envoy gift (value-scaled, capped) | +1..+3 |
| Deal completed | +`standingReward` (suggest +4 basic, +6 building/milestone) |
| Deal FAILED / deadline expired | −`standingPenalty` (suggest −5; failure must sting more than never accepting) |
| Deal offer ignored to expiry / declined | −1 (a nudge, not a cliff) |
| ALLIANCE-PACT milestone completed | +10 (and state → PACT) |

Deal success is THE main upward driver by design — the dispositions and
ripples mostly push down or sideways; this is the builder's ladder up.

**Decay — one pass on the existing DAILY cadence** (the
`tickReputationDrift` → assassin-daily chain already establishes the
per-day hook; diplomacy decay joins it):
- State OPEN (DIPLOMACY): the EARNED delta decays toward 0 by
  `DIPLOMACY_DECAY_PER_DAY` (suggest 0.5) — but ONLY on days with no
  deal completed and nothing in progress (an active relationship
  doesn't rot). Fragile, as designed.
- State PACT (ALLIANCE): decay `ALLIANCE_DECAY_PER_DAY` (suggest 0.1,
  or 0 — barely-decays as specced; recommend 0.1 so a fully abandoned
  alliance eventually frays to WARY and breaks, which is a story).
- State NONE: no decay (the base disposition is the resting state;
  earned deltas from hostility do NOT heal by time — grudges are the
  hostility arc's business, mending is Stage 4's).
Decay writes go through `modifyStanding` with a `DIPLOMACY` (or a new
`DECAY`) reason — visible in the log like every other mover.

## #7 The gate — confirmed: entirely behind `factionSystemEnabled`

Diplomacy reads/writes standing exclusively through
`WorldReputationManager`, whose every entry point already no-ops/reads-
neutral when the toggle is off. On top, gate the diplomacy layer's OWN
entry points (the established four-point pattern): the faction-envoy
scheduler roll, the `DiplomacyManager` mutators (open/accept/deliver →
no-op), the daily decay pass, and the tab (the snapshot payload reports
disabled → the Diplomacy tab renders a "dormant" notice or hides).
With the toggle off: no envoys, no deals, no decay, no tab — and
nothing below the faction layer notices.

---

## Recommended STAGE 1 build scope

1. `DiplomacySavedData` (relations state, active/offered deals, entry
   cooldowns, pending envoy replies) + `DiplomacyManager` sole door
   (every standing write via WorldReputationManager; `isDiplomacyClosed`
   checked at entry — the Orc Disaster door wires in for free).
2. `RelationsState {NONE, OPEN, PACT}` + collapse rules (below WARY →
   NONE; PACT durable to WARY). ALLIANCE-PACT milestone deal offered at
   ALLIED while OPEN.
3. Faction-envoy ENTRY: outbound Send-envoy button (± gift, delayed
   reply) + inbound `FactionEnvoyTag` envoys on the existing
   spawn/dialogue/scheduler machinery, race-gated via two booleans on
   the faction profile + `isMajinSide`.
4. The deal framework: `DealSpec` registry (sealed `Requirement`:
   SupplyItems / BuildingLevel / Population / Happiness) + persisted
   `ActiveDeal` instances; detectors = Deliver-button inventory consume,
   the existing `BuildingConstructionModEvent` hook + offer-time
   filtering, scheduler polls; deadline expiry + AWAITING_PAYOFF timers
   on the 1 s scheduler; ~5 faction-neutral starter deals.
5. RosterScreen tab strip + Diplomacy tab (snapshot payload + action
   payload, the established Networking pattern).
6. Movers table above through the sole door (DIPLOMACY reason live) +
   the daily decay pass (0.5/day OPEN, 0.1/day PACT, idle-days only).
7. Everything behind `factionSystemEnabled`; `/diplomacy` debug command
   (state readout, force-open, force-offer) in the /worldrep idiom.

**Built to extend:** Stage 2 = `LendCitizens` requirement (the verified
serializeNBT → removeCivilian → resurrectCivilianData → incrementLevel
loop) + snapshot-carrying deals; Stage 3 = faction-flavored deal sets
(a per-faction `DealSpec` table swap — data, not code); Stage 4 =
rewards (raid-support, buffs, exclusives) + the MENDING ritual
(`reopenDiplomacy` already exists on the manager; the ritual is a
steep milestone deal — sacrifice a high-EP subordinate / major goods —
offered only while `isDiplomacyClosed`).

PIVOTAL locks: (#3) deals = static string-keyed SPECS + persisted
ACTIVE instances; fulfillment = push-button inventory consumption for
items, the existing building-event hook for construction, scheduler
polls for milestones — no new blocks, no inventory scanning, all
progress in SavedData; (#4) citizen lending is FEASIBLE on
MineColonies' own serializeNBT/removeCivilian/resurrectCivilianData
round-trip + incrementLevel — the framework needs no reshaping, lending
is one more Requirement variant later.
