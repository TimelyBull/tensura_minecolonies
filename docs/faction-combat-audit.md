# Faction combat audit — mob/boss stats & EP vs reward tier

A stats-and-EP audit of the raidable factions' garrisons, feeding the
conquest-balance work in [faction-rewards-roadmap.md](faction-rewards-roadmap.md)
(Phase 2). Goal: make each assault's DIFFICULTY line up with the faction's
REWARD TIER, so raid and diplomacy stay two comparable routes to comparable value.

> All behind `enableFactionSystem` (default OFF). Every `GARRISON_*` /
> `BETRAYAL_*` / power-multiplier value in `RivalColonies` is flagged in-code as
> a BALANCE GUESS — this audit is the first data-grounded pass over them.

Data sources: garrison pipeline in `RivalColonies.java`; boss/rank base combat
stats from each entity's `setAttributes()`; **EP (magicule + aura) from Tensura's
own `data/tensura/entity_existence/<mob>.json`** (the authoritative per-entity EP,
rolled between `min_*` and `max_*` at spawn — `max` values used below).

---

## 1. The scaling pipeline (how a garrison's difficulty is built)

Per `RivalColonies.spawnGarrison`:

```
bossEP  = boss.magicule + boss.aura           (read live from the anchor)
scale   = clamp( (bossEP / 5000)^0.5 × factionPowerMultiplier, 1.0, 6.0 )
count   = clamp( round(6 × scale), 4, 20 )     defenders spawned
statFactor = min( 4.0, 1 + (scale − 1) × 0.5 ) ×HP/ATK/MAGICULE/AURA on RANK
```

Key structural facts:
- **Only the RANK-AND-FILE get `×statFactor`** (`spawnDefender → buffDefender`).
  The **anchor boss keeps its native Tensura stats** — EXCEPT two bespoke buffs:
  Eastern Empire's Mai gets `EMPIRE_BOSS_BUFF ×3.5`, and Tempest's Slime is
  rewritten to demon-lord numbers in `buffRimuruBoss`. So a boss's raw difficulty
  = its canon stat block, untouched by the scaler.
- **`factionPowerMultiplier` is 1.0 for everyone except `eastern_empire` (1.6).**
- **Betrayal (Stage E)** multiplies the rank stats AGAIN on top: OPEN ×1.25,
  PACT ×1.6, COVENANT ×2.0 (separate modifier ids, so it stacks on `statFactor`).

---

## 2. Real boss EP (the number that drives the whole scale)

| Faction (reward tier) | Anchor boss | magicule | aura | **EP** | native HP / ATK |
|---|---|--:|--:|--:|---|
| Luminous **(III)** | Hinata Sakaguchi | 536,332 | 500,000 | **1,036,332** | 3000 / 60 (armor 50) |
| Dwargon **(II)** | Gazel Dwargo | 300,000 | 736,332 | **1,036,332** | 3000 / 80 (armor 60) |
| Eastern Empire **(II)** | Mai Furuki *(placeholder)* | 90,000 | 80,000 | **170,000** | 300 / 30 → ×3.5 buff |
| Leon **(III)** | Ifrit | 150,000 | — | **150,000** | 400 / 40 |
| Falmuth **(II)** | Folgen | 30,000 | 110,000 | **140,000** | 500 / 20 (armor 30) |
| Tempest **(II)** | Slime → "Rimuru" | 100,000* | 10,000* | **110,000*** | 5 / 0.5 → 500 / 20* |

*Tempest's numbers are the post-`buffRimuruBoss` values; the raw slime is EP 990.

**Abstract (no settlement, no garrison — diplomacy/rep only):** Milim (III),
Eurazania (II), Clayman (I). Clayman's calamity boss is the **Orc Disaster
(EP 250,000)**, which drives the lore-event raid, not a garrison.

---

## 3. Derived garrison (what each assault actually fields)

Plugging §2 EP through §1:

| Faction (tier) | scale | **count** | rank stat× | Rank roster (base HP/ATK, EP) |
|---|--:|--:|--:|---|
| Luminous (III) | 6.0 | **20** | 3.5 | 20× Falmuth Knight (50 / 10, EP 3,100) |
| Dwargon (II) | 6.0 | **20** | 3.5 | 20× Dwarf (24 / **1.5**, EP 1,200) |
| Eastern Empire (II) | 6.0† | **20** | 3.5 | 17× Knight + Shin Ryusei / Mark Lauren / Shinji (400–500 HP, EP 100–140k) |
| Leon (III) | 5.48 | **20** | 3.24 | 20× Falmuth Knight *(placeholder)* (50 / 10) |
| Falmuth (II) | 5.29 | **20** | 3.15 | 17× Knight + Kirara / Kyoya / Shogo (150–200 HP, EP 70–100k) |
| Tempest (II) | 4.69 | **20** | 2.85 | 10× Goblin (12 / ~3) + 10× Lizardman (24 / 2) |

