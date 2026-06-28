# Project STATE — Tensura × MineColonies Mod

**Purpose of this file:** the single source-of-truth "where things stand right now." Read this
first in any new session to catch up. Update it at the END of each work session. Keep it in git.
Pair with: `decisions.md` (why things are the way they are), `CHANGELOG.md` (what changed),
`roadmap.md` / TODO (what's next), and the per-system docs in `docs/`.

_Last updated: 2026-06-27 — update this every session._

_Version: **0.1.2 finalized for release** (`mod_version=0.1.2`; CHANGELOG `[0.1.2] - 2026-06-27`
cut, `[Unreleased]` reopened for 0.1.3). 0.1.2 = gold-pillar marker removed + faction settlements
made rare + dependency floors declared (the real fix for the invite crash). Built green
(`tensura_minecolonies-0.1.2.jar`). Previously released: 0.1.0, 0.1.1._

_Repo state: branch `patrol-colony-outskirts`, pushed to `origin`
(`TimelyBull/tensura_minecolonies`). All earlier "uncommitted / not pushed" notes are RESOLVED —
working tree is clean. ✅ The temporary `[TM][DIAG]` logging (FIX 2 invite + Sentient + Rimuru, 5
log statements + 2 comments) was STRIPPED for the 0.1.2 release. NOTE: stripping the Sentient /
Rimuru DIAG removes their verification log lines — those features still function but their in-game
verification (boss casts Water Blade/Corrosion; garrison scaling) is no longer log-traceable; re-add
temporary logging if you want to re-verify._

_**Dependency floors now DECLARED in neoforge.mods.toml (0.1.2).** The mod previously declared NO
hard deps except nightmareutils — so a mismatched MineColonies loaded fine and crashed mid-game.
A user on **MineColonies 1.1.1281** hit `NoSuchMethodError ICitizenManager.spawnOrCreateCivilian(
ICivilianData, Level, List, boolean)` — that generic "Civilian" citizen-manager API
(spawnOrCreateCivilian / getCivilian / removeCivilian / createAndRegisterCivilianData, used
heavily across the citizen pipeline) was introduced AFTER 1281 and is what this build (1.1.1319)
compiles against. Required floors added: `minecolonies [1.1.1319,)`, `structurize [1.0.830,)`,
`blockui [1.0.209,)`, `domum_ornamentum [1.0.231,)`, `tensura [2.0.1.0,)`, `manascore [4.0.0.2,)`
(+ existing `nightmareutils [0.1,)`). Now an incompatible MC refuses to load with a clear message
instead of crashing. Floors = the versions in libs/ (exact build target); true minimum compatible
MineColonies is unverified (no 1281 jar to inspect) — lower the floor only if an older build is
tested working._

---

## What this project is

Standalone NeoForge 1.21.1 mod bridging the Tensura mob mod + MineColonies. Core model:
"two bodies, one identity" — a named Tensura mob ↔ a MineColonies citizen, swapped via
teleport, sharing a persistent identity/stats/inventory. Java 21. Built/run on a MacBook
(Apple Silicon) at `~/Desktop/Project/tensura_minecolonies`. Package root
`com.example.examplemod`. Dependency jars live in `libs/` (gitignored — must be copied per
machine; ~23 jars incl. Tensura NeoForge, MineColonies, ManasCore + 10 sub-modules,
Structurize, Architectury, GeckoLib, SmartBrainLib, TerraBlender, blockui, NightmareUtils).

**Release status: ALPHA.** Description carries a backup warning. Lots built, little playtested.

**Faction system ships OFF by default (since 0.1.1).** The single config option
`enableFactionSystem` (renamed from `factionSystemEnabled`; default flipped `true` →
`false`) is the sole on/off switch — no gamerule, no command. When off the whole
faction + diplomacy layer is dormant AND inaccessible: no settlement/rival-colony
generation, no diplomacy (envoys/deals/trades/war/conquest), no lore raids, no
marked-boss world-rep, and the roster's Diplomacy + Wars buttons are hidden (a
server flag on `RosterResponsePayload` drives visibility; the diplomacy/war/
faction-envoy packet handlers also refuse server-side). The core race-citizen
pipeline, the RACE-envoy scheduler (`runEnvoyScheduler`), colony reputation, and
threat-response are NOT gated by this flag and stay on. ⚠ TOML key changed, so
existing worlds with a `factionSystemEnabled` line fall back to the new default
(off) on update. See faction-model.md + decisions.md "Config gates".

