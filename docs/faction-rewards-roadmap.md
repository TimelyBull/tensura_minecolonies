# Faction rewards review — roadmap

A focused review pass over **what the player gets from each faction**, on the
two axes that matter:

1. **Raid / conquest rewards** — what you win by assaulting and conquering
   that faction's settlement (the `ConquestPayoff` path).
2. **Diplomacy rewards** — what you gain by befriending that faction (deals,
   alliance perks, the Covenant capstone).

Goal: every faction is reviewed on BOTH axes, gaps are closed, and the values
are deliberate rather than placeholder. This is a **balance + completeness**
pass — the machinery already exists; this decides what each faction should
actually award and fills the holes.

> All of this is behind `enableFactionSystem` (default OFF). It reaches a
> player only once the faction system is turned on.

---

## 1. Where each reward lives (source of truth)

**Raid / conquest** (fires from `RivalColonies.resolveWin` → `ConquestPayoff`):
- **Citizen levy** — `ConquestPayoff.PROFILES` (per faction: count + a themed
  skill pair + a role label). Falls back to `DEFAULT_PROFILE`.
- **Boss's Covenant skill (granted by force)** — `DealSpec.covenantSkillFor`,
  which scans the faction's deals for one whose id is in `DealSpec.SKILL_REWARDS`.
- **Loot chest(s) at the ruin** — `DealSpec.factionRewardPool` (all the reward
  `ItemStack`s from that faction's `FACTION_DEALS`, sampled into 1–2 chests).

**Diplomacy:**
- **Quest / deal catalog** — `DealSpec.FACTION_DEALS` (what the faction asks
  for and pays out, tier-gated).
- **Covenant capstone deal** — the `cov_*` entries in `DealSpec` (the milestone
  deal that forges the Covenant) + its `SKILL_REWARDS` skill.
- **Daily caravan goods** — `DiplomacyManager.FACTION_GOODS` (PACT perk).
- **Alliance buff** — `DiplomacyManager.ALLIANCE_BUFFS` (PACT perk, a MobEffect).
- **Rite of Atonement (mending)** — `DealSpec.MENDING_DEALS` (built for every
  faction automatically).

---

## 2. The factions

From `BossFaction`. "Raidable" = has a physical settlement
(`RivalColonies.isPhysical`).

| Faction (display) | id | Raidable? | Notes |
|---|---|---|---|
| Jura-Tempest Federation | `tempest` | ✅ town | forest town |
| Dwargon | `dwargon` | ✅ village | uses Tensura dwarven villages |
| Luminous | `luminous` | ✅ town | |
| Falmuth | `falmuth` | ✅ town | |
| Leon | `leon` | ✅ town | |
| Eastern Empire | `eastern_empire` | ✅ town | ex-"Otherworlders" |
| Moderate Harlequin Alliance | `clayman` | ❌ abstract | diplomacy/rep only |
| Milim | `milim` | ❌ abstract | diplomacy/rep only |
| Eurazania | `eurazania` | ❌ bodiless | ex-"Carrion"; diplomacy/rep only |
| Shizu | `shizu` | ❌ DEPRECATED | soft-retired; still carries reward data |

So: **6 factions need BOTH reviews** (the raidable ones); **3 need only the
diplomacy review** (clayman, milim, eurazania); **Shizu needs a keep-or-purge
decision**.

---

## 3. Current status matrix (what exists today)

### Raid / conquest (raidable factions only)

| Faction | Citizen levy | Covenant skill (grant) | Loot pool |
|---|---|---|---|
| dwargon | ✅ 15 — Str/Sta "miners" | ✅ `dw_grand_forge` → Body Armor | ✅ rich (magisteel) |
| falmuth | ✅ 16 — Sta/Str "soldiers" | ✅ `fa_fortress` → Physical-Atk Resist | ✅ |
| luminous | ✅ 12 — Mana/Knw "clergy" | ✅ `lu_devout` → Holy-Atk Resist | ✅ |
| leon | ✅ 12 — Str/Mana "retainers" | ✅ `le_flamebearers` → Flame-Atk Resist | ✅ |
| eastern_empire | ✅ 14 — Str/Sta "imperial soldiers" | ✅ `ow_specialists` → Eye of Truth | ✅ |
| tempest | ✅ 18 — Knw/Int "sages" | ⚠️ TWO mapped (`tp_joyful`, `ja_sages`) — first wins | ✅ |

Conquest rewards are **structurally complete** for all 6 — the review here is
about **balance/appropriateness**, not missing pieces (plus the tempest
ambiguity below).

### Diplomacy (all factions)

| Faction | Deal catalog | Covenant capstone deal | Caravan goods | Alliance buff | Mending |
|---|---|---|---|---|---|
| dwargon | ✅ | ✅ `cov_dwargon` | ✅ | ✅ Haste | ✅ |
| tempest | ✅ | ✅ `cov_tempest` (+ `cov_train_tempest`) | ✅ | ✅ Regen | ✅ |
| luminous | ✅ | ✅ `cov_luminous` | ✅ | ✅ Resistance | ✅ |
| falmuth | ✅ | ✅ `cov_falmuth` | ✅ | ✅ Strength | ✅ |
| milim | ✅ | ✅ `cov_milim` | ✅ | ✅ Strength | ✅ |
| eurazania | ✅ | ✅ `cov_carrion` | ✅ | ✅ Speed | ✅ |
| clayman | ✅ | ✅ `cov_clayman` | ❌ **missing** | ❌ **missing** | ✅ |
| **leon** | ✅ | ❌ **no `cov_` deal** | ❌ **missing** | ❌ **missing** | ✅ |
| **eastern_empire** | ✅ | ❌ **no `cov_` deal** | ❌ **missing** | ❌ **missing** | ✅ |
| shizu (dep) | ✅ | ❌ | ❌ | ❌ | ✅ |

---

## 4. Confirmed gaps to resolve

1. **Leon & Eastern Empire are raidable towns with a thin diplomacy track.**
   Both lack `FACTION_GOODS`, `ALLIANCE_BUFFS`, and a dedicated `cov_*`
   Covenant capstone deal. They DO have a skill reward (via `le_flamebearers` /
   `ow_specialists`) and a deal catalog, but a player who allies them gets no
   caravan and no alliance buff — strictly worse than the other four towns.
   **Highest priority** (player-facing on both axes).
2. **Clayman (abstract) lacks caravan goods + alliance buff.** Lower priority
   (no settlement), but for parity its PACT perks should exist or be a
   deliberate "this faction gives intel instead" choice.
   → **DECIDED (Phase 0):** add THEMED spy/manipulation goods + buff.
3. **Tempest has two skill-reward deals** (`tp_joyful` → Self-Regeneration and
   `ja_sages` → Thought Communication). `covenantSkillFor` returns the first
   match, so the conquest/Covenant skill grant is ambiguous.
   → **DECIDED (Phase 0):** keep Self-Regeneration; drop the `ja_sages` skill
   mapping.
4. **Shizu is deprecated but still carries a conquest profile + deal table +
   skill reward.** No settlement generates for it.
   → **DECIDED (Phase 0):** purge the reward data; keep the enum + mending.
5. **Eurazania & Milim** are diplomacy-only — confirm their reward sets are
   complete and intentional (they currently look complete).

---

## 5. The per-faction review checklist

Apply this to EACH faction so none is skipped. A faction is "reviewed" when
every applicable box is intentional (a real decision, not a leftover default):

**Raid / conquest (raidable factions only):**
- [ ] Citizen levy `count` fits the faction's size/strength and isn't just
      the default 10.
- [ ] Citizen skill pair matches the faction's theme (e.g. Dwargon → mining
      strength; Luminous → mana/knowledge).
