# Future ideas (recorded, not scheduled)

## Verify Form Hide (and similar concealment) still hides the player (2026-07-10)

Check whether Tensura's **Form Hide** — and other identity/aura-concealment
skills/effects that hide the player from mobs (e.g. presence/aura masking,
"hide EP", disguise) — still work as intended given this mod's additions. Our
faction garrisons, raids, threat-response, and assassin systems do a lot of
explicit targeting (`setTarget` / persistent-anger / brain WALK_TARGET steering
+ the nightmareutils Sentient autocaster reading `mob.getTarget()`). Explicit
targeting can BYPASS the normal "can this mob see/sense the player" gate that
Form Hide relies on, so a hidden player might still be found/attacked by our
garrison defenders, steered raiders, or a manifested assassin. Audit the
targeting entry points (`RivalColonies.steerGarrisonToInvaders`,
`TensuraRaids` steering, `ColonyThreatResponse`, `Assassins`) against whatever
"is the player concealed" check Tensura exposes, and gate our targeting on it
where appropriate.

## Raid wave staggering (2026-07-10, from the in-colony-spawn bug report)

The 2026-07-10 raid-placement fix moved the wave spawn to the colony edge
(see docs/raid-system.md + user-bug-reports.md). The report's secondary ask —
staggering the wave over time instead of all mobs in one tick — was
deliberately NOT done: with the wave at the perimeter, all-at-once matches
MineColonies' own raid behaviour. If raids still feel too abrupt in play, a
follow-on could spawn the wave in 2–3 pulses (e.g. a third of the budget every
~20 s) driven from the existing per-second `TensuraRaids` scheduler; the
budget loop in `startRaid` would need to persist its remaining budget on the
event instead of spending it all at trigger time.

## The RIVAL-COLONY ARC is BUILT (A–E, 2026-06-13) — deferred follow-ons

The rival-colony/settlement arc is complete: A (settlement generation —
faux-towns + Dwargon dwarf-villages), B (boss-EP-scaled garrison +
persistence/reset + 60%-win tracking), C (discovery + Declare-War +
teleport-assault loop), D (conquest payoff — citizens + Covenant skill +
loot + defeated husk), E (betrayal scaling — tier-scaled garrison +
relationship shatter). See docs/rival-colony-investigation.md for the
full A–E as-built records. Remaining deferred follow-ons:

- **PvP colony raiding** — the same assault loop turned on another
  PLAYER's colony (not just generated settlements). Its own pass:
  consent/rules, PvP-safety, scheduling, defender ownership.
- **The SIEGE system — broken-alliance super-raids** (sketched below).
  Stage E now provides the trigger: a betrayal (declaring war on an
  OPEN/PACT/COVENANT faction) shatters relations AND records the betrayed
  tier on the settlement — a siege pass can fire a retaliatory super-raid
  scaled by that tier.
- **"Summon absent subordinates first" war-party polish** — Stage C's
  war party is drawn from the player's LOADED subordinates; a polish pass
  could first bulk-summon absent RaceIdentity subordinates (the existing
  bulk-summon path, at its magicule cost) so the picker isn't limited to
  who happens to be standing nearby.
- **Payout / balance tuning** — all the flagged BALANCE-GUESS constants
  (garrison `GARRISON_*` scaling, the `BETRAYAL_MULT_*` tier multipliers)
  want a combat playtest pass.
- **Warfare REWARDS need editing** (TODO, user-requested 2026-06-26). Warfare
  itself works (declare war → assault → conquest), but the conquest payoff
  (`ConquestPayoff` — the citizen levy, the boss's Covenant skill grant, the
  loot-chest pool) wants a rebalance/redesign pass. Decide what a conquest
  should actually award per faction and retune the `CitizenProfile` counts /
  skill grants / `factionRewardPool` accordingly. Separate from the worldgen
  rework. **NOW SCOPED (2026-06-27):** folded into the broader per-faction
  reward review — see [docs/faction-rewards-roadmap.md](faction-rewards-roadmap.md),
  which covers BOTH the raid/conquest payoff and the diplomacy rewards on one
  per-faction checklist (this conquest retune is its Phase 2).
