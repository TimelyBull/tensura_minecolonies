# Playtesting queue — built but not verified in-game

**Purpose:** the standing list of changes that compile ("built green") but have
NOT been watched working in a real game. Per STATE.md's #1 rule — **compiles ≠
works** — every nontrivial change gets an entry here when it lands, and the
entry is checked off (with date + what was observed) only after a real
`./gradlew runClient` test. Pair with `docs/STATE.md` (overall project state)
and `docs/user-bug-reports.md` (fixes awaiting the REPORTER's confirmation —
an entry can need both).

Format per entry: **what changed** (one paragraph, with the files), **how to
test** (concrete steps + what you should see), **status**.

---

## OPEN — needs playtesting

### 1. Raid spawn placement — waves at the colony edge, never inside (2026-07-10)

**What the fix was:** fixes the 2026-07-10 bug report "tensura raids shouldn't
spawn 10 monsters instantly inside a house." The raid spawn chokepoint
(`TensuraRaids.computeSpawnPos`, shared by generic raids, the Orc Disaster lore
raid, and `/tensuraraid`) prefers MineColonies' own `calculateSpawnLocation()`,
which is perimeter-safe — but returns null in common cases, and our old
fallback was a point 32 blocks from the TOWN HALL (inside the built-up area;
`EntityUtils.getSpawnPoint` could even pick a house interior). The fallback is
now `computeEdgeSpawnPos`: march outward from the colony center in one-chunk
steps while `isCoordInColony` holds (the claimed-border march SubordinatePatrol
uses), then spawn `EDGE_SPAWN_MARGIN` (16) blocks PAST the border, 8 bearings,
water only as a last resort. Additionally, every spawn candidate — MC's result,
each fallback bearing, and the per-raider ±4 scatter in `spawnRaider` — rejects
any fueled barrier's footprint, so raiders can never materialize INSIDE the
shield (they'd be trapped in there with the citizens). Full record:
`docs/user-bug-reports.md` (2026-07-10 entry) + `docs/raid-system.md` ("WAVE
SPAWN PLACEMENT FIX").

**How to test:**
1. World with a built-up colony (several houses + town hall). `/tensuraraid`
   (op) to force a raid. **Expect:** the whole wave appears at/just beyond the
   edge of the colony's claimed territory and walks in — NOT among the houses,
   NOT inside one, NOT on a roof at the center. Repeat several times (the
   primary MC path and our fallback both need sampling — the fallback fires
   when MC's math returns null, which you can't force directly; repetition
   covers both).
2. Same colony, place + fuel a Barrier Core so the field covers part of the
   perimeter. `/tensuraraid` again, several times. **Expect:** raiders always
   appear OUTSIDE the barrier field and press against it; never inside it.
3. Edge cases worth one look each: a colony at a coastline (spawns should
   prefer dry bearings), and a very small/new colony (spawn should still be
   outside the claim, and the raid should still start — watch the log for
   "no raiders could spawn").
4. Watch for the known behavior change: edge spawns CAN land in not-yet-loaded
   chunks on the far side of a big colony — raiders idle until approached, and
   the night timeout still resolves the raid. A raid that "stalls" this way is
   expected, not a regression. (Also don't confuse the separate known bug that
   a mid-raid save/reload silently DROPS the raid event — deps/minecolonies.md
   gotcha §7.1 — with a spawn problem.)

**Status:** OPEN — built green 2026-07-10, never run in-game. Reporter
confirmation also pending (see user-bug-reports.md).

### 2. Carried over from STATE.md (older, still unverified)

Pointers only — full context in `docs/STATE.md` ("Immediate next steps") and
the per-system docs:

- **Identity Fix 1 — transactional summon.** In-RANGE summon→send cycling,
  15+ times; no creature stranded.
- **Identity Fix 3 — `/recoverorphans`.** Verify mechanism with a real capture
  mod or a forced-orphan debug command; confirm dry-run mutates nothing.
- **Latent SUBORDINATE-with-a-body edge.** Name a creature, do NOT send it,
  relog — does a plain colonist appear? (STATE.md Known Bug 0 sub-bullet.)
- **Update-path test.** Load a pre-faction-consolidation save on the current
  build; migrations carry over, no crash. Top risk for updating players.
- **Sphere barrier.** Raid a fueled barrier; watch sections fade/break/regen,
  holes admit mobs, pool-empty collapse + refuel recovery.
- **Sentient refactor + Rimuru.** `/rivalcolony spawn tempest`: boss named
  Rimuru (~500 HP) casts Water Blade + Corrosion (not melee-only); garrison
  ~20 defenders. Regression: bone golems / assassins / colony defenders still
  cast.
- **Raid-event reload loss (deps/minecolonies.md §7.1).** Start a raid, save &
  quit, reload — confirm the raid is silently gone (verifies the documented
  bug before building the fix).

---

## VERIFIED (move entries here with date + what was observed)

- *(none yet — this log started 2026-07-10)*
