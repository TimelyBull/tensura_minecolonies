# Investigation: hostile Tensura mobs should target colony citizens (like they target players)

**Status:** investigation only, no code written yet (2026-06-06).

**Companion to** [subordinate-citizen-targeting.md](subordinate-citizen-targeting.md). The
previous note covered the SUBTRACTIVE direction (owned subordinates must NOT
target own-colony citizens). This note covers the ADDITIVE direction: wild
Tensura mobs that are innately hostile to the player should also be hostile to
the player's colony citizens.

All references were confirmed by decompiling the jars in `libs/` with Vineflower
and inspecting class files with `javap`.

---

## 1. HOSTILE TARGETING MECHANISMS — innate vs retaliatory

Two legacy `TargetGoal`s exist (`TensuraOwnerHurtGoal`, `TensuraOwnerHurtByTargetGoal`)
— both early-out for `ISubordinate` and are not the path for tameable mobs. For
goblins/orcs/spiders/etc. (all `TensuraTamableEntity` descendants), targeting
is **SmartBrain**-driven.

### Active path: SmartBrain `RetaliateOrTarget` + `ISubordinate.shouldTarget`
Hostile mobs register `TensuraBehaviourHelper.getPreyTargeting(this)` in their
idle brain tasks (example: `BlackSpiderEntity.getIdleTasks` calls it with
**no `preyPredicate` override**). The helper builds a `RetaliateOrTarget` whose
`attackablePredicate` invokes `ISubordinate.shouldTarget(subordinate, target, combinedPrey)`.

`shouldTarget`'s wild branch (in `ISubordinate.java`):

```java
LivingEntity owner = SubordinateHelper.getSubordinateOwner(subordinate);
if (owner == null) {
   if (subordinate.getTarget() == target) return true;
   else if (subordinate instanceof NeutralMob m && m.isAngryAt(target)) return true;
   else if (!this.isTame() && nonTameCondition.test(target)) return true;   // <-- INNATE-HOSTILE GATE
   else if (this.getBehaviour() == 2) return true;                          // (manual aggressive)
   ...
}
```

So a wild, untamed mob targets `target` on sight iff `nonTameCondition.test(target)` is true.
`nonTameCondition` for `getPreyTargeting(this)` (no override) is
`getAnimalPreyPredicate(subordinate).and(getSleepingPreyPredicate)`.

`getAnimalPreyPredicate` is:

```java
target -> {
   if (target.getType().equals(EntityType.PLAYER)) return true;                       // <-- INNATE PLAYER HOSTILITY
   else if (subordinate.getHealth() >= subordinate.getMaxHealth() * hostileHPMultiplier) return false;
   else return target.getType() == subordinate.getType() ? false : target.getType().is(TensuraEntityTags.ANIMAL_PREY);
}
```

(The HP gate is Tensura's "wounded animals attack out of desperation" rule.
Player is unconditional; tag membership is gated by low HP.)

### Retaliatory path
The previous behaviour line we already audited — `lastHurtBy` is checked first
inside `shouldTarget` and `subordinate.getBrain()`'s `HURT_BY_ENTITY` memory in
`TargetOrRetaliate.getTarget`. That fires when something HIT the mob.
Independent of the prey predicate. Out of scope: we don't need to change retaliation.

### Distinction by call site, not by class
There is no `interface IHostile` we can `instanceof` to identify innately-hostile
mobs. The distinction is **which arguments a mob passes to `getPreyTargeting` in
its brain wiring**:

| Mob | Call | Player on sight? |
|---|---|---|
| `BlackSpiderEntity` | `getPreyTargeting(this)` | YES — uses `getAnimalPreyPredicate` (Player == true) |
| `GoblinEntity` | `getPreyTargeting(this, entity -> false)` | NO — prey predicate is hard-`false` |
| `OrcEntity` | `getPreyTargeting(this, entity -> false)` | NO — same |

