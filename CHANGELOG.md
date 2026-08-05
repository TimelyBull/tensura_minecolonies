# Changelog

All notable changes to **Tensura MineColonies Integration** are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/).
Copy the relevant version's section into the CurseForge release notes on each update.

## [0.2.2] - 2026-07-26

### Added
- **Luminous's alliance now ends in a real ritual — The Trial of Light & Dark.**
  Reaching the Covenant with Luminous no longer just asks for a pile of blocks.
  She hands you two empty chalices and sets a two-faced trial: a **Show of Faith**
  (cure zombie villagers and raise them to the top of their trade, filling a
  chalice with holy water) and **The Blood Sacrifice** (kill your own subordinates,
  filling a chalice with blood). Bring both full chalices back and she forges them
  into the **Twin Grail** — a two-faced relic that heals and cleanses you by day,
  and grants strength, speed, and life-drain by night. If you follow the majin
  (monster) path, Luminous demands more of you on both counts: your redeemed
  villagers must breed and raise a whole new generation, and the blood must come
  from your three *strongest* subordinates. (Faction features are off by default —
  turn on the faction system to see this.)
- **Tensura block-wrecking no longer damages your colony.** Giant monsters
  like the Orc Lord and Charybdis normally smash through walls just by walking
  into them, and terrain-shaping skills (Earth Manipulation and the like) can
  carve up the ground — neither used to respect your colony's protected area,
  so a raid or a wandering giant could demolish your buildings. Now blocks
  inside a colony are safe from both. Two new world settings let you turn each
  guard off if you'd rather have the old free-for-all: **Protect Colony From
  Mob Griefing** and **Protect Colony From Skill Griefing** (both on by
  default, changeable from the mod's config screen, and they apply right away).
- **You can switch off reputation raids.** A new world setting, **Enable raids**
  (on by default), controls whether a colony with low standing gets attacked by
  monsters at night. Turn it off and those raids simply never happen. A raid
  that's already underway still plays out, and this doesn't affect faction story
  raids like the Orc Disaster. Change it from the mod's config screen and reload
  the world to apply.

### Changed
- **Milim's alliance rewards, reshuffled.** Her top prize — the legendary
  **Absolute Annihilator** hammer (and her Strength skill) — now comes from
  *forging the Covenant* with her by slaying the Warden, rather than from an
  earlier allied deal. That earlier slot instead becomes a simple *Apito's Jelly*
  delivery paying enchanted golden apples. (Faction features are off by default —
  turn on the faction system to see this.)
- **Raiders push through a gap in your barrier instead of milling outside it.**
  If part of your barrier has been battered down to a hole, attackers now head
  for the breach and pour through, then go back to hunting your citizens once
  they're inside — rather than piling up harmlessly against the nearest wall.
  Seal the hole and they're shut out again.
- **Raids now wear you down like a normal MineColonies siege.** A raid is a
  single host that you grind down: as raiders die the raid shrinks toward its
  end, stragglers that wander off or vanish are replaced so the pressure holds,
  and the raid is won once you've broken about 90% of them — you no longer have
  to chase down the last one or two to end it. The raid bar now shows how much of
  the host is left to break.
- **Ordinary MineColonies raiders can break through a magicule barrier.** Before,
  barbarians and pirates would batter a fuelled barrier so slowly they'd just get
  stuck against it for the whole night. A group of them can now wear a section
  down and pour through the gap in a few minutes, while a lone raider is still
  held off for a long time (tougher cores hold longer). Your own tamed monsters
  still never touch the barrier. **How fast they break through now scales with
  the raid's difficulty** — a harder MineColonies raid batters the barrier down
  faster, an easy one slower.
- **Your faction allies now help defend against ordinary MineColonies raids too.**
  Fighters from factions you've allied with (Pact / Covenant) used to only turn
  up for the monster raids tied to your standing. They now also march in to help
  fight off a regular MineColonies raid, and head home when it's over.
- **Your guards no longer attack the allies sent to help you.** The faction
  fighters that arrive to defend a raid used to be treated as hostile monsters by
  your own guard towers, which would cut them down on arrival. Your guards now
  leave your allies alone and focus on the actual raiders.
- **Reshuffled which monsters lead each raid tier.** The weakest raids now field
  Hound Dogs and Direwolves; Giant Ants and Black Spiders have moved up into the
  mid-tier raids (alongside Evil Centipedes). The toughest raids are unchanged.

### Fixed
- **A raid no longer disappears when you save and reload.** Leaving and
  re-entering a world mid-raid used to wipe the attack — the monsters would go
  passive, the raid bar vanished, and your citizens stopped hiding. The raid now
  carries over intact: the same attackers, the same timer, the same boss bar.
- **You won't get hit by two raids at once.** A monster raid tied to your
  standing will no longer start while a regular MineColonies raid is already
  attacking your colony — it waits until that one is over.
- **Strong residents actually fight during a regular MineColonies raid.** Your
  powerful monster citizens would transform to defend the colony but then just
  stand around, because they only recognised our own raiders. They now go after
  MineColonies' barbarians and pirates too.
- **Masterwork weapons no longer lose their damage the moment you hold them.**
  A freshly made Masterwork weapon looked fine in the creative menu and in your
  inventory, but as soon as you selected it on the hotbar its attack damage
  (and attack speed and reach) dropped to nothing. The weapon's "keep the stats
  in sync with its Existence Points" upkeep was rebuilding the stats from an
  empty starting point and wiping them out. It now rebuilds from the weapon's
  real base stats, so the numbers stay correct as the weapon grows. Weapons that
  were already blanked by this bug repair themselves the next time you hold them.

## [0.2.1] - 2026-07-23

### Changed
- **Masterwork weapons have been re-scaled.** Their EP upgrades were stacking on
  top of each other instead of replacing one another, so a fully grown Masterwork
  katana was hitting for 184 — more than double what it was ever meant to. A
  Masterwork now starts noticeably weaker than the hihiirokane weapon you forged
  it from and grows past it, ending **2 damage above** its counterpart at full
  power (katana: 51 → 83). Its real advantages are unchanged: the abilities, the
  durability, the enchantability and the self-repair.
- **The Absolute Annihilator had the same problem** and now tops out at 38 attack
  damage as intended, rather than 63.

### Fixed (Absolute Annihilator)
- **The hammer's knockback resistance and bonus hearts now actually arrive.**
  They were listed as rewards for reaching 400,000 and 700,000 EP but never
  applied to the weapon at all. You now get knockback resistance at 400,000 EP
  (more at 1,000,000) and two extra hearts at 700,000 EP (two more at
  1,000,000), while the hammer is held.
- **Hold SHIFT on the hammer to see its full ladder** — every ability, the EP it
  unlocks at, and how much EP the weapon has right now. Unlocked entries are
  green, locked ones grey.
- **Weapons you already own fix themselves.** You do not need to re-forge:
  put a Masterwork weapon or an Annihilator in your inventory and its stats are
  rebuilt from its current EP within a second. It keeps all its EP and carries on
  growing normally.

### Added
- **You can now choose how big your barrier is.** The Barrier Core menu has a
  **FIELD SIZE** row: `-`/`+` move it 4 blocks at a time, `MIN`/`MAX` jump to the
  ends of the range, and a bar shows where you are within it. The smallest is 8
  blocks; the biggest is your strongest core's radius plus a bonus for every
  OTHER core in the colony — **+2, +4, +6 or +8 blocks** depending on that core's
  tier (hard ceiling 128). So a colony that has outgrown its barrier extends it by
  building more cores, and better cores are worth more than more cores. Existing
  barriers keep the exact size they have now.
  - Lose a core and the barrier shrinks to fit the narrower range — but your
    chosen size is remembered, so rebuilding it puts the barrier back.
  - Every core in a colony still opens the **same** menu, now including the size:
    one barrier per colony, shared fuel, shared settings, whoever clicks it.
- **Monsters born in your village are your named subordinates from birth.** They
  keep the name they were born with, and they already belong to you — summon one
  and it answers to you straight away, with no trip through the naming menu.
  They are not evolved by this (a goblin child is a goblin, not a hobgoblin), and
  it costs you nothing. Hand-naming a wild monster is unchanged: it still costs
  magicule and still evolves them.
- **Monsters breed true to their parents.** A child born in your colony now
  takes its race from its parents instead of a colony-wide dice roll: two
  goblins have a goblin, a goblin and a lizardman have one or the other at
  random, a single parent passes their own race down, and only a colony with no
  parents at all falls back to the old random draw. Human-and-monster pairings
  can go either way.
- **Diplomacy actually puts someone in your colony.** Accepting an envoy now
  brings **one** citizen of that race in straight away, so the alliance shows up
  instead of only unlocking future arrivals.
- **Wanderers of your colony's races drift in and settle — no Tavern, no
  payment.** While any race in your colony has fewer than **3** of its own, one
  will occasionally arrive on its own and join. It favours whichever race you
  have the fewest of (about two times in three; otherwise a random one of the
  others), so your races fill out roughly evenly rather than one taking over.
  Once a race reaches 3 this way it stops — growth beyond that comes from births
  and the Tavern as usual. Humans count as a race here too, so they don't quietly
  crowd everyone out.

### Changed (barriers)
- **Holding a barrier up now costs magicule, and a bigger barrier costs more.**
  Previously a single-layer barrier ran forever for free and your fuel only paid
  for repairs. Now every layer costs **10 magicule a second plus 1 for each block
  of its radius** — so size, layers, and the fact that outer layers sit further
  out all add to the bill. Roughly: a tier-1 core at its default size burns 26/s,
  a tier-4 at its default size 70/s, and three layers on a large field 225/s. At
  default sizes a full tank lasts about an hour whatever your tier; shrink the
  field and it lasts far longer. Keep it fed, or keep it small.

### Fixed
- **Naming a citizen you summoned no longer creates a phantom citizen.** If you
  summoned one of your own residents — a monster born in your village, or one
  that had grown up there — and then named it, the game registered it a SECOND
  time. The original was left behind as a citizen that doesn't exist: no body,
  can't be summoned, can't be sent home, and still occupying a house forever.
  Naming someone who already lives in your colony now simply **renames** them.
- **Summoning a baby gives you a baby.** Children born in your village were
  summoned as fully grown adults.
- **A child sent home is child-sized the moment it appears.** It used to arrive
  looking like an adult and shrink a second or two later, once the game got
  around to noticing. This applies to ordinary colony children too, not just
  Tensura ones.
- **Babies can grow up properly.** A child that grew up while it was out with you
  was marked a child again when you sent it back, and the change wouldn't have
  survived a reload anyway. Age is now kept in step in both directions.
- **Phantom citizens from earlier versions can be cleaned up.** Run
  `/recoverorphans`: it now spots them even while their body is standing next to
  you, and `confirm` turns each one back into a working colonist (or `purge`
  deletes it and frees the house).
- **Masterwork weapons and the Absolute Annihilator can be engraved and
  enchanted again.** They were missing from the item lists the game uses to
  decide what a weapon accepts, so no engraving and no enchantment could ever
  be put on them — and, more quietly, they never picked up the random engravings
  a Tensura weapon earns as it absorbs EP. Now they behave exactly like the
  hihiirokane weapon they were forged from: engravings apply, engravings you
  already have (like Barrier Piercing) actually fire on hit, and the weapon
  starts earning new ones at its EP milestones.
- **Weapon right-click abilities do their full damage.** The sweep, the magic
  slice, the Annihilator's shockwave and its Drago Nova blast were all landing
  inside the brief window of invulnerability left by your previous swing, so the
  game subtracted that swing's damage from theirs — leaving a couple of points
  of damage (or none at all) no matter how strong the weapon was. Abilities now
  land clean.
- **Ability hits count as real weapon hits.** They now set off your engravings
  and the weapon's own on-hit effect (the Masterwork's lifesteal / mending), and
  they are properly credited to you — so kills from an ability grant EP, and
  your own subordinates and colonists are recognised as yours instead of being
  treated as bystanders hit by an unowned explosion.