† Mai's 170k × the 1.6 power multiplier pins scale at the 6.0 cap.

---

## 4. Findings

### A. The EP-scale system barely differentiates factions ⚠ (systemic)
`GARRISON_BASELINE_EP = 5,000` is 22×–207× smaller than real boss EP (110k–1M).
Consequences:
- **`count` is 20 for EVERY faction.** Scale never drops below 4.69, and count
  caps at 20 once scale ≥ 3.33 (EP ≈ 55k). A 9.4× spread in boss EP
  (110k → 1.04M) produces **zero** difference in defender count.
- **`statFactor` compresses to 2.85–3.5** — a 1.23× spread across that same 9.4×
  EP spread. The sqrt dampening + the 6.0 scale cap flatten everything.
- **Dead constants**: `GARRISON_BASE_COUNT` (6), `GARRISON_MIN_COUNT` (4), and
  `GARRISON_STAT_FACTOR_MAX` (4.0, unreachable — real max is 3.5) never occur in
  play. The scaler's whole low end is unused.

Net: the pipeline that's *documented* as the primary per-faction difficulty knob
does almost nothing. Real difficulty today comes from (1) the rank ROSTER's base
stats and (2) bespoke buffs — not from EP scaling.

### B. Boss difficulty is decoupled from reward tier ✗
Boss stats are canon-fixed and NOT normalized to the reward tier:
- **Gazel (Dwargon, II reward) = 1,036,332 EP, 3000 HP, ATK 80** — tied with
  Hinata as the single hardest boss in the game, but Dwargon is a **II** reward.
- **Hinata (Luminous, III)** — same 1M EP; here the difficulty matches the tier.
- **Ifrit (Leon, III) = 150k EP, 400 HP** and **Folgen (Falmuth, II) = 140k, 500 HP**
  are near-identical, yet sit in **different** reward tiers.

Boss EP tracks Tensura canon, not our reward ladder — so it cannot be the sole
difficulty driver if difficulty is meant to match reward.

### C. Rank rosters aren't difficulty-normalized ✗
Base rank power spans ~30× and doesn't track reward tier:
- **Dwargon's Dwarf: ATK 1.5, HP 24** (×3.5 → ATK ~5, HP 84) — trivial fodder.
- **Eastern Empire's lieutenants: HP 400–500, EP 100–140k** (×3.5 → HP 1400–1750).
- **Tempest's Goblins: HP 12** (×2.85 → HP 34) — a II-reward faction defended by
  the weakest mob in the mod.

### D. Eastern Empire triple-dips ✗ (hardest fight, mid reward)
EE (II reward) stacks THREE independent multipliers no one else has:
`factionPowerMultiplier 1.6` (pins count+stat to the cap) **+** `EMPIRE_BOSS_BUFF
3.5` on Mai **+** three genuinely strong lieutenants. It is comfortably the
**hardest assault**, for a **II** payout.

### E. Internal incoherence: Dwargon ✗
Brutal boss (Gazel, 1M EP / 3000 HP / resistance wall) guarded by the game's
weakest rank (dwarves, ATK 1.5). The fight is "ignore the trivial adds, then hit
a brick wall" — neither half matches a II reward, and they don't match each other.

### F. Placeholders still in the difficulty ⚠
- **Leon** rank = Falmuth Knight (fully placeholder; the fire roster was pulled).
  Its granted FLAME/HEAT resistance now sits on human knights (harmless, odd).
- **Luminous & EE** rank also lean on Falmuth Knight.
- **Mai (EE boss)** is a placeholder entity propped up by `EMPIRE_BOSS_BUFF`.

### G. Betrayal stacking is large ⚠ (verify intent)
A COVENANT betrayal applies ×2.0 on top of the ×2.85–3.5 rank `statFactor` →
Falmuth Knights at ~HP 350 / ATK 70, dwarves still trivial. Intended punishment,
but worth a sanity check that the top end isn't a spike wall.

---

## 5. Difficulty vs reward — the mismatch table

Difficulty rank is my assessment (boss stat block + rank roster + buffs); reward
tier from [faction-rewards-roadmap.md](faction-rewards-roadmap.md) §7.

