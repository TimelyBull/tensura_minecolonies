# Diplomacy

Diplomacy is the non-combat way to deal with factions: open relations,
complete deals to raise standing, and progress through three relationship
tiers that unlock rewards. It runs in parallel with
[Warfare](warfare.md), and you can use both.

Diplomacy is part of the faction system, which is **off by default**. Set
`enableFactionSystem` to `true` in the [config](../reference/config.md) to
enable it. While it's off, the **Diplomacy** tab is hidden from the roster
and no diplomatic envoys arrive.

!!! info "Where To Find It"
    Open your roster with the **`G`** key and click the **Diplomacy** tab.
    Each known faction is a row showing its current standing and any offered
    deals.

## Opening Relations

You must **own a colony** (have a town hall) before you can send an envoy or
a gift — the buttons are disabled until then. There are two ways relations
open:

- **They send an envoy to you.** Friendly and neutral factions occasionally
  send a diplomatic envoy that appears near your town hall. Right-click it to
  hear them out and accept — relations open on the spot.
- **You send an envoy to them.** On the Diplomacy tab, press **Send Envoy**
  for a faction. You dispatch one of your **Tensura subordinates** as the
  envoy: a picker lists the subordinates at your side that are strong enough
  for the trip (their EP must meet the faction's requirement — higher for
  more dangerous factions). Pick one and it leaves on the mission. After
  about a day the faction replies: if your standing is high enough, relations
  open. Otherwise they decline. **Either way your subordinate returns to your
  side** when the mission resolves — it's only away temporarily, never lost.
  You can attach a gift (taken from your inventory) for a little extra
  goodwill.

!!! note "Your Envoy Is Away While Travelling"
    A subordinate sent as an envoy is unavailable until the mission
    resolves — you can't summon or use it in the meantime. It comes back
    automatically (even across a logout) when the faction replies. If it
    ever fails to reappear, open the roster (**`G`**) and select it to
    call it back to your side.

!!! warning "Race Matters"
    Luminous and Falmuth send envoys only to human players. A majin must send
    their own, starting from lower standing. Clayman, Leon, and the Eastern
    Empire never send envoys — open relations by sending yours.

## Deals

Once relations are open, a faction offers **deals** — tasks in exchange for
items and standing. Completing deals is the main way standing rises.

A deal asks for one of:

| The faction wants… | You fulfil it by… |
|---|---|
| **Supplies** — one item type (e.g. 64 iron) | Pressing **Deliver** on the tab — the items are taken from your inventory |
| **A bundle** — several item types at once (e.g. Caravan Tolls: 32 emeralds and 8 gold ingots) | The same **Deliver** button; you need everything in the bundle at once |
| **Kills** (a boss, or a number of a mob) | Killing the named target |
| **Lent citizens** (see below) | Sending some colonists to work for them a while |

(Three more asks appear only as Covenant capstones — a colony of many races
(the Jura-Tempest Federation, below), conquering the faction's settlement, or
Luminous's Trial of Light & Dark (below) — see the
[Quest Catalog](../reference/quest-catalog.md).)

Every deal has a **deadline**. Complete it in time for the reward and a standing
gain. Let it expire and standing drops. The active deal shows a progress bar,
and the faction's row shows its current deal.

Which deals a faction puts on the table depends on your standing with it —
higher standing brings out its better deals. Its basic deals stay available at
any standing, so a relationship that has slipped can always be worked back up.

### A Nation Of Many Peoples (Jura-Tempest Federation)