---

## ⚠️ The single most important fact about project state

**A large amount is "built green, logic-verified" but NEVER PLAYTESTED.** Compiles ≠ works.
Fix 2 (below) is a concrete example: it was "sound by construction" and FAILED its first
in-game test. Treat all unplayed features as unvalidated until run. The highest-value action
at almost any point is to `runClient` and watch the relevant feature, not to build more.

Also: **no update-path test has been done** — no pre-change save loaded on a post-change build
to confirm the faction save-migrations don't break/orphan player data. This is the top risk
for players updating.

---

## Current work: identity-swap bug fixes (from a user bug report)

A user reported two bugs (they lost ~250 subordinates). Three fixes were built (purely
additive, no re-architecture of the fragile identity core):

- **Fix 1 — transactional `summonGoblin` (the "strand").** Wraps summon in try/catch +
  rollback, clears travelling-suppression on every failure path. STATUS: built, **needs
  in-game verification** via in-RANGE summon→send cycling (earlier test was done from too far
  away and hit a legitimate distance gate, not the strand). Rollback branch only fires on a
  mid-summon exception — hard to trigger deliberately; code-confidence acceptable if cycling
  never strands.
- **Fix 2 — re-stamp RaceTag on reload (the "revert to plain colonist").** First attempt
  (EntityJoinLevelEvent) FAILED in-game — handler ran before MineColonies populates
  `getCitizenData()` on relog. Re-fixed via **Option B: a periodic reconcile pass** in the
  1s `onServerTickPost` block, resolving the body via `colony...getCivilian(id).getEntity()`
  (only populated after registration), plus Option D (re-stamp on `onStartTracking` for zero
  flicker). STATUS: **CONFIRMED WORKING ✅ (2026-06-27, via logs).** `run/logs/latest.log` from a
  live session shows the happy path end-to-end: invite → `send: race tag attached` →
  `send: complete … IN_COLONY`; after a world reload the body rejoined with `hasTag=true` and the
  client built the renderer + shadow (the creature rendered, persisting across reload). The
  attachment rode the entity NBT on its own, so the reconcile pass never even had to fire this
  session — meaning the harder "MC rebuilds body from CitizenData (tag lost)" branch was NOT
  exercised by these logs, only the NBT-persist branch. The temporary `[TM][DIAG]` logging has now
  been **STRIPPED** (release prep for 0.1.2).
