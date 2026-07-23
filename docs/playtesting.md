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

### 000. Parent race inheritance + envoy seed + free immigration (2026-07-23, 0.2.1)

**What changed** (`ReproductionManagerMixin` re-pointed; `ExampleMod`
`onReproductionChild` + new `inheritRace`/`memberOfCitizen`, `mintRaceCitizen`
(asBaby param) + `spawnColonyMember`/`countColonyMember`/`tryImmigration`, envoy
accept seed, scheduler wiring; `ColonyRaceConfigSavedData` lastImmigrationTick +
NBT): three linked systems. Root discussion in the 2026-07-23 STATE.md session.

- **Parent inheritance.** The reproduction mixin moved from `@WrapOperation` on
  `createAndRegisterCivilianData` to `@Inject` before `spawnOrCreateCitizen`,
  capturing `@Local` newCitizen/firstParent/secondParent (slots 5/6/7, MC
  1.1.1319 — verified live). Race rule: both parents → 50/50 of the two; one
  parent → that race; none → colony draw. A parent's race = its RaceIdentity
  race, or COLONIST if it has none. **Runtime-verified the mixin APPLIES**
  (`runGameTestServer` booted to player login, no injection error, debug.log
  shows "Mixing ReproductionManagerMixin"). The RACE LOGIC itself is unplayed.
  - Side effect (improvement): race skill bias now lands AFTER MC's parent-skill
    init instead of before it, so bred children get both. Previously the bias
    was silently clobbered.
- **Envoy seed.** Accepting an envoy spawns ONE grown citizen of that race
  immediately (`spawnColonyMember`).
- **Immigration.** Per-colony, on the envoy scheduler's per-second loop, cooldown
  `IMMIGRATION_COOLDOWN_TICKS` 2400. For any race in the colony's set below
  `IMMIGRATION_RACE_FLOOR` 3, spawn one grown citizen: 2/3 the least-represented,
  1/3 a random other eligible. COLONIST counts as a race. Gated off for pending /
  no-town-hall / at-max-citizens / empty-member-set colonies.

**How to test:**
1. `/setcolonyrace goblin`, breed (`/racegrow` for the real path). Child should be
   a goblin. Add lizardman to the set, get a lizardman citizen, then breed a
   goblin×lizardman pair repeatedly — children should be ~50/50 goblin/lizardman.
2. A colonist×goblin pair should produce ~50/50 human/goblin.
3. Accept a race envoy (`/spawnenvoy` then accept, or `/envoyforce`) — exactly ONE
   grown citizen of that race should appear at the town hall right away, named and
   yours, adult (not a baby).
