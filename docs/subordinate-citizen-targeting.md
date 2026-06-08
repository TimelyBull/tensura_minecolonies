# Investigation: subordinates assist-attack my own colony citizens

**Status:** investigation only, no code written yet (2026-06-06).

**Bug:** When the player hits a colony citizen, the player's Tensura subordinates
(named goblins/orcs at the player's side) join in and can kill that citizen.
We want to EXCLUDE the player's own colony citizens from subordinate
assist-attacks, even when the player hits one.

All class references below were confirmed by decompiling the jars in `libs/`
(`tensura-neoforge-2.0.1.0.jar`, `manascore-*-4.0.0.2.jar`,
`minecolonies-1.1.1319-1.21.1.jar`) with Vineflower.

---

## 1. OWNER-AGGRESSION SHARING — what makes a subordinate join the owner's fight

There are **two separate targeting code paths** in Tensura. Only the second one
applies to our named goblins/orcs.

### (a) Legacy AI goals — NOT used by our subordinates
`io.github.manasmods.tensura.entity.ai.goal.TensuraOwnerHurtGoal` and
`TensuraOwnerHurtByTargetGoal` are classic `TargetGoal`s. `TensuraOwnerHurtGoal`
reads `owner.getLastHurtMob()` and makes the mob target it — exactly the
"owner hit something, assist" behaviour. **But** both goals begin with:

```java
if (this.entity instanceof ISubordinate) { return false; }
```

Our named goblins (`GoblinEntity`) and orcs (`OrcEntity`) are `ISubordinate`, so
these goals are disabled for them. They exist for non-subordinate owned mobs.
Both goals also already respect alliance: `!this.entity.isAlliedTo(target)`.

### (b) SmartBrain behaviour — THE ACTIVE PATH for goblins/orcs
`GoblinEntity` and `OrcEntity` both extend `PlayerLikeEntity` (→
`TensuraTamableEntity`) and are SmartBrainLib brain owners. In their **idle**
brain tasks both register:

```java
TensuraBehaviourHelper.getPreyTargeting(this, entity -> false)
```

`getPreyTargeting` (in `entity.ai.behaviour.TensuraBehaviourHelper`) returns a
`RetaliateOrTarget` behaviour whose attackable predicate is:

```java
subordinate.shouldTarget(subordinate, entity, combinedPrey)
```

`ISubordinate.shouldTarget(Mob subordinate, LivingEntity target, Predicate nonTame)`
is the decision. The relevant branch (owner present) returns **true** to target
when any of these match — note the assist lines:

```java
} else if (owner.isAlliedTo(target))          { return false; }   // <-- ally veto
  ...
} else if (owner.getLastAttacker()  == target) { return true; }
} else if (owner.getLastHurtMob()   == target) { return true; }   // <-- PLAYER HIT IT
} else if (owner instanceof Mob m && target == m.getTarget()) { return true; }
```

So **player hits citizen → `player.getLastHurtMob() == citizen` →
`shouldTarget` returns true**. That is the trigger.

The whole method is also gated up front by:
```java
if (subordinate.canAttack(target) && !subordinate.isAlliedTo(target)) { ... }
```
i.e. if the subordinate is allied to the target, it never targets it.

### How the target is actually committed (the real chokepoint)
`RetaliateOrTarget.start(entity)` does NOT just call `mob.setTarget`. It first
fires a **ManasCore Architectury event** and only commits if not vetoed:

```java
Changeable<LivingEntity> target = Changeable.of(this.toTarget);
if (!EntityEvents.LIVING_CHANGE_TARGET.invoker().changeTarget(entity, target).isFalse()) {
    BrainUtils.setTargetOfEntity(entity, target.get());   // commit to brain memory
    ...
}
```

`io.github.manasmods.manascore.skill.api.EntityEvents.LIVING_CHANGE_TARGET` is an
Architectury `Event` created with `EventFactory.createEventResult`. Listener
interface:

```java
EventResult changeTarget(LivingEntity entity, Changeable<LivingEntity> target);
```

Returning an `EventResult` whose `.isFalse()` is true **aborts the target
change**. (There are also `_EARLY` and `_LATE` variants of the same event.)

---

## 2. THE EXCLUSION POINT — options evaluated

### Option (b) PRIMARY RECOMMENDATION — ManasCore `LIVING_CHANGE_TARGET` listener
Register a listener on `EntityEvents.LIVING_CHANGE_TARGET` (same Architectury
`.register(lambda)` pattern the project already uses for
`TensuraEntityEvents.NAMING_EVENT` — see decisions.md "Event system"). In the
listener:

- if `entity` is a Tensura subordinate with a player owner, AND
- the proposed `target.get()` is one of that owner's colony citizens,
- return `EventResult.interruptFalse()` (veto); otherwise `EventResult.pass()`.

This is the exact gate Tensura itself consults before committing an assist
target, it requires **no new mixin**, it matches the project's existing
Architectury-event convention, and it covers **both** goblin and orc (both route
through `RetaliateOrTarget`). It is the most surgical fix.

Caveat: it is an **acquisition-time veto** — it stops the subordinate *picking
up* the citizen as a new target. It does not forcibly drop a target already held,
and would not catch a hypothetical future code path that calls `mob.setTarget`
directly without firing this event.

### Option (c) ALTERNATIVE (more complete) — alliance via `isAlliedTo`
`TensuraTamableEntity.isAlliedTo(Entity)` currently is:

```java
public boolean isAlliedTo(Entity entity) {
   LivingEntity owner = this.getOwner();
   if (!this.isTame() || owner == null) return super.isAlliedTo(entity);
   else if (entity == owner) return true;
   else return entity instanceof ISubordinate sub ? sub.isOwnedBy(owner)
                                                   : owner.isAlliedTo(entity); // team-based → false for citizens
}
```

A mixin that makes this also return true when `entity` is one of the owner's
colony citizens would cover **every** path at once: the `shouldTarget` front gate
(`!subordinate.isAlliedTo(target)`), `shouldStopTarget`
(`subordinate.isAlliedTo(target)` → drops an already-held target), the legacy
goals, and friendly-fire/AoE checks. More thorough, but it is a **broad
behavioural change** to a very-frequently-called method and needs a Tensura-side
mixin (project currently only mixins MineColonies). Higher blast radius.

### Option (a) target predicate on the goal — N/A
The legacy goals are disabled for `ISubordinate`, so editing/predicating them
does nothing for our case. Not viable.

**Recommendation:** ship Option (b) as the primary fix. If testing shows a
subordinate that already locked onto a citizen keeps attacking (target held
before veto), add Option (c)'s `isAlliedTo` mixin (or call
`SubordinateHelper.removeTarget`) to also drop in-progress targets.