So the **set of mobs that attack players on sight** is exactly the set whose brain
wiring uses the **default `getAnimalPreyPredicate`**. Anything passing
`entity -> false` (named-races, passive/tamable companions) is NOT innately hostile
and must not become hostile to citizens.

---

## 2. ADDING CITIZENS AS TARGETS — least-fragile approach

### Why `LIVING_CHANGE_TARGET` is the wrong tool here
ManasCore's `LIVING_CHANGE_TARGET` fires inside `RetaliateOrTarget.start(entity)`
**after** the candidate target has already been chosen. The listener can VETO
(`interruptFalse`) or REDIRECT (`target.set(other)`), but cannot generate a
target-change event for a mob that wasn't going to target anything.

Confirmed by reading `TargetOrRetaliate.getTarget`: the candidate set comes from
the brain memories `NEAREST_ATTACKABLE` → `HURT_BY_ENTITY` →
`NEAREST_VISIBLE_LIVING_ENTITIES` (populated by sensors), then filtered by
`canAttackPredicate` (which is `shouldTarget` → `nonTameCondition`).
**Citizens never become candidates because they fail `nonTameCondition`.**
`LIVING_CHANGE_TARGET` cannot widen this filter — it gates a transition that
hasn't happened.

### Option A — PRIMARY RECOMMENDATION: add `minecolonies:citizen` to the
### `tensura:animal_prey` entity-type tag via our mod's datapack
`getAnimalPreyPredicate` returns true for `target.getType().is(TensuraEntityTags.ANIMAL_PREY)`.
Adding `minecolonies:citizen` to that tag (a 6-line JSON file at
`src/main/resources/data/tensura/tags/entity_types/animal_prey.json`) makes
citizens satisfy `nonTameCondition` for every mob using the **default
`getPreyTargeting(this)`** call — i.e., exactly the set we want.

Cross-impact audit: a full scan of decompiled Tensura `entity/ai/**` for
`ANIMAL_PREY` returns **one usage** (`TensuraBehaviourHelper.getAnimalPreyPredicate`
line 145). Tight scope, no surprise interactions.

Wrinkle: the tag is consulted **only when the mob's HP > maxHP × hostileHPMultiplier**
is false (the second branch in `getAnimalPreyPredicate`). Player is unconditional;
ANIMAL_PREY targets require the mob to be wounded. So this option makes citizens
"opportunistic prey" — wild hostile mobs target citizens **when wounded**, but
not unconditionally on full HP. **This does NOT match the design rule**
("wherever a Tensura mob already targets players, it should also target citizens"
implies unconditional, like Player).

### Option B — RECOMMENDED for "unconditional like Player": Mixin on `getAnimalPreyPredicate`
The cleanest way to put citizens on the **unconditional** branch (alongside
`EntityType.PLAYER`) is a Mixin that wraps `TensuraBehaviourHelper.getAnimalPreyPredicate`
(or `@ModifyReturnValue` on the lambda) so the returned `Predicate<LivingEntity>` also
returns true when `target instanceof AbstractEntityCitizen`. One-method mixin in
our mod's mixin config (we already have a `tensura_minecolonies.mixins.json` for
the CreateColonyMessage mixin). Targets ONE method that has exactly ONE usage.

This is the least-fragile way to match the design rule precisely.

### Option C — REJECTED: add a `NearestAttackableTarget<EntityCitizen>` Goal via `EntityJoinLevelEvent`
For each Tensura hostile mob type, on `EntityJoinLevelEvent` add a vanilla goal
to its `targetSelector`. Problems: (1) requires enumerating which mob types are
innately hostile (no clean property to ask), (2) SmartBrain mobs route targets
through brain memories, and a `targetSelector` goal calling `mob.setTarget`
fights the brain (`TargetOrRetaliate.canSwapTarget` periodically re-elects),
(3) bypasses Tensura's own gating (sleep, behaviour mode, allies). Higher
fragility, more code, less aligned with how Tensura already models hostility.

