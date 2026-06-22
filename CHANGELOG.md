# Changelog

All notable changes to **Tensura MineColonies Integration** are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/).
Copy the relevant version's section into the CurseForge release notes on each update.

## [Unreleased]

### Added
- **Barriers are now spheres made of panels.** A barrier is a dome/sphere
  around its core, built from 24 panels (each concentric layer is its own
  sphere). Each panel takes damage on its own: hammer one spot and that panel
  dims through three stages and then shatters into a hole — while the rest of
  the barrier stays up. Enemies can slip through a hole until it heals, but the
  intact panels still block and shove them back. The sphere sinks partway into
  the ground, which is intended.
- **Broken panels heal back.** A shattered panel starts mending 15 seconds
  after it was last hit, growing back in three steps (one every 15 seconds).
  Each step draws fuel from the barrier's pool, so with no fuel a hole stays
  open until you refuel.
- **Panel toughness scales with the core tier.** Each panel can soak
  10,000 / 20,000 / 40,000 / 60,000 damage at tiers 1–4 before it breaks, so
  higher-tier barriers hold their shape far longer under attack.
- **Barriers now stop enemy arrows and spells.** Enemy projectiles — arrows,
  fireballs, thrown magic and the like — are absorbed by an intact panel
  instead of flying through, chipping that panel a little as they hit. They
  still pass through a broken panel's hole, and your own (and your citizens')
  shots can still fire outward.
- **Strong citizens defend the colony.** When your colony is under attack,
  citizens react by their power. Ordinary citizens (and weaker Tensura-race
  citizens) flee for safety as before, and your guards fight as always. But a
  Tensura-race citizen who has grown strong enough (10,000 Existence Points
  or more) will switch into its true monster form on the spot, fight the
  raiders with its skills, and switch back to its colonist self once the
  attack is over. If that monster body is slain in the fight, the citizen
  dies — being a defender is a real risk. (The power threshold is tunable.)

### Fixed
- **The dwarf envoy's 20-day peace timer no longer counts while you're away.**
  The "20 in-game days without the colony owner dying" condition that can
  unlock dwarf envoys now only advances while you are actually online. It
  resets when you log off (and again when you log back in), so time that
  passes while you're off the server — even if your colony stays loaded —
  never counts toward it.
- **Envoys can no longer be harmed, and only one visits at a time.** Every
  envoy — both the colony-join race envoys and the diplomacy faction envoys —
  is now fully protected from all damage, so they can't be accidentally (or
  deliberately) killed while you decide. And only a single envoy of any kind
  will be waiting at your colony at once; a new one won't arrive until the
  current visit is resolved.

### Changed
- **The Otherworlders are now the Eastern Empire.** The Otherworlders faction
  has been re-themed into the **Eastern Empire** — a major eastern military
  power. Its town still stands and Mai Furuki still leads it (a stand-in for
  proper Empire commanders later), but it now fields a heavy **magitech golem
  army** and ranks among the strongest factions — comparable to the Holy
  bloc. It's a secular power that keeps no alliances (notably, it is **not**
  allied with the Holy bloc). Existing worlds carry over automatically: any
  standing or relations you had with the Otherworlders become your standing
  with the Eastern Empire, with no lost progress.
- **Shizu is retired as a faction; Leon takes over her territory.** Shizu no
  longer appears as a faction you can deal with — her Pagoda town no longer
  generates, she sends no events, and she's gone from the diplomacy list. (If
  an old world already had a Shizu town, it simply sits inert; your past
  standing with her is kept but unused.) In her place, **Leon** now fields a
  proper fortress garrison: a scaled-up Ifrit boss leading fire-resistant
  defenders (a salamander and a heavy construct), fitting his strength as a
  Demon Lord.
- **The Carrion faction is now "Eurazania" (the Beast Kingdom).** Calion's
  beastfolk nation now shows the name "Eurazania" everywhere a faction label
  appears. It stays exactly as it was otherwise — diplomacy and reputation
  only, no settlement to attack. Existing worlds carry over automatically: any
  standing or relations you had with Carrion become your standing with
  Eurazania, with no lost progress.
- **Falmuth's summoned heroes now fight under Falmuth.** The Otherworlder
  champions Shogo Taguchi, Mark Lauren, Shinji Tanimura, and Kirara Mizutani —
  the heroes the Kingdom of Falmuth summoned — now belong to Falmuth, both for
  reputation purposes and as Falmuth settlement defenders. The Otherworlders
  faction still exists (Mai Furuki remains) and is unchanged otherwise.
- **The schemer faction is now called the "Moderate Harlequin Alliance."**
  Clayman's faction — the schemers behind the Orc Disaster — now shows that
  name everywhere a faction label appears (diplomacy, reputation, messages).
  Clayman himself, the Demon Lord who marches the Orc Disaster, keeps his name.
  This is only a label change: your standing, relations, and the Orc Disaster
  event with the faction are untouched, so existing worlds carry over with no
  loss of progress.