- **The Absolute Annihilator's Drago Nova blast now grows with the weapon**
  instead of dealing a fixed amount forever.

## [0.2.0] - 2026-07-21

### Added
- **Masterwork weapons — a legendary weapon line that grows with you.** Ally with
  **Dwargon** to Covenant and complete *The Masterwork Commission* (deliver a
  Block of Netherite + a Hihiirokane Ingot) to receive a **Masterwork Weapon
  Core** and a **Masterwork Schematic**. Right-click the schematic to learn the
  recipes — it is used up in the process, and a spare copy tells you that you
  already know them instead of being wasted. Then forge at a
  **Tensura Smithing Bench**: any hihiirokane weapon +
  a Masterwork Weapon Core becomes its Masterwork version. All **12 weapon types**
  are supported — sword, short/long/great sword, katana, kodachi, odachi, tachi,
  spear, scythe, axe and sickle.
  - **Stronger than what it replaces.** Each Masterwork weapon hits for slightly
    more than its hihiirokane counterpart, with more durability and enchantability,
    and its damage keeps climbing as the weapon absorbs EP — up to roughly +45 more
    at full power.
  - **The blade awakens as it grows.** Newly forged, it's a plain sleek steel
    blade. As its EP rises it gains colour and a subtle glimmer, in four stages,
    at the same milestones its damage increases.
  - **It fights differently depending on who wields it.** A **majin** wielder
    drains life on hit; a **non-majin** wielder is mended instead. Master more
    **Battlewills** than Magics and right-click unleashes a **sweeping strike**
    (spending aura); master more **Magics** and it fires a **flying magic slice**
    (spending magicule).
  - **Mastery perks:** at **10** mastered skills nearby drops fly to you, at **15**
    you step up full blocks while holding it, and at **20** the weapon stays with
    you when you die.
  - It repairs itself from its own EP, and hovering it with **SHIFT** lists
    everything it can do.