**Recommendation:** ship **Option B** (Mixin on `getAnimalPreyPredicate`).
Option A is the simplest fix if "wounded only" hostility is acceptable
(reasonable trade-off — keeps Tensura's HP gating intact and avoids any
Tensura-side mixin); confirm intent with the user before picking.

---

## 3. WHICH MOBS — identifying innately-hostile mobs without a marker interface

There is **no shared "innately hostile" property/interface** in Tensura
(checked: `ISubordinate` is the only shared interface, and it's added to
`TamableAnimal` itself via `MixinTamableAnimal` so every Tensura mob — and
every vanilla wolf/cat — is one). Hostility is determined per-mob by the
preyPredicate argument passed to `getPreyTargeting` in that mob's brain wiring.

**This is exactly why Options A and B are clean cuts:** they hook the
preyPredicate path itself. A mob that uses `entity -> false` (goblin, orc) does
NOT consult `getAnimalPreyPredicate` and therefore is unaffected — citizens
stay invisible to it. A mob that uses the default `getAnimalPreyPredicate`
(BlackSpider, and other wild hostiles using the default helper) immediately
gains citizens as valid prey alongside Player.

No mob-type enumeration is required. Tensura's own modeling does the
classification for us.

---

## 4. INTERACTION WITH THE SUBORDINATE VETO — disjoint by `shouldTarget` branch

The subordinate veto (in `ExampleMod.onSubordinateChangeTarget`) fires when:
- `entity instanceof ISubordinate` (true for every TamableAnimal via Mixin)
- `target instanceof AbstractEntityCitizen`
- `SubordinateHelper.getSubordinateOwnerUUID(entity) != null` AND equals the
  citizen's colony owner UUID.

The hostility addition (Options A/B) fires inside `shouldTarget` only on the
**`owner == null && !this.isTame()` branch** (wild mob path).

These are disjoint by construction:

| Entity state | Owner UUID | Veto fires? | Hostility predicate consulted? |
|---|---|---|---|
| Wild hostile mob (no owner) | null | **No** (UUID check) | **Yes** (wild branch) |
| Player-X subordinate vs player-X citizen | non-null | **Yes** | No (owner branch, different code path) |
| Player-X subordinate vs player-Y citizen | non-null | No (UUID mismatch) | No (still owner branch) |
| Vanilla wolf tamed by other player | non-null (different) | No (UUID mismatch) | No (different brain, Tensura helpers not registered) |

**No collision.** The two changes operate on disjoint conditions; neither
re-enables the other's intended behaviour.

Re-verified the subordinate veto itself: the `MixinTamableAnimal` finding above
means every Tensura mob is `instanceof ISubordinate` at runtime, so the veto
correctly fires for goblins/orcs/etc. (had briefly worried that the
non-explicit `implements` list would mean the veto never fires — javap on
`MixinTamableAnimal.class` confirms it `implements ISubordinate`, applied via
Mixin to all `TamableAnimal`s).

---

## 5. MINECOLONIES GUARD INTEROP — likely free, with one tag-membership wrinkle

MC's guard target test is `AbstractEntityAIGuard.isAttackableTarget(citizen, entity)`:

```java
if (IColonyManager.getInstance().getCompatibilityManager().getAllMonsters()
       .contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
    && !hostilesEntityListExcludesThisType) {
    return true;
}
```