- **Settlement placement polish — Stage 6 (IMPLEMENTED 2026-06-27, pending more
  visual tuning).** Part A laid one continuous flat plateau (`levelTownFoundation`);
  on user feedback ("looks better but cuts some land, generates at tree height,
  want different buildings on different Y") it was reworked to **Part B —
  terrain-FOLLOWING**: (1) `groundSurfaceY` scans past trees/leaves/foliage/snow
  to TRUE ground, fixing the tree-height look (also used by `findBuildableCenter`
  / `surfaceRange`); (2) each building is placed at its OWN local ground Y
  (computed up front) so the town drapes over slopes like a hillside village;
  (3) each gets its own biome-matched, edge-graded pad (`levelBuildingPad`:
  reuses the column's surface block, fills holes, clears terrain incl. trees,
  `FOUNDATION_SKIRT`=6 taper). REMAINING to iterate by eye: tune skirt / clear
  headroom and `BUILDING_PAD_HALF`; very steep sites may still need work (the
  pads can step hard between buildings); cross-faction spacing still optional.
- **Faction mobs must not wander far outside the settlement** (TODO,
  user-reported 2026-06-26). The garrison tether (`tickGarrison`,
  `GARRISON_TETHER_RADIUS = 40`) walks idle strays back to center, but it
  YIELDS to native combat — so a defender that aggros (e.g. Leon's
  Ifrits/Salamanders) chases the target far from the settlement, blasting
  terrain well outside the town. This is the same "fire mobs roaming far,
  catching everything on fire" the original settlement-generation bug report
  described. Future work: contain garrison mobs to a bounded region around
  their settlement even while in combat — e.g. a hard leash (forcibly return
  past a max radius regardless of target), cap chase distance, or make
  defenders disengage + return when pulled beyond a threshold. Applies to
  BOTH the current runtime-placed settlements and the planned worldgen ones.
  Tie the leash radius to a named constant alongside `GARRISON_TETHER_RADIUS`.

## Generated bosses belonging to colonies (rival-colony-arc preview)

Generated bosses should have a CHANCE of BELONGING to a colony. A boss
WITHOUT a colony carries no kill-penalty (killing it is consequence-free
progression); a boss WITH a colony is that colony's, and killing it
feeds the physical-colony-connected systems (faction standing, sieges,
intel). Configurable mode:
- **ALL** — every generated boss belongs to some colony.
- **SOME** — a per-spawn chance (the intended default once rival
  colonies exist).
- **NONE** — disables the physical-colony-connected systems entirely
  (bosses are free-floating; no colony attribution).
This is a preview of the rival-colony / settlement arc — the marked-boss
machinery (FactionMarkTag) already exists; this adds the colony
attribution + the config mode on top.

## The 10+-quests-per-faction CATALOG — DONE (2026-06-12)

Authored: 8 diplomable factions × 10 quests + 3 aloof factions × 4, on
the existing framework. See docs/diplomacy.md "FACTION QUEST CATALOG
AUTHORED". Remaining future expansion: territory/settlement quests
("build at the faction's settlement", "defend their holdings") once the
rival-colony/settlement arc lands as a quest ingredient.



## Sieges — broken-alliance super-raids

A BETRAYED ally hits harder than any stranger: when an ALLIANCE is
SHATTERED by player action (killing the ally's marked boss, standing
crashed below WARY while PACT — not mere decay), the faction launches a
SIEGE — a super-raid above the lore-event class. Sketch:
- Trigger: a `PACT → NONE` collapse caused by an offense (the collapse
  path knows why it fired — thread a reason through).
- Scale: lore-event budget × a betrayal coefficient; multiple waves
  and/or a lead boss + elite guard; possibly multi-night.
- The ally-support machinery inverts cleanly: the faction that fought
  FOR you knows your defenses — flavor for harder
  steering/composition.
- Builds on: the raid engine, the lore-event spine, Stage-3 ally
  support (all exist). Needs: betrayal detection on the collapse path,
  a siege encounter descriptor, balance work.
- UPDATE (2026-06-13): rival-colony Stage E now supplies the betrayal
  TRIGGER — `declareWar` writes `WorldRepReason.WAR_DECLARED` and records
  the betrayed tier (`Settlement.betrayalTier`) before the standing crash
  shatters relations. A siege pass can key off WAR_DECLARED (and the
  recorded tier) instead of inventing new betrayal detection.

## INVESTIGATE — are Tensura coins actually "barrier fuel"? (2026-07-06)

`deps/tensura.md` §1 claims `BRONZE_COIN`/`SILVER_COIN` are "merchant currency
+ barrier fuel." The **merchant-currency** half is verified (Goblin/merchant
`OneForOneTrade` uses `BRONZE_COIN`). The **barrier-fuel** half could NOT be
substantiated on a quick check: our own Barrier Core is fueled by player
magicule + Tensura magic CRYSTALS (`BarrierBlock.crystalValue`), not coins, and
Tensura's own "barriers" are all magic spells/skills, not coin-fueled blocks.
TODO: full-decompile Tensura to find whether coins have any hidden magicule
value / fuel use anywhere; if not, CORRECT the `deps/tensura.md` line (drop or
qualify "barrier fuel"). Low priority, but the deps docs are meant to be
source-grounded, so an unverifiable claim should be fixed.

## Faction standing reacts to the player's race choices/actions (2026-07-06)

Two related ideas for making factions respond to WHO the player is / what they
do, beyond the existing marked-boss-kill fan-out:

1. **Certain actions LOWER standing with certain factions.** e.g. naming a
   majin, or choosing Goblins as the starting race for the colony, should anger
   the human/holy factions (Luminous, Falmuth) — a standing hit when the action
   happens. Hook points: the naming event (majin detection via the existing
   race-side classifier), the race-picker choice (`onRaceChoice`), etc. Ties
   into the faction-model's per-faction disposition already keyed on the
   player's race side.
2. **Starting race changes quest DIFFICULTY per faction.** If the player starts
   Goblin (or Human), certain factions' catalog quests get harder — MUCH harder
   for the factions that dislike that choice (bigger delivery counts, tougher
   slays, higher building/lend bars). A per-faction difficulty multiplier keyed
   on the player's starting race, applied to the deal requirements at offer
   time. Makes the starting-race choice a lasting diplomatic identity, not just
   a cosmetic/spawn choice.

## Higher tiers take MORE deals to advance friendship (2026-07-15)

Idea: make each relations tier require progressively more completed deals to
climb. Right now standing rises a flat amount per deal regardless of tier, so
NEUTRAL→FRIENDLY takes about as much work as FRIENDLY→ALLIED→COVENANT. Make the
higher tiers demand MORE (e.g. scale the standing-per-deal down, or the
threshold up, as the tier rises) so deep alliances feel earned. Tuning knob on
the standing/tier math (`WorldReputationManager` / `FactionTier` thresholds +
`DealSpec.standingReward`).

## Absolute Annihilator — custom Milim weapon (2026-07-15)

**✅ FULLY BUILT (item registered, in creative menu, sprite, given as the "Prove
Your Strength" reward enchanted with crushing + Sharpness V + Unbreaking III,
AND now EP-capable).**
Item `tensura_minecolonies:absolute_annihilator` — a SwordItem on a custom Tier
(gold enchantability 22), attributes: 20 attack damage, 1.8 attack speed, +2.5
ENTITY_INTERACTION_RANGE (reach), EPIC + fire-resistant.
**EP done (2026-07-15):** weapon EP is NOT an item-class/interface thing in
Tensura — it's a datapack entry in the `tensura:gear_existence` registry keyed by
item id. Shipped `data/tensura_minecolonies/gear_existence/absolute_annihilator.json`
(the registry merges across namespaces): `minEP 10k`, `maxEP 1M`, `epGain 0.01`,
plus `uniqueEvolutions` — cumulative per tier at 150k/400k/700k/1M — adding
attack damage +4/+8/+13/+18 (grows 20 → 38), attack speed +0.2/+0.3/+0.4/+0.5,
knockback resist (from 400k) and +max health (from 700k). Tensura's `GearHandler` stamps the EP components
(`TensuraDataComponents.EP/MAX_EP/EP_GAIN/EP_DURABILITY`) on pickup/equip and
grows them on kills — no Java change needed. ⚠ Playtest: confirm the EP tooltip
appears and the evolution ladder fires (esp. that a deal-reward stack given
straight to inventory gets stamped on first equip, not only on world-pickup).
Also worth revisiting: whether "+2.5 reach" (added to the default ~3) matches
intent vs. a total of 2.5. Original spec (user):
- **Sprite:** the 1254×1254 PNG download (`ChatGPT Image Jul 15…png`) — usable
  (square PNG). Fallback if unusable: a plain Netherite Axe.
- **Name:** "Absolute Annihilator". **20 base attack damage.** **Attack speed
  1.8.** **Reach/range 2.5** (needs `ENTITY_INTERACTION_RANGE` +
  `BLOCK_INTERACTION_RANGE` attribute modifiers — vanilla Sword/Axe don't add
  these). **Gold enchantability** (custom `Tier`, enchantmentValue 22).
