# Changelog

All notable changes to **Tensura MineColonies Integration** are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/).
Copy the relevant version's section into the CurseForge release notes on each update.

## [Unreleased]

### Changed
- **Barriers now protect your colony, not just the block.** A Barrier Core
  placed anywhere inside your colony's claimed land projects its sphere around
  your **town hall** instead of around the block itself. A core placed out in
  the wild still centres on itself, as before.
- **Multiple Barrier Cores in one colony merge into a single barrier.** Extra
  cores no longer make overlapping spheres — the strongest core sets the
  barrier's size and panel strength, and every core's fuel tank pools into one
  shared supply. Opening the menu on any of them shows and controls the shared
  barrier.
- **The third barrier layer now grants a buff that depends on who raised it.**
  A true **Demon Lord** grants players inside 10% faster magicule regeneration;
  a true **Hero** blesses the citizens inside with stronger healing and extra
  absorption hearts. (This buff used to come from tier 3+ cores automatically —
  it now comes from the third layer instead.)

### Added
- **The Absolute Annihilator — a new legendary hammer from Milim.** Earned from
  Milim's "Prove Your Strength" pact, this two-headed warhammer hits for heavy
  base damage with extra reach, comes pre-forged with a crushing engraving,
  Sharpness V and Unbreaking III, and — like other Tensura weapons — **gains EP
  as you fight**, growing stronger (up to a much higher attack power) the more it
  kills. It also appears in the mod's creative tab.
- **`/recoverorphans purge` — delete lost subordinates that can't be rescued
  and free their housing.** Some vanished subordinates can be restored as
  colonists with `/recoverorphans confirm`, but ones that were lost before the
  game ever saved a copy of them have nothing to restore from — they just sit
  there occupying a house forever. The new `/recoverorphans purge` permanently
  deletes those (and only those), freeing the housing slot. Run
  `/recoverorphans` first to see which of your lost subordinates are
  recoverable versus purge-only; it changes nothing until you run `confirm` or
  `purge`.

### Fixed
- **Named subordinates can no longer become permanently un-rescuable.** The
  game now saves a copy of a named subordinate as soon as you name it (and
  keeps that copy up to date while it's near you), instead of waiting until the
  first time you send it to your colony. So if a subordinate vanishes — for
  example, scooped up by another mod's mob-catching item — before you ever sent
  it to the colony, `/recoverorphans` can now bring it back as a colonist
  instead of it being lost for good.

### Changed
- **Higher-tier Barrier Cores are now crafted by upgrading the tier below.**
  Each Barrier Core above the first now requires the previous tier's Barrier
  Core placed in the center of the crafting grid (surrounded by the same
  materials as before), instead of building from raw materials alone. Tier 2
  needs a Tier 1 core, Tier 3 needs a Tier 2 core, and so on. The Tier 1 recipe
  is unchanged.
- **Rival-faction settlements now defend at a strength that matches their
  reward.** A faction's garrison (how many defenders it fields and how tough
  they are) is now set by how valuable that faction is to conquer, on a
  four-step ladder, rather than swinging wildly off its leader. Apex settlements
  — Luminous and Leon — field the largest, strongest garrisons (about 20
  defenders); the next step — Dwargon and the Eastern Empire — a bit fewer;
  Falmuth and Tempest fewer still. Each still feels distinct (their leaders and
  troops differ). **Leon's boss (Ifrit) is now a proper apex threat** — much
  tougher, to match the fight to the reward. The Eastern Empire is no longer
  over-stacked.
