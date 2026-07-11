# User suggestions (recorded, not scheduled)

Community feature requests, kept here for future consideration. These are
captured as the user phrased them (lightly clarified) — not commitments, and
not yet evaluated for feasibility. When one is picked up, move the design work
into `docs/future-ideas.md` / `docs/roadmap.md` and a decision into
`docs/decisions.md`.

## 2026-07-10

1. **Magic barrier should scale with the size of the colony.** The player
   believes the barrier does NOT currently size itself to the colony — a big
   colony isn't fully covered.
   - *Suggested balancing:* require MULTIPLE barrier blocks of the same tier to
     cover a bigger colony (bigger colony → needs more cores), rather than one
     block auto-growing without cost.
   - *Suggested re-anchoring:* make the barrier's POSITION depend on the COLONY
     rather than the block itself. Overlapping barriers (from multiple blocks)
     are "kinda ugly to look at"; anchoring to the colony would let the player
     place barrier blocks in several spots at once for better aesthetics /
     customization without visually clashing fields.
   - *Dev note:* today the barrier is a square footprint centered on the Barrier
     Core BLOCK with a fixed per-tier radius (16/28/42/60), independent of colony
     size — see `BarrierBlockEntity` (field driver) + `BarrierBlock` (tier radii)
     and docs/raid-system.md. This ask is a design shift from block-anchored to
     colony-anchored coverage, plus a multi-block "coverage budget" model. Not
     yet evaluated.

2. **True Hero should give a DIFFERENT third-layer barrier buff than the Demon
   Lord.** Currently the top barrier layer (layer 3) is gated by Demon Lord /
   Hero (see docs). The player suggests the True Hero variant grant a distinct
   effect instead of the same one — e.g. HP regeneration, OR a debuff that lowers
   the stats of non-humanoid races by 10% (their example: "non-humanoid" =
   excludes Humans, Elves and Dwarves; everything else takes the −10%).
   - *Dev note:* layers 1–3 exist with a DL/Hero gate on the upper layer
     (`BarrierBlockEntity`, docs/raid-system.md); this asks to split the Hero
     path from the DL path so the two grant different field effects. Not yet
     evaluated.

