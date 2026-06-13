# Future ideas (recorded, not scheduled)

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

## The 10+-quests-per-faction CATALOG (pending content pass)

Re-noted: every diplomable faction should carry 10+ faction-exclusive,
faction-geared quests. The FRAMEWORK is complete (per-faction DealSpec
tables, all the Requirement variants incl. SupplyBundle/SlayEntities/
LendCitizens-with-secondaries, tier gating, Covenant milestones, reward
kinds). This remains a dedicated CONTENT-AUTHORING pass — NOT done in
the systems batches — with fullest variety unlocking once Stage-3
rewards (done) and the rival-colony/settlement arc are available as
quest ingredients.



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