- **Dwargon is now a top-tier power.** Dwargon (King Gazel's realm) has been
  moved up to the highest difficulty/reward tier to match its standing in the
  story, so its settlement fields a full apex garrison.
- **Dwargon's dwarves are now real soldiers.** Instead of near-harmless miners,
  a Dwargon settlement is defended by hardened dwarven troops — far more health
  and damage, plus tough hide — led by Gazel and a single War Gnome lieutenant.
- **Faction defenders now sling elemental magic.** Each faction's garrison casts
  an attack spell that fits its theme — fireballs for Leon and Luminous, wind
  blades for Falmuth, stone shots for Dwargon and the Eastern Empire, water
  cutters for Tempest — so settlement fights aren't just melee.
- **Settlement defenders now fight as mages and warriors.** A faction's rank
  splits into spellcasters — who wield staves, hang back, sling their element's
  magic, and only resort to fists when you close in — and warriors, who carry
  tier-appropriate swords (diamond up to high-magisteel), charge in, and dash
  with a quick step. Casters ward against magic; warriors shrug off physical
  blows. Defenders of the same settlement can't harm each other, so their
  spellcasters can throw area magic without wiping out their own side. (All of
  the above is part of the faction system, which is off by default; combat
  values are a first pass and may be tuned.)

### Added
- **A fueled magicule barrier now stops hostile mobs from spawning inside it.**
  While your Barrier Core has fuel, hostile monsters — vanilla ones and Tensura
  ones (spiders, direwolves, daemons, orcs, and the rest of Tensura's hostile
  list) — can no longer appear anywhere inside the protected area. This covers
  wild night-time spawns as well as pillager patrols, mob spawners, trial
  spawners, and zombie reinforcements that would otherwise pop up inside your
  walls. It does NOT block things you place on purpose (spawn eggs, `/summon`,
  dispensers, breeding, your own tamed/summoned creatures), and it leaves raids
  alone — raiders still spawn OUTSIDE the field and have to break through, as
  before. An empty barrier protects nothing: refuel it to keep the area clear.
- **New setting: "Citizens Transform to Defend Raids"** (on by default). When a
  colony is raided, strong non-guard Tensura-race citizens transform into their
  monster body and fight with skills, then change back when the raid ends. If you
  don't want your residents turning into monsters mid-raid, turn this off — they
  flee like ordinary colonists instead (guards still guard). Turning it off also
  reverts anyone currently transformed. (Per-world setting — reload the world
  after changing it.)
- **Race colonies now breed their own kind.** A goblin/orc/dwarf/lizardman
  colony that grows a new resident through normal colony growth now produces a
  **baby of that race**, born to its actual colony parents, that grows up like
  any citizen — instead of a plain human villager. The whole colony stays true
  to its race as it grows, with no naming step required for each newcomer.

### Fixed
- **"Patrol Colony Outskirts" now actually walks the colony's edge.** Patrolling
  creatures used to pick a fresh random spot on the border each time and walk
  straight to it, which meant they kept cutting back and forth across the middle
  of the colony past the town hall instead of guarding the perimeter. They now
  circle the colony — moving a short way around the border to the next point
  each time — so they trace the outer edge in a loop. If part of the border is
  water or blocked, they turn past it and keep going the same way around.
- **Raid monsters no longer appear in the middle of your colony.** When a
  Tensura raid started, the whole wave could materialize deep inside the
  built-up area — even inside a house — leaving no time to react. Raiding
  monsters now always appear at the edge of your colony's territory and march
  in, so you can meet them at the walls. They also never appear inside an
  active magicule barrier's field anymore (they'd have been trapped in there
  with your citizens); they show up outside it and have to break through like
  any other attacker.
- **Your own tamed creatures no longer attack the magicule barrier.** A tamed
  Tensura creature (a dire wolf, for example) standing near your barrier could be
  mistaken for a raider — the barrier would shove it back, make it swing at the
  block, and lose fuel doing so. The barrier now ignores any creature you've
  tamed, so your pets and subordinates can wander through your own barrier
  freely. Wild, untamed monsters are still blocked as before.
- **Defending citizens no longer slaughter your livestock.** When a strong
  citizen transformed to help fight off a raid, it could run off and kill every
  passive animal (and other harmless mobs) in the area instead of sticking to the
  attackers. Transformed defenders now only ever target the raiders and other
  genuine hostiles — never pigs, cows, villagers, or your own creatures. (They
  also proactively engage other hostile mobs near the colony now, not just the
  specific raid party, but stay tethered to your colony instead of chasing a
  stray monster off across the map.)
- **Subordinates no longer stay in "attack everything" mode after patrolling.**
  Taking a creature off the "Patrol Colony Outskirts" command (by cycling its
  command back to Follow, or changing its command any other way) left it stuck in
  the aggressive stance patrol puts it in — so it would then attack every mob
  around you, peaceful or not. Leaving patrol now returns the creature to its
  calm stance: it follows you and only fights back when attacked.