- [ ] Role label reads correctly in the conquest message.
- [ ] The forced Covenant skill is the faction's signature skill and is
      unambiguous (one clear grant).
- [ ] Loot pool stacks are worth the assault effort and are faction-flavoured.

**Diplomacy (all factions):**
- [ ] Deal catalog asks for what the faction VALUES and pays out fittingly
      (already authored 10+/faction — verify, don't rebuild).
- [ ] A Covenant capstone deal exists and forges the alliance with a worthy
      reward.
- [ ] Alliance buff (PACT) exists and suits the faction.
- [ ] Caravan goods (PACT) exist and are faction-flavoured.
- [ ] Mending rite price/flavour is appropriate (built automatically — sanity
      check only).

**Cross-axis sanity:** the conquest payoff and the diplomacy payoff should
feel like two routes to comparable value (raid = fast/violent/one-shot;
diplomacy = slow/cumulative), so neither path is strictly dominant.

---

## 6. Phased plan

**Phase 0 — Decisions (no code): ✅ DONE (2026-06-27)**
Locked decisions recorded in `docs/decisions.md` → "Faction rewards review —
Phase 0 decisions (2026-06-27)":
- **Shizu** → PURGE its reward data (profile + deal table + `sh_pupils` skill
  mapping); KEEP the enum value + auto-built mending. Truly dormant.
- **Tempest** → Self-Regeneration is the single capstone/conquest skill; DROP
  the `ja_sages → Thought Communication` `SKILL_REWARDS` mapping (the deal
  stays, just grants no skill).
- **Clayman** → gets THEMED spy/manipulation PACT perks (`FACTION_GOODS` +
  `ALLIANCE_BUFFS`), not generic parity and not nothing. Exact item list +
  MobEffect chosen in Phase 1.

**Phase 1 — Close the structural gaps (highest player impact):**
- Add `FACTION_GOODS` + `ALLIANCE_BUFFS` + a `cov_*` capstone deal for **Leon**
  and **Eastern Empire** (bring them to parity with the other four towns).
- Add (or deliberately decline) `FACTION_GOODS` + `ALLIANCE_BUFFS` for
  **Clayman**.

**Phase 2 — Conquest balance pass (6 raidable factions):**
- Walk each through the raid checklist; retune `PROFILES` counts/skills, the
  forced skill, and `factionRewardPool` so the conquest of each faction feels
  distinct and worth it. (This is the "warfare rewards need editing" TODO from
  future-ideas.md.)

**Phase 3 — Diplomacy balance pass (all factions):**
- Walk each through the diplomacy checklist; confirm capstone rewards, buffs,
  and goods are deliberate and faction-distinct.

**Phase 4 — Cross-axis tuning + record:**
- Compare raid vs. diplomacy value per faction; adjust so neither route
  dominates. Record final decisions in `docs/decisions.md` and update
  `docs/diplomacy.md` + `docs/rival-colony-investigation.md`.

> Recommended starting point: **Phase 0 decisions, then Phase 1** — it closes
> real holes (Leon/Eastern Empire/Clayman) before any balance bikeshedding.
