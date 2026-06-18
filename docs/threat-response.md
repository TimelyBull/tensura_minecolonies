# Colony threat-response — as-built

**Status:** BUILT (2026-06-17). EP-gated colonist↔Tensura defense swap +
autocaster. Behind no new config; only acts while a colony is raided.

## What it does

When a colony is under attack, each of its citizens reacts by power tier:

- **MineColonies guards** — untouched. They fight exactly as MineColonies
  intends. Detected via `citizenData.getJob().isGuard()` and excluded from
  every code path below.
- **Regular citizens, and Tensura-race citizens below `FORM_SWAP_EP`** —
  flee. We add NO custom flee-steer; we lean on MineColonies' **native**
  non-combatant raid behaviour (citizens flee / hide on their own while
  `getRaiderManager().isRaided()` is true). A low-EP Tensura citizen is a
  normal colonist body, so it flees with everyone else.
- **Tensura-race citizens with EP ≥ `FORM_SWAP_EP` (10,000, ⚠ BALANCE
  GUESS, tunable)** — **place-swap** to their Tensura subordinate body and
  fight. The colonist body de-materialises; the Tensura body appears in its
  place (true in-place swap) and casts skills at the raiders. When the threat
  ends, it swaps back to colonist form.

Skill-fighting only ever happens in the Tensura subordinate form — the only
skill-bearing body. The colonist form holds no skills, and plain MineColonies
citizens have no ManasCore skill storage, so the autocaster never touches
them.

## How the swap works (NOT the player menu)

The swap reuses the SAME helpers as the player's roster-menu summon/send
(`ExampleMod.summonGoblin` / `sendGoblinToColony`) — there is one swap
implementation, not two. The menu's cost gate (magicule), magic-circle
visuals, and dramatic delay all live in a SEPARATE upper layer
(`handleMenuAction → chargeOrPrompt → queueDelayedSwap → executePendingSwap`),
which the threat-response bypasses entirely.

Two programmatic, menu-less, cost-less entry points were added:

- `ExampleMod.defenseSwapToSubordinate(level, saved, identity)` — calls
  `summonGoblin` with `player = null` (positions/facing/overflow fall back to
  the citizen's own position) and `animate = false` (no underground rise — the
  body appears instantly, ready to fight). Materialises at the citizen's exact
  position. On success: flags the identity `defendingColony = true` and tags
  the live mob `COLONY_DEFENDER`.
- `ExampleMod.defenseSwapToColony(level, saved, identity)` — calls
  `sendGoblinToColony` with `triggeringPlayer = null` (advisory chat
  suppressed; overflow dropped at the town hall). On success: clears
  `defendingColony`.

The helpers were made `player`-nullable + given an `animate` flag; the single
existing menu caller passes the player and `animate = true`, so menu behaviour
is unchanged.

## Trigger + evaluator

`ColonyThreatResponse.tick(server)` runs every second from the realtime
scheduler pass (beside `TensuraRaids.tick` / `Assassins.tick` / …). The threat
signal is `colony.getRaiderManager().isRaided()` — which covers BOTH
MineColonies' own raids and our `TensuraRaidEvent` (it's registered in MC's
colony-event registry).

Per colony (town hall required for both swap directions):
- **Raided:** for each non-guard IN_COLONY Tensura citizen with a loaded body
  and EP ≥ threshold → `defenseSwapToSubordinate`, then steer onto a raider.
  For each existing defender (`defendingColony` + SUBORDINATE) → re-assert the
  tag + targeting.
- **Not raided:** for each `defendingColony` identity → `defenseSwapToColony`.

**Targeting** reuses the ally-support dual-write idiom: lock onto the nearest
living `RAID_TAG` mob via `BrainUtils.setTargetOfEntity` + `setTarget`. The
autocaster reads `mob.getTarget()`, so once a defender is steered onto a
raider it casts; with no raider in range it does nothing (combat-only).

**Autocaster** (`registerAutocaster`, common setup): one public-API
`NightmareUtilsApi.registerReflectiveManascoreAutocaster` keyed on the
`COLONY_DEFENDER` tag, allowing any learned skill (the lib filters passives),
cooldown `DEFENDER_CAST_COOLDOWN_TICKS` (100). No mixins into the lib.

## Mass-swap performance

A big raid can push many citizens over the threshold at once. Swaps are capped
at `MAX_SWAPS_PER_TICK` (3) across all colonies, spreading a mass swap over a
few seconds so no single tick is heavy. The qualifying set is small anyway
(only ≥10k-EP non-guard Tensura citizens).

## Edge cases

- **Tensura body dies while swapped-in** — handled by the EXISTING death hook
  (`ExampleMod.onLivingDeath` → `getByMobUUID` → `removeCivilian` +
  `removeIdentity`). A defender dying = the citizen permanently dies (correct);
  the `defendingColony` flag is dropped with the record. No new code.
- **Threat ends mid-fight** — the next tick sees `isRaided() == false` and
  swaps every defender back.
- **Logout / reload mid-swapped-state** — `mode = SUBORDINATE` already
  persists, and `defendingColony` is a PERSISTENT flag on the identity (the
  source of truth that distinguishes a defense-swap from a player summon). The
  `COLONY_DEFENDER` attachment is also NBT-serialized. On reload the evaluator
  reconciles: threat over → swap back; still raided → re-assert tag +
  targeting. The body returns to the correct form.
- **No town hall / chunk unloaded** — the colony (or that swap) is skipped and
  retried next tick; the swap helpers' existing chunk-not-loaded handling
  applies. A defender whose mob is unloaded stays out until it loads, then
  swaps back.

## Tunables (all ⚠ BALANCE GUESS)

| Constant (`ColonyThreatResponse`) | Default | Meaning |
|---|---|---|
| `FORM_SWAP_EP` | 10,000 | EP at/above which a Tensura citizen fights instead of fleeing |
| `MAX_SWAPS_PER_TICK` | 3 | place-swaps performed per second |
| `RAIDER_SCAN_RADIUS` | 80 | blocks around the town hall scanned for raiders |
| `DEFENDER_CAST_COOLDOWN_TICKS` | 100 | autocaster cooldown |

## Deferred / not done

- No custom flee-steer (MC native flee is used). If MC's flee ever proves
  insufficient, a WALK_TARGET-toward-guard-tower steer is the documented
  fallback.
- No debug command yet (test by triggering a raid on a colony with a ≥10k-EP
  Tensura citizen).
