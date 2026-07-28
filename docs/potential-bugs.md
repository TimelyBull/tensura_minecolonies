# Potential bugs (self-identified, unverified)

Suspected bugs surfaced by code audits / reasoning — **not yet reproduced
in-game**. Distinct from:
- `docs/user-bug-reports.md` — issues a PLAYER actually reported.
- `docs/playtesting.md` — changes that compile but need an in-game check
  (a normal verification queue, not a suspected defect).

An entry here is a "watch this — it might be broken" note. When one is
confirmed, move it to `user-bug-reports.md` (or fix it and delete the entry);
when a playtest shows it's fine, delete it with a note.

---

## 2026-07-26 — [CONFIRMED then FIXED, verify no target-thrash in playtest] MineColonies guards attacking our faction ALLY fighters

**Where:** `TensuraRaids.spawnAllySupport` / `allyTypeFor` — the PACT/COVENANT
ally fighters spawned to help defend a raid (both our own raids and, as of
0.2.2, MineColonies native raids via `handleMcRaidAllies`).

**CONFIRMED (bytecode, 2026-07-26).** The ally fighters are ordinary Tensura
mobs — `tensura:dwarf` (Dwargon), `tensura:lizardman` (Milim/Eurazania),
`tensura:goblin` (everyone else). Verified in the Tensura jar: **`goblin` and
`lizardman` are registered `MobCategory.MONSTER`** (`MonsterEntityTypes`);
`dwarf` looks like `MobCategory.CREATURE` (`HumanEntityTypes`, the exception).
MineColonies auto-lists every `MobCategory.MONSTER` type as guard-attackable
(`CompatibilityManager.discoverMobs()`), and `TargetAI.isAttackableTarget`
accepts `Enemy`/listed types — so guard towers WOULD target and kill the goblin/
lizardman allies (the default types) the moment they arrive. Latent all along
(ally support is an "UNPLAYED" seam); 0.2.2's MC-raid ally support just spawns
them right next to your towers, making it prominent.

**FIXED (2026-07-26) — `ExampleMod.onLivingChangeTarget`.** MC guards commit a
chosen target via `TargetAI.onTargetChange → Mob.setTarget` (verified in the MC
jar), which fires NeoForge's `LivingChangeTargetEvent`. A new `@SubscribeEvent`
handler cancels that change when a colony citizen is about to target an
`ALLY_TAG` mob of the SAME colony (or one whose colony can't be resolved —
conservative). Acquisition-time veto: the guard never commits a vanilla target
on the ally, so its melee/ranged goals (which read `getTarget()`) never engage
it. A DIFFERENT player's colony guards (colony-id mismatch) may still treat the
mob as wild. Chose the NeoForge event over the ManasCore `LIVING_CHANGE_TARGET`
used for Tensura-mob targeting because the attacker here is an MC citizen, not a
Tensura mob — the ManasCore event wouldn't fire. See decisions.md.

**Residual to verify in playtest (docs/playtesting.md).** The veto is
acquisition-time only. If a guard's ThreatTable ranks a nearby ally as its top
entry, it may repeatedly re-select → get vetoed → stall (target thrash) instead
of engaging real raiders. Expected to be rare (a friendly ally accrues ~no
threat vs. raiders damaging citizens), but confirm guards fight raiders normally
with allies present. If thrash shows up, additionally exclude `ALLY_TAG` mobs
from the guard's target search (mixin `TargetAI.isEntityValidTarget` /
`skipSearch`) so allies never enter the threat table.

**Status:** FIXED (compiles green); awaiting in-game confirmation that guards
leave allies alone AND still fight raiders without thrash.