- **The Absolute Annihilator — a new legendary hammer from Milim.** Earned from
  Milim's "Prove Your Strength" pact, this two-headed warhammer hits for heavy
  base damage with extra reach, carries a **Holy Coat engraving** (extra damage
  against holy foes), and — like other Tensura weapons — **gains EP as you
  fight**, growing stronger (up to a much higher attack power) the more it
  kills (up to a 1,000,000 EP cap), and **unlocks new powers at EP milestones**:
  - **150,000 EP** — its strikes inflict brief Weakness.
  - **500,000 EP** — it becomes "charged": the dark detailing **lights up with
    energy**, and right-click **unleashes a Drago Nova blast** (the same floating
    charge-up and explosion), on a cooldown, without being consumed.
  - **700,000 EP** — strikes heal you (lifesteal), and the blast recharges faster.
  - **1,000,000 EP** — strikes burst into a small shockwave that hits nearby
    enemies, and the blast recharges faster still.

  Alongside these, its raw stats climb with EP too (more attack damage and speed,
  then knockback resistance, then bonus health). It also appears in the creative
  tab.
- **`/recoverorphans purge` — delete lost subordinates that can't be rescued
  and free their housing.** Some vanished subordinates can be restored as
  colonists with `/recoverorphans confirm`, but ones that were lost before the
  game ever saved a copy of them have nothing to restore from — they just sit
  there occupying a house forever. The new `/recoverorphans purge` permanently
  deletes those (and only those), freeing the housing slot. Run
  `/recoverorphans` first to see which of your lost subordinates are
  recoverable versus purge-only; it changes nothing until you run `confirm` or
  `purge`.

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

