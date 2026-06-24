# Investigation: the expanded faction model (Layer 1 — the spine)

> **STEP 5 / BATCH 2 (2026-06-21) — Leon faction skills.**
> Per the native-casting table, **Ifrit (boss) and Salamander both NATIVE-CAST
> their fire** — so NO active fire skills were granted (granting+driving them
> would duplicate native casting; rule violation). Only safe passives were
> applied:
> - **Ifrit (boss):** upgraded to boss-tier fire IMMUNITY —
>   `FLAME_ATTACK_NULLIFICATION` + `HEAT_NULLIFICATION` (supersede the
>   rank-and-file resistance) + `SELF_REGENERATION` (already). Its offensive
>   fire kit + mastery are NATIVE (not ours to scale without duplicating).
> - **Salamander (r&f):** keeps `FLAME_ATTACK_RESISTANCE` + `HEAT_RESISTANCE`
>   (lesser-spirit tier); native fire offence left alone.
> - **Bone Golem (placeholder):** unchanged — already driven by the proven
>   bone-golem autocaster at Leon mastery 0.8 (demon-lord tier). Seam stays.
> - **Garrison autocaster NOT needed/used:** Leon has no non-native fire-caster
>   (Ifrit + Salamander are native; the golem has its own autocaster). Only the
>   golem is grant+driven; everything else is native or passive. No skill is
>   double-implemented.