- **Pre-applied enchants/engravings REMOVED (2026-07-15):** the deal now grants
  the hammer PLAIN (no crushing/Sharpness/Unbreaking). Instead it carries a
  material-line engraving via `gear_existence` — `tensura:holy_coat` level 3
  (mithril/adamantite line; force-stamped past holy_coat's anvil max_level 1, as
  Tensura's own mithril data does at level 2 — bumped to 3 for our higher 1M EP
  max). Durability lowered to 2031 (a netherite axe). holy_coat = anti-holy
  damage; swap to `severance`/`crushing` if an always-on offensive engraving is
  preferred.
- **"Able to have EP":** Tensura weapon-EP — the `Simple*Item` bases are plain
  vanilla extensions (NOT inherently EP-capable), so weapon EP comes from
  Tensura's item-energy/engraving system (`growth`/`transcendence` engravings
  reference weapon "Energy"). NEEDS INVESTIGATION: how a weapon holds/gains EP
  (ManasCore item storage? a base item class? an engraving?) before claiming it
  works.
Build = a new `Item` class + custom `Tier` + attribute modifiers + model +
texture + registration in `ExampleMod` + lang, then give it via the
enchanted-reward path on `mi_mighty_town`.
**TODO — redo the sprite (2026-07-15):** the current texture is an
AI-generated image machine-processed (background flood-fill + alpha bleed +
hard-binarized alpha, downscaled to 32×32 to tame the extruded-side spikes).
It's serviceable but not great — the low res muddies detail and the drawn
silhouette is still spiky edge-on. Replace with a purpose-drawn pixel-art
sprite (ideally 48×48 to match Tensura scythes, clean silhouette, no reliance
on the flat image's baked-in spikes). Files:
`assets/tensura_minecolonies/textures/item/absolute_annihilator.png` AND the
charged variant `..._charged.png` (the charged one is currently machine-derived
from the base by lighting up dark pixels to electric cyan — redraw both together
so the charged look is hand-tuned, not a recolor).
**Charged sprite (2026-07-15, BUILT; threshold 500,000 EP):** the item swaps to a
lit-up "charged" texture via a client item property `tensura_minecolonies:charged`
(reads `TensuraDataComponents.EP`) + a model override. Threshold =
`AbsoluteAnnihilatorItem.CHARGE_EP` (shared with the ability); glow colour is in
the texture (regen via the derive-from-base script). Possible follow-on: more
charge stages (extra override tiers matching the EP ladder), emissive render.

**EFFECT LADDER (2026-07-15, BUILT):** `maxEP` is 1M; the ability unlocks at
500k. Effects live in `AbsoluteAnnihilatorItem` (`hurtEnemy` + `use`), stats in
the gear_existence `uniqueEvolutions` (above):
- 150k — on-hit Weakness (~5s).
- 500k — charged sprite + Drago Nova nova (right-click, no consume, 60s cd).
- 700k — lifesteal (heal 8% of attack damage on hit) + nova cd → 45s.
- 1M — on-hit sonic-boom AoE shockwave (hostiles only, spares players/citizens/
  ally+race-tagged) + nova cd → 30s.
Future tuning knobs: shockwave radius/damage (0.3× attack dmg, r=4), lifesteal %,
Weakness duration, nova cooldown tiers. Engravings: added all-at-stamp (not
threshold-gated) — could ship an innate
themed set (e.g. growth to speed EP gain) if wanted.

## Code cleanup / good-practices pass (2026-07-06)

A dedicated hygiene pass over the codebase (not tied to a feature): remove
UNNECESSARY / dead code and make what's left read nicely and follow good
practices. Candidates already known: the MDK-rename leftovers (`ExampleMod`/
`ExampleModClient`/`Config` names, `com.example.examplemod` package, the
`examplemod` lang-namespace file, vestigial placeholder config options + example
block/item), superseded-but-still-present classes (e.g. the unregistered
`SubordinateTradeButtonHandler`), any remaining `[DIAG]`/debug logging, and the
one very large file (`ExampleMod.java`) that could be split. Also a consistency
sweep: naming, comment density, dead imports, small dup helpers. Do it in
reviewable chunks (rename ≠ behavior change) with a compile + ideally a runClient
check per chunk. Low urgency, high readability payoff.

## Enchanted / engraved equipment as deal rewards (2026-07-06)

**✅ MECHANIC BUILT (2026-07-06, approach B).** `DealSpec` now has an
`enchantedRewards` component (`EnchantedReward(item, count, List<EnchantSpec>)`
+ `EnchantSpec(ResourceKey<Enchantment>, level)`), a delegating 10-arg
constructor (plain deals untouched), and `resolvedRewards(HolderLookup.Provider)`
which all reward consumers use — so enchanted/engraved stacks are materialised
via the world registry everywhere (grant + conquest loot + UI summary). Helper
`DealSpec.engraving("holy_weapon")` builds a Tensura engraving key. First use:
Falmuth "I Need More Steel!" (enchanted Diamond Sword). Extended 2026-07-06 for
ENCHANTED BOOKS (`item == ENCHANTED_BOOK` → writes `STORED_ENCHANTMENTS`) — used
by Tempest's Forbidden Knowledge (Mending) + A Scholar's Reward. See decisions.md.
**Enchant/engrave review DONE for Dwargon + Tempest + Luminous (2026-07-06):**
Dwargon — engraved High Magisteel Katana (Grand Forge) + enchanted Diamond
Pickaxe (A Master's Tools); Tempest — 2 enchanted books + engraved Pure
Magisteel Katana; Luminous — enchanted Netherite Sword (Crusader's Blade),
Diamond Chestplate (Blessed Aegis), Smite book (A Sacred Verse). Still open:
Milim/Eurazania/Clayman/Leon/Eastern Empire (not yet reworked at all), Tensura
engraving *level* semantics / rarity-weighted rolls via `EngravingHelper`, and
runtime-verifying that engravings actually FUNCTION on the chosen weapons.

**Parked engraved-weapon DEALS (deferred, maybe for other factions/colonies):**
- **A Masterwork Blade** — deliver premium mats → an engraved forged sword
  (`severance` + `crushing` + Unbreaking).
- **The Living Blade** — deliver 1 Hihi'irokane Ingot → an engraved weapon
  (`growth` + `magic_weapon`; strengthens with EP).
These were reviewed for Dwargon but NOT added (Dwargon looks good as-is); keep
them as ready-made engraved-gear deals to drop onto a fitting faction later.

Idea: give ENCHANTED / ENGRAVED gear as catalog-deal rewards so martial/smith
factions can hand out real weapons/armor.

**INVESTIGATED (2026-07-06) — one small mechanic covers BOTH:**
- **Engravings ARE enchantments.** Tensura engravings are registry enchantments
  tagged `#tensura:engraving` (`EngravingHelper`, `EngraveCommand`,
  `EngraveEvent`). ~30 of them, many super-thematic: `holy_weapon` (holy dmg),
  `magic_weapon`, `severance`, `crushing`, `energy_steal`, `soul_eater`,
  `barrier_piercing`, `swift`, `sturdy`, `vitality`, `magic_capacity`,
  `elemental_boost`, `transcendence`, `growth`, `restoration`, … So a vanilla
  enchant and a Tensura engraving are applied the **same way** (an
  `ItemStack.enchant(Holder<Enchantment>, level)` off `Registries.ENCHANTMENT`).
- **Do we need a SEPARATE grant mechanic? NO.** The normal reward path is a
  single chokepoint — `DiplomacyManager.giveItems(ServerPlayer, List<ItemStack>)`
  — and it already has the player → `registryAccess`. The only reason we can't
  bake enchants into the reward at `DealSpec` static-load is that enchantment
  Holders need a loaded registry (unavailable at class-load). Fix = carry the
  intended enchant/engraving list on the reward (e.g. a `CUSTOM_DATA` marker, or
  a tiny parallel structure) and APPLY it inside `giveItems` (resolve holders,
  `enchant()`, strip the marker). ~15 lines, one method, handles vanilla enchants
  AND engravings together. `EngravingHelper` also offers rarity-weighted random
  engraving if we want "roll an engraving" rewards.
- **Parked example waiting on this:** Falmuth's **"I Need More Steel!"** → Diamond
  Sword w/ Sharpness III + Looting + Unbreaking (plain for now).
- **TODO once it lands — review these for enchant/engrave gear:** Dwargon
  (smith-forged engraved blades: `magic_weapon`/`severance`/`crushing`; the
  Masterwork Trade below), Luminous (holy: `holy_weapon` blade, Protection
  armor), Tempest (enchanted BOOKS for the academy), Falmuth (the sword).

## Dwargon "Masterwork Trade" at Covenant (2026-07-06)

Idea (user): once the player reaches **Covenant** with Dwargon, unlock a
standing **Masterwork Trade** (a shop, per the "Covenant = a standing SHOP"
idea) that sells **"Masterwork" versions of every metal tier — Low Magisteel →
High → Pure Magisteel → Mithril → Orichalcum → Adamantite → Hihi'irokane**.
"Masterwork" isn't an existing Tensura item tier (no `masterwork_*` gear
exists), so it = the base gear of that tier pre-ENGRAVED / pre-enchanted with
strong Tensura engravings (`severance`, `crushing`, `magic_weapon`, `growth`,
etc.) — the master-smith's finest work. Depends on: the covenant-shop mechanic +
the enchant/engrave-at-grant mechanic above. Naturally Dwargon-exclusive and a
great capstone reason to reach Covenant with the smith kingdom.

## Time-windowed / conditional slay quests — e.g. "in one night" (2026-07-06)

The `SlayEntities` requirement currently just ACCUMULATES total kills over the
deal's whole lifetime (`ActiveDeal.progress++` in `DiplomacyManager.onPlayerKill`);
there's no time window. To support quests like "slay 24 undead **in one night**"
(or "within X in-game days", "without dying", "before dawn"), add a condition to
`SlayEntities` (e.g. a `window` enum / `oneNight` flag) plus reset logic in
`onPlayerKill`: track the game-time of the streak's first kill and RESET
`progress` to 0 (or 1) if the window lapses (new dawn / not night / too long).
Needs a small persisted field on `ActiveDeal` for the streak anchor.
**Parked deal that wants this:** a Luminous **"Crusader's Trial"** — *slay 24
undead in one night* → **Anti-Magic Mask Schematic** + diamonds + coins. Left
out of the catalog for now; add it (if at all) once time-windowed slays exist.

## Covenant = a standing SHOP instead of deals (2026-07-06)

Idea: once a player reaches **Covenant** (or a sufficiently high standing) with a
faction, the deal/quest flow is REPLACED by a standing trade — they can buy (in
some fashion — coins? materials?) the items they'd otherwise have earned through
that faction's catalog deals. At Covenant, they **no longer see any deals** from
that faction; the relationship graduates from "do quests for rewards" to "you're
a trusted partner, just shop." Design notes: the item pool is naturally the
faction's `factionRewardPool` (already computed for conquest loot); the trade
currency is a natural fit for the coin economy (bronze/silver/gold coins); needs
a shop UI (or reuse the merchant/`MerchantMenu` path); gate on
`RelationsState.COVENANT` in the deal-offer loop (suppress offers, open the shop
instead). Complements the "quest bulletin board" idea below.

## Quest deadlines by type + a quest bulletin board (2026-07-06)

Two related diplomacy-deal (quest) UX ideas, captured during the faction-reward
pass:

1. **Per-quest-TYPE time limits scaled to what's reasonable.** Today each
   `DealSpec` carries a hand-set `deadlineTicks`, but they're not consistently
   tuned to the task's nature. Fetch/deliver quests should be QUICK (a few
   in-game days max — you either have the items or you go get them), while
   building-level-up quests should have a VERY long window (leveling a hut to
   L5 takes real time). Do a pass that sets deadlines by requirement type:
   Supply/SupplyBundle → short; SlayEntities → medium; Population/Happiness →
   long; BuildingLevel → very long; LendCitizens → matches the lend duration.
   Could be a helper that derives a sensible default deadline from the
   `Requirement` variant (with per-deal overrides still allowed).

2. **A quest "bulletin board" menu of accepted quests + due dates.** A UI
   panel listing every deal the player has ACCEPTED across all factions, with
   each one's task, faction, progress, and its DUE DATE / time remaining. Today
   the active deals live in `DiplomacySavedData` (`ActiveDeal` with a deadline)
   but there's no single screen to review them — the player has to open each
   faction. A bulletin-board screen (or a tab on the Diplomacy window) would
   surface all commitments and their countdowns in one place.

## The faction quest catalog — the 10+ per-faction content pass

GOAL: every diplomable faction carries 10+ faction-exclusive,
faction-GEARED quests (deals), so each relationship plays distinctly.
The FRAMEWORK is done (Stage 2: per-faction `DealSpec` tables, sealed
Requirement variants, tier gating, lending; Stage 3: reward kinds —
goods, buffs, gifts, spare bosses). This is CONTENT AUTHORING, not
engineering: a dedicated pass writing `FACTION_DEALS` tables out to
10+ entries per faction with per-faction requirement/reward mixes.

Fullest variety unlocks once these exist as quest INGREDIENTS:
- Stage-3 rewards (done) — buffs/goods/gifts as deal rewards, not just
  items.
- The RIVAL-COLONY / SETTLEMENT arc — "build at the faction's
  settlement", "visit their town", "defend their holdings", territory
  requirements; plus Carrion/Milim standing movers beyond ripples.
- Stage 4 mending — the diplomacy-closed recovery ritual as the
  capstone Clayman quest line.

## Race-citizen lending (Stage-2 follow-on)

Lending currently filters to VANILLA colonists (RaceIdentity keys on
citizenId; `resetId=true` on return would orphan identity records).
The follow-on: remap the identity's citizenId on return (or resurrect
with stable ids) so named race-citizens can be lent too.