- **Tempest and the Jura Alliance are now one faction: "Jura-Tempest Federation."**
  They were always the same power (the forest alliance that grew into the
  Tempest Federation), so they've been combined into a single faction. It keeps
  Tempest's full set of deals and its Covenant, plus the Jura Alliance's town
  (the Jungle Treehouse settlement and its serpent/goblin/lizardman/slime
  defenders) and its sage citizen reward when you conquer it. One overlapping
  "happy citizens" deal was removed, and the faction stays separate from your
  own colony as before. Existing worlds carry over automatically: any standing,
  relations, deals, or settlement you had with the Jura Alliance are merged into
  the combined faction with no lost progress.
- **Barrier damage is now local to each panel.** Attacks still drain the
  barrier's shared fuel pool, but they now also wear down the specific panel
  being struck; that panel breaks into a hole when its own health runs out,
  without dropping the rest of the barrier. The whole barrier still falls only
  when the fuel pool reaches zero — and refueling from empty restores every
  panel to full. Keeping extra layers raised still costs a steady trickle of
  fuel (50 per second for each layer beyond the first).
- **Tier 3 barriers no longer throw enemies out — they recharge you instead.**
  The old tier-3 "teleport hostiles back outside" effect has been removed.
  Now, while you stand inside a tier 3 or 4 barrier, your own magicule
  regenerates 10% faster.
- **Any hostile drains the barrier, not just raid monsters.** Previously only
  monsters that were part of an active raid wore down a barrier's fuel; ordinary
  wild hostiles were blocked for free. Now any hostile pressing the wall —
  wild monsters and raid monsters alike — costs the barrier fuel.
- **Barrier fuel drain halved.** Hostiles now wear down a barrier's magicule at
  half the previous rate, so a given amount of fuel lasts about twice as long.
- **Enemy spell-casting now runs on Nightmare's Tensura Utils.** Garrison
  bone golems and the colony assassin still cast their magic in combat, but
  the casting is now handled by the Nightmare's Tensura Utils mod's
  autocaster instead of our own hand-built logic. Behaviour is the same —
  golems cast their element spell, the assassin uses the skills it copied
  from you, and nothing casts unless it has a target — but the timing and
  skill-choice are smarter (weighted selection, passive skills filtered out).
  **This makes Nightmare's Tensura Utils a required dependency.**

## [0.1.0] - 2026-06-13

First changelogged release. (The mod's earlier systems — races & citizens,
colony & world reputation, raids & the magicule barrier, the Harvest
Festival, assassins, the full diplomacy system, and the rival-colony arc —
were built before this changelog began; this entry starts the running log.)

### Added
- **Bone golem battle-magic.** Garrison bone golems now actively cast
  combat magic during an assault. Each spawns attuned to one of five
  elements (darkness, wind, earth, fire, water) and casts that element's
  spell at invaders on a cooldown. The spell's power scales with the
  faction's lore strength.
- **War highlight.** A settlement's defenders are outlined/highlighted for
  the duration of an assault, so you can see your targets; the highlight
  clears when the assault ends (win, retreat, death, or logout).
- **Inbound-envoy colony requirement.** A faction will only send you an
  envoy once your colony is established — at least 4 buildings, with at
  least one at level 2+.

### Changed
- **Outbound envoy EP cost rebalanced and danger-scaled.** The subordinate
  you dispatch as an envoy now needs more Existence Points the more hostile
  the faction is: a 5,000-EP floor for the friendly nations (Tempest, Jura),
  scaling up to 15,000 for the Holy Empire. (All thresholds are tunable.)
- **Faction settlements generate more sensibly.** Site selection now picks
  the flattest nearby ground and lays a level foundation under each
  building, so settlements no longer spill off cliffs or mountainsides.
- **Marked-boss nameplates** no longer carry a faction-possessive prefix
  ("Luminous's Hinata Sakaguchi" → "Hinata Sakaguchi"). The Orc Disaster
  (Geld) keeps its name unchanged.
- **Faction settlements are decorative.** MineColonies hut blocks are
  stripped from generated settlements (conquest is rewards-only — it never
  founds a colony, so the huts were vestigial).

### Fixed
- **Overlapping settlement buildings.** Widened the town layout spacing so
  buildings can no longer clip into or delete one another (e.g. a building
  cutting into the town hall).
- **Textureless "Clone" defenders.** Luminous (and Otherworlder) garrisons
  no longer spawn the Clone entity, which rendered with the missing-texture
  skin because it had no source entity to copy; replaced with valid units.

[Unreleased]: https://github.com/TimelyBull/tensura_minecolonies/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/TimelyBull/tensura_minecolonies/releases/tag/v0.1.0