4. Immigration: a fresh colony with e.g. {colonist, goblin} — over a few minutes
   each race should climb to 3 via arrivals (watch the log "immigration: a X
   joined"), then stop. Add lizardman via envoy; lizardmen should start arriving
   up to 3 as well. Confirm no race exceeds 3 FROM IMMIGRATION (births may push
   higher — that's fine).
5. Balance: with three races all at 0, the fewest-first bias should keep their
   counts within ~1 of each other as they fill.
6. Reload mid-fill: `lastImmigrationTick` persists (no burst of catch-up
   arrivals on load).
7. Regression: a plain colony that never set a race / accepted an envoy (empty
   member set) must get NO immigration and NO race births — pure vanilla.

**Watch for:** immigration + the game's own INITIAL top-up (to 4) both run early,
so a brand-new colony may reach ~6–7 citizens faster than vanilla. Intended, but
confirm it's not jarring. Also immigrants spawn at the town hall regardless of
housing — unhoused citizens hurt happiness; if that reads badly, gate on a free
bed later.

**Status:** OPEN — mixin application runtime-verified; the race/immigration
BEHAVIOUR is not yet watched in-game.

### 00. Adjustable per-colony barrier size (2026-07-22, 0.2.1)

**What changed** (`BarrierBlockEntity` field-size block + `resolveNetwork` core
count + NBT; `Networking.OpenBarrierMenuPayload` +4 fields and 4 new actions;
`BarrierCoreScreen` FIELD SIZE row, panel 216 -> 254 tall): the barrier's radius
was fixed at the primary core's tier value, so a colony that outgrew 60 blocks
had no answer. It is now a per-colony CHOICE inside an earned range.

- `MIN_RADIUS` 8, `RADIUS_PER_EXTRA_CORE_TIER` 2 (user-specified: an extra
  tier-1/2/3/4 core is worth 2/4/6/8 blocks), `RADIUS_HARD_CAP` 128,
  `RADIUS_STEP` 4, `UPKEEP_BASE_PER_LAYER` 10, `UPKEEP_PER_RADIUS_BLOCK` 1
  — the two upkeep numbers are BALANCE GUESSES; the radius bonuses were
  specified by the user.
- max = min(HARD_CAP, primary tier radius + (sum of the OTHER cores' tiers) x 2);
  min = 8.
- **Upkeep is new**: every active layer costs 10/s + 1/s per block of ITS OWN
  radius, replacing "layer 1 free, +50/s per extra layer". Nothing is free now.
- The chosen radius lives on the network PRIMARY next to the layer count and the
  shared pool, so every core in the colony edits the same value.
- Clamped on READ, not on write: losing a core shrinks the field immediately but
  remembers the player's number, so rebuilding restores it.
- Backward compatible: `fieldRadius` absent in old saves = "tier default", which
  is exactly the previous behaviour.

**How to test:**
1. One tier-1 core outside a colony: menu shows `16 blocks (8-16)`, `1 core`.
   `-` steps down to 8 and stops (button greys out); `MAX` returns to 16. The
   rendered sphere must resize to match, immediately.
2. Same core inside a colony: field still centres on the town hall, size control
   behaves the same.
3. Build a second core in the colony: the menu (from EITHER core) should read
   `2 cores` and a max of the primary's tier radius + that second core's bonus
   (2/4/6/8 by ITS tier). Add more and the bonuses sum.
4. Mixed tiers: the BASE must come from the highest tier present (the field
   driver), and every other core contributes its own tier's bonus — e.g. a tier-4
   primary plus two tier-2 spares = 60 + 4 + 4 = 68.
4b. **Upkeep.** Watch the menu's drain readout: it must rise when you enlarge the
   field and when you add a layer, and fall when you shrink it. Sanity numbers:
   tier-1 at 16 = 26/s, tier-4 at 60 = 70/s, tier-4 at 128 = 138/s, tier-4 at 60
   with 3 layers = 225/s. Then leave a barrier running and confirm the tank
   actually empties at roughly that rate and the field falls at zero.
5. Open the menu from core A, change the size, then open it from core B — B must
   show A's new size, the same fuel and the same layers. Confirm from a SECOND
   player too: the barrier is per-colony, not per-player.
6. Break a core while the field is dialled to the top: the barrier should shrink
   to the new max. Rebuild it: the barrier should go back to the size you chose.
7. Verify the size actually drives the mechanics, not just the render — raiders
   should be blocked at the new sphere, and hostile spawns suppressed inside it
   (the raid report re-publishes `getEffectiveRadius()` every second).
8. Reload the world: size persists.

**Watch for:** panel health does NOT scale with size, so a 128-radius sphere has
the same per-panel health as a 16 one — a big barrier is thinner per unit of
wall. Also the per-second collision sweep scans a box around the whole sphere;
at the 128 cap that is a large volume, worth an eye on tick time.

**Status:** OPEN — compiles green, not yet run in-game.

### 0a. Phantom citizen fix + baby age sync + auto-named children (2026-07-22, 0.2.1)

**What changed** (`ExampleMod.onRaceNamed` + new `renameExistingCitizen` + new
`applyAutoNaming`, `mintRaceChildCitizen`, `summonGoblin` steps 3c/3d, the send
path's child propagation (steps 2c + body), the `/recoverorphans` orphan scan;
new `mixin/EntityCitizenBabyMixin`; `RaceIdentitySavedData.renamePending` +
`PendingRaceMob.name` no longer final): naming a mob that is ALREADY a citizen
(a colony-born child, or any citizen summoned out to the player's side) used to
mint a SECOND `CitizenData` + identity, displacing the first in the
mob→identity index and leaving it as an unreachable citizen slot — the reported
phantom. Naming an existing citizen is now a rename. Full root cause in
`docs/user-bug-reports.md` (2026-07-22).

Also: a summoned baby arrived as an adult (a colony-born child's snapshot is
taken from a transient ADULT mob and is never refreshed by a send), and the
child flag was one-way and written only to the entity, so a baby that grew up
while out with the player was re-marked a child on return.

Children are also **auto-named** now: a colony-born citizen is your named
subordinate from birth, carrying the name MineColonies gave it, so it never has
to be summoned and hand-named at all. `applyAutoNaming` copies the "this is
yours and it's called X" half of Tensura's naming commit (Existence name,
display name, permanent owner, tame flag) and deliberately skips the
name-EVOLUTION (`INameEvolution.onPreNamed` — what turns a named goblin into a
hobgoblin) and the magicule transfer. The citizen's name is the source of truth
and is re-synced onto the mob at every summon, which also repairs citizens minted
before this existed.

**How to test:**
1. Breed a race child (`/racegrow force` if you don't want to wait). Note the
   colony's citizen count.
2. Summon the child with the roster (G). **It must arrive as a BABY**, already
   **named** (the citizen's name over its head) and already **yours** — it
   follows you and obeys subordinate commands without any naming step.
   It must NOT have evolved (a goblin child is a goblin, not a hobgoblin).
3. Try to open Tensura's naming menu on it. Tensura should refuse by itself
   ("already named" / "you already own this one") — that's `canName`, a second
   lock on the duplicate path. If you CAN still open it (e.g. an older citizen
   minted before auto-naming, before its first summon), naming must **NOT**
   raise the citizen count, and you should get "… is already one of your
   citizens — renamed to X". Check the town hall: one citizen, new name.
4. Send it back. It returns as a child citizen; the count is still unchanged and
   there is no extra entry in the roster.
5. Repeat with a child that has GROWN UP in the colony — summon must give an
   adult mob, and naming must still not add a citizen.
6. Leave a summoned baby out long enough to grow up (~20 min), then send it back:
   the citizen should now be an ADULT, and stay one after a reload (the flag is
   written to CitizenData, not just the body).
6b. **The adult-then-child pop** (reported 2026-07-22). Send a child home and
   watch it closely as it rises out of the materialize circle: it must be
   child-sized for EVERY frame, including the first. Before the fix it rendered
   full-size for up to two seconds and then snapped down — the timing merely
   coincided with the ~1s rise animation, which is why it read as "changes when
   the animation completes".
   Two changes together: the CitizenData flag is now written BEFORE the body
   spawns (so MineColonies' own `initEntityValues` stamps the body correctly in
   the same tick, and the first packet already describes a child), and
   `EntityCitizenBabyMixin` makes the CLIENT read the synced `DATA_IS_CHILD`
   instead of a cached field it only refreshes on a 40-tick timer.
   Also worth checking on a plain MineColonies child (not one of ours) — the
   mixin should make those crisper too.
7. **Existing-save repair:** on a world that already has a phantom, run
   `/recoverorphans` — it should now list the phantom even while its mob is
   alive beside you. `confirm` turns it into a working colonist; `purge` deletes
   it and frees the slot.
8. Regression: naming a genuinely WILD goblin must still create a citizen
   exactly as before (count +1, travelling-suppressed, no body until sent), and
   must still cost the player magicule and evolve the goblin into a hobgoblin —
   auto-naming must not have leaked into the hand-naming path.
9. Non-race subordinates: name a direwolf / spider / any non-goblin-orc-
   lizardman-dwarf mob. It must NOT appear in the roster and the colony's
   citizen count must not move. (Verified by reading the code — `Races.of()`
   returns null for anything outside the four races and `onRaceNamed` returns
   before any citizen is created — but worth one in-game confirmation.)

**Status:** OPEN — compiles green, not yet run in-game.

### 0b. Masterwork / Annihilator damage re-scale + in-place stat repair (2026-07-22, 0.2.1)

**What changed** (new `GearEvolution.java`; `ExampleMod` masterwork
registration; `MasterworkItem` constants + `inventoryTick`;
`AbsoluteAnnihilatorItem.inventoryTick`; all 13 gear_existence ladders): a
Masterwork katana was reading **184** attack damage. The `uniqueEvolutions`
amounts COMPOUND (`GearHandler.applyUniqueGearEvolution` adds the lowest
not-yet-applied tier to the stack's CURRENT modifiers and then drops it from the
list), but 0.2.0 authored them as absolute per-tier values — so all four summed
on top of a base that was already counterpart+2.

Re-scaled per the user's intent: the **maximum** is counterpart **+2**, not the
base. A Masterwork now starts **30 below** its hihiirokane counterpart and climbs
+5/+7/+9/+11 (cumulative, sum +32) to **+2 above** it. The Absolute Annihilator's
ladder had the same authoring error and is re-expressed as increments so it caps
at the originally intended 38 damage instead of 63.

Because Tensura bakes stats into the stack, retuning the datapack does nothing
for weapons that already exist — and a player can't re-forge without another
Masterwork Core. `GearEvolution.recalibrate` therefore rebuilds any Masterwork /
Annihilator stack in a player's inventory once a second: item base attributes +
exactly the tiers its EP has reached, folded through Tensura's own helper. A
correct stack is left untouched.

**How to test:**
1. Creative: a fresh Masterwork katana should read **51** attack damage
   (hihiirokane katana is 81); great sword/odachi 53; kodachi/short sword 48.
2. Feed it EP past 1.1M / 1.4M / 1.7M / 2.0M — damage should step
   **51 → 56 → 63 → 72 → 83**, ending exactly 2 above the counterpart, with the
   shimmer changing at the same four moments.
3. **The migration** — the important one. Load a save from 0.2.0 that has a
   Masterwork weapon (or an Annihilator) already forged. Within a second of it
   being in your inventory its tooltip should drop to the correct number for its
   current EP. Confirm it does NOT keep dropping (it should settle and stay), and
   that continuing to kill things still advances it normally.
4. Absolute Annihilator: 20 base, stepping 24 / 28 / 33 / **38** at
   150k/400k/700k/1M, and — newly working — **+2 knockback resistance** at 400k
   (+1 more at 1M) and **+2 hearts** at 700k (+2 more at 1M). A fresh hammer must
   show NO zero-value lines for those two.
5. Annihilator tooltip: SHIFT lists the four ability unlocks with their EP costs,
   green once reached and grey while locked, plus the current EP total.

**Status:** OPEN — compiles green, not yet run in-game.

### 0c. Weapon engravings + right-click ability damage (2026-07-22, 0.2.1)

**What changed** (new `WeaponAbilities.java`; `MasterworkItem`,
`AbsoluteAnnihilatorItem`, `DragoNovaItem`; new tag files under
`data/tensura/tags/item/` and `data/minecraft/tags/item/`): fixes the two
reported 0.2.0 weapon bugs — see `docs/user-bug-reports.md` (2026-07-22) for the
full root-cause writeup.

1. **Tags.** All 12 Masterwork weapons + the Absolute Annihilator are now in the
   same item tags as their hihiirokane counterparts, so engravings and vanilla
   enchantments can be applied at all, and Tensura's EP-milestone engraving
   grant (`EngravingHelper.grantRandomEngraving`, which filters on
   `canEnchant`) stops coming back empty.
2. **Abilities clear invulnerability frames before hitting**, so the swing that
   came just before no longer eats the ability's damage (`amount - lastHurt`).
3. **Abilities run Tensura's on-hit pipeline** — `hurtEnemy` +
   `doPostAttackEffectsWithItemSource` + `doAdditionalAfterDamage/AfterAttack`,
   the same recipe Tensura's own Battlewill arts use — so engravings and the
   weapon's on-hit effect fire from an ability too.
4. **Attacker-credited damage sources**: the magic slice now uses
   `tensura:magic` with the player as the attacker (was an ownerless
   `minecraft:magic`), the shockwave uses `player_attack`, and the Drago Nova
   blast is credited to the caster.
5. **Nova scaling**: fired from the Annihilator it adds 4× the weapon's attack
   damage to the base 150 (⚠ BALANCE GUESS, unplayed).

**How to test:**
1. Creative: get a Masterwork weapon. Its tooltip should now let an **enchanting
   table / anvil** offer enchantments at all — check an anvil with a Sharpness
   book accepts it (before the fix it refused every book).
2. Engraving: apply Barrier Piercing (or any engraving) via whatever route your
   pack uses. Hit a mob with a barrier — the barrier should visibly take the
   piercing hit and play the barrier-break sound.
3. **The "2 damage" case** — this is the important one. Swing at a tanky mob,
   then IMMEDIATELY right-click to fire the branch ability. Before: ~0-2 damage.
   After: the ability's full damage (0.6× your attack damage for the sweep,
   0.8× for the slice), regardless of how recently you swung.
4. Confirm the ability now also triggers the weapon's on-hit: as a majin you
   should heal a little from an ability hit; as a non-majin you should gain
   Regeneration.
5. Kill something with an ability (not a swing) — you should get the EP for it,
   and your weapon's EP should tick up.
6. Kill mobs with a Masterwork weapon until its EP crosses a milestone; it
   should start picking up engravings on its own (50k / 250k / 1M EP thresholds
   from Tensura's enchantment config; Masterwork weapons start at 800k so the
   first ones come quickly). 3% chance per grant of a CURSE engraving — same as
   Tensura's own gear, expected.
7. Absolute Annihilator at 1M EP: the on-hit shockwave should hurt nearby
   enemies for a real amount, and the nova blast should hit noticeably harder
   than it used to.

**Watch for:** the sweep/slice now costs 1 durability per target hit (it runs
the weapon's real on-hit path). Masterwork weapons self-repair from EP, so this
should be invisible — flag it if a weapon visibly degrades.

**Status:** OPEN — compiles green (`./gradlew compileJava`), not yet run in-game.

### 0d. Masterwork weapons — FULL LINE (2026-07-21)

**What changed** (`ExampleMod` items + `MasterworkItem` + `ExampleModClient`,
`DealSpec` cov_dwargon, 12x gear_existence/recipes/models/textures): the Dwargon
Covenant "The Masterwork Commission" (deliver **1 Netherite Block + 1 Hihiirokane
Ingot**) grants a **Masterwork Weapon Core** (renamed from Forging Core) + a
**Masterwork Schematic** (Tensura's native `SmithingSchematicItem` — right-click
to unlock). At the **Tensura Smithing Bench**: `hihiirokane_<type> + core ->
masterwork_<type>`, schematic-gated, for all **12 weapon types**.

**Stats (re-scaled 2026-07-22, 0.2.1 — supersedes the 2026-07-21 numbers):**
MASTERWORK_TIER damage bonus **76** (= hihiirokane), durability **4000**
(> their 3600), enchantability **50** (=), `epGain` **0.04** (=).
gear_existence: **minEP 800,000, maxEP 2,000,000**, ladder
**1.1M/1.4M/1.7M/2.0M -> +5/+7/+9/+11**, and those amounts are **CUMULATIVE**
(sum +32). `MasterworkItem.SHIMMER_TIERS` MUST equal those four EPs and
`EVOLUTION_STEPS` MUST equal those four amounts.

Each weapon is positioned purely RELATIVE to its hihiirokane counterpart
(`MasterworkItem.START_OFFSET` -30, `MAX_OFFSET` +2): freshly forged it is **30
BELOW** its counterpart, at max EP it is **2 ABOVE** it. Katana: 51 -> 83
(hihiirokane katana is 81). Kodachi/short sword: 48 -> 80. Odachi/great sword:
53 -> 85.
⚠ An EP loss drops the weapon back toward its base, forfeiting up to 32.
⚠ 0.2.0 authored this ladder as ABSOLUTE per-tier values (+8/+18/+30/+45) on the
mistaken belief that only the highest-reached tier applied — they compound, so a
katana capped at 184. Weapons forged under those numbers are rebuilt in place by
`GearEvolution.recalibrate` (see entry 0b).

**Look:** tier 0 = sleek steel (static); tiers 1-4 = 10-frame animated strips
(+ `.mcmeta`) whose subtle in-place glimmer matches the hihiirokane weapons
(tuned to their measured ~27% changed / max ~24 / mean ~4.5 per frame). Katana
has bespoke art; the rest come from the sprite sheet. Schematic texture is still
a PLACEHOLDER.

**Abilities** (`MasterworkItem`): on-hit alignment (majin lifesteal + dark burst /
non-majin Regeneration + light burst); right-click branch from the mastered
Battlewill-vs-Magic spread (PHYSICAL sweep spends aura / MAGIC slice spends
magicule / BALANCED none, 30 s cd); mastered-count QOL (10 magnet, 15 step assist,
20 soulbound); EP-backed self-repair; SHIFT-hover ability list.

**How to test:**
1. Creative: grab the Schematic + Core + a Masterwork weapon from the mod tab.
   Right-click the schematic -> gold "techniques learned" message, level-up sound,
   and THE SCHEMATIC IS CONSUMED (count drops by 1 / it leaves the hand).
   Right-click a second copy -> grey "you already know" message and it is NOT
   consumed. Both paths are our `MasterworkSchematicItem`, not Tensura's.
2. Tensura Smithing Bench: hihiirokane weapon + Core -> the recipe should appear
   ONLY after the schematic is unlocked. Craft it.
3. Tooltip: normal hover shows "Hold SHIFT to see abilities"; SHIFT lists them.
   Damage should read ~2 higher than the hihiirokane you consumed.
4. Kill things -> EP climbs from 800k; at 1.1M/1.4M/1.7M/2.0M damage steps up AND
   the blade gains colour/glimmer. Durability should not deplete (EP-backed).
5. Hit a mob as a majin race -> heal a sliver + dark particles; as a human race ->
   Regeneration + light particles.
6. Master 2+ more Battlewills than Magics -> right-click sweeps (aura drops);
   2+ more Magics -> forward slice (magicule drops); even -> nothing.
7. Master 10 / 15 / 20 skills -> drops magnetise (only while HELD) / step up full
   blocks / weapon survives death.
8. Faction system ON: ally Dwargon to Covenant, complete the Commission.

### 0c. Drago Nova charge-up animation (2026-07-15)

**What changed** (`DragoNovaItem`, `ExampleMod.onServerTickPost`): Drago Nova no
longer detonates instantly. `use()` (and the Sage-warning confirm path) now call
`beginCharge`, which spawns a floating no-gravity un-pickable `ItemEntity` orb at
the caster's waist. A new per-tick driver `tickCharges` (called every tick from
the server tick handler, cheap early-return when idle) rises the orb ~1.2 blocks
over `CHARGE_TICKS` (50t = 2.5s) while spawning (a) inward-streaming
`SOUL_FIRE_FLAME` particles, (b) a growing `GLOW` bubble shell (radius 0 → 2.0),
(c) a `SOUL` core glow. At the top of the rise `blast` fires the original AoE
(150 magic dmg in r=12) + optional terrain explosion + unworthy-user backlash,
now centered on the orb position (not the player's current spot). If the caster
logs out mid-charge the AoE still fires; only the self-backlash is skipped.

**How to test:**
1. Get a Drago Nova (creative tab, or Milim Covenant). As a true Demon Lord /
   Hero, right-click it → the item should leave your hand, float up, pull in
   blue particles, grow a blue bubble, and after ~2.5s explode at head height
   (no self-damage). Confirm the item was consumed (1 stack).
2. As a NON-worthy player → same animation, but the blast should kill YOU at the
   end.
3. As a Sage / Great Sage holder → the warning screen still appears first;
   confirming starts the charge, cancelling consumes nothing.
4. Walk away during the charge → the orb stays where cast and detonates there.
5. `dragoNovaBreakBlocks` / `dragoNovaHarmAllies` configs still gate terrain
   damage and ally damage as before.

### 0b. Absolute Annihilator — sprite fix + EP capability (2026-07-15)

**What changed** (`absolute_annihilator.png`, `absolute_annihilator.json` model,
new `data/tensura_minecolonies/gear_existence/absolute_annihilator.json`): (1)
the item sprite had its black background removed (border flood-fill, true-black
only) + colors bled outward + hard-binarized alpha so there are no see-through /
semi-transparent pixels in-hand; texture rebuilt at 32×32 (was 256×256) so the
item/generated extrusion produces coarse/clean depth-teeth instead of a fine
spiky "saw" edge in the 3D in-hand view; the item model parents
`tensura:item/scythe_handheld` for the oversized Blade-Tiger-Scythe-style
render. (2) The weapon is now EP-capable via a `tensura:gear_existence` datapack
entry (minEP 10k / maxEP 1M / epGain 0.01 / cumulative stat evolutions at
150k/400k/700k/1M: attack damage +4/+8/+13/+18, attack speed +0.2/+0.3/+0.4/+0.5,
knockback resist +0.2/+0.2/+0.3 from 400k, max health +4/+8 from 700k) PLUS a
custom effect ladder in `AbsoluteAnnihilatorItem` (see tests 5–7).

**How to test:**
1. `/give @s tensura_minecolonies:absolute_annihilator` (or grab it from the
   creative menu) → the hammer should render LARGE (scythe-sized) in-hand and on
   the ground, fully opaque (no transparent chunks anywhere on the head/handle).
2. Equip it and check the tooltip → it should show a Tensura EP line (EP / max
   EP). Kill some mobs → EP should tick up; at the evolution thresholds the
   attack-damage should climb (20 → up to 38).
3. Complete Milim's "Prove Your Strength" deal → the granted stack should be
   PLAIN (no crushing/Sharpness/Unbreaking) but EP-capable — confirm the EP line
   appears once held/equipped (not only when picked up), that it shows a **Holy
   Coat** engraving (applied by its gear_existence entry on first equip/pickup),
   and that its durability bar matches a **netherite axe (2031)**.
4. **Charged sprite at 500,000 EP** (`ExampleModClient` item property `charged`
   + `absolute_annihilator_charged` texture/model): below 500,000 EP it shows the
   normal sprite; once EP ≥ 500,000 the model override swaps to the charged
   texture (dark detailing glows electric cyan) and switches back if EP drops
   below. Fastest check: temporarily lower `AbsoluteAnnihilatorItem.CHARGE_EP`,
   or farm EP. Cap is 1M so 500k is a clean midpoint.
5. **Charged nova ability at 500,000 EP** (`AbsoluteAnnihilatorItem.use`): with
   EP ≥ 500,000, right-click (in air) → the Drago Nova charge-up + blast fires
   (floating orb, blue particles, explosion) WITHOUT consuming the weapon;
   cooldown sweep shows on the item. Cooldown is **60s**, dropping to **45s** at
   ≥700k EP and **30s** at 1M. Below 500k → right-click does nothing. Worthiness
   applies (unworthy caster is caught in the blast). No Sage warning (that's only
   the one-use Drago Nova item).
6. **On-hit effect ladder** (`AbsoluteAnnihilatorItem.hurtEnemy`): melee an enemy
   and confirm — at ≥150k EP the target gets **Weakness** (~5s); at ≥700k EP you
   **heal** ~8% of your attack damage per hit; at ≥1M EP each hit spawns a
   **sonic-boom shockwave** that damages + knocks back nearby HOSTILES only
   (players, citizens, and ally/race-tagged mobs are spared).
7. **Stat evolutions** (`gear_existence` uniqueEvolutions): as EP crosses
   150k/400k/700k/1M, confirm the tooltip attack damage climbs **20 → 24 → 28 →
   33 → 38** and attack speed **1.8 → 2.3**.
   ⚠ CORRECTED 2026-07-22 (0.2.1): the tiers COMPOUND — each is added to the
   stack's current stats, not swapped in — so the ladder is now authored as
   INCREMENTS (+4/+4/+5/+5 damage). Under 0.2.0's absolute values it capped at
   63 damage instead of 38. Hammers forged under the old numbers are rebuilt in
   place by `GearEvolution.recalibrate`.
   ⚠ FIXED 2026-07-22 (was: the knockback-resistance and max-health steps did
   NOTHING). `GearHandler.getEvolvedAttributeModifiers` only bumps attributes the
   BASE item already declares, and `absoluteAnnihilatorAttributes()` declared
   only attack damage / speed / reach — so those two tiers were silently dropped
   by Tensura. Both attributes are now declared on the base item at **0**, which
   is what lets the tiers raise them; vanilla renders no tooltip line for a
   zero-amount modifier (`ItemStack.addModifierTooltip` only prints for
   `amount > 0` or `< 0`), so nothing shows until it actually grows. VERIFY:
   a fresh hammer's tooltip has NO "+0 Knockback Resistance" / "+0 Max Health"
   line, and at 400k / 700k those lines appear with the right values.
8. **EP reaches the milestones** — confirm the weapon climbs to 500k/1M by
   killing high-EP mobs; EP grows as min(current+gain, maxEP=1M), so 500k is a
   normal midpoint. If it's too slow to test, raise `epGain` or the
   `epGainMultiplier` gamerule.

**Setting EP directly for testing** — there is NO Tensura command for weapon EP.
Two ways:
- **Instant, specific value** via vanilla `/give` (or `/item replace`). You MUST
  set `tensura:max_existence_point` too, because `GearHandler.initiateGearExistence`
  skips (won't overwrite) any stack that already has MAX_EP — otherwise equipping
  resets EP to minEP:
  `/give @s tensura_minecolonies:absolute_annihilator[tensura:existence_point=600000.0,tensura:max_existence_point=1000000.0]`
  Change `existence_point` to hit each threshold (150k/500k/700k/1M). The
  charged sprite / nova / on-hit effects (our code) read EP directly and react
  immediately; Tensura's stat-evolution attribute modifiers only recompute on an
  EP-changing event, so land one hit/kill after giving to see the stat tier apply.
- **Natural path, fast:** `/gamerule epGainMultiplier 1000` then kill mobs — this
  exercises the real stamp + gain + evolution flow, just accelerated.

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