- **Fix 3 — `/recoverorphans` recovery tool (dry-run by default).** Walks SUBORDINATE
  identities whose mob UUID resolves to nothing; restores from `entitySnapshot` as colonists;
  reconciles ghost CitizenData/travelling state; never deletes records; snapshot-less records
  reported separately. STATUS: built, **needs verification.** Can't test with `/kill` (that
  fires death-cleanup and removes cleanly — NOT the orphan state). Need either the real capture
  mod (Iron's Spells "Tamer's Pocket" or similar — `discard()` without death event), OR a
  dev-only forced-orphan debug command, OR at minimum verify dry-run mutates nothing on a clean
  world. **The 250-orphan world is the USER's, not ours** — real 250 recovery happens on their
  machine, so dry-run safety + clear user instructions (backup → dry-run → check → confirm) are
  critical.
- **DEFERRED (do NOT build under time pressure):** Bug-2 auto-detection via
  `EntityLeaveLevelEvent` — it can mistake a chunk unload for a capture and DESTROY valid
  identities. `/recoverorphans` is the fail-safe manual stand-in.

**Caveat to tell the user:** pre-fix citizens have a null `raceTagSnapshot`, so they re-stamp
to the race's DEFAULT appearance (correct race, generic look) until the next summon→send cycle
recaptures the exact variant. Two-step recovery, not a flaw.

---

## Faction consolidation (11 → ~8) — status

Done as a sequence of investigate-then-build steps with save-migrations. **None update-tested.**
- Step 1: Tempest + Jura → merged (external to player colony). ⚠ display name later renamed
  "Tempest Jura Alliance" → **"Jura-Tempest Federation"** (`bf521fa`); id stays `tempest`. (The
  `clayman` id likewise shows as **"Moderate Harlequin Alliance"**, `54fd77a`.)
- Step 2: Carrion → renamed **Eurazania** (still bodiless); Otherworlder summoned-heroes moved.
- Step 3: **Shizu DEPRECATED (not deleted)** — kept in enum (so old saves don't break), gated
  out of active play, content/physical role retired (Pagoda retired). Standing left INERT.
  Hard-removal deferred to a future version once old saves age out.
- Step 4: Otherworlders slot → re-themed **Eastern Empire** (rename-in-place + migration). Boss
  = Mai Furuki (PLACEHOLDER), combatants = golems (PLACEHOLDER), high power tier.
- Step 5 (skills): Pass-0 resistances applied. Leon = Ifrit/Salamander + passives; Bone Golem
  casts its element. Remaining per-faction active-skill batches are mostly passives.
  - **Enemy casting is now driven by the "Sentient" skill (UNCOMMITTED — `19431ec` checkpoint +
    working-tree changes; in-game verify PENDING).** We REMOVED all four of our
    `registerReflectiveManascoreAutocaster` registrations (bone-golem / slime / assassin /
    colony-defender) and instead grant Nightmare's Utils' **`nightmareutils:sentient`** skill per
    mob (`ExampleMod.grantSentient`). The mod's own `SentientSkillService` auto-drives any mob with
    the skill to cast its learned ACTIVE skills — same machinery as the autocaster, one mechanism,
    no per-mob registration. Colony defenders get it removed on swap-back so it doesn't leak into a
    normal summon. See decisions.md "Sentient skill replaces our hand-built autocasters."
    - The earlier dedicated `registerTempestSlimeAutocaster` was built then REMOVED in this same
      refactor (superseded by Sentient). The "Slime only melees" root cause stands (its brain has
      no skill-cast behaviour — jar-verified), but the driver is now Sentient.
  - **Rimuru boss (UNCOMMITTED working-tree; verify PENDING).** The Jura-Tempest anchor Slime is
    named **Rimuru** and buffed to demon-lord tier in `buffRimuruBoss` (called from
    `spawnAnchorBoss`): HP ×100 (→500), ATTACK ×40 (→20), spiritual ×10; magicule cap SET to
    100,000 and aura cap to 10,000 with the CURRENT pools filled to match. The filled 100k magicule
    is also what finally lets it pay its cast cost (the "won't cast" bug was an empty pool, not just
    a missing driver). **Garrison side effect (intended):** filling the pools sets EP ≈ 110k →
    garrison scales to its **20-defender cap at ~×2.85**. Predator stays learn-only; Water
    Blade/Corrosion driven by Sentient. ⚠ all BALANCE GUESSES. See decisions.md "Rimuru" +
    faction-model.md.

**Roster edits also done:** Dwargon lost War/Beast Gnome; Empire lost Elemental Colossus;
Shin Ryusei + Mark Lauren + Shinji Tanimura → Eastern Empire (Falmuth keeps Kirara/Kyoya/Shogo)
with a logged "FUTURE CANON UPDATE: these three later join Jura-Tempest" note. Hinata + the five
summoned heroes are in a skill-untouched guard (get nothing).

**Final roster:** Dwargon, Luminous, Falmuth, Clayman (display "Moderate Harlequin Alliance"),
Leon, Eurazania (bodiless), Milim (bodiless), Jura-Tempest Federation (id `tempest`), Eastern
Empire. (Shizu = deprecated/inert.)

**Decided NOT to merge** Luminous into the Eastern Empire — Western religious bloc vs. Eastern
military empire are distinct, not allied.

---

## Other major systems (built, mostly UNPLAYED)

- **Spherical sectional barrier (REDESIGN — replaced the old prism/slice model).** Quad-sphere,
  24 sections, per-section health by tier (10k/20k/40k/60k), two-counter damage (section + pool),
  3-stage fade → break → 15s-stepped regen (cost 0.5×tier), pool→0 = whole barrier falls,
  refuel resets to full. Collision spherical with per-section holes (mobs pass opportunistically,
  don't seek). Radial push horizontalized near ground. T3 = +10% PLAYER magicule regen inside
  (eject REMOVED). Drain mult 0.001; all hostiles drain. **Never playtested — needs runClient.**
- **Threat-response body-swap:** on a raid, regular citizens flee, guards fight, Tensura citizens
  ≥10k EP place-swap to their mob form and fight, swap back after. Riskiest OLD feature. Unplayed.
- **Mob skill casting (NightmareUtils "Sentient" skill):** REPLACED our four hand-built
  autocasters (bone-golem / slime / assassin / colony-defender) — we now grant
  `nightmareutils:sentient` per mob and the mod auto-drives their learned active skills.
  UNCOMMITTED; in-game verify pending (regression check: bone golems still cast; Rimuru casts).
- **Lore events:** Orc Disaster is the only working one (the template). Feasible next ones (entities
  exist): Falmuth Invasion (best — human army, canon-heavy), Ifrit/Leon, Hinata's Crusade,
  Demon Incursion. Charybdis = high-value but needs a custom FLYING (non-wave) encounter.
- **Other built:** harvest festival, colony reputation, tiered raids, diplomacy (Stages 1-4 +
  Covenant + quest catalog), rival-colony arc (settlements/garrisons/conquest), world reputation,
  magicule storage network, assassin system.

---

## Collaboration (shared mod)

Merging with another dev (Jinjer). Shared repo: `Jinjer-prog/tensura-minecolonies-compat`.
His architecture differs fundamentally: **mixins on the wander mechanic** make the Tensura mob
ITSELF the colonist (one entity) — vs. our two-bodies swap (separate entities + identity record).
- **Barrier ported** to the shared mod (commit `a4e83f2`, on master). Self-contained; raid-steering
  dropped (no raids there); EP via `TensuraStorages.getExistenceFrom`. **Pushed to shared master
  directly (skipped a PR — flagged as a collaboration risk; tell Jinjer.)**
- Shared repo has **no `.gitignore`** (tracks build/run/.idea) — staging must be manual.
- His JDK (Microsoft OpenJDK 21) vs ours (Temurin 21) = fine, same version.
- **The identity bugs are artifacts of OUR architecture; his mixin approach MIGHT avoid this bug
  class entirely** — worth investigating as architectural intelligence (does his colonist model
  handle chunk-unload + external-capture cleanly?). The merge does NOT auto-fix our bugs.

---

## Known bugs

0. **CRASH when "inviting" (sending) a Tensura mob to the colony, + the half-converted plain
   colonist** — user-reported 2026-06-27. **ROOT CAUSE FOUND + RESOLVED (2026-06-27):** the user
   was on **MineColonies 1.1.1281**, below the generic-"Civilian" citizen-manager API this build
   compiles against → `NoSuchMethodError` mid-send crashed the game, and the send died before the
   RaceTag persisted + before `mode` flipped to IN_COLONY, leaving a plain colonist with the
   creature's name. **The crash guard** (`executePendingSwap` / per-swap loop try/catch → refund +
   "try again", no crash) shipped in 0.1.1, AND **the dependency floor** (`minecolonies [1.1.1319,)`
   in 0.1.2) now refuses to load on an incompatible MC instead of crashing. The user confirmed:
   after updating MineColonies the invite renders the creature correctly and the crash guard works.
   Logs (`run/logs/latest.log`) corroborate the happy path. **CLOSED — was a version mismatch, not
   an identity-core defect.**
   - ⚠ LATENT (separate, NOT the user's bug, UNTESTED): all three RaceTag re-stamp paths
     (`tickReconcileRaceTags`, `onEntityJoinLevel`, `onStartTracking`) skip `mode != IN_COLONY`.
     If a colonist body ever materializes for a still-SUBORDINATE identity (MC respawn defeating the
     naming-time travelling suppression, or a send that spawns the body then throws OUTSIDE
     `sendGoblinToColony`'s rollback try — e.g. the item transfer at the top), no handler stamps it
     → permanent plain named colonist. The version fix made this unreachable for the reported case;
     left as a known latent edge. Test: name a creature, do NOT send it, relog — does a plain
     colonist appear? See the investigation in this session's notes.
1. **Chunk-unload / relog colonist revert** — Fix 2 (Option B reconcile pass) **CONFIRMED WORKING
   ✅ (2026-06-27, via logs)** for the NBT-persist branch (see Fix 2 above). The harder
   "MC rebuilds body from CitizenData" branch the reconcile pass exists for was not exercised by
   those logs — treat that specific branch as still unverified-in-game.
2. **Summon→send strand** — Fix 1 (needs in-range verification).
3. **Third-party capture orphans subordinates** — Fix 3 recovery tool (needs verification);
   auto-prevention deferred. User lost ~250 (recoverable as colonists from snapshot, correct race,
   default appearance until re-summoned; snapshot-less ones = identity-only).
4. **Jura-Tempest Slime boss melee-only (does NOT cast)** — DIAGNOSED + FIX BUILT (UNCOMMITTED).
   Two root causes: (a) no driver — now the **Sentient** skill; (b) empty magicule pool — now
   filled to 100k by the **Rimuru** buff. **In-game verify PENDING** (does it cast Water Blade +
   Corrosion now?). See the Sentient + Rimuru blocks under faction consolidation.

---

## Backup / git status

- This is the project that's been mostly LOCAL — **push to your own repo for backup (overdue).**
- Before pushing a big stack, get a `git log` summary so you know what you're pushing.
- A stranded **committed-not-pushed commit on the old Windows laptop** may exist — get someone
  to `git push` it, or determine its contents, before assuming it's lost.

---

## Pending update (shipping ~2-3 days) — decision points

Bundling faction consolidation + identity fixes in ONE update (user's choice; flagged that
bundling makes a regression harder to diagnose).
- **MUST do before shipping:** the update-path test (pre-change save on post-change build —
  migrations carry over, no crash) + verify Fix 1 (in-range cycling), Fix 2 (relog re-stamp —
  still UNCONFIRMED), and Fix 3 (mechanism + dry-run safety). If shipping the faction stack,
  also eyeball the Slime-boss autocaster in a quick siege.
- **If identity fixes aren't solidly verified in the window:** ship the faction stack, SLIP the
  unverified fixes to a follow-up. ⚠ **Correction:** the prior note said "Fix 2 is confirmed" —
  it is NOT (see Fix 2 above; DIAG logging still in, confirmation pending). Treat all three
  identity fixes as unverified. Don't ship unverified fixes to the fragile identity core.

---

## How to use this file (the new memory system)

1. **This file + `decisions.md` + `docs/` are the source of truth — NOT the chat.**
2. Start a NEW chat per task. Point it at this file + the relevant doc.
3. Do the task. Then UPDATE this file (and decisions/CHANGELOG) with what changed.
4. Close the chat. Knowledge lives in the (git-committed) files, not the conversation.
5. `CLAUDE.md` should point new Claude Code sessions here automatically.

## Immediate next steps (the TODO)

- [x] **Verify Fix 2** — CONFIRMED via logs 2026-06-27 (NBT-persist branch); DIAG logging stripped.
- [x] **Strip the `[TM][DIAG]` logging** (FIX 2 + Sentient + Rimuru) — DONE for 0.1.2 release.
- [x] **Finalize CHANGELOG `[0.1.2] - 2026-06-27`** and reopen `[Unreleased]` for 0.1.3 — DONE.
- [ ] **Publish 0.1.2 to CurseForge** — upload `build/libs/tensura_minecolonies-0.1.2.jar`; paste
      the `[0.1.2]` CHANGELOG section as release notes; ensure the page lists required deps
      (MineColonies 1.1.1319+, Nightmare's Tensura Utils, Tensura, ManasCore, Structurize, BlockUI,
      Domum Ornamentum).
- [ ] Verify Fix 1 (in-range summon→send cycling, 15+ times).
- [ ] Verify Fix 3 (real capture mod OR dev-only forced-orphan command; confirm dry-run mutates
      nothing).
- [ ] Investigate the LATENT SUBORDINATE-with-a-body edge (Known Bug 0 sub-bullet): name a creature,
      do NOT send it, relog — does a plain colonist appear? If so, extend the reconcile pass.
- [ ] **Update-path test** (pre-change save → new build; migrations + no crash). ← top risk for the
      faction stack (already shipped in 0.1.0/0.1.1; 0.1.2 itself adds no new save migrations).
- [ ] Playtest the sphere barrier (runClient; raid it; watch sections/regen/holes).
- [ ] **Re-verify the Sentient refactor + Rimuru if needed** — `/rivalcolony spawn tempest`: boss
      named **Rimuru** (~500 HP), casts Water Blade + Corrosion (not just melee), garrison ~20
      defenders. Regression: bone golems / assassins / colony defenders still cast. (DIAG logging
      for this was stripped — re-add temporary logging if you want log traces.)
- [ ] Tell Jinjer the barrier landed on shared master; consider a `.gitignore` for the shared repo.
