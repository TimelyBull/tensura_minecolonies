# Future ideas (recorded, not scheduled)

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

## Mob aggression — a "Progressive" level (2026-06-27)

Idea (user-requested 2026-06-27): add a fourth value to the `mobAggression`
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