### Changed
- **Covenant alliance rewards are being reworked.** The final "Covenant" deal for
  each faction now grants **Enchanted Golden Apples** scaled to that faction's
  standing tier, as a placeholder while the unique per-faction rewards are
  designed. Dwargon's already awards its Masterwork Weapon Core + schematic.
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

- **Eastern Empire (magitech/imperial) deals reworked.** Its board leans into what
  the Empire is — a magitech military that summons soldiers. Milestone deals became
  real tasks, coins were added, and it gained three new deals: **The Imperial Levy**,
  **Arcane Conscripts**, and **A New Type of Soldier** (build a magisteel golem to
  fight for you). Several deals now grant a **Summoning Tome** or the new **Imperial
  Stimulant** potion, and *An Imperial Garrison* awards an enchanted magisteel sword.
- **Some deal names cleaned up** so they match what the deal actually asks for —
  e.g. Eurazania's Charybdis fight is now "The Charybdis Hunt," and a couple of
  Leon/Empire deals were renamed to fit their tasks.
- **Leon (the Platinum Saber) deals reworked.** Leon now reads as what he is in
  the story — a master swordsman and spirit-summoner whose signature is the fire
  spirit Ifrit — rather than a generic flame faction. Deals that just watched
  your colony were replaced with real tasks, coins were added throughout, and the
  board gained a martial spine: **Flame Knights** and the new **Trial by Fire**
  now grant Battlewill Manuals, and a new capstone, **The Platinum Blade**, awards
  a fire katana (Fire Aspect II + Sharpness IV). Several deals also hand out the
  new **Flamewarden's Brew** potion.