`CompatibilityManager.discoverMobs` populates the monster set from:
1. `entity.getCategory() == MobCategory.MONSTER`, OR
2. `entity.is(ModTags.hostile)` (MC's `minecolonies:hostile` entity tag).

So guards will engage Tensura mobs **iff** that mob's EntityType has
`MobCategory.MONSTER` or is in `minecolonies:hostile`. Tensura's hostile mobs are
generally registered as `MobCategory.MONSTER` (standard practice for
on-sight-hostile mobs), so the expected outcome is: **guards engage for free**.

Two follow-up mitigations available if testing shows gaps:
- **Per-type fix:** add the offending EntityType to the
  `minecolonies:hostile` entity tag via our datapack (parallel to the
  `tensura:animal_prey` add).
- **No code change needed** to MC's threat system: once `isAttackableTarget`
  passes, the standard guard flow (`startHelpCitizen` → `threatTable.addThreat`
  → `CombatAIStates.ATTACKING`) takes over.

Report-back from testing should note: when a hostile Tensura mob attacks a
citizen near a guard, does the guard engage? If not, the EntityType is not in
MC's monster set — fix is a 6-line tag JSON.

---

## UPDATE (2026-06-27) — now a 3-level config, default OFF

Player feedback was that citizen aggression was too high. The mixin's gate
changed from the boolean `tensuraHostileToCitizens` gamerule (default true) to
the `Config.CITIZEN_AGGRESSION` enum config (`citizenAggression`:
**OFF / MEDIUM / HIGH, default OFF**):
- **OFF** — the prey branch returns false; citizens are never added as prey
  (back to vanilla-Tensura behaviour, no added aggression).
- **HIGH** — the original Option-B behaviour (unconditional prey on sight).
- **MEDIUM** — a stable ~50% split per `(mob, citizen)` pair derived from their
  entity ids: deterministic (no per-tick re-roll / flicker), so a given mob
  consistently treats a given citizen as prey-or-not; across the population
  mobs lock on about half as often.

The handler captures the mob via `@ModifyReturnValue`'s param-capture so the
MEDIUM coin can key on both ids. Everything below is the original
investigation that landed Option B.

## SCOPE confirmation — clean cut

For the first pass:
- **Innate-hostile mobs target citizens on sight** — Option B (`getAnimalPreyPredicate`
  Mixin) treats citizens as unconditional prey like Player, matching the design
  rule. Option A (datapack tag entry) is the lighter, no-mixin alternative if
  HP-gated/wounded-only hostility is acceptable.
- **Retaliatory/neutral mobs unaffected** — retaliation reads `HURT_BY_ENTITY`
  (separate branch in `shouldTarget`, doesn't consult prey predicate).
  Neutral mobs use NeutralMob `isAngryAt` (also separate).
- **Subordinates unaffected** — they use a different branch in `shouldTarget`
  (owner present), and the existing veto further blocks own-colony citizens.
- **Guard interop noted** — most likely free; one-line tag fallback if any
  Tensura EntityType lacks `MobCategory.MONSTER`.

---

## Pivotal answers

**#2 — can `LIVING_CHANGE_TARGET` ADD targets?** **No.** It fires after candidate
selection inside `RetaliateOrTarget.start`; can veto or redirect, cannot
generate. The actual extension point is the **prey predicate** — Option B
(Mixin on `getAnimalPreyPredicate`) for unconditional citizen-as-prey, or
Option A (datapack tag on `tensura:animal_prey`) for wounded-only.

**#4 — no collision with the subordinate fix.** The veto fires only when the
target is an `AbstractEntityCitizen` AND the subordinate's owner UUID matches
the colony owner — a path disjoint from `shouldTarget`'s wild branch where
the new hostility kicks in. Veto-true and hostility-true cannot both hold for
the same `(entity, target)` pair: wild mobs have null owner (veto skipped),
owned subordinates take the owner branch (hostility predicate skipped).

**Biggest risk:** the design rule "should attack citizens wherever it attacks
players" suggests Option B (Mixin), but Option B is a Tensura-side Mixin which
adds maintenance cost. If a future Tensura update renames or restructures
`TensuraBehaviourHelper.getAnimalPreyPredicate`, the mixin breaks silently
(citizens stop being targeted). Option A (tag) is more robust against Tensura
refactors but trades off correctness vs the design rule (wounded-only). Worth
flagging the trade-off explicitly when choosing.
