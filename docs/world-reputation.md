# Investigation: World reputation — per-boss standings + derived notoriety

> **FACTION MERGE (2026-06-21):** `tempest` and `jura_alliance` are now ONE
> faction, **"Tempest Jura Alliance"** (id `tempest`). The cast is now **TEN
> factions** (was eleven). The Shin Ryusei / Shinji / Mark Lauren anchors
> below now point at TEMPEST, so TEMPEST is **anchored** (no longer
> unanchored); only CARRION and MILIM remain unanchored. Old-save
> `jura_alliance` standing/offense folds into `tempest` on load. See
> `docs/faction-model.md` for the full merge record.

**Status:** v1 BACKBONE BUILT (2026-06-10) per the design below. As-built
notes:

- `BossFaction` (9 factions, string ids), `FactionTier`
  (HOSTILE/WARY/NEUTRAL/FRIENDLY/ALLIED, thresholds in the enum),
  `WorldRepReason` (BOSS_ATTACKED / BOSS_KILLED / ADMIN + reserved
  RAID_SURVIVED / CONQUEST / DIPLOMACY).
- `WorldReputationSavedData`: player → (faction id STRING → double),
  default 50; **unknown faction ids round-trip untouched** (the
  user-confirmed addon door). No cleanup lifecycle (player-level).
- `WorldReputationManager`: the sole door — getStanding/getTier/
  modifyStanding/setStanding/isAtLeast/isBelow + `factionOf` (lazy
  entity map) + `computeNotoriety`/`getOverallNotoriety` (pure derived,
  never stored; breakdown record powers the readout). All notoriety
  weights are named constants.
