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

## 2026-07-26 — [needs playtest] MineColonies guards may attack our faction ALLY fighters

**Where:** `TensuraRaids.spawnAllySupport` / `allyTypeFor` — the PACT/COVENANT
ally fighters spawned to help defend a raid (both our own raids and, as of
0.2.2, MineColonies native raids via `handleMcRaidAllies`).

**The suspicion.** The ally fighters are ordinary Tensura mobs —
`tensura:dwarf` (Dwargon), `tensura:lizardman` (Milim/Eurazania),
`tensura:goblin` (everyone else). The ally code comments assert these are
"PASSIVE-category on purpose: MineColonies guards auto-engage MONSTER-category
types" — i.e. the assumption is that guards will leave the allies alone. But
elsewhere the project notes (see `CLAUDE.md` / the barrier spawn-prevention
work) that **Tensura registers goblins / lizardmen as `MobCategory.MONSTER`**
despite being passive-natured. If that's true for the ally entity types, then
MineColonies guard towers — which auto-list every `MobCategory.MONSTER` entity
in their attack lists (`CompatibilityManager.discoverMobs()`) — would target and
kill the allies we just sent to help.

**Why it matters more now (0.2.2).** Allies used to only appear for our own
Tensura raids; they now also spawn for MineColonies' own native raids, i.e.
right next to your guard towers during a vanilla-style siege. If guards attack
them, the "allies help you fight" feature becomes "allies get cut down by your
own guards the moment they arrive."

**Not introduced by 0.2.2** — this is pre-existing ally behavior; the 0.2.2
MC-raid ally support just makes it more visible/likely to be noticed.

**How to check.** Trigger a raid with a Pact/Covenant ally faction and a built
guard tower nearby. Watch whether guards path to and attack the arriving
"&lt;Faction&gt; Ally" mobs. Confirm both cases: our Tensura raid AND a native
MineColonies raid.

**If confirmed, options.**
- Give allies an explicit friendly/no-target marker the guard targeting
  respects (cleanest — a tag the guard attack-list or a targeting veto honors,
  mirroring the `subordinate-citizen-targeting.md` `LIVING_CHANGE_TARGET` veto
  idea).
- Or switch the ally entity types to genuinely passive/non-`MONSTER` Tensura
  mobs guards won't auto-engage.
- Or accept it and document that allies are fragile near guards.

**Status:** UNVERIFIED. Recorded from the raid-integration audit; deferred to a
playtest per the 0.2.2 conversation.