The Jura-Tempest Federation's Covenant deal completes when your colony has at
least 25 citizens from at least 4 different races (colonists, goblins, orcs,
lizardmen, dwarves, otherworlders). It has no deadline. The reward is the **Seal of
Ascension**: use it on one of your subordinates to raise its EP by 25%, and for
the next 30 minutes every EP gain it makes is 50% larger. The seal recharges
in 45 minutes (real time, including while you're logged out).

### Tribute To The Platinum Saber (Leon)

Leon's Covenant deal is a delivery: 16 gold blocks, 16 blaze rods, and 1
netherite ingot. The reward is the **Otherworlder Summoning Codex**, which
summons an otherworlder to your side as your subordinate; you can then send it
to your colony (see [Races & Citizens](races-citizens.md#otherworlders)).

### Prove Your Might (Falmuth)

Falmuth's Covenant deal completes when you kill the Wither. The reward is the
**Holy Field Stone**. Using it raises a holy field with a 12-block radius
around where you stand, lasting 30 seconds. Enemies inside get Anti-Skill and
Anti-Magic (they can't use skills or cast spells) and take 1 heart of holy
damage per second. Enemies are hostile monsters (including wild Tensura
monsters), raiders, rival garrison defenders, and any mob attacking you, your
subordinates, or a colony citizen. Wild goblins, orcs, lizardmen, and dwarves
are left alone unless they attack your side. You, other players, anyone's
subordinates and pets, colony citizens, unnamed settlers, and allied fighters
are never affected, even if you or they are monsters. The stone recharges in 30 minutes
(real time).

### The Great Hunt (Eurazania)

Eurazania's Covenant deal completes when you kill 3 great beasts: any mix of
the Wither, Warden, Elder Guardian, Charybdis, and Ifrit. The reward is the
**Pack Leader's Mark**. Right-click a mob (on it, or looking at it from up to
32 blocks away) to mark it as prey for 60 seconds:

- The prey glows, so you can see it through walls.
- All of your subordinates within 48 blocks of the prey stop what they're doing
  and attack it.
- Your subordinates deal 50% more damage to it.
- When it dies, each subordinate nearby heals a quarter of its max health.

You can have one mark at a time. Players, colony citizens, allied fighters, and
your own subordinates and pets can't be marked. The mark only uses its charge
when it lands, then recharges in 15 minutes (real time).

### Souls For The Core (Moderate Harlequin Alliance)

The Moderate Harlequin Alliance's Covenant deal completes when you kill 10
villagers. The reward is the **Orb of Domination**, the pendant Clayman used
on Milim. Right-click a hostile mob with it and the orb is hung on that mob,
which then serves you until it dies:

- It won't attack players, colony citizens, or anything you own.
- It attacks whatever is attacking you, then whatever you attack, then the
  nearest hostile mob.
- With nothing to fight, it follows you. It teleports to you if it falls more
  than 32 blocks behind.
- It never despawns, and it keeps serving you after a restart.
- A zombie that turns into a drowned (or a similar change) keeps the orb.

When the mob dies, the orb drops where it fell. Dropped orbs never despawn.
The orb also drops if the mob is removed without dying, such as when the world
is switched to Peaceful.
Killing your own puppet is how you get the orb back early.

Only hostile mobs can take the orb: monsters, or any mob currently attacking
you. The mob's EP must be at most half of yours, and it can't already have an
owner. Bosses, raiders, rival garrison defenders, assassins, and goblins, orcs,
lizardmen, dwarves, and otherworlders can't be dominated.

### The Imperial Compact (Eastern Empire)

The Eastern Empire's Covenant deal is a delivery: 4 diamond blocks, 32 amethyst
shards, and 16 redstone blocks. The reward is the **Imperial Garrison
Charter**. Right-click it while standing inside a colony you own. The charter
is used up, and from then on every guard in that colony (knights, archers,
and druids) has:

- +50% max health
- +4 armor and +2 armor toughness
- +50% knockback resistance
- 25% more damage (melee, arrows, and druid attacks)

The bonus is permanent and covers guards you hire later. A guard who is fired
or changes jobs loses it. Each colony can hold one charter.

### The Trial Of Light & Dark (Luminous)

Luminous's Covenant deal is a task, not a delivery. Accepting it gives you two
empty chalices, and you fill each one separately:

- **A Show of Faith** — cure zombie villagers and raise the cured villagers to
  the top of their trade. This fills the holy chalice.
- **The Blood Sacrifice** — kill your own named subordinates. This fills the
  blood chalice.

Each chalice fills in three steps, and its appearance changes as it fills. With
both full, press **Deliver** on the deal to hand them over. Luminous forges them
into the **Twin Grail**: by day it heals you and clears harmful effects; by
night it grants strength and speed, and 25% of your melee damage comes back to
you as health.

If you are on the majin (monster) path, Luminous asks for more on both counts:
your cured villagers must breed and raise a new generation, and the blood must
come from your three strongest subordinates. Which version you get is fixed when
you accept the deal and does not change if you switch sides afterwards.

The chalices cannot be lost — they stay with you on death.

### Lending Citizens

Some deals ask you to **lend** a few citizens with a particular skill. You pick
exactly who goes. Those colonists leave your workforce for the deal's duration
(their jobs go unstaffed) and return with that skill raised.

!!! success "Your Citizens Are Never Lost"
    Lent colonists always return. If the deal is interrupted or relations
    break, they come home untrained. If their colony is gone, they join
    another colony you own.

## Relationship Tiers

Relations progress through three tiers, each unlocking more.

=== "Diplomacy"

    The entry tier. You can take deals and trade. Earned standing decays
    slowly while the relationship is idle (no active or in-progress deal),
    and a large enough drop in standing ends relations. An active
    relationship does not decay.

=== "Alliance"

    Formed by accepting the **alliance prompt** that appears at alliance-range
    standing (not a deal). Alliances decay far slower than Diplomacy, but a
    sharp standing crash still shatters one (see *Ending relations* below). It
    unlocks:

    - **No raids from the faction** — its monster events won't target your
      colony.
    - **An alliance buff** — an ambient effect themed to the faction (Dwargon
      Haste, Tempest Regeneration, Luminous Resistance, etc.), active while
      the alliance holds.
    - **A daily trade caravan** — claim a bundle of that faction's items once
      per day.
    - **Caravan Home** — teleport to your town hall from faction settlements.

=== "Covenant"

    The top tier. After Alliance, standing rises slowly toward a Covenant
    threshold. Crossing it unlocks the faction's unique **milestone deal**.
    Completing it forms the Covenant and grants that faction's unique reward:

    - **Dwargon** — a daily generator of industrial goods, plus a masterwork
      forging recipe.
    - **Milim** — the **Absolute Annihilator**, a custom growing hammer, for
      slaying the Warden (also grants her Strength skill), plus a **Drago Nova**
      blast you can claim about once an hour.
    - **Luminous** — the **Twin Grail**, from her
      [Trial of Light & Dark](#the-trial-of-light-dark-luminous), plus starter
      elemental spirits (only if you have none).
    - **Jura-Tempest Federation** — the **Seal of Ascension**: +25% EP for one
      subordinate and faster EP growth for 30 minutes
      ([details](#a-nation-of-many-peoples-jura-tempest-federation)).
    - **Leon** — the **Otherworlder Summoning Codex**: summons an otherworlder
      as your subordinate ([details](#tribute-to-the-platinum-saber-leon)).
    - **Falmuth** — the **Holy Field Stone**: a 30-second field that blocks
      enemy skills and magic ([details](#prove-your-might-falmuth)), plus
      stronger faction reinforcements during raids.
    - **Eurazania** — the **Pack Leader's Mark**: marks a mob as prey for your
      subordinates ([details](#the-great-hunt-eurazania)).
    - **Moderate Harlequin Alliance** — the **Orb of Domination**: makes a
      hostile mob serve you until it dies
      ([details](#souls-for-the-core-moderate-harlequin-alliance)), plus
      advance notice of incoming raids, and a tame Orc Disaster to kill
      without penalty.
    - **Eastern Empire** — the **Imperial Garrison Charter**: permanently
      strengthens one colony's guards
      ([details](#the-imperial-compact-eastern-empire)).

    Covenant also reduces supply-deal costs and increases raid reinforcements.

!!! tip "Two Ways To A Faction's Skill"
    Each faction teaches a Tensura skill as the reward for its hardest deal.
    You can earn it through diplomacy, or by
    [conquering that faction's settlement](warfare.md) — both grant the
    same skill.

## Ending Relations

Two things end relations:

- **Decay** — idle Diplomacy-tier relations decay and can lapse. Alliances
  decay much more slowly, but a fully abandoned one eventually breaks.
- **Standing crash** — a large drop in standing (e.g. killing one of the
  faction's marked bosses, or [declaring war on it](warfare.md))
  resets relations to none, cancels active deals, and returns lent citizens.

### The Rite Of Atonement

If a faction refuses to deal with you at all (for example after you
[declared war](warfare.md#declaring-war-ends-diplomacy) on it), it offers one
deal while in that state: the **Rite of Atonement**. It costs a
tribute of diamonds plus the sacrifice of your strongest named subordinate
(the subordinate must be present). Completing it reopens relations at the
lowest standing — you restart from near zero rather than recovering the prior
relationship. It is repeatable — each performance costs another subordinate.

## Quick Reference

- **Open the Diplomacy tab:** press `G`, then click **Diplomacy**.
- **`/diplomacy`** — shows your current relations and active deals for every
  faction.
- **Reroll offers:** if you don't like a faction's current deals, the tab has
  a reroll button that swaps them for a few high magic crystals (with a
  cooldown).
- The entire faction layer — diplomacy included — can be turned off in the
  [config](../reference/config.md).

!!! note "Want The Full List Of Factions And Their Flavour?"
    See the [Factions reference](../reference/factions.md) and the
    [Quest Catalog](../reference/quest-catalog.md).