- Entity anchors (user-revised): Hinata → LUMINOUS; Gazel Dwargo →
  DWARGON; Falmuth Knight + Folgen + **Kirara + Kyoya + Shogo** →
  FALMUTH (Falmuth's otherworlders fight under its banner);
  **Shin Ryusei + Shinji + Mark Lauren → JURA_ALLIANCE** (new faction);
  Mai Furuki → OTHERWORLDERS (unaffiliated); **Shizu → her OWN SHIZU
  faction**; Charybdis + Orc Lord + Orc Disaster → CLAYMAN;
  Ifrit → LEON. Cast is now ELEVEN factions;
  TEMPEST / CARRION / MILIM unanchored (rival-colony arc).
- Movers (user-confirmed magnitudes): boss attacked **−3** per hit
  (5 s per-attacker-per-boss dedupe, the citizen idiom, riding the
  same damage pass); boss killed **−20** (riding the same death pass +
  killer attribution; runs ALONGSIDE the colony +10 boss-kill bonus —
  the colony cheers, the faction declares war).
- `/worldrep` lists all 9 factions tier-colored + the notoriety
  breakdown (hostility · power · demon lord · colony rule);
  `/worldrep set <faction> <0-100>` (op).
- Notoriety has NO consumer in v1 BY DESIGN — correct-but-unused
  infrastructure for the escalating-threat/angel-raid layer.
- DEFERRED: the data-driven faction overlay (door open),
  encroachment/conquest/raid-survival movers, and every consumer
  (lore raids, rival colonies, diplomacy, reclaim) — all hook the
  manager API later.

---

## Original investigation (design rationale)

**Status when written:** investigation only (2026-06-10).

**Concept:** the player holds a SEPARATE standing with each boss-faction
(attacking Carrion angers Carrion specifically), plus a DERIVED global
"notoriety" aggregate for anything keying off overall world-threat.
Player-level (the colony system stays untouched). This is the BACKBONE
design: store + API + computable movers + a readout. Faux-rival
colonies, raids-on-enemies, diplomacy, and reclaim are DEFERRED
consumers that hook the API later.

Verified by `javap` against the jars in `libs/`.

---

## #1 PIVOTAL — Faction definition: fixed Java ENUM. Entity→faction via a code map.

**Recommendation: a fixed `BossFaction` enum** (the user's lean —
confirmed). Rationale: the cast is known and small, every faction will
need bespoke logic when the deferred consumers land (rival-colony
behavior, raid flavors, diplomacy lines), and the codebase's idiom for
exactly this shape is `ColonyMember`/`ReputationTier` — ordered enums
with ids, display names, and colors. A registry/data-driven approach
buys flexibility nobody needs and costs the bespoke-logic ergonomics.

**The decisive finding — what entities exist to anchor factions:**
Tensura ships named LORE CHARACTERS as real entities
(`HumanEntityTypes`): **Hinata Sakaguchi**, **Gazel Dwargo**,
**Falmuth Knight** + **Folgen**, and six otherworlders (Kirara, Kyoya,
Mai, Mark Lauren, Shin Ryusei, generic Otherworlder). It does NOT ship
Carrion, Rimuru, Milim, Luminous, or Clayman as entities — but several
monster bosses are canonical proxies (Charybdis = the Clayman scheme;
Orc Disaster/Orc Lord = the Gelmud plot under Clayman; Ifrit = Leon
Cromwell's summon).

**Proposed cast + anchors:**

| BossFaction | Entity anchors (v1 detectable) | Notes |
|---|---|---|
| TEMPEST (Rimuru) | — none | pure-lore; standings move via future consumers |
| DWARGON (Gazel) | GazelDwargoEntity (+ optionally DwarfEntity kills?) | king entity exists |
| LUMINOUS (Holy Church) | HinataSakaguchiEntity | Hinata serves Luminous |
| FALMUTH | FalmuthKnightEntity, FolgenEntity | kingdom with live troops |
| CLAYMAN (clowns) | CharybdisEntity, OrcDisasterEntity, OrcLordEntity | canonical proxies |
| LEON | IfritEntity | Leon's summon |
| OTHERWORLDERS | the six otherworlder entities | |
| CARRION (Eurazania) | — none yet | anchors arrive with the rival-colony system |
| MILIM | — none yet | same |

**Entity→faction mapping:** a lazy static
`Map<EntityType<?>, BossFaction>` built from the registry suppliers
(the `TensuraRaids.rosters()` lazy-array idiom), exposed as
`WorldReputationManager.factionOf(EntityType<?>) → @Nullable BossFaction`.
Unanchored factions simply have no entries — their standings exist in
storage from day one and move when later consumers write them. A
datapack-tag overlay (`tensura_minecolonies:faction/<name>` entity-type
tags) is a clean LATER extension if modpack flexibility is ever wanted;
not needed for v1.

## #2 Storage — WorldReputationSavedData, player-keyed. FITS the idiom.

Mirror the established SavedData pattern (overworld-scoped,
`computeIfAbsent`, list-of-compounds NBT):

```
WorldReputationSavedData (DATA_KEY "tensura_minecolonies_world_reputation")
  Map<UUID player, EnumMap<BossFaction, Double>> standings
NBT: players: [ { uuid, standings: [ { faction: byte id, value: double } ] } ]
```

Missing player or faction key → default 50 (NEUTRAL) — zero migration,
same discipline as colony reputation.

**Lifecycle (different from colonies — by design):** there is no
"player deleted" event worth handling; entries for departed players are
a few bytes and stay (standing survives a long absence, which is also
the right gameplay). No cleanup hook needed. NOT stored on the player's
own NBT (would die with player-data resets and complicates cross-player
queries for future consumers).

**Relationship to the plumbed colony player-store:** colony
`ReputationSavedData.reputationByPlayer` (a single double per player)
is conceptually SUPERSEDED by this richer per-faction store. Recommend
leaving it untouched-and-unused (harmless) rather than migrating;
world-rep gets its own file because the value shape (map per player,
not double per player) doesn't fit.

## #3 PIVOTAL — The API: static WorldReputationManager, sole door.

Mirror the ReputationManager discipline exactly — one chokepoint,
clamping + logging inside, reason enum for every write:

```java
WorldReputationManager:
  // per-boss standing (the core)
  double       getStanding(ServerLevel, UUID player, BossFaction)
  FactionTier  getTier(ServerLevel, UUID player, BossFaction)
  double       modifyStanding(ServerLevel, UUID player, BossFaction,
                              double amount, WorldRepReason)  // THE mutator
  double       setStanding(...)                               // admin/debug
  boolean      isAtLeast(ServerLevel, UUID, BossFaction, FactionTier)
  boolean      isBelow(ServerLevel, UUID, BossFaction, FactionTier)

  // entity → faction (the mover helper)
  @Nullable BossFaction factionOf(EntityType<?>)

  // the derived aggregate — PURE FUNCTION, never stored
  double getOverallNotoriety(ServerPlayer player)   // 0..100
```

`WorldRepReason`: `BOSS_ATTACKED, BOSS_KILLED, ADMIN` for v1, with the
deferred consumers adding values later (`RAID_SURVIVED, CONQUEST,
DIPLOMACY, …`) — the ReputationReason extension seam, repeated.

**Notoriety aggregate (the design decision):** derived ON READ, never
stored or drifted — a pure function, so it can never desync from its
inputs. Recommended blend (0–100, weights as named constants):

```
hostility = avg over factions of max(0, 50 − standing) × 2   // 0..100
power     = min(1, baseMaxEP / NOTORIETY_EP_REFERENCE) × 100  // 0..100
status    = +20 if true demon lord (checkable via IExistence)

notoriety = clamp(0.5 × hostility + 0.3 × power + status
                  + colonyRepPenalty, 0, 100)

colonyRepPenalty = max(0, 50 − avgOwnedColonyReputation) × 0.4  // ≤ +20
```

Rationale: a SUM over factions would punish merely existing as more
factions are added (9 factions × small grudges = huge notoriety);
AVERAGE hostility keeps the scale stable as the cast grows. Power and
demon-lord status capture "the world fears what you are", colony
mistreatment captures "the world fears how you rule".
`NOTORIETY_EP_REFERENCE` (suggest 200k — endgame-ish EP) is the main
tuning knob. All weights are constants for play-tuning.

## #4 Scale + tiers

Per-boss: **0–100 double, default 50**, clamped in the mutator (the
colony-rep discipline verbatim). Tier enum — DISPOSITION, both
directions (will-raid-you ←→ will-ally), ordered ascending, thresholds
in one place:

```java
enum FactionTier { HOSTILE(0), WARY(20), NEUTRAL(40), FRIENDLY(60), ALLIED(80) }
//   0–19 HOSTILE | 20–39 WARY | 40–59 NEUTRAL | 60–79 FRIENDLY | 80–100 ALLIED
```

Every faction starts NEUTRAL (absent key = 50). Deferred consumers
gate declaratively: "Carrion raids while isBelow(player, CARRION, WARY)",
"diplomacy opens at isAtLeast(..., FRIENDLY)".

## #5 Movers — what's cleanly computable TODAY

| Mover | Verdict | Hook / read |
|---|---|---|
| Attacking an anchored boss | ✅ CLEAN | `LivingDamageEvent.Post` (the citizen-attack mover pattern incl. its dedupe window); victim's type through `factionOf` → `modifyStanding(attacker, faction, −, BOSS_ATTACKED)` |
| Killing an anchored boss | ✅ CLEAN | `LivingDeathEvent` + the existing killer-attribution idiom (the envoy boss-flags already detect Orc Disaster/Ifrit kills — same pass) → bigger − to THAT faction |
| Total EP → notoriety | ✅ CLEAN | derived read: `EnergyHelper.getBaseMaxEP(player)` — no event needed (and the colony-strength EP machinery from tiered raids exists for any colony-EP variant) |
| Demon-lord status → notoriety | ✅ CLEAN | derived read: `IExistence.isTrueDemonLord()` (the envoy-condition read) |
| Colony reputation → notoriety | ✅ CLEAN | derived read: iterate `IColonyManager.getColonies(level)` filtered to `colony.getPermissions().getOwner() == player` (the owner-death idiom), average `ReputationManager.getReputation` |
| Encroachment / conquest / surviving faction raids | ⛔ DEFERRED | need the rival-colony system — they arrive as new WorldRepReason writers later |

Note the derived inputs (EP, demon-lord, colony rep) feed NOTORIETY
only — they don't write per-faction standings, so there's nothing to
store or keep in sync.

## #6 Visible effect — /worldrep

Lowest-effort readout: **`/worldrep`** (mirroring `/reputation`):

```
World standing of <player>:
  Dwargon        Neutral (50.0)
  Luminous       Wary (35.0)        ← tier-colored
  Falmuth        Hostile (10.0)
  ... (one line per faction)
Overall notoriety: 42.5  (hostility 30 · power 25 · demon lord no)
```

Plus `/worldrep set <faction> <value>` (op) for testing. Other cheap
surfaces considered: the roster header is colony-scoped (wrong home);
envoy dialogue could read notoriety later (a consumer, not v1). The
command is the right v1 readout.

---

## Recommended v1 BACKBONE scope (build later from this)

1. `BossFaction` enum (9 factions, anchors per the table) +
   `FactionTier` enum + `WorldRepReason` enum.
2. `WorldReputationSavedData` (player → faction → double; overworld).
3. `WorldReputationManager` — the sole door (API above), including
   `factionOf` and the pure-function `getOverallNotoriety` with the
   blended formula (all weights named constants).
4. Movers: boss-attack (−, deduped) and boss-kill (−−) for anchored
   factions, riding the existing damage/death passes.
5. `/worldrep` (+ `set`) readout.

Built to be extended: lore raids, rival colonies, reclaim, and
diplomacy all hook `getTier/isBelow/modifyStanding` later — never the
SavedData. PIVOTAL locks: faction = fixed ENUM with a code
entity→faction map (anchors exist for 6 of 9 factions today);
notoriety = derived-on-read weighted blend (average hostility + power +
status + colony-rule), never stored.
