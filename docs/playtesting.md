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

### 0. Colony-centered barrier + core networks + layer-3 DL/Hero buff (2026-07-13)

**What changed** (`BarrierBlockEntity`, `TensuraRaids`, `BarrierFieldRenderer`,
`Networking`): (1) a Barrier Core inside a colony's claimed land centers its
sphere on the TOWN HALL (core outside any claim = self-centered, as before);
(2) multiple cores in one colony merge into ONE barrier — highest-tier core is
elected primary (drives field/render/menu at its radius), all member tanks +
deduped storage networks pool together; (3) the +10% magicule-regen buff MOVED
from core tier 3+ to the THIRD LAYER, split by the raiser: Demon Lord = +10%
player magicule regen, Hero = citizens get Regen II + Absorption. Full record:
docs/raid-system.md → "COLONY-CENTERED BARRIER…".

**How to test:**
1. Found a colony, place a T1 core at the claim edge, fuel it → the sphere
   should appear around the TOWN HALL, not the core. Break/replace the core
   outside the claim → sphere around the core block.
2. Place a SECOND core (higher tier) elsewhere in the same colony → within a
   second there should be ONE sphere (the higher tier's radius/colour);
   right-click either core → same shared fuel/capacity numbers; channeling
   into the T1 core raises the shared pool.
3. Break the primary (higher-tier) core → the T1 takes over (sphere shrinks to
   T1 radius) within a second.
4. As a true Demon Lord raise 3 layers → stand inside, magicule regen visibly
   faster; as a true Hero (or a second player) raise 3 layers → citizens
   inside get Regen II + Absorption hearts (particle-less; check the extra
   yellow hearts). Tier-3/4 core WITHOUT 3 layers → no regen buff any more.
5. Hostile-spawn suppression + raid steering should track the town hall
   footprint (spawn-proof area follows the sphere, raiders walk toward it).
6. Reload mid-everything: centering, network membership, layers + buff type
   survive (legacy saves: buff restores when the layer-raiser next logs in).

**Status:** OPEN.

### 0. Barrier Core tiers 2–4 upgrade from the tier below (2026-07-13)

**What changed** (`data/tensura_minecolonies/recipe/magicule_barrier_tier{2,3,4}.json`):
the higher-tier Barrier Core recipes now require the previous-tier Barrier Core
in the center slot instead of a third magic crystal there. Pattern changed from
`SCS / MCM / SCS` to `SCS / MBM / SCS`, where `B` = the prior tier's barrier
block (`magicule_barrier` for T2, `magicule_barrier_tier2` for T3,
`magicule_barrier_tier3` for T4). Silver/crystal/magisteel key entries
unchanged; T1 recipe untouched. NOTE: the user's request mentioned "a chest in
the center" — that describes the STORAGE block recipe, not the barrier; the
barrier center was a magic crystal. Confirm this matches their intent.

**How to test:**
1. `./gradlew runClient`, creative or give yourself the materials.
2. Craft a Tier 1 Barrier Core (unchanged recipe).
3. Open a crafting table: place silver in the 4 corners, magisteel top/bottom
   center-of-sides, magic crystal top-center and bottom-center, and the **Tier 1
   Barrier Core** in the true center → should yield a Tier 2 Barrier Core.
4. Repeat with the Tier 2 core in the center → Tier 3; Tier 3 core → Tier 4.
5. Confirm the old raw-materials-only recipes for T2–T4 no longer work (JEI/EMI
   should show the upgrade recipe).

**Status:** OPEN — recipe JSON only, not yet run.

### 0. Orphan subordinates: snapshot-at-naming + `/recoverorphans purge` (2026-07-13)

**What changed** (`ExampleMod.java`, `docs/decisions.md` FIX 3 follow-up):
closes two gaps in `/recoverorphans`. (1) A subordinate's snapshot is now
captured at NAMING time (`captureSnapshotFromLiveMob` after `addIdentity` in
both `onRaceNamed` and the pending-pool drain) and refreshed every 5 s for
every loaded subordinate (`tickRefreshSubordinateSnapshots`, wired into the
AMBIENT scheduler block), instead of only at first send. So a body that
vanishes before it was ever sent is now RECOVERABLE, not identity-only. (2) New
`/recoverorphans purge` (perm 2) DELETES the identity-only orphans
(`removeCivilian` + `removeIdentity`) to free the housing slot they occupied;
handler refactored to an `OrphanAction {DRY_RUN, CONFIRM, PURGE}` enum.

**How to test:**
1. **Snapshot-at-naming / recover-before-send:** name a wild goblin (don't send
   it to the colony). Kill/remove its body *without* our death hook — easiest
   is another mod's mob-catch item, or `/kill` won't work (that fires the death
   hook and cleans up properly). If you can't force an out-of-hook removal,
   verify indirectly: name a goblin, then `/recoverorphans` — the named-but-
   never-sent goblin, if its body is gone, should now show under **recoverable**
   (green "restore:"), NOT under identity-only. Before this change it would have
   been identity-only.
2. **Periodic refresh:** name+send+summon a subordinate so it's SUBORDINATE and
   loaded near you; let it gain EP / level for >5 s; confirm no errors in the
   log (look for any `snapshot capture threw`). The stored snapshot should track
   the current form (verify via a later recover/summon showing current stats).
3. **Purge:** with at least one genuine identity-only orphan present (e.g. a
   legacy pre-update stuck record), run `/recoverorphans` → it lists it as
   "purge?"; note your colony's citizen count. Run `/recoverorphans purge` →
   message reports "N deleted (housing freed)"; confirm the citizen count
   dropped by N and a house slot is now free. Confirm recoverable orphans were
   NOT purged (still listed by a following `/recoverorphans`).
4. **Safety:** `/recoverorphans purge` with no orphans → "No orphaned
   subordinates found". Purge only ever removes identity-only, owner-matched
   records; a loaded live subordinate is never touched.

**Status:** OPEN — compiles green (`./gradlew compileJava`); not yet runClient-tested.

### 0a. Patrol walks the colony perimeter (was: circled the town hall) (2026-07-10)

**What changed** (`SubordinatePatrol.java` + `PatrolOrder.java`): the "Patrol
Colony Outskirts" command used to pick an INDEPENDENT random bearing from the
colony centre on every leg, so the mob walked straight-line chords back and
forth across the middle (past the town hall) instead of tracing the edge —
the reported "only circles the town hall" bug (docs/user-suggestions.md
2026-06-29 #1). Fix: `PatrolOrder` gained a persisted `float bearing`; the new
`nextPerimeterTarget` ADVANCES that bearing by a fixed angular step
(`PATROL_ANGULAR_STEP` = 25°) around the ring each leg and stores it back, so
the mob loops the perimeter. Water / unreachable sectors are skipped by
continuing to turn the same way (step ×2, ×3, … up to a full circle). The
bearing is stored on the order (not derived from live position) so it keeps
marching even when the mob is momentarily stuck on an unreachable sector.
`beginPatrol` seeds the bearing from the mob's angle at command time; the recall
path re-seeds it to the re-entry direction. Legacy saved orders decode `bearing`
as 0 (backward compatible). The old random `computeOutskirtsTarget` is kept only
as the recall fallback.

**How to test:** found or enter a reasonably-sized colony. Name a Tensura
subordinate (e.g. a goblin), then sneak + right-click it to cycle its command to
PATROL (AQUA "now patrolling…" message). Watch it: it should walk to the colony
edge and then move AROUND the border in one consistent direction (a slow loop),
NOT dart across the middle past the town hall between opposite edges. Confirm it
still engages hostiles at the edge and returns to the border after a chase.
Try a colony with a water/pond edge — it should turn past the water and keep
looping rather than stalling. Relog mid-patrol — it should resume looping.
**Status: OPEN — compiles green (`compileJava`), not yet run in-game.**

### 0. Faction garrison — tier-keyed difficulty rework (2026-07-10)

**What changed** (`RivalColonies.java`; behind `enableFactionSystem`, default
OFF): the settlement garrison scaler was reworked from "difficulty = boss EP" to
"difficulty = REWARD TIER, nudged by boss EP" (see
`docs/faction-combat-audit.md` §6b). New FOUR-tier `DifficultyTier` IV/III/II/I +
`difficultyTierFor`; `epF = clamp((bossEP/150 000)^0.5, 0.80, 1.30)`; count and
stat× come from the tier. **Tiers (canon KINGDOM power): IV Luminous/Leon/Dwargon
· III Eastern Empire · II Falmuth/Tempest** (Milim/Eurazania/Clayman abstract).
**Leon's Ifrit
boss buffed** (`buffIfritBoss`, ~2800 HP / EP ~380k); **Eastern Empire's 1.6×
power multiplier removed**; **Dwargon rank = buffed dwarf-soldiers**
(`strengthenDwarfDefender`: HP ×2.5 / ATK ×6.0 + Body Armor) **+ 1 War Gnome
lieutenant** (capped via `isUniqueGarrisonMob`). **Every garrison now casts an
elemental attack Magic** (`assignFactionDefenderSkills`): Leon/Luminous Fire Ball,
Falmuth Wind Cutter, Eastern Empire/Dwargon Stone Shot, Tempest Water Cutter (+
each element's Manipulation as support). All values are BALANCE GUESSES.

**How to test:** enable the faction system, then for each raidable faction spawn
a colony settlement (`/rivalcolony spawn <faction>`) and check the garrison log
line (`garrison raised — N defenders, tier … epF … stat×…`). Expected:
Luminous/Leon/Dwargon (IV) ≈ 20 @ ~3.6; Eastern Empire 15 @ ~2.5 (III);
Falmuth 11 @ ~1.9 / Tempest 9 @ ~1.7 (II). Then `/rivalcolony declare
<id>` and fight each — confirm: (a) the four tiers feel distinctly stepped;
(b) **Dwargon dwarves fight as real
soldiers** (not one-shot chaff) and the single War Gnome casts earth magic;
(c) **Leon's Ifrit is a genuine boss** (≈2800 HP, casts fire) not a 400-HP
pushover; (d) EE is hard but not the old triple-dip wall; (e) after a partial
assault, `/rivalcolony reset <id>` respawns the garrison at the same strength
(dwarves still buffed); (f) **defenders actually CAST their element attack**
(dwarves/EE lob Stone Shot, Falmuth Wind Cutter, Tempest Water Cutter,
Leon/Luminous Fire Ball). **Highest-risk item:** the attack magics have a
cast-time — verify the Sentient autocaster completes the cast on a mob (it fires
`onPressed`; if the charge never finishes, the mob just melees). If a magic never
fires, fall back to an instant-cast attack skill for that element (fire→HEAT_WAVE,
water→WATER_BLADE, wind→VOICE_CANNON; earth has none — magic only). Also watch
the War Gnome roaming off (garrison tether).

### 0c. Garrison rank splits into CASTER / WARRIOR roles (2026-07-10)

**What changed** (`RivalColonies.java`; behind `enableFactionSystem`). The
generic rank-and-file (NOT bosses or named lieutenants) now splits ~40% CASTER /
~60% WARRIOR at spawn (`spawnDefender` → `applyCasterRole` / `applyWarriorRole`):
- **Casters:** the faction attack Magic (+ 25% chance of a 2nd same-element magic:
  fire→Fire Lance, wind→Tornado Blade, earth→Mud Spears, water→Icicle Lance),
  **Magic Resistance** instead of Physical, a tiered **magic staff**
  (`casterStaffFor`: I=Low, II/III=Medium, IV=High), and **0.65× move speed** so
  they trail the rush. Per-second best-effort retreat (`applyCasterRetreat` from
  `steerGarrisonToInvaders`): back off when a player is within 7 blocks but >2;
  within 2 blocks stop and melee (native).
- **Warriors:** current elemental resistances + **Physical Attack Resistance**,
  **Shadow Motion** (the flash-step dash), a tiered **long sword**
  (`warriorSwordFor`: I=Diamond, II=Netherite, III=Low Magisteel, IV=High
  Magisteel), and **NO attack magic** — they rush + melee (native).
- Role is INFERRED from the held staff (`isCasterDefender`), no new storage.
  Lieutenants (unique mobs) + bosses keep the full elite kit, native behaviour.
All values are BALANCE GUESSES.

**How to test:** `/rivalcolony spawn <faction>` then `declare` and observe the
rank: some defenders should **hang back and lob spells** (staff in hand) while
others **charge with swords**. Confirm: (a) casters keep distance and cast, then
switch to melee when you get within ~2 blocks; (b) warriors rush and occasionally
**flash-step** (Shadow Motion) toward you; (c) weapon materials scale with tier
(diamond swords at Tempest/Falmuth → high-magisteel at Luminous/Leon; low→high
staves likewise); (d) casters shrug off physical hits less but resist magic.
**CASTING FIX (2026-07-10, two root causes):**
1. *Cast-time channel* — a spell learned via `createDefaultInstance()` is
   unmastered, so it has a cast-time channel the press-once autocaster never
   completes. Fixed by granting attack magic **MASTERED**
   (`grantMasteredSkill` → instant-cast). (Correct, but wasn't the visible blocker.)
2. **THE blocker — magicule cost.** The attack magics cost **500–70,000 magicule**
   (Fire Ball 30k, Stone Shot 45k, Mud Spears 70k), but a rank mob's native pool
   is ~100–370 — it can't afford ONE cast, so it just melees (matches the
   observed "runs at me and punches"). Fixed: casters get a **200,000 magicule
   pool** (`CASTER_MAGICULE`, cap + fill) so the autocaster can pay for spells.
   (Side effect: caster EP ≈ 200k — intended, they're the garrison's mages.)

Quick check WITHOUT fighting: `/rivalcolony spawn <faction>` logs
`sample caster (<faction>) — <magic> … instantCast=true cost=… magicule=… affordable=true`
(`logSampleCasterReadiness`). **`affordable=true` + `instantCast=true` ⇒ they cast.**

**FOLLOW-UP tweaks (2026-07-10, after casting confirmed working):**
- **Magicule now BOSS-SCALED** (was a flat 200k — "too much"):
  `casterMagiculeFor` = `clamp(bossEP×0.12, primaryCost×1.5, 120k)`. Results:
  Tempest ~13k, Falmuth ~17k, Eastern Empire ~68k, Leon ~46k, Dwargon/Luminous
  ~120k (capped). Weak-boss factions field poorer casters; floor guarantees they
  can still afford their signature spell.
- **Cadence** (was "too long between shots"): the autocaster fires only ~once per
  second (nightmareutils' 20-tick gate — not ours to change) and picks among the
  mob's skills. Casters now ALSO carry a CHEAP **quick-spell** they always cast
  (Fire Lance 1k / Wind Gust 100 / Mud Hand 5k / Icicle Lance 850), so they
  alternate spells and more of each 1 s gate lands an attack. Verify shots feel
  more frequent. If STILL too slow, the only way past the 1 s gate is our OWN
  faster autocaster registration or a per-tick manual cast driver (both bigger).
- **Caster "retreat" REMOVED (2026-07-10)** — the per-second WALK_TARGET-away
  fought the brain's per-tick approach and produced a side-to-side SHIMMY without
  keeping casters back. Gone. Casters now only move slower (`CASTER_SPEED_MULT`)
  so they trail the warriors and cast as they close — no strict kiting (that
  needs the per-tick driver). Verify the shimmy is gone.
- **No garrison FRIENDLY FIRE (2026-07-10)** — `ExampleMod.onGarrisonFriendlyFire`
  (`LivingIncomingDamageEvent`) cancels any hit where attacker (or the caster
  behind a spell/projectile, via `source.getEntity()`) and victim share a
  settlement garrison (`RivalColonies.isGarrisonFriendlyFire`, matched on
  `GarrisonTag.settlementId`). Covers caster AoE (Fire Ball / Stone Shot) + melee
  + boss↔defender. Invaders (player + war-party) unaffected. Verify defenders no
  longer damage each other with spells, but still hit YOU.
- **Dwargon → Tier IV + Mai buffed (2026-07-10)** — tiers are canon KINGDOM power,
  not the placeholder boss; Gazel's Armed Nation is a great power, so Dwargon is
  now IV (garrison 20 @ ×3.64; dwarf-soldiers ~218 HP / ~33 ATK; War Gnome ~1456
  HP). Eastern Empire's Mai (placeholder) buffed: HP ~1500 (`EMPIRE_HP_MULT ×5`),
  ATK ~54 (`EMPIRE_DMG_MULT ×1.8`, kept below Gazel's 80 — the old flat ×3.5 made
  her out-hit him). Verify Dwargon fields a full apex garrison and Mai is a
  sturdier III boss without out-punching Gazel.
**Still best-effort:** the caster "stay back" is a per-second WALK_TARGET nudge
fighting the brain's own approach — NOT smooth kiting (casters may creep in /
jitter). A true kite needs a per-tick `EntityTickEvent` driver (synchronous —
never async) or a brain-behaviour mixin (bigger job). Also verify Shadow Motion
autocasts, and that staves/swords visibly equip.

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

### 1b. Barrier blocks hostile/Tensura spawns inside its field (2026-07-10)

**What changed:** the fueled-barrier in-field spawn suppression was broadened
from `NATURAL` + `CHUNK_GENERATION` to the whole *environmental* spawn set (now
also SPAWNER / TRIAL_SPAWNER / PATROL / REINFORCEMENT / JOCKEY / STRUCTURE /
EVENT / TRIGGERED), via a new `FinalizeSpawnEvent` hook alongside the existing
`PositionCheck` hook (both share `ExampleMod.BARRIER_BLOCKED_SPAWN_TYPES` +
`shouldBarrierBlockSpawn`). Deliberate placement (spawn eggs, `/summon`,
dispensers, breeding, our own SPAWN_EGG mob spawns) is intentionally NOT blocked.
Full record: `docs/raid-system.md` ("IN-FIELD SPAWN SUPPRESSION — BROADENED").

**How to test:**
1. Build/fuel a Barrier Core with a decent radius (T2+ is easiest to see) over
   open ground near your base. Confirm the wall is up (fuel > 0).
2. **Natural spawns:** `/time set night`, stand inside the footprint, and watch.
   **Expect:** no zombies/skeletons/spiders/Tensura hostiles (black spiders,
   direwolves, giant ants, etc.) appear anywhere inside the square footprint —
   they only spawn beyond it. Compare by draining the barrier to 0 fuel
   (`MIN` in the core menu) → hostiles start appearing inside again.
3. **Mob spawner:** place a vanilla monster spawner (or find a dungeon) inside
   the footprint while fueled. **Expect:** it ticks but no mob materializes.
4. **Patrol / reinforcement (optional):** a pillager patrol wandering into the
   footprint should not complete its spawn; a zombie calling reinforcements
   inside should get none.
5. **Deliberate placement still works:** inside the fueled footprint, use a
   hostile spawn egg or `/summon minecraft:zombie` — **expect it to spawn**
   (we only block environmental spawns). Your own tamed/summoned creatures are
   unaffected.
6. **Raids unaffected:** `/tensuraraid` with the barrier fueled — raiders still
   appear OUTSIDE the field and press in (covered by test #1 above too).

**Status:** OPEN — built green 2026-07-10, never run in-game.

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
