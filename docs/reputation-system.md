# Reputation system — investigation + v1 as-built record

**Status:** v1 built (spine + starter movers + two visible effects). No
reputation-DEPENDENT feature exists yet — crime / raids / assassins /
reclaim / trades hook into this API later.

---

## Investigation findings (what shaped the design)

### Does MineColonies already have a reputation-like system?

Two colony-standing metrics exist in MC 1.1.1319. Neither models
"how a faction / the world regards this colony or its ruler", so
reputation is a NEW system — but both informed the design:

**A. Happiness — internal citizen morale (NOT reusable).**
Per-citizen (`CitizenHappinessHandler` on `ICitizenData`), aggregated via
`IColony.getOverallHappiness()`. Scale 0–10
(`HappinessConstants.MAX_HAPPINESS`). Recomputed authoritatively every
MC day (`processDailyHappiness`) from ~14 living-condition modifiers
(homelessness, unemployment, health, security, food, death, quest, …).
Drives citizen work speed, complaints, and the skill `levelCap` our
`RaceSkillProfiles` already layers on. Wrong semantics, wrong scale,
and any injected value gets clobbered daily. It IS the sanctioned
post-v1 effect channel though — `ICitizenHappinessHandler.addModifier`
is a clean public API with a `"quest"` precedent for external nudges.

**B. Quest "reputation" — a quest-gating currency (NOT reusable).**
`IColony.getQuestManager()` → `IQuestManager.getReputation(): double` /
`alterReputation(double)`. One unbounded, unclamped `double
questReputation` per colony, NBT key `"questreputation"`. Read/written
ONLY by the datapack quest system (`QuestReputationTriggerTemplate`
gates quests; `QuestReputationRewardTemplate` adds on completion). No
in-game effect outside quest availability. Overloading it would
two-way-entangle our standing with quests, so ours is a separate store.

### Hook availability (confirmed in jars / codebase)