| Faction | Reward tier | Assault difficulty (est.) | Verdict |
|---|---|---|---|
| Eastern Empire | **II** | **1st (hardest)** — triple-dip | ✗ over-hard for reward |
| Luminous | **III** | 2nd — 1M-EP holy wall | ✓ coherent |
| Dwargon | **II** | 3rd — 1M-EP boss, trivial rank | ✗ boss over-tier; internally split |
| Falmuth | **II** | 4th — boss + 3 hero uniques | ✓ roughly coherent |
| Leon | **III** | 5th — strong caster boss, placeholder rank | ⚠ under-hard for III |
| Tempest | **II** | **6th (easiest)** — weak boss + goblin fodder | ✗ under-hard for reward |

Coherent: Luminous, Falmuth. Mismatched: Eastern Empire, Dwargon, Tempest, Leon.

---

## 6. Recommendations (concrete, tied to the constants)

**R1 — Raise `GARRISON_BASELINE_EP` so real EP spans the scale.**
At 5,000 everything pins to count 20. At **~40,000–50,000** the sqrt curve gives a
real spread: e.g. baseline 40k → Tempest ~10 defenders, Falmuth ~11, Leon ~12,
EE/Luminous/Dwargon 20; stat× 1.33–3.05 (a 2.3× spread vs today's 1.23×). This
alone makes count a live differentiator again and revives the dead low-end
constants. Pick the baseline from the *weakest* boss you want at ~scale 1.5.

**R2 — Stop treating boss EP as the sole difficulty driver.** Canon boss EP
doesn't track the reward ladder (Gazel 1M in a II faction). Introduce an explicit
per-faction **difficulty target keyed to reward tier** (generalise the existing
`factionPowerMultiplier` into a principled `factionDifficultyTier` table:
III > II > I), and let boss EP be a *secondary* modifier around that target.

**R3 — Normalise rank rosters to a per-faction power budget.** Give Tempest
stronger fodder (its Goblins are too weak for a II reward — add Tempest Serpents
or a statFactor floor); consider whether EE's lieutenants are too strong for II.
Set a target "rank HP/ATK budget" per reward tier and pick/scale rosters to it.

**R4 — Fix Eastern Empire's triple-dip.** If EE is a II, drop `factionPowerMultiplier`
toward ~1.2 (or remove it and lean on the lieutenants), OR promote EE's reward to
match the fight. Don't keep 1.6 mult + 3.5 boss buff + elite rank all at once.

**R5 — Fix Dwargon's internal split.** Either buff the Dwarf rank to a real II
threat (garrison-only weapon / statFactor floor) or accept a boss-centric design
and document it — but don't ship a 1M-EP boss behind ATK-1.5 fodder.

**R6 — Replace placeholders where they gate difficulty.** Leon needs a real rank
roster (and then its fire-resistance grant makes sense); Mai/EE want a non-
placeholder anchor so `EMPIRE_BOSS_BUFF` can retire.

**R7 — Reconcile the ceilings.** Once R1 lands, either lower
`GARRISON_STAT_FACTOR_MAX` to the real max or raise the scale/exponent so 4.0 is
reachable; make `MIN_COUNT`/`BASE_COUNT` occur for the weakest faction.

**R8 — Sanity-check betrayal stacking (Finding G)** against the new statFactor
after R1, so COVENANT betrayals aren't a spike wall.

---

## 6b. Implemented (2026-07-10) — the tier-keyed rework

The audit's R1/R2/R4 + a targeted roster pass landed in `RivalColonies`
(compiles green; **combat NOT yet playtested** — see `docs/playtesting.md`).
User direction: prescribe difficulty from the reward tier but keep canonical
variance; **upgrade the Leon boss**; strengthen the Dwargon rank without a War
Gnome swarm. The tier ladder was then expanded to **FOUR tiers** (below).

**Difficulty is now prescribed by reward tier, nudged by boss EP:**
- New `DifficultyTier {IV(16, 2.8), III(14, 2.4), II(11, 2.0), I(7, 1.5)}` =
  (baseCount, baseStat) + `difficultyTierFor(factionId)`: Luminous/Leon/Dwargon
  → IV; Eastern Empire → III; Falmuth/Tempest → II; Clayman → I. (Milim IV /
  Eurazania III / Clayman I are ABSTRACT — reward mapping only, no garrison.)
  ⚠ Tiers are keyed to the kingdom's CANON power, not the placeholder boss mob's
  strength (Dwargon → IV: Gazel's Armed Nation is a canon great power).
- `count = clamp(round(baseCount × epF), 4, 20)`,
  `stat× = clamp(baseStat × epF, 1, 4)`, where
  `epF = clamp((bossEP/150 000)^0.5, 0.80, 1.30)`.
- Old constants removed (`GARRISON_BASELINE_EP 5 000`, `SCALE_*`, `BASE_COUNT`,
  `STAT_PER_SCALE`); `factionPowerMultiplier` (EE's 1.6× triple-dip) **deleted**.

**Resulting garrisons (vs the flat "20 @ ~3" before):**

| Faction | tier | boss EP | epF | count | stat× |
|---|---|--:|--:|--:|--:|
| Luminous | IV | 1,036,332 | 1.30 | 20 | 3.64 |
| Leon | IV | 380,000¹ | 1.30 | 20 | 3.64 |
| Dwargon | IV | 1,036,332 | 1.30 | 20 | 3.64 |
| Eastern Empire | III | 170,000 | 1.06 | 15 | 2.55 |
| Falmuth | II | 140,000 | 0.97 | 11 | 1.93 |
| Tempest | II | 110,000 | 0.86 | 9 | 1.71 |

¹ Leon's Ifrit is now buffed (below). The four tiers separate cleanly
(IV 20 @ 3.64 · III 15–18 @ 2.5–3.1 · II 9–11 @ 1.7–1.9); within a tier, EP +
rosters + boss kits still vary.

**Leon boss upgraded (`buffIfritBoss`, mirrors `buffRimuruBoss`):** Ifrit HP
×7 (400→2800), ATK ×1.5 (→60), spiritual ×1.8; magicule/aura caps set to
350,000 / 30,000 (EP ≈ 380k → top of the band). Applied in `spawnAnchorBoss`
BEFORE the garrison reads EP.

**Dwargon rank strengthened (R3/R5):** roster is now `DWARF + WAR_GNOME`, with
War Gnome added to `isUniqueGarrisonMob` so exactly ONE spawns (Gazel's earth-
magic lieutenant — HP ~1248, native earth kit). Each Dwarf is promoted by
`strengthenDwarfDefender`: HP ×2.5 / ATK ×6.0 on TOP of the tier stat× (→ ~187
HP / ~28 ATK at Tier III), plus **Body Armor**. Dwarves stay the base troop;
population is the Tier III count (≈17 dwarves + 1 War Gnome + Gazel).

**Every garrison gets an active elemental attack (2026-07-10):**
`assignFactionDefenderSkills` now grants each non-untouched defender one
Aspectual attack **Magic** + the element's low-tier **Manipulation**. Tensura
`Magic` is a `ManasSkill` with an attacking `onPressed`, so the Sentient
autocaster fires it; a bare Manipulation is element control that does nothing
offensive alone (so it's only the support skill, never the attack). Themes:
Leon/Luminous `FIRE_BALL`, Falmuth `WIND_CUTTER`, Eastern Empire/Dwargon
`STONE_SHOT`, Tempest `WATER_CUTTER`. Earth has no attack *skill* — only Magic —
which is why the old `EARTH_MANIPULATION` "attack" (dwarves + `boneGolemElementSkill`)
did nothing; both fixed to `STONE_SHOT`. ⚠ Playtest-unverified: whether the
autocaster completes magic *cast-times* on mobs.

**Rank splits into caster / warrior (2026-07-10):** the generic rank (not
bosses/lieutenants) is ~40% CASTER (attack magic + optional 2nd same-element
magic + Magic Resistance + tiered staff + 0.65× speed + best-effort retreat) /
~60% WARRIOR (Physical Resistance + Shadow Motion flash-step + tiered long sword
[Diamond I → High Magisteel IV] + no magic, native rush). Role inferred from the
held staff; details in decisions.md + playtesting.md 0c. ⚠ Caster "stay back" is
best-effort only (brain mobs + per-second steering — no true kiting AI yet).

**Still open (not in this pass):** Tempest's Goblin/Lizardman rank is still the
weakest (per-tier stat× only; no dedicated buff) — a candidate follow-up like
the dwarf treatment. Eastern Empire's `EMPIRE_BOSS_BUFF ×3.5` on Mai was left
as-is (only the garrison triple-dip was removed). Boss-stat normalisation across
same-tier factions (Finding B) and the betrayal re-check (Finding G / R8) remain
future work.

## 7. Open data notes

- EP is rolled between `min_*` and `max_*` per spawn; `max` used here. The
  min/max gap is small for the 1M bosses and modest elsewhere — it does not
  change any conclusion (every boss sits far above the 5k baseline either way).
- Goblin base ATK wasn't cleanly readable from `setAttributes` (≈2–4); HP 12 is
  the load-bearing figure and is confirmed.
- This audit covers the 6 raidable factions. The abstract three (Milim,
  Eurazania, Clayman) have no garrison; their only combat surface is
  world-rep marked-boss kills and the Clayman Orc-Disaster (EP 250k) lore raid,
  audited separately if/when that layer is balanced.
</content>
</invoke>
