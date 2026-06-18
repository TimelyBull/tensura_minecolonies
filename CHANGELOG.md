# Changelog

All notable changes to **Tensura MineColonies Integration** are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/).
Copy the relevant version's section into the CurseForge release notes on each update.

## [Unreleased]

### Changed
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