- **Rival faction towns no longer generate in the wrong place.** Automatically
  generated rival settlements are now built only in the Overworld. Previously
  they could try to generate in the Nether, where the code mistook the bedrock
  roof for the ground — burying the town in the ceiling and spawning its
  defenders and citizens on top of the roof. Faction towns are Overworld
  content, so generation (and the `/rivalcolony spawn` debug command) is now
  refused elsewhere with a clear message.
- **Your colonists can no longer be scared to death.** Colony citizens are now
  immune to the Fear effect, so using a fear-inducing skill near your own town
  won't slowly kill your residents. This only protects citizens living in the
  colony — a named follower out in its monster form (and hostile boss-type
  enemies like a betrayer) can still be feared as normal.
- **Race colonies no longer fill up with ordinary human villagers.** In a
  goblin/orc colony, once the colony grew past its first few residents it
  started adding plain colonists (with random names) straight into the town
  hall, and naming a mob couldn't replace them. Normal colony growth now stays
  on-race (see "breed their own kind" above).
- **The "Enable Faction System" setting now works from the in-game config
  menu.** Previously, changing it in the Mods → Config screen appeared to do
  nothing and you had to edit the config file by hand. It's now a per-world
  setting: change it in the menu and reload the world to apply (the menu will
  prompt you to). Note this moves the setting out of the global config file into
  each world's own config, so existing setups will show it back at its default
  (off) and can re-enable it per world.

### Changed
- **Rival faction towns are bigger and better built.** Their buildings now
  generate at their level-4 (well-established) size instead of level-1 starters,
  so a conquered town looks like a proper city (taverns cap at level 3, the
  largest the game provides). The Eastern Empire now builds in a heavy stone
  style, and the Jura-Tempest Federation builds as a normal medieval-oak town
  instead of a jungle treehouse village. Towns are also spaced out more so the
  larger buildings don't overlap. (Only affects newly generated settlements.)
- **Leon and the Eastern Empire now offer a full slate of alliance deals.** Both
  had only a handful of quests; each now has a complete set (about ten),
  themed to the faction (fire/martial for Leon, magitech/imperial for the
  Eastern Empire), with rewards on par with the other major factions.
- **The Moderate Harlequin Alliance's final alliance deal now gives a reward.**
  Its Covenant milestone previously paid out nothing; it now grants emeralds
  like the others.
- **Allying with Leon or the Eastern Empire now gives the same perks as the
  other factions.** Both were missing their alliance rewards. Now each offers a
  daily caravan, an alliance buff while you're allied (Leon → Fire Resistance,
  Eastern Empire → Absorption), and a final "Covenant" milestone deal so the
  alliance can reach its highest tier like the others.
- **The Moderate Harlequin Alliance now sends caravans and grants an alliance
  buff too** — themed to its spy/manipulation streak (Night Vision, plus
  emeralds and ender pearls in its caravan).
- **Retired faction tidy-up.** Shizu (long since soft-retired and not part of
  normal play) no longer carries leftover deal/reward data.

_(All faction features are off by default — turn on the faction system in the
config to see any of this.)_

## [0.1.21] - 2026-06-28

### Added
- **The four Barrier Cores are now craftable.** Each tier is built like its
  matching Magicule Storage block — a silver-ingot frame with a vertical
  magic-crystal core and magisteel sides — and uses the same materials that
  climb tier by tier: Tier 1 Low Quality Magic Crystal + Low Magisteel, Tier 2
  Medium Crystal + High Magisteel, Tier 3 High Crystal + Pure Magisteel, Tier 4
  High Crystal + Hihiirokane. (Previously the Barrier Cores had no recipe.)

### Changed
- **Hostile Tensura mobs no longer hunt your colonists by default, and you can
  now tune how aggressive they are.** A new "Mob Aggression" config option
  controls it: **Off** (the new default) means Tensura monsters add no extra
  aggression toward colonists; **Medium** is about half as aggressive; **High**
  is the old behaviour (they treat colonists as prey on sight). Existing worlds
  switch to Off too — set it to Medium or High in the config if you want the
  monsters to threaten your colony. (This replaces the old
  `tensuraHostileToCitizens` gamerule.)
- **The mod's creative-menu tab now shows its real name and icon.** It was
  still showing the template placeholder ("Example Mod Tab" with a
  missing-texture checkerboard square). It's now titled "Tensura MineColonies"
  with the Drago Nova item as its icon.

