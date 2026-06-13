# Colony Reputation

Colony reputation is a 0–100 score per colony, starting at 50. It measures
how the world regards the colony, and it gates [raids](raids-barriers.md)
and colours [envoy](world-reputation.md) dialogue. It is separate from your
standing with the Tensura [factions](world-reputation.md).

## Tiers

The score falls into six tiers:

| Score | Tier |
|---|---|
| 0–9 | Hostile |
| 10–19 | Passive-Aggressive |
| 20–39 | Wary |
| 40–59 | Neutral |
| 60–79 | Loyal |
| 80–100 | Devoted |

Your current value and tier show on the roster (press `G`), next to the
colony name.

## What changes it

| Action | Reputation |
|---|---|
| Build or upgrade an amenity building (tavern, restaurant, hospital, library, school, university, etc.) | +4 |
| Build or upgrade any other building | +2 |
| Repel a raid | +8 |
| Kill a major boss near the colony | +10 |
| Damage one of your own citizens | −5 |
| Kill one of your own citizens | −15 |

(Repairing or removing buildings doesn't change reputation.)

## Drift toward a resting point

Once per in-game day, reputation drifts toward a resting point set by your
colony's **happiness**:

- Higher happiness sets a higher resting point (around 70 at maximum
  happiness).
- Low happiness sets a low resting point, and very low happiness pulls it
  down steeply.

The drift is gradual (a fraction of the gap per day, capped), so one-off
events like repelling a raid or losing a citizen clearly outweigh it — drift
just normalises reputation between events over a week or two. The practical
takeaway: **keep your citizens happy and reputation settles high on its
own; let happiness collapse and it sinks.**

## What it affects

- **Raids** — a colony below Neutral can be [raided](raids-barriers.md) at
  night, more often the lower it sits.
- **Envoy tone** — [faction envoys](world-reputation.md) speak more warmly
  or coldly depending on the tier.
- **Assassins** — sustained low reputation *and* low happiness can breed an
  [assassin](assassins.md) among your own citizens.