3. **Make the barrier prevent Tensura and hostile mobs from SPAWNING inside it.**
   The player asks whether a fueled barrier could stop hostile (and Tensura) mob
   spawns within its field — so nothing materializes inside the protected area.
   - *Dev note — may ALREADY be partially implemented:* CLAUDE.md /
     docs/raid-system.md record "hostile-spawn prevention inside fueled barriers"
     as shipped (post-v1 barrier expansion). Needs VERIFICATION whether that
     coverage (a) actually works in-game, and (b) includes TENSURA mob types
     (not just vanilla `MobCategory.MONSTER`). Directly related to the raid
     bug report below (Tensura raids spawning mobs INSIDE the colony) — a working
     in-field spawn block would mitigate that too. Check
     `BarrierBlockEntity` for the spawn-suppression logic.
   - *Update (2026-07-10), raid half handled:* the raid-placement fix (see
     docs/user-bug-reports.md 2026-07-10) confirmed raid spawns BYPASS the
     `MobSpawnEvent.PositionCheck` suppression (direct `addFreshEntity`) —
     the fix instead rejects fueled-barrier footprints in the raid
     spawn-point selection itself, so raiders always materialize OUTSIDE any
     fueled barrier. The NATURAL-spawn suppression (PositionCheck keyed on
     the `tensura_minecolonies:barrier_blocked` tag, which does cover
     Tensura's hostile types) was already shipped; only its in-game
     verification remains open.
   - *IMPLEMENTED (2026-07-10) — coverage broadened, built green:* verified at
     the code level that (b) TENSURA mobs ARE covered — `barrier_blocked` pulls
     in `#tensura:hostile_monster` (all real Tensura hostiles), and Tensura's
     wild spawns use standard `neoforge:add_spawns` → vanilla `NaturalSpawner` →
     posts `PositionCheck`. The old hook only accepted `NATURAL` +
     `CHUNK_GENERATION`, so dungeon/mob spawners, pillager patrols, trial
     spawners, and reinforcements could still pop hostiles inside a fueled
     barrier. Fixed: the shared `BARRIER_BLOCKED_SPAWN_TYPES` set now covers the
     whole *environmental* set (chosen scope: block involuntary/world spawns,
     leave deliberate player placement alone), split across the existing
     `PositionCheck` hook (NATURAL/CHUNK/SPAWNER) and a NEW `FinalizeSpawnEvent`
     hook (patrol/trial/reinforcement/jockey/structure/event). Works whenever
     the barrier is fueled (peacetime included). Raids stay outside, unaffected.
     Full as-built in docs/raid-system.md ("IN-FIELD SPAWN SUPPRESSION —
     BROADENED"). (a) In-game verification still OPEN — see docs/playtesting.md.

4. **Lightweight kingdom-conquest system (expand rival settlements into a kingdom
   with continuing purpose).** A large design proposal to give conquered
   settlements ongoing meaning instead of being inert after the battle. As
   phrased by the player:
   - *Kingdom structure:* a kingdom contains outposts, villages, cities and one
     capital. Smaller settlements SUPPORT the capital with food, materials,
     reinforcements or magical protection. The player can attack the capital at
     full strength, or conquer supporting settlements first to WEAKEN it.
   - *Claiming:* after defeating a settlement leader, replace its banner and claim
     the location.
   - *Not full colonies (respects MC's one-colony limit):* captured settlements do
     NOT become full MineColonies colonies — the player's original colony stays
     the only fully managed one. Conquered locations become simplified,
     addon-controlled TERRITORIES with four attributes:
     - **Loyalty** → affects tribute, military support, and rebellion risk.
     - **Development** → affects the amount and quality of resources sent.
     - **Garrison** → helps resist rebellions and enemy reconquest.
     - **Specialization** → defines the reward type (food, ores, reinforcements,
       or Tensura materials).
   - *Governors:* assign a named Tensura subordinate as governor of a territory;
     while governing, that subordinate is UNAVAILABLE for work or combat.
   - *Staged rebellions (give the player time to react):* reduced tribute → unrest
     → refusal to cooperate → open rebellion.
   - *Post-conquest choice per settlement:* ANNEXATION (more tribute, lower
     loyalty), VASSAL (less tribute, greater stability), ALLIANCE (support without
     direct control), or LOOT (immediate reward at the cost of damage and
     hostility).
   - *Player's stated reason:* the current conquest system gives little reason to
     care about a settlement after the battle. This gives conquered locations a
     continuing purpose, benefits the main colony, creates reasons to defend
     territory, and adds meaningful choices — while respecting MineColonies'
     limits (simplified territories, not full colonies).
   - *Dev note:* this is a large extension of the completed rival-colony arc
     (Stages A–E, currently conquest = DEFEATED HUSK + one-time citizen/skill/loot
     payoff — see docs/rival-colony-investigation.md and DESIGN CHANGE 2
     "conquest is REWARDS-ONLY, no second colony"). It aligns with the arc's own
     deferred "SIEGE system" seam and the future-ideas notes. Significant new
     persistent-state + tick systems (per-territory loyalty/development/garrison/
     specialization, tribute economy, rebellion state machine, governor binding).
     Not yet scoped or evaluated.

## 2026-06-29

1. **Patrol team only circles the town hall, not the whole colony (reported as a
   bug).** When the "Patrol Colony Outskirts" command is on duty, the NPCs only
   walk around the town hall area instead of patrolling the colony's edges.
   - *Status: likely a real bug, not just a suggestion.* The patrol is supposed
     to walk the OUTER RING of the colony's claimed chunks
     (`SubordinatePatrol` — outskirts = outermost claimed chunks, water
     avoided). If they're staying near the town hall, the outskirts-ring
     targeting or the colony-bounds lookup may be resolving to the colony
     center rather than the perimeter. Needs investigation in
     `SubordinatePatrol` (the WALK_TARGET steering + outskirts computation).
   - *Related ask below — the player wants a stationary "guard a spot" mode too.*

2. **A "stand and guard" / sentry command.** A command that makes NPCs stand
   still in place and attack anyone (hostile) who approaches them — a stationary
   guard post, as opposed to the roaming patrol. Would complement the existing
   FOLLOW / WANDER / STAY / PATROL command cycle (STAY already keeps them in
   place, but doesn't make them engage approaching threats).

## 2026-06-28

1. **High-EP bosses (e.g. Ifrit) take too little damage from strong vanilla
   weapons.** A player reported hitting an Ifrit with a very strong sword and it
   barely taking damage — its very high EP makes it extremely tanky.
   - *Status: NOT a regression we caused.* The mod never lowers mob stats/EP
     (verified across git history + code: every attribute change scales mobs
     UP — garrison/boss/assassin buffs — never down). This is Tensura's core
     combat model: EP acts as effective durability and Tensura gear/skills
     scale damage off EP, so vanilla weapons underperform against EP-heavy
     mobs. Leon's garrison scaler actually depends on Ifrit's high EP.
   - *If picked up,* the options are: (a) a config to scale DOWN specific
     boss/mob EP or apply a vanilla-damage-vs-EP multiplier for our
     marked/garrison mobs only (keep it scoped — don't globally override
     Tensura balance); or (b) treat it as working-as-intended (fight EP-heavy
     bosses with Tensura-tier weapons/skills, not vanilla swords) and just
     document it. Needs a decision before any work.

2. **Audit the EP of FACTION mobs so the damage dealt is appropriate.** Review
   the EP (and the derived effective durability / damage) of the faction
   garrison defenders and faction bosses — the ones the mod spawns/scales — to
   make sure fights land in a reasonable range (not so tanky that even strong
   weapons barely scratch them, not trivially weak). This is the concrete
   review-task companion to item 1 above (which is the player-facing "Ifrit too
   tanky" report + the general EP-vs-damage principle).
   - *Scope:* the mod's own faction content — `RivalColonies` garrison spawns
     and the `GARRISON_*` / boss-EP scalers, plus the marked faction bosses.
     Cross-check against what damage a reasonably-geared player actually deals.
   - *Note:* tie this to the faction-rewards balance work — a faction's
     conquest difficulty (boss/garrison EP) should match its reward tier (see
     `docs/faction-rewards-roadmap.md`), so this audit feeds Phase 2 (conquest
     balance). Keep any EP changes scoped to the mod's faction mobs, not a
     global Tensura override.

## 2026-06-27

1. **Per-profession colonist customization + shared access to a Tensura
   creature's inventory.** Two related asks:
   - *Customize how a race-citizen looks/behaves when assigned a job* (e.g. as a
     lumberjack or farmer) — likely in the work-area / "range plan" (field/work
     area) setup. I.e. give the player some control over the working appearance
     or settings of their Tensura citizens per profession.
   - *Let OTHER players access a named Tensura creature's inventory* — i.e.
     shared/multiplayer access to a subordinate/citizen's inventory, not just the
     owner. (Needs an ownership/permission model — today identity actions are
     owner-gated.)

2. **An in-game advisor/help NPC.** An NPC the player can talk to for guidance
   on how the mod works — a tutorial / "explain the mod" helper to lower the
   learning curve. (Could tie into the existing envoy/dialogue-screen patterns.)

3. **Working subordinates don't pick up everything they harvest.** When a Tensura
   subordinate is given a hoe or an axe, they work a field/tree but leave some of
   the drops behind. Increase their item-pickup range, or have them path to and
   collect items lying on the ground.
   - *Dev note (from the maintainer): this may just be default subordinate/AI
     behaviour from Tensura — needs to be looked into before deciding what (if
     anything) to change.*