- **Factions now reward custom brews.** Several deals that paid out a single
  filler item now hand you a themed, custom-named potion (each lasting 1:30):
  Falmuth's **Crusader's Draught** (Strength + Resistance) and **Siegebreaker's
  Tonic** (Haste + Resistance), Milim's **Dragon's Vigor** (Strength II +
  Regeneration), Eurazania's **Beast-Blood Draught** (Speed, Strength, Jump
  Boost), and Clayman's **Potion of Invisibility** / **Potion of Night Vision**
  for its spy work. Falmuth also gained a new deal, **The Siege Train**.
- **Milim's two boss hunts swapped payouts.** Slaying the **Warden** ("The
  Ultimate Brawl") is now Milim's signature feat and awards the **Absolute
  Annihilator**, while slaying the Wither ("Prove Your Strength") pays out
  enchanted golden apples, diamonds, gold and a Battlewill Manual.
- **Clayman (the Moderate Harlequin Alliance) deals reworked.** Its quest board no
  longer hands back the same thing you delivered, every deal now pays coins scaled
  to your standing, and the deals that just watched your colony's buildings, size,
  or happiness were replaced with real tasks. Two new schemes were added: **A Grand
  Illusion** (commission a grade-A grimoire) and **The Marionette** — a capstone
  that asks you to **declare war on a rival colony and win it**, granting Clayman's
  signature reward.
- **Eurazania (the Beast Kingdom) deals reworked.** Its quest board now pays out
  in **monster leather, beast horns, meaty stew, a Full Potion, and beast-armor
  schematics** instead of plain leather, and every deal pays coins scaled to your
  standing. Several deals that just watched your colony's size/happiness were
  replaced with real tasks — including **A Great Hunt** (slay 40 monsters) and a
  new capstone, **A Wild Haven** (hunt down a Charybdis for an Armorsaurus
  Scalemail schematic + a Battlewill Manual). Five new deals were added too: **The
  Beast-Horn Spear** (forge an enchanted Beast Horn Spear), **Blade Tiger Cull**,
  **Armorsaurus Hide**, **The Tanner's Trade**, and **Pack Hunters**.
- **Drago Nova now has a dramatic charge-up.** Instead of exploding the instant
  you use it, the Drago Nova lifts out of your hand and hovers in the air while
  blue energy is drawn into it and a glowing blue sphere swells around it — then,
  once it rises to head height (about 2.5 seconds), it unleashes the full blast.
  The explosion now happens where the orb is floating, so stepping away during
  the wind-up moves the blast with it.

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
- **Every dwarven village is now a Dwargon settlement.** Previously only about
  half of the Tensura dwarven villages you found became Dwargon territory (with
  Gazel and a garrison) and the rest stayed ordinary villages. Now all of them do.
  Note that dwarven villages are fairly common, so exploring a dwarf-heavy region
  can put several Dwargon settlements on your map.

_(All faction features are off by default — turn on the faction system in the
config to see any of this.)_

### Fixed
- **Named subordinates can no longer become permanently un-rescuable.** The
  game now saves a copy of a named subordinate as soon as you name it (and
  keeps that copy up to date while it's near you), instead of waiting until the
  first time you send it to your colony. So if a subordinate vanishes — for
  example, scooped up by another mod's mob-catching item — before you ever sent
  it to the colony, `/recoverorphans` can now bring it back as a colonist
  instead of it being lost for good.

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

### Removed
- **Two redundant config options are gone:** `rivalSettlementMode` and
  `rivalSettlementSomeChance`. Its "all" and "some" settings had come to mean the
  same thing, and "none" did the same job as turning `rivalNaturalGeneration` off.
  That option is now the single switch for whether faction settlements appear on
  their own, and it also covers the dwarf villages that become Dwargon
  settlements. If you had settlement generation turned off, it stays off.
- **Leftover template content is gone.** The placeholder "Example Block" and
  "Example Item" (which showed up in the creative menu with broken, untranslated
  names) have been removed, along with four unused config options that did
  nothing: `logDirtBlock`, `magicNumber`, `magicNumberIntroduction`, and `items`.
  Your existing config file will simply ignore them.

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