## [0.1.2] - 2026-06-27

### Fixed
- **No more stray gold pillars scattered across the world.** The faction-
  settlement markers from 0.1.1 generated even with the faction system off (the
  default), leaving bare gold columns everywhere that never became towns. The
  marker is gone — a settlement site is now invisible until an actual town
  builds (faction system on + you get close).

### Changed
- **Faction settlements are now rare.** Each faction's settlements generate far
  less often (roughly woodland-mansion rarity), so finding them all is a
  proper exploration goal rather than something you trip over constantly.
- **Now requires MineColonies 1.1.1319 or newer.** MineColonies changed its
  citizen API in that build; on older versions the mod would crash when you
  invited a creature to your colony. The mod now declares its required mods and
  versions, so an incompatible version shows a clear "requires MineColonies
  1.1.1319+" message at load instead of crashing mid-game. (It also now properly
  declares Tensura, ManasCore, Structurize, BlockUI and Domum Ornamentum as
  requirements.)

_(Existing worlds keep any gold pillars already generated — worldgen only
affects newly-generated land. Both changes need the faction system turned on
to see settlements at all.)_

## [0.1.1] - 2026-06-27

### Changed
- **Rival faction settlements now generate as real world structures.** Instead
  of towns popping into existence right next to you in areas you'd already
  explored, faction settlements are now part of normal world generation: they
  appear in newly-generated land ahead of you, in biomes that fit each faction,
  spaced apart, and they can be found with `/locate` and placed with `/place`
  like any other structure (and respected by mods that control structure
  spawning). Each settlement still raises its boss and garrison the first time
  you get close. (Faction system off by default — see `enableFactionSystem`.)
  Note: existing worlds keep any settlements they already had; the new
  structures only appear in land generated from now on.
- **Faction settlements sit on the land more naturally.** Buildings now follow
  the lie of the land — standing at different heights on a slope like a hillside
  village instead of all on one flat platform. Each building's foundation matches
  the ground around it (grass, sand, snow, etc.) rather than a slab of bare
  stone, fills in holes underneath, clears terrain that would bury it (including
  trees), and tapers its edges into the surrounding land instead of forming a
  hard cliff. (Buildings also sit on the real ground now, not up at tree height.)
- **The whole faction & diplomacy system is now off by default, behind one
  setting.** A single config option, `enableFactionSystem` (in the mod's
  config file), turns the entire faction layer on or off — rival faction
  colonies and their settlements, all diplomacy (visiting envoys, deals,
  trades), war and conquest, the special lore raids (like the Orc Disaster),
  and the world-reputation reactions to killing named bosses. It now defaults
  to **off**, so a fresh install is the simple "Tensura mobs as colony
  citizens" experience with none of the faction extras. Turn it on in the
  config to get them back.
- **The Diplomacy and Wars buttons are hidden when the faction system is off.**
  With the system disabled, those buttons no longer appear on the roster
  screen and the menus refuse to open, so nothing faction-related is reachable.
- Core features are unaffected by this switch and stay on regardless: naming
  Tensura mobs into your colony, race emissaries that let a colony grow new
  races, colony reputation and its raids, the barrier, festivals, and the
  assassin system (which keeps its own separate on/off setting).

- **Leon's settlement guards are now (placeholder) human soldiers.** Leon's
  rival settlement is now defended by soldiers instead of the previous fire
  demons and salamanders, while its boss is unchanged. This is a temporary
  stand-in until Leon gets its own proper defenders. (Only relevant when the
  faction system is turned on.)

### Fixed
- **Inviting a Tensura mob into your colony no longer crashes the game.** Sending
  a named goblin (or any Tensura creature) to your colony from the roster could
  throw an error mid-swap that crashed Minecraft, and on restart the citizen
  showed up as an ordinary colonist with the name you gave it. The swap is now
  guarded: if it can't complete it refunds your magicules and asks you to try
  again instead of crashing.
- **Rival settlements no longer generate as empty, building-less towns.** If a
  settlement tried to generate before the game had finished loading its town
  schematics, the buildings silently failed and only the boss and guards
  appeared. Generation now waits until the schematics are ready. (Only relevant
  when the faction system is turned on.)