> **ROSTER EDIT (2026-06-21) — Mark Lauren + Shinji Tanimura + Shin Ryusei → Eastern Empire.**
> All three now belong to the **Eastern Empire** (BOSS_PROFILES NOTABLE +
> the Empire's garrison roster). Mark + Shinji were moved out of **Falmuth**;
> Shin Ryusei was already there. Membership-only — **no skills granted** to any
> of the three (their canon skills are uncertain; all three are in
> `isSkillUntouched`, and all are native casters anyway).
>
> **⚠ FUTURE CANON UPDATE (do not lose this):** these three are placed in the
> Eastern Empire to reflect their EARLIER canon allegiance (the Empire's
> summoned/affiliated otherworlders). **LATER in Tensura canon, Mark Lauren,
> Shinji Tanimura and Shin Ryusei move to / join the Jura-Tempest Federation.**
> A future update may therefore RELOCATE all three from the Eastern Empire to
> the Jura-Tempest Federation (`tempest`) to reflect their later-canon arc —
> updating both their `BOSS_PROFILES` faction and their garrison-roster
> placement. (This same trio were the old Jura anchors before step 2, so the
> later-canon move is effectively a return.)

> **CONSOLIDATION STEP 5 / BATCH 1 (2026-06-21) — roster edits + native-casting
> verification + Pass-0 resistances + the Slime boss.**
> - **Native-casting verified from the jar** (`ManasSkill`/`SkillAPI` density +
>   `getFightTasks`). NATIVE-CASTS: Ifrit, Salamander, **Slime**, Lizardman,
>   Gazel, Hinata, Mai, Shin Ryusei, Orc Lord, Orc Disaster. NO-NATIVE: Goblin,
>   Dwarf, Falmuth Knight, Bone Golem (the last already has its own autocaster).
>   Folgen UNCONFIRMED (very low signal). **Rule:** never grant+autocaster a
>   skill a mob already casts natively.
> - **Roster edits:** Dwargon garrison → `[DWARF]` (War Gnome + Beast Gnome
>   removed). Eastern Empire garrison → `[BONE_GOLEM, SHIN_RYUSEI]` (Elemental
>   Colossus removed; Shin Ryusei moved here from Jura-Tempest, and re-pointed
>   NOTABLE→EASTERN_EMPIRE in `BOSS_PROFILES`). Jura-Tempest: Tempest Serpent
>   removed; anchor changed Shin Ryusei → **SLIME** (`BOSS_PROFILES` SLIME →
>   TEMPEST KEYSTONE; only the MARKED anchor slime triggers the fan-out);
>   rank-and-file `[GOBLIN, LIZARDMAN]`.
> - **Slime boss kit:** heavy `SLIME_BOSS_BUFF ×8` (slime base stats are low)
>   + a canon kit (Predator / Water Blade / Corrosion / Self-Regeneration).
>   **NO autocaster** — the Slime casts natively, so the kit feeds its own AI
>   (per the rule). ⚠ buff is a balance guess.
> - **Pass-0 resistances** (PASSIVE — work on learn, safe on native casters):
>   per-faction in `assignFactionDefenderSkills` — Leon fire/heat (already),
>   Empire physical (already), Jura-Tempest water/wind, Dwargon physical/heat,
>   Luminous physical/darkness, Falmuth physical. An `isSkillUntouched` guard
>   skips Hinata + the five Falmuth-summoned heroes (Kirara/Kyoya/Shogo/Mark/
>   Shinji) entirely.
> - **Garrison autocaster NOT built this batch:** no NO-NATIVE mob we want
>   casting lacks a driver (golems already have one; the fodder are melee).
>   Deferred until a later batch needs it.

> **CONSOLIDATION STEP 4 (2026-06-21) — Otherworlders → Eastern Empire (re-theme IN PLACE).**
> The `otherworlders` slot was RE-THEMED, not deleted/recreated: the enum
> constant `OTHERWORLDERS → EASTERN_EMPIRE`, id `otherworlders →
> eastern_empire`, display "Eastern Empire" (Nasca Namrium Ulmeria), colour
> BLUE. **Mai Furuki is retained as the anchor** — a DELIBERATE PLACEHOLDER
> (no real Empire-leadership entity exists; swap later, same discipline as
> Leon's golems). Her `BOSS_PROFILES` importance was raised NOTABLE →
> **KEYSTONE** (a major power's head). So the importance table below is out of
> date: Mai is now KEYSTONE→EASTERN_EMPIRE, not NOTABLE→OTHERWORLDERS.
> - **Combatants = golems (placeholders):** garrison roster `[BONE_GOLEM,
>   ELEMENTAL_COLOSSUS]` — magitech soldiers + a war-construct heavy, standing
>   in for the Empire's real magitech/cyborg forces. Skills: Physical Attack
>   Resistance (armoured); Mai also gets Self-Regeneration + Body Armor.
> - **HIGH power tier:** new `RivalColonies.factionPowerMultiplier` (1.6 for
>   the Empire) scales the EP-driven garrison up (count + stat factor)
>   independent of Mai's modest EP; plus `EMPIRE_BOSS_BUFF` (×3.5) on Mai and
>   the colossus's native strength. Among the strongest factions. ⚠ All
>   balance guesses.
> - **Settlement kept:** the "Space Wars" pack (its futuristic look reads as
>   Empire magitech).
> - **Disposition/web:** 50/50 (secular — judges by power, not race; distinct
>   from the anti-monster Holy bloc), swing **0.5 → 1.0** (a real engaged
>   power, no longer aloof), threshold **15 → 8**, outbound-only. **No web
>   edges — independent, explicitly NOT allied with Luminous/Falmuth.** Envoy
>   EP threshold 8000 → 11000. Deal catalog (`ow_*`) + skill reward carried
>   over (keys kept, map key renamed); conquest levy re-themed to imperial
>   soldiers.
> - **Save migration:** `otherworlders → eastern_empire` added to the load
>   folds (`WorldReputationSavedData` + `DiplomacySavedData`) — same rules as
>   Carrion→Eurazania. Clean move (new key never pre-exists).

> **CONSOLIDATION STEP 3 (2026-06-21) — Shizu DEPRECATED (soft-retire); content → Leon.**
> Shizu was soft-retired, NOT hard-deleted. The `SHIZU` enum value + `shizu`
> id are KEPT so existing saves load (her standing/relations stay valid but
> **inert**); hard removal is deferred to a future version once old saves age
> out.
>
> - **The "is active?" gate:** `BossFaction.DEPRECATED_IDS = {"shizu"}` +
>   `isActive()` / `isActiveId(String)`. Every active system skips deprecated
>   factions: diplomacy offers/envoys/mending/alliance-buffs/caravan + the UI
>   faction list (`DiplomacyManager` `values()` loops), raid ally-support
>   (`TensuraRaids`), and notoriety (`WorldReputationManager`, excluded from
>   the average). Settlement gen + garrison + `/rivalcolony spawn` are gated
>   for free by REMOVING shizu from `ANCHORS`/`PACKS` (the picker reads
>   `PACKS.keySet()`; `isPhysical`/`isTownFaction` become false). `tickDiscovery`
>   also skips inactive-faction settlements so an old-save Pagoda can't be
>   warred on.
> - **Pagoda retired:** `ANCHORS`/`PACKS`/garrison `case "shizu"` removed. The
>   `BOSS_PROFILES` SHIZU-entity mapping + her `FactionProfile` + deal/envoy/
>   conquest entries are KEPT but never reached (inert; valid `byId`). No web
>   edges (aloof) → `validateWeb()` unaffected.
> - **No save migration:** shizu keys remain valid; nothing reads them
>   actively. Standing sits inert until eventual hard removal.
> - **Leon** (keeps his Caledonia settlement, anchor `IFRIT`): rank-and-file
>   roster is now `[BONE_GOLEM (PLACEHOLDER), SALAMANDER]` (replacing the old
>   Arch/Greater/Lesser Daemon roster — golem is a flagged seam, NOT a flame
>   statement). The boss is the high-EP Ifrit (the EP-driven garrison scaler
>   makes Leon among the strongest — there is no separate per-faction power
>   multiplier; ⚠ balance seam). Canon skills granted at spawn
>   (`assignFactionDefenderSkills`): defenders get Flame-Attack + Heat
>   Resistance; the Ifrit boss also gets Self-Regeneration.

> **CONSOLIDATION STEP 2 (2026-06-21) — Carrion → Eurazania + heroes → Falmuth.**
> (A) The `carrion` faction was renamed to **Eurazania** (the Beast Kingdom):
> id/storage key `carrion` → `eurazania`, display "Carrion" → "Eurazania",
> enum constant `CARRION` → `EURAZANIA`. It stays BODILESS (diplomacy/rep only;
> no boss/settlement/garrison) — pure rename, same swing 1.5× / threshold 8 /
> enemy {clayman}. Old-save `carrion` standing/offense/relations migrate to
> `eurazania` on load (`WorldReputationSavedData` + `DiplomacySavedData`,
> `foldRename`). Deal ids keep their `ca_*` / `cov_carrion` prefix (opaque,
> not faction ids). Wherever the tables below say `CARRION`, read `EURAZANIA`.
>
> (B) The four Falmuth-summoned Otherworlder heroes — **Shogo, Mark, Shinji,
> Kirara** — now belong to **FALMUTH** (BOSS_PROFILES + garrison roster). Note
> the importance table below lists Shinji + Mark under JURA_ALLIANCE/Tempest;
> they have MOVED to Falmuth, so the **Jura-Tempest Federation now has only
> Shin Ryusei** as its NOTABLE anchor. Kyoya was already Falmuth. The
> **Otherworlders faction is NOT deleted and Mai Furuki is NOT moved** — Mai
> stays as its placeholder boss (its rank-and-file roster is now empty;
> `spawnGarrison` no-ops gracefully) pending the Eastern Empire step.

> **DISPLAY-NAME RENAME (2026-06-21) — `clayman` faction → "Moderate
> Harlequin Alliance".** Only the faction's human-readable DISPLAY NAME
> changed (the enum's display-name field in `BossFaction`). The id /
> storage key `clayman`, the enum constant `CLAYMAN`, the relationship-web
> edges, the offense ledger, diplomacy state, and the wired Orc Disaster
> lore event are ALL UNCHANGED — zero save migration. Wherever the tables
> below use the **code symbol** `CLAYMAN` or the **id** `clayman`, they
> remain accurate (those did not change). The Demon Lord **character**
> "Clayman" (who personally schemes and marches the Orc Disaster) keeps his
> name — only the faction/organization is now the Moderate Harlequin
> Alliance (canon: Clayman is a member of the Moderate Clown Troupe /
> Harlequin Alliance, into which "the Clowns" were folded for v1).

**FACTION MERGE (2026-06-21) — Tempest + Jura Alliance → "Jura-Tempest Federation".** The first of the faction-consolidation passes (see the
audit in this session). The two were the same canon power — the Jura
Forest Grand Alliance became the Jura Tempest Federation — so they are
now ONE faction. As-built:
- **Surviving id:** `tempest` (storage/command key unchanged). **Display
  name:** "Jura-Tempest Federation". The `JURA_ALLIANCE` enum constant and
  the `jura_alliance` id are GONE.
- **Body (from Jura):** `tempest` flips abstract → PHYSICAL — it takes
  Jura's anchor (Shin Ryusei), pack ("Jungle Treehouse") and garrison
  (Tempest Serpent / Goblin / Lizardman / Slime) in `RivalColonies`, and
  its three NOTABLE boss anchors (Shin Ryusei / Shinji / Mark Lauren) now
  point at `TEMPEST` in `BOSS_PROFILES`. So the disposition table and #2
  importance table below are out of date for the abstract/anchor columns:
  TEMPEST is now physical with three NOTABLE anchors; JURA_ALLIANCE no
  longer exists.
- **Diplomacy (both catalogs):** the merged `tempest` deal table is the
  union of the `tp_*` and `ja_*` deals, MINUS `ja_enlightened` (Happiness
  7.0 — a duplicate of `tp_content`). One Covenant + one training deal per
  faction, so `cov_jura` ("The Grand Academy") and `cov_train_jura`
  ("Sage Training") were dropped; Tempest's `cov_tempest` + Warrior
  Training survive. Both capstone skill rewards (`tp_joyful`,
  `ja_sages`) survive (keyed by deal id). Conquest levy = Jura's sages
  profile. Caravan good + alliance buff = Tempest's.
- **Relationship web (deduped):** tempest allies {dwargon}, enemies
  {clayman}; the `jura_alliance` edges were removed from dwargon's allies
  and clayman's enemies (tempest already on both). `validateWeb()` stays
  symmetric. Disposition unchanged (both were 50/55 → 50/55).
- **Save migration:** `WorldReputationSavedData.load` folds
  `jura_alliance` standing (keep larger magnitude) / offense (max) /
  diplomacy-closed (union) into `tempest`. `DiplomacySavedData.load`
  folds relations state (higher tier) / active deal (keep tempest's) /
  offers + seen (union) / timers (max) / envoy-away value into `tempest`.
  `Settlement.load` renames the faction id. Edge case: an
  already-loaded entity carrying a `FactionMarkTag(jura_alliance)` won't
  crash (consumers null-guard) but won't ripple as tempest — rare, not
  migratable from SavedData.

The original v1 record follows (tempest/jura split as designed; read it
with the merge above in mind).

---

**Status: v1 BUILT (2026-06-11)** — all 7 scope items below landed, all
[CONFIRM] items user-approved as specced. As-built notes:
- Code: `FactionProfile` (dispositions/web/swing/thresholds + the
  startup `validateWeb()` symmetry check), `BossProfile` (+ Importance),
  `FactionMarkTag` (+ `Attachments.FACTION_MARK`),
  `WorldReputationManager` reworked (live base + earned delta,
  `isMajinSide` classifier, `applyMarkedBossKill/Attack` fan-outs,
  offense ledger + `isProvoked`, `markBoss`), offense persistence in
  `WorldReputationSavedData`, the shipped
  `data/tensura_minecolonies/tags/manascore_race/races/human_side.json`,
  the reworked ExampleMod mover hooks (marked-only), the expanded
  `/worldrep` (base/earned/effective/offense/provoked + `mark` debug).
- **Config gates:** `factionSystemEnabled` (default true) — the whole
  faction layer goes dormant at its entry points (manager reads return
  flat NEUTRAL, writes no-op, mover hooks skip, /worldrep reports
  disabled); colony-level systems below are untouched and boss kills
  behave pre-faction-system (colony +10 + envoy unlocks, zero faction
  effect). `enableAssassins` (pre-existing, default true) is the
  independent assassin kill-switch.
- **Divergence:** the #2 worked example listed Carrion at +7.2 on a
  marked Orc Disaster kill — the confirmed TABLE gives Carrion the same
  1.5× swing as Milim, so it actually lands **+10.8**. Table wins.
- **Clowns = Clayman in v1** (user-confirmed). A separate `clowns`
  faction later = one `PROFILES` entry + mirrored web edges (+ enum
  value or a pure string-id addon faction).
- The flat `BOSS_ATTACKED/BOSS_KILLED` reasons retired; replaced by
  `MARKED_BOSS_ATTACKED/MARKED_BOSS_KILLED/MARKED_BOSS_RIPPLE`.

Original investigation follows (the design of record).

---

**Investigated:** 2026-06-11, no code written at that stage.

**What this expands:** the world-reputation v1 backbone
(docs/world-reputation.md) — flat per-faction standing, flat
one-directional movers (boss attack −3 / kill −20) — into the richer
model BOTH hostility events (Orc Disaster next) AND future diplomacy
read: race-modulated disposition profiles, a faction relationship web,
importance-weighted TWO-SIDED movers, marked bosses, provocation, and
soft influence (no hard rep gates).

**Approved lore data (user-confirmed, baked in):**
- Holy bloc = Luminous + Falmuth (+ Hinata, who anchors Luminous) —
  internally allied, anti-monster.
- Schemers = Clayman + the Clowns (oppose the rising forest powers).
- Neutral / diplomacy-open = Dwargon + Tempest/Jura Alliance —
  hostile-to-violence.
- Swingable-toward-ally = Milim + Carrion. Leon / Otherworlders =
  neutral/aloof.
- The player's COLONY is its own thing — NOT Tempest. Tempest/Jura is
  an external faction.
- Killing a faction's boss lowers that faction AND raises its enemies
  simultaneously, scaled by the boss's lore importance; minion kills
  stay SMALL (killing mobs is core Minecraft).
- Holy bloc starts wary→HOSTILE, much more hostile to a MAJIN player.

---

## #1 PIVOTAL — Disposition profiles: a LIVE race-computed BASE + a stored EARNED DELTA

**The question:** does a new player START at the disposition standing
(seeded into storage), or is disposition a separate layer?

**Recommendation: a separate layer — `effective = clamp(base + delta)`.**
- **base** = the faction's disposition toward the player's CURRENT
  race-side (majin vs human), computed LIVE on read — never stored.
- **delta** = what the stored standing becomes: the EARNED component,
  default 0, the only thing movers write (through the sole door,
  signature unchanged).
- Every consumer (tiers, isAtLeast/isBelow, notoriety, event
  modulation, /worldrep) reads EFFECTIVE.

**Why live-base beats seed-once (the deciding argument):** race change
is core Tensura progression. A player can start human, walk the demon
lord path, and BECOME majin mid-game (or use a Race Reset Scroll). A
seeded-at-first-contact standing goes stale the moment that happens; a
live base shifts the Holy bloc's posture the day you change — "they
sense what you have become" — with zero bookkeeping, no migration
hooks, no re-seed events. It also keeps storage semantics clean: an
absent key means "no history with this faction," not "we already
decided what they think of you."

**Storage impact:** the stored number is reinterpreted from absolute
(default 50) to delta (default 0). The save format itself (string-keyed
`player → faction id → double`) is unchanged; existing dev-world values
would read ~50 too high. Acceptable at this stage (the backbone is days
old, no shipped worlds); if desired, a one-time `-50` migration on load
is three lines.

### How majin-vs-human is read (VERIFIED against the jars)

The chain: `RaceAPI.getRaceFrom(player).getRace()` →
`Optional<ManasRaceInstance>` → `instance.getRace()` (a
`TensuraRace`) → **`getAlignment()`** returning
`Alignment {DEFAULT, MAJIN, HOLY, CHAOS}`. Plus
`ManasRaceInstance.is(TagKey<ManasRace>)` for race-registry tags
(`TensuraRaceTags.HUMAN_LIKE`, `DAEMON`, `DIVINE`, ...).

**Two verified wrinkles — alignment alone is NOT enough:**
1. `Alignment.MAJIN` is only overridden by some races (slime, the
   daemon chain, ...). **Goblin and Ogre return DEFAULT** — yet the
   Holy bloc must count them as monsters.
2. Tensura's `human_like` tag lists ONLY the five base races (human,
   dwarf, elf, merfolk, beastfolk) — **evolutions (Enlightened Human,
   Human Saint, Divine Human, Dragonewt, ...) are untagged**, so a
   late-game human-side player would misclassify.

**Recommended classifier `isMajinSide(player)` (first match wins):**
1. No race instance present → **human-side** (an unawakened player is
   a plain human).
2. `getAlignment() == MAJIN || CHAOS` → **majin-side**;
   `== HOLY` → **human-side**.
3. `instance.is(TensuraRaceTags.HUMAN_LIKE)` → **human-side**.
4. `instance.is(OUR_HUMAN_SIDE_TAG)` → **human-side** — a mod-shipped
   datapack tag `data/tensura_minecolonies/tags/manascore_race/races/
   human_side.json` listing the human-family EVOLUTIONS Tensura's own
   tag misses (enlightened/saint/divine human, the dwarf/elf/merfolk/
   beastfolk chains). Same pattern as the `barrier_blocked` entity tag
   — and the addon door: addon races join the side by joining the tag,
   no code.
5. Otherwise → **majin-side** (goblin, ogre/oni, lizardman, harpy,
   giant, vampire, slime, daemon — i.e. monsters default to monster).

Cheap enough to compute on read (a capability lookup + tag checks);
cache per-tick if a profiler ever complains.

### The disposition table (base by side; all named constants, [CONFIRM] the numbers)

| Faction | Human base | Majin base | Tier it lands in | Character |
|---|---|---|---|---|
| LUMINOUS | 30 | 10 | WARY / **HOSTILE** | Holy bloc core — hates monsters |
| FALMUTH | 35 | 15 | WARY / **HOSTILE** | Holy bloc muscle |
| CLAYMAN | 45 | 45 | NEUTRAL (cool) | neutral-but-scheming |
| DWARGON | 50 | 50 | NEUTRAL | diplomacy-open, violence-sensitive |
| TEMPEST | 50 | 55 | NEUTRAL | monster nation — slight majin warmth |
| JURA_ALLIANCE | 50 | 55 | NEUTRAL | same bloc as Tempest |
| MILIM | 50 | 50 | NEUTRAL | swingable (see multiplier) |
| CARRION | 50 | 50 | NEUTRAL | swingable |
| LEON | 50 | 50 | NEUTRAL | aloof (dampened movers) |
| OTHERWORLDERS | 50 | 50 | NEUTRAL | aloof |
| SHIZU | 50 | 50 | NEUTRAL | kind to all |

Two per-faction sensitivity knobs round out the profile:
- **Swing multiplier** (MILIM, CARRION = 1.5×; LEON, OTHERWORLDERS =
  0.5×; everyone else 1.0×) — scales every mover hitting that faction.
  "Swingable-toward-ally" and "aloof" become one number.
- **Provocation threshold** — see #4.

### The profile record (the data shape)

```java
record FactionProfile(
    String factionId,
    double baseHuman, double baseMajin,
    Set<String> allies, Set<String> enemies,   // the web (#2)
    double swingMultiplier,
    double provocationThreshold)               // offense points (#4)
// static Map<String, FactionProfile> PROFILES — string-keyed like the
// storage, so an addon faction = one map entry + (optionally) showing
// up in others' ally/enemy sets. Unknown ids in saves still round-trip.
```

The CLOWNS note: the approved web names "Clayman + Clowns," but the
enum has no CLOWNS faction. **Fold the Clowns into CLAYMAN for v1**
(they are his instruments in this arc); the string-keyed profile map
means a `clowns` faction later is additive, not a rework.

## #2 PIVOTAL — Two-sided weighted movers: a relationship web + per-boss importance

**The data, two maps (both string-keyed, addon-extensible):**

**(a) The relationship web** — lives in the `FactionProfile` ally/enemy
sets above, populated from the approved lore:

| Faction | Allies | Enemies |
|---|---|---|
| LUMINOUS | FALMUTH | CLAYMAN |
| FALMUTH | LUMINOUS | CLAYMAN |
| CLAYMAN | — (Clowns folded in) | LUMINOUS, FALMUTH, TEMPEST, JURA_ALLIANCE, MILIM, CARRION |
| TEMPEST | JURA_ALLIANCE, DWARGON | CLAYMAN |
| JURA_ALLIANCE | TEMPEST, DWARGON | CLAYMAN |
| DWARGON | TEMPEST, JURA_ALLIANCE | — |
| MILIM | — | CLAYMAN |
| CARRION | — | CLAYMAN |
| LEON / OTHERWORLDERS / SHIZU | — | — (aloof) |

Symmetry is a VALIDATION rule, not a storage assumption: a static
sanity check asserts every enemy/ally edge appears on both ends (so an
addon adding one side gets a log warning), but the sets are stored
per-faction so addons merge naturally.

**(b) Boss profiles** — `factionOf` (the bare `EntityType → faction`
map in WorldReputationManager) grows into:

```java
record BossProfile(String factionId, BossImportance importance)
enum BossImportance { KEYSTONE(1.0), MAJOR(0.6), NOTABLE(0.3), MINOR(0.1) }
// Map<EntityType<?>, BossProfile> BOSS_PROFILES — same lazy-init spot.
```

| Importance | Weight | Who (current cast) |
|---|---|---|
| KEYSTONE | 1.0 | Hinata→LUMINOUS, Gazel→DWARGON, Shizu→SHIZU |
| MAJOR | 0.6 | Charybdis→CLAYMAN, Orc Disaster→CLAYMAN, Ifrit→LEON |
| NOTABLE | 0.3 | Kirara/Kyoya/Shogo→FALMUTH, Shin Ryusei/Shinji/Mark→JURA_ALLIANCE, Mai→OTHERWORLDERS |
| MINOR | 0.1 | Falmuth Knight, Folgen→FALMUTH; Orc Lord→CLAYMAN |

**The fan-out (replaces the flat −3/−20):** one entry point on the
manager applies everything atomically:

```
applyMarkedBossKill(player, boss):              // base constants, all named
  F = boss faction, w = importance, m = each target's swingMultiplier
  F          : −KILL_BASE × w × m                (KILL_BASE = 30)
  allies(F)  : −KILL_BASE × w × ALLY_LOSS × m    (ALLY_LOSS  = 0.5)
  enemies(F) : +KILL_BASE × w × ENEMY_GAIN × m   (ENEMY_GAIN = 0.4)
  offense[F] += OFFENSE_KILL × w                 (OFFENSE_KILL = 10)
applyMarkedBossAttack(player, boss):            // deduped, own faction only
  F: −ATTACK_BASE × w;  offense[F] += OFFENSE_ATTACK × w   (3 / 1)
```

Attacks deliberately do NOT ripple — only a kill is a statement the
world reacts to; attack spam staying single-target also keeps the
existing 100-tick dedupe sufficient.

**Worked examples (the approved scenarios reproduce):**
- Kill marked **Hinata** (KEYSTONE): Luminous **−30** (down hard),
  Falmuth **−15**, Clayman **+12** (up slightly). ✓
- Kill marked **Charybdis** (MAJOR): Clayman **−18**; Milim/Tempest/
  Jura/Carrion **+7.2** each (Milim's 1.5× → **+10.8**). ✓
- Kill marked **Orc Disaster** (MAJOR): Clayman **−18**; Milim (+10.8
  w/ swing), Carrion/Tempest/Jura **+7.2**. ✓ (Note: the Orc Disaster
  lore event ALSO applies its forced-HOSTILE clamp + diplomacy-closed
  flag after this mover — lore-events.md #4 unchanged; the −20 it cited
  becomes this weighted −18.)
- Kill marked **Falmuth Knight** (MINOR): Falmuth **−3**, Luminous
  **−1.5**, Clayman **+1.2** — minion kills stay small. ✓

Each leg goes through `modifyStanding` individually (one log line per
faction per reason — the existing audit discipline), under two new
reasons: `MARKED_BOSS_KILLED` / `MARKED_BOSS_RIPPLE` (+ `..._ATTACKED`).

## #3 Marked vs. unmarked bosses — an attachment + a title; unmarked kills are FREE

Killing bosses is core progression; only a kill the FACTION ARRANGED
should anger the faction. **A wild or player-summoned Hinata/Charybdis/
Orc Disaster kill carries ZERO faction consequences** under this model
— which means the CURRENT flat movers' "any kill of an anchored entity"
behavior is replaced, not just reweighted.

**The mark — the established attachment pattern (EnvoyTag/RaidTag/
AssassinTag):**

```java
record FactionMarkTag(String factionId, String sourceEventId)
// Attachments.FACTION_MARK — NBT-serialized, survives chunk reload.
```

- **Who sets it:** the spawner. The Orc Disaster lore event marks its
  lead boss (and can mark the horde MINOR if desired); future faction
  raids/patrols mark their spawns; an `/worldrep mark` debug command
  covers testing. Natural spawns and player summons never get it.
- **The visible title is the honest signal:** marking also sets the
  custom name — `"Clayman's Orc Disaster"` in the faction's color
  (faction display name + color already exist on the enum). The player
  can SEE which kills are consequential before swinging. (The lore
  event's own bespoke name, "Geld, the Orc Disaster — Clayman's
  Calamity", satisfies this; the default title is for non-lore marks.)
- **The mover check:** the existing death/damage hooks gain one early
  line — `if (!entity.hasData(FACTION_MARK)) return;` then read the
  faction FROM THE TAG (not from `factionOf`). That makes the tag the
  authority: an addon can mark ANY entity for ANY faction id without
  the entity being in our boss map at all — `BOSS_PROFILES` supplies
  the default importance, and the tag could carry an importance
  override later if needed.

**What deliberately does NOT change:** wild Orc Disaster / Ifrit kills
keep their existing non-faction effects — colony rep +10 `BOSS_KILL`
(the colony cheers regardless of who arranged the fight) and the Stage
J envoy unlock flags (orc/lizardman conditions). Those are colony and
progression systems; only WORLD-faction consequences go marked-only.

## #4 Provocation — derived from the offense ledger, thresholded per faction

**A faction is "provoked" when your offense against it crosses ITS
threshold** — no new stored state:

```
isProvoked(player, F) = offense(player, F) >= PROFILES[F].provocationThreshold
```

The offense ledger (lore-events.md #3) already has exactly the right
shape — a no-decay record of ACTS, written by the marked-boss movers
(now weighted: +10×w kill, +1×w attack), consumed/reset by events. With
marked-only movers, minion-spam can't provoke anyone (a MINOR marked
kill writes just 1 offense; UNMARKED kills write zero), which delivers
the "killing mobs is core Minecraft" requirement structurally.

**Per-faction thresholds (the profile field; [CONFIRM]):**

| Faction | Threshold | Reading |
|---|---|---|
| CLAYMAN | 3 | schemers move on the first real slight |
| LUMINOUS / FALMUTH | 5 | quick to crusade |
| MILIM / CARRION | 8 | takes a real insult |
| DWARGON / TEMPEST / JURA | 10 | patient, diplomacy-first — but "hostile-to-violence" is covered because ONLY violence writes offense, so the patient factions are provoked exclusively by sustained violence |
| LEON / OTHERWORLDERS / SHIZU | 15 | aloof |

What counts as a provoking act in v1: attacking/killing MARKED bosses
(the only offense writers that exist). Territory encroachment stays
deferred to the rival-colony arc — it adds WRITERS to the same ledger,
not a new mechanism.

## #5 Soft influence — standing modulates, provocation arms; NO rep floor (confirmed supported)

The model supports this cleanly because the two carriers are already
separated: **offense (what you did) ARMS an event; standing (how they
feel) scales its likelihood and intensity.** Recommended replacement
for the Orc Disaster trigger condition (lore-events.md #2 — supersedes
its hard `isBelow WARY` gate):

```
armed  = offense(player, CLAYMAN) >= threshold (3)        // provocation
hostility01 = clamp01((50 − effectiveStanding) / 50)      // 0 at neutral+, 1 at 0
chance/night = EVENT_BASE_CHANCE (10%) + 30% × hostility01
budget coefficient: ... + 0.15 × hostility01 (replaces the binary
                    "+0.15 if HOSTILE tier" — continuous, same ceiling)
```

A NEUTRAL-standing player who commits a grave act still gets the march
(at low odds, modest intensity); a HOSTILE-standing player who never
touched a marked boss gets nothing. Disposition feeds in naturally: a
majin player's lower BASE with the Holy bloc means future Holy-bloc
events run hotter sooner — race shapes the world's reaction without a
single explicit race check in any event. No tier comparison anywhere in
a trigger; `isAtLeast/isBelow` remain for FLAVOR selection (envoy tone,
message variants), not gates.

## #6 Integration — what changes vs. what stays

**CHANGES:**
- **Stored standing → EARNED DELTA** (default 0); effective = clamp
  (liveBase + delta). `getStanding` returns effective; new `getBase` /
  `getEarned` accessors for the /worldrep breakdown.
- **`factionOf` → `BOSS_PROFILES`** (faction + importance). The flat
  −3/−20 movers in ExampleMod become calls to the manager's new
  `applyMarkedBossAttack/Kill` fan-outs — and gain the mark check, so
  unmarked kills go consequence-free (a deliberate behavior change).
- **`WorldRepReason`** grows MARKED_BOSS_KILLED / MARKED_BOSS_RIPPLE /
  MARKED_BOSS_ATTACKED (BOSS_ATTACKED/BOSS_KILLED retire with the flat
  movers).
- **lore-events.md adjustments:** trigger loses the hard tier gate
  (#5 above); the −20 ripple becomes the weighted −18 + enemy upticks;
  the offense writers become importance-weighted. Everything else
  (engine reuse, lead boss, ledger consumption, diplomacy-closed flag)
  stands.
- **/worldrep readout:** per faction shows `base (race side) + earned =
  effective (tier)`, plus offense and provoked-or-not — the tuning
  surface for everything above.

**STAYS:**
- `WorldReputationManager` as the SOLE DOOR — the fan-out entry points
  live on it and call the same single `modifyStanding` mutator per leg
  (clamp on the DELTA range ±50 so effective stays in [0,100]; log
  line per write unchanged).
- `WorldReputationSavedData` format: string-keyed maps, unknown ids
  round-trip, offense ledger + diplomacyClosed as designed.
- `BossFaction` enum + `FactionTier` bands; tiers now derive from
  effective standing.
- The colony reputation system — entirely untouched (the colony is its
  own thing, per the approved data).
- **Notoriety:** formula structurally unchanged; its hostility
  component now reads EFFECTIVE standing, so a majin player carries
  some base notoriety from the Holy bloc's disposition — lore-correct
  ("the world fears what you are" already includes the EP term; now the
  hostility term agrees). No consumer exists yet, so this is free to
  revisit.

**The addon door, restated:** every new structure is string-keyed or
data-shaped — `FactionProfile` map (faction = one entry), ally/enemy
sets (edges merge), `BOSS_PROFILES` (or a FactionMarkTag on any entity,
bypassing the map), the human_side datapack TAG (races join by tag).
v1 keeps them as in-code static maps like the existing patterns; a
future data-driven JSON overlay can replace the literals without
touching the spine.

---

## Recommended bounded v1 SCOPE (build later from this)

1. `isMajinSide(player)` classifier (alignment → HUMAN_LIKE →
   our `human_side` race tag → default majin) + the shipped tag JSON.
2. `FactionProfile` record + `PROFILES` map (dispositions, web,
   swing multipliers, provocation thresholds — the two tables above).
3. Stored-standing reinterpretation to earned-delta; `getStanding` =
   clamp(base + delta); `getBase/getEarned` accessors.
4. `BossProfile`/`BossImportance` replacing `factionOf`;
   `FactionMarkTag` attachment + title-on-mark + `/worldrep mark`.
5. Manager fan-outs `applyMarkedBossAttack/Kill` (KILL_BASE 30,
   ATTACK_BASE 3, ALLY_LOSS 0.5, ENEMY_GAIN 0.4, OFFENSE 10/1 × w —
   all named constants); ExampleMod hooks gain the mark check.
6. Offense ledger + `isProvoked` (thresholds from the profile).
7. /worldrep expanded readout (base/earned/effective/offense/provoked).

PIVOTAL locks: (#1) disposition = a LIVE base computed from the
player's CURRENT race side (verified read: RaceAPI → ManasRaceInstance
→ TensuraRace.getAlignment + race tags, with a mod-shipped human_side
tag patching Tensura's incomplete HUMAN_LIKE), layered under a stored
earned delta — race changes shift the world's posture automatically;
(#2) two-sided movers = ally/enemy sets on a string-keyed
FactionProfile map × a per-boss importance weight, fanned out atomically
by the sole-door manager, firing ONLY for FactionMarkTag-marked bosses.

[CONFIRM] items when building: the disposition table numbers; the
mover constants (30/3, 0.5/0.4); the importance assignments; the
provocation thresholds; and that retiring faction consequences for
UNMARKED boss kills (colony +10 and envoy unlocks unaffected) matches
intent.