---

## 3. IDENTIFYING "MY CITIZEN" from the targeting check

Inside the `LIVING_CHANGE_TARGET` listener we hold the subordinate (`entity`) and
the proposed target. Determine ally-citizen status with cheap, server-side,
O(1) lookups — **no SavedData / RaceIdentity lookup needed**:

1. Subordinate's owner UUID:
   `UUID ownerUuid = SubordinateHelper.getSubordinateOwnerUUID(entity);`
   (Use this helper, not raw `ISubordinate.getOwnerUUID()` — it resolves the full
   temporaryOwner → summoner → permanentOwner → ISubordinate chain.)
2. Target is a citizen:
   `target instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen`
3. Citizen's colony + owner:
   `IColony colony = citizen.getCitizenColonyHandler().getColony();`
   `colony != null && colony.getPermissions().getOwner().equals(ownerUuid)`
   (`getPermissions().getOwner()` is already used elsewhere in `ExampleMod`.)

If all hold → it's the player's own citizen → veto.

The RaceIdentity store / citizen↔identity link is **not** needed and is the
wrong signal for the requested scope: identity only covers our race-citizens,
whereas the scope is *all* citizens in colonies the player owns.

---

## 4. SCOPE confirmation

"Is an `AbstractEntityCitizen` in a colony owned by this subordinate's owner" is
a **clean, cheap, server-side check**: one `instanceof`, one
`getCitizenColonyHandler().getColony()` (returns the live `IColony` server-side;
may be null — guard it), one `getPermissions().getOwner()` UUID compare, one
`SubordinateHelper.getSubordinateOwnerUUID`. No iteration, no persistence read.
It naturally covers ALL of the player's colony citizens (race-citizens and
vanilla citizens alike), as required.

---

## Least-fragile approach + single biggest risk

**Least-fragile approach:** the ManasCore `EntityEvents.LIVING_CHANGE_TARGET`
listener (Option b). It hooks the exact, intended extension point Tensura
already routes assist-targeting through, needs no coremod/mixin, follows the
project's existing Architectury-event registration pattern, and covers both
goblin and orc subordinates with one narrow, gated predicate.

**Single biggest risk:** it is an **acquisition-time veto, not a target
clear**. It reliably prevents a subordinate from *acquiring* the citizen as a new
target, but it does not forcibly drop a citizen target the subordinate already
holds, nor would it catch a target set through a path that bypasses this event
(e.g. a future Tensura skill calling `mob.setTarget` directly). Secondary risk:
the listener fires for *every* mob target change in the game, so the predicate
must be tightly gated (subordinate-with-player-owner first, null-safe
`getColony()`) to avoid both a perf cost and accidentally vetoing legitimate
enemy targeting. Mitigation if observed in testing: pair with the `isAlliedTo`
route (Option c) or a `SubordinateHelper.removeTarget` sweep so already-held
citizen targets are also dropped.