- **Config options now show readable names.** In the in-game config screen the
  settings were showing their raw internal keys (e.g.
  `tensura_minecolonies.configuration.enableAssassins`) instead of proper
  labels. Every option now displays a clear name (Enable Faction System, Enable
  Assassins, Rival Settlement Mode, and so on).

## [0.1.0] - 2026-06-26

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
- **War highlight.** A settlement's defenders are outlined/highlighted for
  the duration of an assault, so you can see your targets; the highlight
  clears when the assault ends (win, retreat, death, or logout).
- **Inbound-envoy colony requirement.** A faction will only send you an
  envoy once your colony is established — at least 4 buildings, with at
  least one at level 2+.

### Changed
- **The Jura-Tempest Federation's boss is now Rimuru — a true demon-lord-tier
  slime.** The faction's anchor slime is named **Rimuru** and is vastly
  stronger: far more health, much harder hits, a huge magicule/aura pool (so it
  freely casts its water and corrosion attacks), and tougher spiritual defense.
  Because Rimuru is now so powerful, his settlement fields a **larger, stronger
  garrison** to match — expect a full warband of buffed goblins and lizardmen
  around him. (He keeps his Predator skill.)

### Fixed
- **Enemy mobs no longer fly up over the barrier.** Mobs that hit the barrier
  used to get launched up and over the dome; they're now pushed straight back
  horizontally and pile against the wall as intended.
- **Enemy skills and breath attacks no longer pass through the barrier.** Beam
  and breath skills (such as a direwolf's voice cannon) used to ignore the
  barrier and hit whoever was inside. The barrier now stops them at an intact
  panel — chipping that panel, like it does arrows — while they still pass
  through an open hole.
- **Barrier panels now look even.** Panels farther from you used to appear
  fainter (as if weaker) than nearby ones because of a rendering quirk; every
  panel now shows its true strength consistently.
- **Faction defenders now actually fight you when you declare war.** Most
  faction garrison units are "neutral" mob types that ignore a target they
  aren't angry at, so they often just stood there during an assault. They now
  properly turn hostile and attack you — and their casters use their spells.
- **No more duplicate heroes in a garrison.** Named characters (Kyoya, Kirara,
  Shogo, Mark Lauren, Shinji, Shin Ryusei) now appear at most once per garrison
  instead of spawning as several copies; the remaining slots are filled by
  ordinary troops.
- **The Jura-Tempest Slime boss now uses its skills in a fight.** The Slime
  that leads the Jura-Tempest Federation was only attacking with melee body-slams
  and never used the water and corrosion attacks it was meant to have. It now
  casts Water Blade and Corrosion at whoever it's fighting, on a short cooldown.
  (Only the faction's boss Slime does this — ordinary wild slimes are
  unaffected.)
- **Named Tensura colonists no longer turn back into plain villagers after a
  reload.** A goblin, dwarf, lizardman or orc citizen could sometimes lose its
  monster appearance and look like an ordinary colonist after the area around
  it reloaded or you relogged. The colony now remembers what each named citizen
  really is and restores its proper form automatically within about a second of
  its body reappearing. (Citizens that were named before this update may briefly
  come back with their race's default look the first time; the exact saved
  appearance is restored the next time you send them out and bring them back.)
- **Bringing a citizen out to your side can no longer "lose" them.** Very
  rarely, if something went wrong partway through summoning a citizen to your
  side, the citizen could get stuck with no body in the colony and no way to
  call them back — effectively lost. That step is now all-or-nothing: if it
  can't finish, the citizen simply stays a normal colonist in the colony (their
  body comes right back) and any magicule you spent is refunded.
- **New `/recoverorphans` command to rescue lost subordinates.** If a named
  subordinate vanished while at your side — for example, scooped up by another
  mod's mob-catching item — it used to be impossible to ever add it back to your
  colony. Run `/recoverorphans` to see a report of which of your subordinates
  are stranded (it changes nothing on its own), then `/recoverorphans confirm`
  to bring the recoverable ones back as colonists. Subordinates that were named
  but never sent to a colony are listed separately and left alone, and nothing
  is ever deleted.
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
- **Overlapping settlement buildings.** Widened the town layout spacing so
  buildings can no longer clip into or delete one another (e.g. a building
  cutting into the town hall).