| Mover | Hook |
|---|---|
| Boss kill (UP) | `LivingDeathEvent` (already used by envoy kill-gate; `OrcDisasterEntity` / `IfritEntity` already detected there) |
| Building built/upgraded (UP) | `BuildingConstructionModEvent` on `IMinecoloniesAPI.getEventBus()` — carries `IBuilding` + `WorkOrderBuilding` (whose `getWorkOrderType()` distinguishes BUILD / UPGRADE / REPAIR / REMOVE) |
| Citizen attacked (DOWN) | `LivingDamageEvent.Post` on an `AbstractEntityCitizen` (NeoForge bus) |
| Citizen killed (DOWN) | `LivingDeathEvent` with a player killer (chosen over `CitizenDiedModEvent`, which doesn't carry the killer — see deviations) |
| Theft (DOWN, later) | no v1 hook — deferred behind the API |

---

## v1 as-built

### Storage — `ReputationSavedData`

Overworld-scoped `SavedData`, `DATA_KEY =
"tensura_minecolonies_reputation"`, mirroring
`ColonyRaceConfigSavedData`'s pattern:

- `Map<Integer, Double> reputationByColony` — per-colony standing, the
  v1 core.
- `Map<UUID, Double> reputationByPlayer` — per-ruler standing. Fully
  plumbed (storage + API) but driven by NO v1 mover; reserved for
  future features (assassins, reclaim, ruler dialogue).
- NBT: `colonies: [{colonyId:int, value:double}]` +
  `players: [{uuid, value:double}]`. Missing key → default 50.0, so
  legacy worlds and fresh colonies need zero migration.
- `ColonyDeletedModEvent` cleanup via `ReputationManager.onColonyDeleted`
  (wired into the existing `onColonyDeleted` handler) — a re-created
  colony under the same id starts back at neutral.

**ACCESS RULE (load-bearing): `ReputationManager` is the SOLE door.**
The SavedData class and its accessors are package-private and
deliberately dumb (no clamping, no defaults) — all policy lives in the
manager. Nothing else may touch the SavedData. This is what lets future
side-effects (HUD sync, logging, throttles, per-reason multipliers) be
added in exactly one place.

### Scale — 0–100 double with derived tiers (`ReputationTier`)

Default **50.0**. Clamped to [0, 100] in the mutator. Tiers are DERIVED
from the number; every band threshold lives in the `ReputationTier`
enum and nowhere else:

| Band | Tier | Colour |
|---|---|---|
| 0–9 | HOSTILE | dark red |
| 10–19 | PASSIVEAGGRESSIVE | red |
| 20–39 | WARY | gold |
| 40–59 | NEUTRAL | gray |
| 60–79 | LOYAL | green |
| 80–100 | DEVOTED | aqua |

The enum is ordered ascending so `compareTo` expresses "at least":
downstream gates are `isBelow(colony, WARY)` one-liners, not magic
numbers scattered through crime/raid code.

### The API — `ReputationManager` (LOCKED signature)

```java
// per-colony (v1 core)
double          getReputation(IColony)
double          getReputation(ServerLevel, int colonyId)
ReputationTier  getTier(IColony)            // + (ServerLevel, int) twin
double          modifyReputation(IColony, double amount, ReputationReason)  // THE mutator
double          setReputation(IColony, double value, ReputationReason)      // admin/debug
boolean         isAtLeast(IColony, ReputationTier)
boolean         isBelow(IColony, ReputationTier)

// per-player (plumbed, no v1 mover)
double          getPlayerReputation(ServerLevel, UUID)
ReputationTier  getPlayerTier(ServerLevel, UUID)
double          modifyPlayerReputation(ServerLevel, UUID, double, ReputationReason)
```

`modifyReputation` clamps, persists (marks dirty), and logs — it is the
ONLY write path features use. `ReputationReason` (BOSS_KILL,
BUILDING_COMPLETED, CITIZEN_ATTACKED, CITIZEN_KILLED, THEFT, QUEST,
ADMIN) makes call sites self-documenting and gives a future per-reason
policy layer one home. Colonies resolve via the project-standard
`IColonyManager.getInstance().getColonyByWorld(colonyId, level)`.

### Starter movers (magnitudes are STARTING values, tune freely)

Constants live in `ExampleMod` next to the mover methods (mover policy,
not manager policy):

| Mover | Amount | Hook | Attribution |
|---|---|---|---|
| Boss kill (Orc Disaster / Ifrit) | +10 | `LivingDeathEvent` via `processReputationOnDeath` | colony NEAREST the kill (`getClosestColony`); killer attribution = source entity then `getKillCredit()`, same as the envoy boss flags |
| Building built or upgraded | +2 | `BuildingConstructionModEvent` (`onBuildingConstruction`) | the event's colony. REPAIR / REMOVE excluded — maintenance isn't growth |
| Player damages a citizen | −5 | `LivingDamageEvent.Post` (`onLivingDamagePost`) | the citizen's own colony via `getCitizenColonyHandler().getColony()` |
| Player kills a citizen | −15 | `LivingDeathEvent` via `processReputationOnDeath` | same |

**Deviations from the original sketch (with reasons):**

- **Citizen kill uses `LivingDeathEvent`, not `CitizenDiedModEvent`.**
  The MC event doesn't carry the killer, so hooking it would penalise
  the player for raider / fall / starvation deaths. Reputation tracks
  how the colony regards the PLAYER, so the kill must be
  player-attributed — `LivingDeathEvent` carries the damage source.
- **Attack dedupe window.** A raw −5 per damage event would count a
  3-hit sword combo as three offences. A 100-tick (5 s) in-memory
  per-(attacker, citizen) window counts one hit per combo. Not
  persisted; a reload mid-combo at worst counts one extra offence.
- **Envoy entities are exempt** from the attack/kill movers (they're
  diplomatic visitors with their own kill-gate semantics).

Killing a citizen still stacks one −5 (the killing blow's combo window)
with the −15 — i.e. a murder reads ≈ −20. Accepted; magnitudes are
tuning knobs.

### Visible effects

1. **Roster header line.** `RosterResponsePayload` gained a
   `colonyReputation` double (read through the manager in
   `sendRosterTo`); `WindowRoster` renders "`Loyal · 72`" coloured per
   tier (`ReputationTier.argb()`) next to the colony-name subtitle —
   new `repLine` Text pane in `windowroster.xml`. Hidden when the
   player has no colony.
2. **Envoy dialogue tone.** `OpenEnvoyDialoguePayload` gained a
   `reputationTierId` byte (server computes the tier at right-click).
   `EnvoyDialogue.body(member, conditions, tier)` appends ONE tone
   sentence after the base + condition snippets:
   HOSTILE guarded-dark → PASSIVEAGGRESSIVE pointed → WARY guarded →
   NEUTRAL **nothing** (default dialogue byte-identical to
   pre-reputation copy) → LOYAL warm → DEVOTED reverent. One
   race-neutral line per tier; per-race tone variants are future
   polish.
3. **`/reputation` debug command.** Bare form (permission 0) shows the
   player's colony value + coloured tier; `/reputation set <0..100>`
   (permission 2) routes through `setReputation(..., ADMIN)`. Mirrors
   the `/envoystate` diagnostic convention.

### Extension contract for future features

Crime, raids, assassins, reclaim, settlement systems, dialogue, trades:

- READ via `getReputation` / `getTier` / `isAtLeast` / `isBelow`.
- WRITE via `modifyReputation(colony, amount, reason)` — add a new
  `ReputationReason` value per feature.
- Ruler-level features use the `*PlayerReputation` twins (storage
  already persists).
- NEVER touch `ReputationSavedData` directly.