## Patrol / subordinate commands via Thought Communication (2026-06-27)

Idea (user-suggested, captured for reference — NOT yet scoped): tie the
subordinate command system into Tensura's **Thought Communication**
skill, so commanding a subordinate (today the native command cycle —
FOLLOW → WANDER → STAY → **PATROL** → FOLLOW, see roadmap.md "Patrol
Colony Outskirts" / decisions.md) can flow through the skill rather than
only the per-mob right-click cycle.

⚠ **Intent still open — clarify before scoping.** "Incorporate patrol
into Thought Communication" could mean any of:
- Issue/toggle the PATROL command (and the other commands) **remotely**
  through Thought Communication's UI, instead of having to stand next to
  and cycle each subordinate.
- Surface patrol/command STATUS in the Thought Communication channel
  (e.g. read back which subordinates are patrolling / where).
- A broader group-command surface (command many subordinates at once via
  the skill).

Notes / unknowns:
- "Thought Communication" currently appears in the codebase/docs ONLY as
  the Jura faction Covenant skill REWARD (docs/diplomacy.md) — there is
  no command-system tie-in today. Needs investigation of Tensura's
  Thought Communication skill API (does it expose a usable menu/targeting
  hook we can attach to?).
- The patrol command itself is brain-native via `WALK_TARGET` and is
  inserted into Tensura's own `cycleCommands` by `ISubordinateCommandMixin`
  (`SubordinatePatrol.handlePatrolCycle`). A remote-command path would
  need a server-side way to set the same state without the in-person
  cycle (the `PatrolOrder` attachment + `beginPatrol` already are the
  state; the missing piece is a remote trigger + a UI).

## Player ↔ player colony interactions — the broad design area (2026-06-27)

Captured as a design area to SORT OUT (user-flagged 2026-06-27): how two
(or more) players' colonies relate to each other. Today the mod is
effectively single-colony / owner-gated — identity actions, sends,
summons, diplomacy gifts, and war declarations are all keyed to the
OWNING player. There is no coherent model for what one player can do
with, against, or alongside another player's colony.

This is the umbrella item; existing notes are narrow fragments of it that
should be folded in when this is picked up:
- **PvP colony raiding** (already listed above under the RIVAL-COLONY
  ARC follow-ons) — the assault loop turned on another PLAYER's colony.
  Needs consent/rules, PvP-safety, scheduling, defender ownership.
- **Shared subordinate/citizen inventory access** (user-suggestions.md,
  2026-06-27) — letting OTHER players access a named Tensura creature's
  inventory; explicitly needs an ownership/permission model since today
  identity actions are owner-gated.

Open questions for the eventual design pass:
- Permissions/ownership model — who can view/act on whose colony +
  subordinates (allow-list, MineColonies' own colony permissions, a
  Tensura-side owner check?).
- Cooperative interactions (visiting, helping build, lending across
  players) vs. adversarial ones (raiding, war) — and whether diplomacy/
  reputation extends player-to-player or stays player-to-NPC-faction.
- PvP-safety + consent (opt-in, server config) so the faction/war
  machinery can't grief a non-participating player's colony.

## Custom items need real uses — Masterwork Forging Core etc. (2026-06-27)

The mod ships several custom items that are craftable / obtainable but have
**no functional use** — they're dead-end trophies. They need a purpose
(ingredient in a recipe, a consumable effect, a building/block input, etc.).

- **Masterwork Forging Core** (`ExampleMod.MASTERWORK_FORGING_CORE`,
  recipe `CDC / DPD / CDC` of High Quality Magic Crystal + Diamond Block +
  Pure Magisteel Ingot). Currently it's craftable AND given as the Dwargon
  Covenant reward (`cov_dwargon` "The Masterwork Commission"), but **nothing
  consumes it** — you can make/receive one and then do nothing with it.
  - NOTE (2026-06-27): it was DELIBERATELY left OUT of the new Barrier Core
    recipes (those mirror the Magicule Storage progression instead — silver
    frame + tiered magisteel + tiered magic crystal). A good future home for
    the Forging Core would be a premium/endgame craft (e.g. the top Barrier
    tier, a Covenant-only upgrade, or a Tensura-gear masterwork recipe) so the
    Dwargon reward and the craft both matter.
- **Apito Nectar / Apito's Jelly** (`APITO_NECTAR`, `APITOS_JELLY`) — Apito's
  Jelly is a deal `SupplyItems` input (Milim `cov_milim`), but otherwise these
  are also under-used; review whether they need consumable effects or further
  recipe roles.

General task: audit every custom item for "can the player actually USE this?"
and give the trophy items a real sink.

## Unify currency — MineColonies money → Tensura coins (2026-07-16)

If MineColonies has any currency / money mechanic, route it through Tensura's
coin currency (Bronze → Silver → Gold → Stellar Gold Coin, + Coin Pouches) so
the whole modpack shares ONE money system instead of two parallel economies.
Our diplomacy deals already pay out in Tensura coins; the goal is that the
colony side uses the same coins rather than a separate token/emerald economy.
NEEDS INVESTIGATION FIRST: determine what (if any) currency MineColonies
actually uses in this version — colonists don't natively use money, but check
the Bazaar / merchant / any coin-like item or trade token, and whether Tavern /
recruitment / trades consume anything spendable. Then either (a) swap that
item for the Tensura coin in the relevant recipes/handlers, or (b) add a
converter/bridge. Scope depends entirely on what the investigation finds — may
be small (a couple of recipes) or large (a trade-handler mixin). Keep it behind
a config toggle if it changes vanilla-MC economy behaviour.

## Legendary weapons — ally-forged, player-stat-scaling (2026-07-15)

**Config-optional** (a toggle; off unless enabled). A big system: certain ALLIES
can FORGE a legendary weapon for you after you complete a special deal / trade
with them, then wait a few in-game days (the forging takes time — like the envoy
/ deal cooldowns). The Absolute Annihilator is the first hand-built proof of the
"weapon that grows" idea; this generalizes it.

**Progresses with PLAYER status, not just weapon EP.** The weapon reads the
wielder's Tensura standing and levels its abilities/stats off multiple signals:
- Demon Lord SEED / Hero SEED status.
- TRUE Demon Lord / TRUE Hero status (bigger unlocks).
- Named status.
- Number of skills MASTERED — and this may **BRANCH**: the weapon changes based on
  WHICH TYPES of skills you've mastered, so it reflects the player's build /
  preferences (a mostly-Sword/physical masterer gets a different legendary form
  than a mostly-Spatial/magic masterer). The mastered-skill spread is the "class
  identity" input.

**Prestige = a DEBUFF, not a reset.** On prestige the weapon is DEBUFFED (its
current power dips, matching the player's reset progression), BUT the ABILITIES
it has unlocked are NOT lost — they stay, just temporarily weakened, so prestige
feels rewarding rather than punishing (you re-earn the numbers, keep the toys).

**Ties into the Masterwork idea.** Fold this into the Masterwork Forging Core
([above](#custom-items-need-real-uses--masterwork-forging-core-etc-2026-06-27)):
completing the Covenant deal grants a **Masterwork Forging Core**, which the
player uses to craft a **masterwork version of ANY weapon Tensura adds** — the
masterwork variant is the "legendary" one that grows with the player as above.
This gives the Forging Core its long-missing sink AND makes the legendary system
apply to the whole Tensura weapon roster, not just one custom item.

Implementation notes / seams: the Annihilator already proves the pieces — EP-gated
effects in a custom `Item` (`hurtEnemy`/`use`), a client model-override sprite
swap, and Tensura `gear_existence` stat evolutions. A legendary layer would add:
a per-player-status read (reuse `ExampleMod.readExistence` for DL/Hero/true
status; SkillAPI for mastered-skill counts + type breakdown), a branch selector
keyed on the mastered-skill spread, and a prestige hook that scales-down without
clearing unlocked abilities. Keep it all behind the config flag.

**DECIDED so far (2026-07-16, Masterwork = the Dwargon-covenant instance):**
- Crafting is NATIVE — Tensura's Smithing Bench (`tensura:smithing_bench` recipe,
  up to 5 inputs + a `schematics` unlock gate + `SmithingSchematicItem`). Recipe:
  `hihiirokane [weapon] + Masterwork Core → Masterwork [weapon]`, gated by a
  Masterwork schematic. No custom recipe code.
- BRANCH = mastered **Battlewills vs Magics**: ≥2 more battlewills → PHYSICAL lean;
  ≥2 more magics → MAGIC lean; otherwise BALANCED.
- 15+ skills mastered → unlocks a QOL/utility passive (ability TBD).
- Also reads: EP (gear_existence), majin/human alignment (two forms), DL/Hero
  seed + true status, prestige (decaying debuff, abilities kept).
- **Unique-skill-SPECIFIC buffs = DEFERRED / MAYBE-NEVER (user, 2026-07-16):** a
  possible future layer where a specific unique skill grants a specific weapon
  buff (not just counts). Explicitly not committed — parked here. For now the
  weapon only uses the battlewill-vs-magic spread + the 15+-mastered QOL unlock;
  it does NOT read individual unique skills.

**LOCKED (2026-07-16, cont.):**
- **cov_dwargon task** = deliver **1 Block of Netherite + 1 Hihiirokane Ingot**.
- **Core** = reuse `MASTERWORK_FORGING_CORE`, relabel display "Masterwork Weapon
  Core". **One** schematic unlocks the whole line. First cut = 3 weapons
  (sword, katana, great sword).
- **Alignment** (classifier = `WorldReputationManager.isMajinSide`, confirmed a
  binary majin-vs-non-majin): MAJIN → slight lifesteal; NON-MAJIN → slight regen
  boost. Dark on-hit (majin) / light on-hit (non-majin).
- **Branch right-clicks** (battlewill-vs-magic mastered spread): PHYSICAL → a
  sweep attack (for now a sweeping-edge particle arc in front, 30s cooldown);
  MAGIC → a magic-slice projectile that flies forward; BALANCED → base weapon
  (no special right-click).
- **Prestige debuff**: on prestige, base damage drops to a floor of **10**
  (cut% = (current−10)/current); apply that SAME % cut to every other stat;
  recovers as EP regrows. Abilities/forms are kept.
- **Aura/Magicule → log-curve damage multiplier = DEFERRED (do not build yet).**
  RESEARCH (2026-07-16): a per-race split IS real — every `TensuraRace` defines
  BOTH `getBaseAuraRange()` and `getBaseMagiculeRange()`, entities track
  `getAura()`/`getMagicule()` separately + an `isSpiritualForm()` flag. Direction
  (Tensura energy model): magicule = monster/majin/spiritual energy (magic +
  skills), aura = warrior/physical energy (battlewill) — so majin/spiritual skew
  magicule, human/physical skew aura, but EVERY race has some of BOTH (a ratio,
  not a binary). ⇒ RECOMMENDATION: when we do build it, key the multiplier to
  the WEAPON BRANCH (physical→aura, magic→magicule, balanced→EP or max), NOT to
  majin/non-majin — cleaner and uses values that already exist. (Exact per-race
  ratios weren't extractable via javap; would need decompile / in-game to prove
  magnitude, but the structural split is confirmed.)

## Citizen aggression — a "Progressive" level (2026-06-27)

Idea (user-requested 2026-06-27): add a fourth value to the `citizenAggression`
config (today `OFF` / `MEDIUM` / `HIGH`, default OFF — see
`Config.AggressionLevel` + `TensuraBehaviourHelperMixin`, and decisions in the
CHANGELOG). **PROGRESSIVE** would SCALE how aggressive innately-hostile Tensura
mobs are toward colonists based on how powerful / developed the colony is —
weak/young colonies are mostly left alone, strong/established ones draw more
aggression. Makes the threat ramp with the player instead of being a flat
on/off.

What it could scale on (pick one or a blend, all already reachable):
- **Total colony EP** — sum of named race-citizens' / colony strength (the raid
  system already computes a colony-strength EP figure in `TensuraRaids`; reuse
  that math so raids and aggression share one "how strong is this colony"
  number).
- **Hut / building levels** — sum or max building level (MineColonies
  `IBuildingManager`), a proxy for development.
- **Citizen count** — simplest proxy; already used for envoy unlocks.

Implementation sketch:
- The mixin's MEDIUM branch already computes a stable per-(mob, citizen)
  acceptance coin. PROGRESSIVE would replace the fixed ~50% with a
  colony-derived probability `p` (e.g. `p = clamp(strength / THRESHOLD, 0..1)`),
  still hashed to a stable per-pair decision so it doesn't flicker.
- Resolve the citizen's colony in the predicate (citizen → `getCitizenColonyHandler`
  / colony id) to read its strength. Cache per-colony so it isn't recomputed
  every targeting check (the per-second schedulers are a natural home to
  refresh a cached `colonyId → aggressionProbability` map).
- Tunable threshold(s) as named constants; decide the curve (linear vs. eased)
  and a floor so brand-new colonies aren't instantly hunted.

Open question: which input feels best (EP vs. hut levels vs. count) — EP tracks
actual power, hut levels track investment/visibility. EP is the most
thematically "they notice strong magicule signatures," and reuses existing raid
math.

## Barrier projectile blocking — close the cheese gap (2026-06-27)

Balance change (user-requested 2026-06-27): the barrier field should also
stop **projectiles** from passing through, in BOTH directions, to prevent
cheesing a defended position. Today the field pushes back / drains
barrier_blocked-tagged hostiles and raiders (entity contact), but
projectiles fly straight through, so a player (or their citizens /
subordinates) can stand safely behind a barrier and shoot out, and ranged
attackers can shoot in.

Specifically block (do not let pass the active wall):
- **Player** projectiles (arrows, thrown items, Tensura ranged skill
  projectiles the player fires).
- **Colonist** projectiles (e.g. archer guards firing out).
- **Subordinate** projectiles (named Tensura mobs firing out).
- (Already-intended) hostile / raider projectiles firing IN.

Notes / unknowns to resolve when scoped:
- The existing spherical/sectional barrier already does some
  **projectile blocking** for the spinning shell (see commits
  `07303b8 feat(barrier): spherical sectional barrier redesign + projectile
  blocking` and `54d3f5a` "block enemy skills"). Confirm what that path
  already catches and whether it's direction-aware — this may be
  extending existing logic rather than net-new.
- Decide the rule cleanly: block ANY projectile crossing an active wall
  layer regardless of owner (simplest, symmetric, hardest to cheese), vs.
  owner-aware exceptions. User intent = block player/colonist/subordinate
  outbound too, so the simple "block all crossing projectiles" rule
  matches.
- Implementation likely a per-tick AABB/segment test against the active
  wall footprint in `BarrierBlockEntity`, consuming barrier pool on a
  block (mirror the contact-drain idiom), or a projectile-impact hook.

## Creative-tab polish — name + icon (2026-06-27) — ✅ DONE (2026-06-27)

Implemented: tab title key switched to `itemGroup.tensura_minecolonies`
("Tensura MineColonies") and the icon switched from the MDK `EXAMPLE_ITEM`
to `DRAGO_NOVA` in `ExampleMod.EXAMPLE_TAB`. The broader MDK-rename debt
(package / class / asset-namespace) is still outstanding (see below).

Cosmetic housekeeping (user-reported 2026-06-27): the mod's creative-menu
tab still shows the **MDK placeholder** — title "Example Mod Tab" and the
default purple-and-black checkered (missing-texture) square as its icon.

Fix when convenient:
- **Icon:** use the **Drago Nova** item sprite as a placeholder tab icon
  (the item already exists, see DragoNovaItem). A proper dedicated tab icon
  can come later.
- **Name:** rename the tab to the mod's real display name ("Tensura
  MineColonies Integration", or a shorter label like "Tensura
  MineColonies").

Part of the broader MDK-rename housekeeping debt (see CLAUDE.md "Known
housekeeping debt" — `com.example.examplemod` package + `ExampleMod` class
names + the `examplemod` asset-namespace lang file). The creative tab's
title/icon are likely defined alongside that placeholder naming; tidy them
together with that pass, or do this small cosmetic fix standalone.

## Bred race children: leave them UNNAMED (2026-06-29)

Race colonies now breed their own kind — a goblin/orc/dwarf/lizardman colony
that grows via MineColonies reproduction produces a baby of that race (see
`ExampleMod.onReproductionChild` / `mintRaceChildCitizen`, driven by
`ReproductionManagerMixin`). Per the implementation decision, bred children are
currently **auto-named**: they get the same starting skill bias + named-citizen
happiness modifier a hand-named citizen receives, so they are full race
citizens immediately with no naming step.

**Idea (deferred):** make bred children **unnamed** instead — born as ordinary
goblins/etc. that the player can later choose to *name* (in Tensura, naming is
the evolution event that turns a goblin into a hobgoblin and empowers it). This
is closer to the source material — Rimuru's village is mostly unnamed goblins
with a chosen few named into hobgoblins — and would make the naming ceremony
meaningful for colony-born members, not just wild intake.

Implications to work through if pursued:
- A born-unnamed child needs a render variant (base race form, not evolved) and
  should NOT get the named skill bias / happiness until named.
- A path to *name an existing colony-born citizen* (today naming runs on a wild
  mob via `onRaceNamed`; a born citizen has no wild body unless summoned first).
  Likely: summon the subordinate body, name it, then the existing pipeline
  applies — or a dedicated "name this citizen" UI action.
- The evolved (hobgoblin) appearance: `evolutionState` is NBT-only with no
  public setter (see `applyGoblinVariant`), so faithfully rendering the
  post-naming evolved form needs Tensura's evolution mechanism, not just a
  variant field flip. Auto-named children today therefore render as the base
  race form regardless; this is the same gap.

## Bred race children: surnames + inherited traits (2026-06-29)

Follow-on to the integrated child-growth work (`ExampleMod.onReproductionChild`
/ `mintRaceChildCitizen`) and pairs with the "leave them UNNAMED" idea above.
Today a bred race child gets a MineColonies pool name (race-agnostic) and a
freshly-randomised variant with no link to its parents beyond MC's family tree.
Idea: make lineage MEAN something — a child should carry its parents' name and
inherit a slice of their power.

**Surnames / lineage.**
- Give bred children a **surname drawn from their colony parents** (MC already
  passes both parent names into `generateName`; the hook point is the same
  `trySpawnChild` naming path — note MC overrides our name after the
  `@WrapOperation`, so a themed/inherited name needs a second wrap/skip of the
  `generateName` call). Optionally a race-themed given name + inherited surname
  so a family reads as one house (e.g. all "…of the Rigur line").
- Ties into the naming-ceremony idea: an UNNAMED child keeps a plain lineage
  name until the player names/evolves it.

**Inherited power (the interesting part).** All EP = Tensura magicule + aura;
read/write via the existing `IExistence` / EnergyHelper idioms already used by
the assassin EP-theft and stat-sync code.
- **Partial EP transfer** — a child is born with a fraction of the *average of
  its parents'* base max EP (e.g. 10–25%), so a colony of strong named parents
  produces stronger children. Cap it so it can't runaway-compound across
  generations.
- **Base EP increase / heterosis** — small flat or percentage bump when both
  parents are named (hobgoblin-tier), rewarding investment in naming.
- **Skill transfer / copying** — a chance to pass one or more parent skills to
  the child (mirror the assassin skill-copy path: `SkillAPI.getSkillsFrom`,
  learn intrinsic/resistance skills). Weight by parent mastery; maybe a low
  chance for a strong active, higher for passives/resistances. Could gate rare
  skills behind BOTH parents having them.
- **Skill-profile bias inheritance** — instead of (or on top of) the flat
  per-race `RaceSkillProfiles` bias, nudge the child's MC skill init toward the
  parents' strong stats (a builder couple → craftier kids).

**Design cautions to work through if pursued.**
- Anti-runaway: cap generational compounding (EP and skills) so a colony doesn't
  breed itself into invincibility. Diminishing returns or hard ceilings.
- Balance vs. the naming ceremony: naming should still be the *big* power step;
  inheritance is a gentle nudge, not a replacement.
- Where the parent snapshot comes from: bred children currently mint their
  body/variant from a transient wild mob, NOT from the parents — inheritance
  would need to read the parents' `RaceIdentity` / EP at birth (both parents are
  known in `trySpawnChild`; thread them through `onReproductionChild`).