- **Textureless "Clone" defenders.** Luminous (and Eastern Empire) garrisons
  no longer spawn the Clone entity, which rendered with the missing-texture
  skin because it had no source entity to copy; replaced with valid units.

### Changed
- **Bone golems removed from faction garrisons.** Faction defenders no longer
  include bone golems — golems are constructs the player controls, so they
  refused to fight you. They've been replaced with fitting troops: **daemons**
  for Leon, **imperial knights** for the Eastern Empire, and more **Holy
  Knights** for Luminous. Luminous's garrison is now its boss **Hinata** plus
  Holy-Knight soldiers (Kyoya fights for Falmuth, not Luminous).
- **Leon's fire spirits are now properly fireproof.** Ifrit, the flame-spirit
  boss of Leon's keep, is now fully immune to fire and heat (on top of its
  existing self-healing and its own native fire attacks), and its salamanders
  keep their fire resistance — a fitting fire-domain garrison. (No new attacks
  were added: Ifrit and the salamanders already cast their own fire magic.)
- **Mark Lauren, Shinji Tanimura, and Shin Ryusei now fight for the Eastern
  Empire.** These three otherworlder fighters are gathered under the Eastern
  Empire's banner (Mark and Shinji moved over from Falmuth). It's a
  membership change only — their abilities are unchanged.
- **Faction garrisons reshuffled, and a new Slime boss leads the Jura-Tempest
  Federation.** Dwargon's gnome auxiliaries and the Empire's giant construct
  were dropped; Shin Ryusei now fights for the Eastern Empire. The Jura-Tempest
  Federation is now led by a powerful **boss-tier Slime** (heavily buffed, with
  its own combat skills) in place of the storm serpent. Every faction's
  defenders also gained fitting **damage resistances** (fire for Leon, water
  and wind for Jura-Tempest, toughness for the dwarves and human kingdoms, and
  so on), making garrisons more durable and on-theme.
- **The Otherworlders are now the Eastern Empire.** The Otherworlders faction
  has been re-themed into the **Eastern Empire** — a major eastern military
  power. Its town still stands and Mai Furuki still leads it (a stand-in for
  proper Empire commanders later), but it now fields **imperial soldiers**
  (knight rank-and-file under its named lieutenants) and ranks among the
  strongest factions — comparable to the Holy
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
  defenders (daemons and a salamander), fitting his strength as a Demon Lord.
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
- **Barrier damage is local to each panel — and no longer drains your fuel.**
  Attacking a barrier wears down only the panel being hit; it does NOT drain the
  shared fuel pool. A panel breaks into a hole when its own health runs out,
  without dropping the rest of the barrier. The fuel pool is now spent only on
  keeping extra layers raised and on REPAIRING broken panels (a repair costs
  exactly the health it restores). The whole barrier still falls only when the
  fuel pool reaches zero, and refueling from empty restores every panel to full.
  (This fixes higher-tier barriers, whose pool used to empty before a tough
  panel could be broken.)
- **Tier 3 barriers no longer throw enemies out — they recharge you instead.**
  The old tier-3 "teleport hostiles back outside" effect has been removed.
  Now, while you stand inside a tier 3 or 4 barrier, your own magicule
  regenerates 10% faster.
- **Any hostile wears the barrier down, not just raid monsters.** Previously
  only monsters that were part of an active raid damaged a barrier; ordinary
  wild hostiles were blocked for free. Now any hostile pressing the wall — wild
  monsters and raid monsters alike — wears down the panel it's pushing on.
- **Barrier damage rate halved.** Hostiles now wear a barrier's panels down at
  half the previous rate, so a barrier holds about twice as long under attack.
- **Enemy spell-casting now runs on Nightmare's Tensura Utils.** Garrison
  bone golems and the colony assassin still cast their magic in combat, but
  the casting is now handled by the Nightmare's Tensura Utils mod's
  autocaster instead of our own hand-built logic. Behaviour is the same —
  golems cast their element spell, the assassin uses the skills it copied
  from you, and nothing casts unless it has a target — but the timing and
  skill-choice are smarter (weighted selection, passive skills filtered out).
  **This makes Nightmare's Tensura Utils a required dependency.**
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

[Unreleased]: https://github.com/TimelyBull/tensura_minecolonies/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/TimelyBull/tensura_minecolonies/releases/tag/v0.1.0
