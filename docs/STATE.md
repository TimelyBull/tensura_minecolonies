# Project STATE — Tensura × MineColonies Mod

**Purpose of this file:** the single source-of-truth "where things stand right now." Read this
first in any new session to catch up. Update it at the END of each work session. Keep it in git.
Pair with: `decisions.md` (why things are the way they are), `CHANGELOG.md` (what changed),
`roadmap.md` / TODO (what's next), and the per-system docs in `docs/`.

_Last updated: 2026-06-24 — update this every session._

_Repo state: branch `patrol-colony-outskirts`. HEAD = `19431ec` (Sentient-refactor checkpoint).
UNCOMMITTED working-tree changes on top: the **Rimuru boss buff** (RivalColonies) + docs. The
Sentient refactor itself is committed in `19431ec`; the Rimuru buff is not yet committed. Multiple
`[TM][DIAG]` logging lines (FIX 2 + Sentient + Rimuru) remain in, pending the in-game verify._

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

**Faction system now ships OFF by default (Unreleased).** The single config option
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
  flicker). STATUS: **BUILT + COMMITTED** (`bca87f6`), in-game confirmation **PENDING**.
  ⚠ **FLAG — corrected this session:** the prior STATE.md marked this "CONFIRMED WORKING ✅,"
  but the repo contradicts that — the temporary `[TM][DIAG]` logging is **STILL IN the code**
  (4 lines in `ExampleMod.java`, committed in `bca87f6` as "pending the user's confirmation
  relog"), and the "strip + commit confirmed Fix 2" TODO is unchecked. The diagnostics were
  deliberately left in so the user could relog and verify `citizenData=NULL` + the reconcile
  re-stamp; that confirmation has not been recorded. Treat Fix 2 as **awaiting verification**,
  not confirmed. Once the user confirms, strip the `[TM][DIAG]` lines and commit clean.
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

0. **CRASH when "inviting" (sending) a Tensura mob to the colony via the roster** — user-reported
   2026-06-27 (unknown version). The delayed body-swap executed in `onServerTickPost` ran
   `executePendingSwap → executeAction → sendGoblinToColony` with NO top-level try/catch, so any
   throw in that fragile path (entity spawn / stat copy / race-tag attach / Tensura storages)
   propagated to the server tick and crashed the game (integrated server = whole client). On
   restart the half-converted citizen showed as a plain colonist with the given name (the send
   crashed before the RaceTag/`raceTagSnapshot` was persisted + before `mode` flipped to IN_COLONY,
   so the Fix-2 reconcile skips it → no race appearance). **CRASH GUARD ADDED (uncommitted):**
   wrapped the `executeAction` call (refund + "try again" on throw) AND the per-swap loop call —
   a failed invite now degrades to a logged error + magicule refund, never a crash. ⚠ This is a
   SAFETY NET, not a root-cause fix: the underlying exception isn't identified (need the user's
   `crash-reports/crash-*.txt` to pin it; may already be fixed in current code). With the crash
   gone, a normal send should complete and persist the appearance (no revert).
1. **Chunk-unload / relog colonist revert** — Fix 2 (Option B reconcile pass) BUILT+COMMITTED
   (`bca87f6`); **in-game confirmation PENDING** (DIAG logging still in — see Fix 2 above). NOT
   yet "confirmed fixed." (Bug 0 above is a crash-driven instance of this revert.)
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

- [ ] **Verify Fix 2 in-game** (relog a Tensura colonist; watch for `[TM][DIAG] citizen join …
      citizenData=NULL` + `[TM] FIX2B: reconcile re-stamped …`; colonist returns to Tensura form
      within ~1s). THEN strip the `[TM][DIAG]` logging and commit the clean version.
- [ ] Verify Fix 1 (in-range summon→send cycling, 15+ times).
- [ ] Verify Fix 3 (real capture mod OR dev-only forced-orphan command; confirm dry-run mutates
      nothing).
- [ ] **Update-path test** (pre-change save → new build; migrations + no crash). ← top risk.
- [ ] Playtest the sphere barrier (runClient; raid it; watch sections/regen/holes).
- [ ] **Verify the Sentient refactor + Rimuru** — `/rivalcolony spawn tempest`: boss is named
      **Rimuru** (~500 HP), casts Water Blade + Corrosion (not just melee), garrison comes up at
      ~20 defenders. Regression check: bone golems / assassins / colony defenders still cast.
      Watch `[TM][DIAG] sentient: granted …`, `[TM][DIAG] rimuru: buffed boss …`, and the
      `tempest slime boss kit verification — …=LEARNED` lines. THEN strip the `[TM][DIAG]`
      logging and commit.
- [ ] Push to own repo for backup (branch `patrol-colony-outskirts`, 12 commits unpushed).
- [ ] Tell Jinjer the barrier landed on shared master; consider a `.gitignore` for the shared repo.
- [ ] Decide: bundle vs. slip identity fixes for the 2-3 day release.
