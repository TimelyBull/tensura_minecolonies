# Diplomacy

Diplomacy is the non-combat way to deal with factions: open relations,
complete deals to raise standing, and progress through three relationship
tiers that unlock rewards. It runs in parallel with
[Rival Colonies](rival-colonies.md); you can use both.

!!! info "Where to find it"
    Open your roster with the **`G`** key and click the **Diplomacy** tab.
    Each known faction is a row showing its current standing and any offered
    deals.

## Opening relations

Before you can deal with a faction, you have to be on speaking terms. There
are two ways relations open:

- **They send an envoy to you.** Friendly and neutral factions occasionally
  send a diplomatic envoy that appears near your town hall. Right-click it to
  hear them out and accept — relations open on the spot.
- **You send an envoy to them.** On the Diplomacy tab, press **Send Envoy**
  for a faction. You can attach a gift (it's taken from your inventory and
  earns a little goodwill). After about a day, they reply: if your standing
  is high enough, relations open. If not, they decline — but asking costs you
  nothing except the gift.

!!! warning "Race matters"
    Some factions are picky about who they'll deal with. The Holy Empire and
    its allies court **humans** readily but will never *reach out* to a
    majin — if you've gone down the majin path, you'll have to send your own
    envoy, and they're a harder sell. A few factions (schemers and the truly
    aloof) never send envoys at all, but may still open relations if you make
    the first move.

## Deals: the heart of diplomacy

Once relations are open, a faction offers **deals** — tasks in exchange for
items and standing. Each faction offers tasks themed to it, and completing
deals is the main way standing rises.

A deal asks for one of:

| The faction wants… | You fulfil it by… |
|---|---|
| **Supplies** (e.g. 64 iron, a food bundle) | Pressing **Deliver** on the tab — the items are taken from your inventory |
| **A building** at a certain level (e.g. a Library L3) | Building/upgrading it in your colony |
| **A population** (e.g. 15 citizens) | Growing your colony to that size |
| **Happiness** (e.g. average ≥ 7) | Keeping your colonists content |
| **Lent citizens** (see below) | Sending some colonists to work for them a while |

Every deal has a **deadline**. Complete it in time and you collect the
reward and a bump in standing; let it expire and your standing takes a hit
(failing stings more than never accepting in the first place). You'll see a
progress bar on the active deal, and a faction's row shows its current deal
at a glance.

### Lending citizens

Some deals ask you to **lend** a few citizens with a particular skill. When
you accept, a picker opens so you choose exactly who goes. Those colonists
leave your workforce for the agreed time — their jobs genuinely go unstaffed,
so it's a real cost — and when the deal pays off, they **come home trained**,
returning with that skill noticeably higher than when they left.

!!! success "Your citizens are never lost"
    Lent colonists are always returned safely — even if the deal is
    interrupted, the colony they came from is gone (they'll join another
    colony you own), or relations break down mid-loan (they come straight
    home, untrained but unharmed).

## Relationship tiers

Relations progress through three tiers, each unlocking more.

=== "Diplomacy"

    The entry tier. You can take deals and trade. Earned standing decays
    slowly while the relationship is idle (no active or in-progress deal),
    and a large enough drop in standing ends relations. An active
    relationship does not decay.

=== "Alliance"

    Reached by completing a faction's **Alliance Pact** deal, offered once
    your standing with it is high. An alliance survives standing drops that
    would end Diplomacy, and unlocks:

    - **No raids from the faction** — its monster events won't target your
      colony.
    - **An alliance buff** — an ambient effect themed to the faction (Dwargon
      Haste, Tempest Regeneration, Luminous Resistance, etc.), active while
      the alliance holds.
    - **A daily trade caravan** — claim a bundle of that faction's items once
      per day.
    - **Caravan Home** — teleport to your town hall.

=== "Covenant"

    The top tier. After Alliance, standing rises slowly toward a Covenant
    threshold; crossing it unlocks the faction's unique **milestone deal**.
    Completing it forms the Covenant and grants that faction's unique reward,
    for example:

    - **Dwargon** — a daily generator of industrial goods, plus a masterwork
      forging recipe.
    - **Milim** — the **Drago Nova**, a one-use area blast (once per
      real-world hour).
    - **Luminous** — starter elemental spirits (only if you have none).
    - **Falmuth** — stronger faction reinforcements during raids.
    - **Clayman** — advance notice of incoming raids, and a tame Orc Disaster
      to kill without penalty.

    Covenant also reduces supply-deal costs and increases raid reinforcements.

!!! tip "Two ways to a faction's skill"
    Each faction teaches a Tensura skill as the reward for its hardest deal.
    You can earn it through diplomacy, or by
    [conquering that faction's settlement](rival-colonies.md) — both grant the
    same skill.

## Ending relations

Two things end relations:

- **Decay** — idle Diplomacy-tier relations decay and can lapse. Alliances
  decay much more slowly, but a fully abandoned one eventually breaks.
- **Standing crash** — a large drop in standing (e.g. killing one of the
  faction's marked bosses, or [declaring war on it](rival-colonies.md))
  resets relations to none, cancels active deals, and returns lent citizens.

### The Rite of Atonement

If a faction is pushed far enough that it refuses to deal with you at all, it
offers one deal while in that state: the **Rite of Atonement**. It costs a
tribute of diamonds plus the sacrifice of your strongest named subordinate
(the subordinate must be present). Completing it reopens relations at the
lowest standing — you restart from near zero rather than recovering the prior
relationship. It is repeatable; each performance costs another subordinate.

## Quick reference

- **Open the Diplomacy tab:** press `G`, then click **Diplomacy**.
- **`/diplomacy`** — shows your current relations and active deals for every
  faction.
- **Reroll offers:** if you don't like a faction's current deals, the tab has
  a reroll button that swaps them for a few high magic crystals (with a
  cooldown).
- The entire faction layer — diplomacy included — can be turned off in the
  [config](../reference/config.md) if you'd rather build in peace.

!!! note "Want the full list of factions and their flavour?"
    See the [Factions reference](../reference/factions.md) and the
    [Quest Catalog](../reference/quest-catalog.md).