- Persistence + reload safety: any inherited EP/skills live on the child's
  `RaceIdentity` snapshot like the rest of the two-bodies state.

## Magicule Storage blocks: possibly redundant after core-pool stacking (2026-07-13)

The colony core-network change (multiple Barrier Cores in one colony share one
centered barrier and POOL THEIR CAPACITY) overlaps the Magicule Storage block's
whole job: extra capacity is now available by just placing another core, which
also brings its own tank AND raises nothing extra (the network uses the highest
tier core's radius/sections). Storage blocks are KEPT for now, but their niche
has narrowed to "cheaper capacity per block than a full core." Options recorded
for a future pass (pick one deliberately, don't drift):

1. **Repurpose (preferred candidate)** — give storage a job cores can't do:
   e.g. passive trickle-refill (storage slowly recharges from ambient/area
   magicule, cores don't), or act as the REPAIR battery (section repairs must
   be paid from storage, cores only power upkeep), or player-withdrawable bank
   (a colony magicule ATM; cores stay barrier-only).
2. **Keep as-is** — cheaper capacity/block is a real (if thin) niche; recipes
   already climb by tier. Zero work.
3. **Remove** — delete the blocks + recipes, migrate existing placed storage by
   refunding contents into the adjacent core network. Cleanest world model, but
   invalidates crafted items in existing saves.
4. **Rebalance instead** — lower the cores' base capacities (100k–250k) so
   storage is *needed* again for a deep tank; makes multi-core stacking a
   radius/redundancy choice rather than a capacity one.

## Evil barrier variant — ability-suppression field (2026-07-13, needs expanding)

A craftable EVIL counterpart to the Barrier Core (its own tier ladder) that
doesn't wall enemies out but SUPPRESSES what they can do inside the field —
anti-magic / sealing-barrier flavour (Tensura's holy-field/anti-magic ward
imagery). Recorded early; needs a full design pass before any build.

Sketch of the idea as given + first open questions:
- Prevents "factions from using certain things" inside the field — candidate
  suppressions per tier: block skill/magic casting (hook the same
  LivingIncomingDamageEvent / skill-cost seams the barrier already uses),
  silence resistances, slow EP regen, disable teleports, weaken specific
  elements.
- **EP-limited**: each enemy/boss resists suppression based on its EP vs the
  field's strength — a weak field can't silence an Orc Disaster; scale like the
  existing EP-scaled drain (attacker EP × multiplier). Tier raises the EP
  ceiling the field can suppress.
- Tiered like the core (radius + suppression ceiling + upkeep climb); evil
  aesthetic (dark sprites/tint) and possibly an alignment/repute cost to run it
  (colony reputation or faction standing penalty while active?).
- Open questions: does it affect OUR citizens/subordinates too (double-edged)?
  fuel type (magicule, or something darker — soul points?); does running one
  flip Holy-bloc dispositions (ties into the majin side-watch); interaction
  with raids (suppressing a raid boss's skills trivializes lore events?).

## Barrier main-thread cost — throttle the per-tick entity scans (2026-07-13)

Recorded from an optimization discussion. NOT async — Minecraft's world state
(entities, block entities, EP storage, colony data) is single-threaded and
moving barrier work off the main thread would race the save. The real lever is
LESS main-thread work.

`BarrierBlockEntity.serverTick` runs two `getEntitiesOfClass` AABB scans (mobs
+ projectiles) EVERY tick, per active barrier. MC buckets entities by
chunk-section so the cost is ~"entities near the barrier," not sphere volume —
but every tick is more often than the effect needs. Options, all main-thread,
all safe, do them only if a `runClient` profiling pass shows the barrier
actually costs something (don't optimize on a guess):

1. **Throttle the mob/projectile scans to every 2–3 ticks**, accumulating
   section damage across the interval — visually indistinguishable, cuts that
   scan cost by half to two-thirds.
2. **Cache the per-second colony lookup** in `resolveNetwork`
   (`getColonyByPosFromWorld` every second per core → refresh every few
   seconds; a core's claiming colony rarely changes).
3. **Stagger the per-second schedulers** (`TensuraRaids`, `ColonyThreatResponse`,
   garrison, diplomacy, envoy, rival-colony) so they don't all fire on the same
   tick — spread work across the 20-tick second.

Already banked (not a future idea, just context): multi-core networking made
only the elected PRIMARY run the field driver, so a colony with N cores went
from N per-tick entity scans to 1.
