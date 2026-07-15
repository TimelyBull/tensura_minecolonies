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
| clayman | ✅ | ✅ `cov_clayman` | ✅ ¹ Night Vision | ✅ ¹ Ender Pearls | ✅ |
| **leon** | ✅ | ✅ ¹ `cov_leon` | ✅ ¹ Fire Resist | ✅ ¹ gold + blaze | ✅ |
| **eastern_empire** | ✅ | ✅ ¹ `cov_eastern_empire` | ✅ ¹ Absorption | ✅ ¹ iron + amethyst | ✅ |
| shizu (dep) | ⛔ purged ¹ | ❌ | ❌ | ❌ | ✅ |

¹ = added/changed in Phase 1 (2026-06-27).

---

## 4. Confirmed gaps to resolve

1. **Leon & Eastern Empire are raidable towns with a thin diplomacy track.**
   Both lack `FACTION_GOODS`, `ALLIANCE_BUFFS`, and a dedicated `cov_*`
   Covenant capstone deal. They DO have a skill reward (via `le_flamebearers` /
   `ow_specialists`) and a deal catalog, but a player who allies them gets no
   caravan and no alliance buff — strictly worse than the other four towns.
   **Highest priority** (player-facing on both axes).
   → **RESOLVED (Phase 1):** added `cov_leon` / `cov_eastern_empire`, plus
   `FACTION_GOODS` + `ALLIANCE_BUFFS` for both.
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

**Phase 1 — Close the structural gaps: ✅ DONE (2026-06-27)**
- **Leon** + **Eastern Empire** brought to parity with the other towns:
  - `cov_leon` ("Tribute to the Platinum Saber", SupplyBundle gold+blaze+
    netherite) and `cov_eastern_empire` ("The Imperial Compact", SupplyBundle
    diamond+amethyst+redstone) added to `COVENANT_DEALS` → their alliances can
    now reach COVENANT.
  - `ALLIANCE_BUFFS`: Leon → Fire Resistance (fire knights); Eastern Empire →
    Absorption (imperial shields).
  - `FACTION_GOODS`: Leon → gold + blaze rods; Eastern Empire → iron + amethyst.
  - (Their conquest skill already worked via `le_flamebearers` /
    `ow_specialists` in `SKILL_REWARDS`.)
- **Clayman** themed spy/manipulation PACT perks added: `ALLIANCE_BUFFS` →
  Night Vision (insight); `FACTION_GOODS` → emeralds + ender pearls.
- **Shizu** purged (Phase 0 decision): removed its `ConquestPayoff.PROFILES`
  entry, its `FACTION_DEALS` catalog table, and its `sh_pupils` `SKILL_REWARDS`
  mapping. Enum value + auto-built mending kept.
- **Tempest** skill disambiguated (Phase 0 decision): dropped the
  `ja_sages → Thought Communication` `SKILL_REWARDS` mapping; Self-Regeneration
  (`tp_joyful`) is now its sole capstone/conquest skill.
- Effect/item choices are BALANCE-GUESS first passes — Phase 3 tunes them.

**Phase 2 — Conquest balance pass (6 raidable factions):**
- Walk each through the raid checklist; retune `PROFILES` counts/skills, the
  forced skill, and `factionRewardPool` so the conquest of each faction feels
  distinct and worth it. (This is the "warfare rewards need editing" TODO from
  future-ideas.md.)
- **Fight-difficulty side of this axis** is audited in
  [faction-combat-audit.md](faction-combat-audit.md): real boss/rank EP + stats
  vs reward tier, the garrison scaling pipeline, the confirmed difficulty↔reward
  mismatches (Eastern Empire / Dwargon / Tempest / Leon), and tuning
  recommendations (baseline EP, per-faction difficulty tier, rank normalisation).

**Phase 3 — Diplomacy balance pass (all factions): 🔄 IN PROGRESS (2026-06-28)**
Done BEFORE Phase 2 on purpose — the peaceful route is the REFERENCE raids will
match. Philosophy: **TIERED by difficulty** (user decision). Full record in
`docs/decisions.md` → "Faction rewards review — Phase 3".
- Tiers (updated 2026-07-10 — expanded to a **FOUR-tier ladder**; combat side
  landed in [faction-combat-audit.md](faction-combat-audit.md) §6b):
  **IV/Apex** Luminous, Leon, Dwargon, Milim · **III/High** Eastern Empire,
  Eurazania · **II/Major** Falmuth, Tempest · **I/Minor** Clayman. (Tiers = canon
  KINGDOM power, not the placeholder boss mob; Dwargon → IV 2026-07-10.)
  ⚠ Reward reconciliation owed: re-space the Covenant-emerald guides
  (were 64/48/32 for 3 tiers) across the four tiers, and lift/adjust each moved
  faction's catalog to its new tier — the per-faction tables in §7 still show
  the OLD tier labels.
- ✅ Leon + Eastern Empire catalogs expanded 4 → 10 deals.
- ✅ `cov_clayman` reward fixed (empty → 32 emeralds).
- ⬜ Catalog deals being reworked MANUALLY (user-led) against the updated tiers.
  Covenant emerald reconciliations owed: Dwargon 64→48; Milim + Leon 48→64.
- **Full reward inventory for the review is in section 7 below**, split by
  reward style (catalog / covenant / skill / caravan / alliance buff).

**Phase 4 — Cross-axis tuning + record:**
- Compare raid vs. diplomacy value per faction; adjust so neither route
  dominates. Record final decisions in `docs/decisions.md` and update
  `docs/diplomacy.md` + `docs/rival-colony-investigation.md`.

> Recommended starting point: **Phase 0 decisions, then Phase 1** — it closes
> real holes (Leon/Eastern Empire/Clayman) before any balance bikeshedding.

---

## 7. Phase 3 reward inventory — by reward style

The full review reference, split into the five reward styles. Reflects the
**current code** (post Phase 1 + the Phase 3 structural edits: Leon/Eastern
Empire expanded, `cov_clayman` reward fixed). Catalog magnitudes are being
reworked MANUALLY against the tiers below, so some deals don't yet match their
faction's tier. Tiers (updated 2026-07-10 — FOUR-tier ladder): **IV**
Luminous/Leon/Dwargon/Milim · **III** Eastern Empire/Eurazania · **II**
Falmuth/Tempest · **I** Clayman. (The per-faction tables below still show the
OLD tier labels pending the manual reward reconciliation — the COMBAT tiers are
as listed here.)

**Task shorthand:** *Deliver N X* = hand over the items via the deal's deliver
button · *Build <hut> N* = own that hut at level N · *Pop N* = colony reaches N
citizens · *Happy ≥ X* = average colony happiness ≥ X · *Lend N × Skill≥L* =
send N citizens with that skill at level L for a few days (they return trained)
· *Slay …* = kill the named target. **Tier** = the relations tier at which the
deal becomes offered. ★ marks the deal that also grants the faction's skill
(see 7C).

### 7A. Catalog deals

**What it is / in game:** the faction's quest board. Each faction offers a
rotating set of deals in the Diplomacy screen; you ACCEPT one, complete its task
before the deadline, and receive the listed items + a standing gain. Deals
unlock by relations tier (NEUTRAL → FRIENDLY → ALLIED). This catalog is ALSO the
conquest loot pool — the raid route (Phase 2) samples these same reward stacks,
which is exactly why the peaceful values are being locked first.

**Coin layer (Tensura currency; added 2026-07-06).** Tensura's currency is
Bronze → Silver → Gold → Stellar Gold Coin (+ Coin Pouches). Deals pay a "cash"
layer on top of their themed items, scaled by relations tier — a RANGE, so the
exact amount varies per deal:
- **NEUTRAL** → 6–35 **Bronze Coin**
- **FRIENDLY** → 3–20 **Silver Coin**
- **ALLIED** → 1–6 **Gold Coin**
- **Covenant / capstone** → 1 **Stellar Gold Coin** or a **Coin Pouch**

Coins are NOT mandatory on every deal — signature-reward deals (a schematic, a
golem) can skip them — and coins may also appear in REQUIREMENTS (commission
fees: pay coin to have something forged). Tempest (the trade capital) should
sit at the generous end of each range.

**No task-duplicate rewards.** A deal's reward should NOT hand back the same
item its task asked for (e.g. "Deliver 64 Iron → 16 Iron" reads as pointless).
Swap the duplicate for coins, a tier-up of the material, or a themed item.

**Tome & manual guidance.** When a deal rewards a Tensura **spell tome**
(`magic_tome_*`), pick the element that matches the faction's theme — earth
(Dwargon), fire (Leon), illusion (Clayman), enhancement (Falmuth), gravity
(Milim), summoning (Eastern Empire), recovery/barrier i.e. holy (Luminous),
water (Tempest). **Battlewill Manuals** (physical combat aura) may **replace or
accompany** a tome when the deal is martial/combat-themed (e.g. holy-warrior /
war-faction deals) — use whichever fits the faction's flavor.

**Dwargon** (Tier II — craft/industry; reworked 2026-06-28 to give Tensura
weapon/tool SCHEMATICS + smith-craft items — the smith-kingdom teaches the
smithing tree and forges staves, steel thread, earth cores, and metal golems.
Ingot-gear schematics are omitted since they auto-unlock with the metal.
Coins backfilled 2026-07-06 per the coin ladder [NEUTRAL Bronze / FRIENDLY
Silver / ALLIED Gold]; coins are skipped on signature-reward deals and used as
COMMISSION FEES in a couple of requirements. 14 deals):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Iron for the Forges | Deliver 64 Iron Ingot | 15 Medium Magic Crystal + 20 Bronze Coin | NEUTRAL |
| Fuel for the Forges | Deliver 64 Coal | 12 Monster Leather (B) + 12 Bronze Coin | NEUTRAL |
| Silver for the Smiths | Deliver 32 Silver Ingot | 2 Magic Stone + 6 Low Magisteel Nugget + 8 Silver Coin | FRIENDLY |
| Magisteel Quota | Deliver 8 Low Magisteel | **Spear Schematic** + 8 Medium Magic Crystal + 6 Silver Coin | FRIENDLY |
| A Proper Smithy | Deliver 8 Gold Block | 8 Iron Block + 16 Coal + **Short Sword Schematic** + 15 Bronze Coin | NEUTRAL |
| Fires of Industry | Deliver 2 Low Magisteel + 2 Magic Stone | **Magic Staff Schematic** + Battlewill Manual + 2 Low Magisteel *(no coin)* | FRIENDLY |
| The Grand Forge | Deliver 1 Pure Magisteel + 2 High Crystal | **Great Sword Schematic** + **Engraved High Magisteel Katana (Crushing)** | ALLIED |
| A Master's Tools | Deliver 3 Low Magisteel | **Enchanted Diamond Pickaxe (Efficiency III + Fortune II)** | FRIENDLY |
| A Blade for Every Hand | Deliver 2 Pure Magisteel Ingot | **Long Sword + Kunai Schematics** + 8 Magic Stone + 6 Silver Coin | FRIENDLY |
| Strong Backs for the Mines | Lend 3 × Strength≥8 | 16 Gold + 4 Low Magisteel + 12 Silver Coin | FRIENDLY |
| Master Artisans Abroad | Lend 2 × Creativity≥8 | **Spatial Blade Schematic** + 1 Mithril Ingot + 1 Orichalcum Ingot + Earth Tome *(no coin)* | ALLIED |
| Staff of the Smiths | Deliver 8 Magic Stone **+ 10 Silver Coin** (commission fee) | **Medium Magic Staff** | FRIENDLY |
| Threads of Steel | Deliver 16 Iron + 16 String | 8 Steel Thread + 10 Bronze Coin | NEUTRAL |
| The Mountain's Heart | Deliver 30 Gold + 20 Diamond + 10 Emerald | **Element Core (Earth)** + 6 Magic Stone + 4 Gold Coin | ALLIED |
| Forge a Sentinel ★ | Deliver 4 High Magisteel + 1 Magic Stone + 32 Bone + **3 Anvil** **+ 2 Gold Coin** (forging fee) | **High Magisteel Bone Golem** + Battlewill Manual *(no coin back)* | ALLIED |

**Tempest** (Tier II — trade capital + academy; reworked 2026-07-06 to ACTIVE
DEALS ONLY — no Population/Happiness/Building "milestone" reqs. Milestone deals
were reincarnated as delivery/slay/lend deals; adds hipokute medicine, a serpent
hunt, coin/caravan trade, a slime capstone, and (2026-07-06) enchanted books +
an engraved katana. 24 deals):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Provisions for Travellers | Deliver 32 Bread | 8 Iron + 20 Bronze Coin | NEUTRAL |
| Meat for the Market | Deliver 64 Cooked Beef | 16 Gold + 8 Emerald | NEUTRAL |
| Timber for Expansion | Deliver 48 Oak Log | 16 Bread + 8 Cooked Beef | NEUTRAL |
| A Place to Gather | Deliver 32 Glass + 16 Bricks | 16 Bread + 15 Bronze Coin | NEUTRAL |
| Helping Hands | Lend 2 × Adaptability≥5 | 12 Iron + 8 Gold + 6 Magic Stone | FRIENDLY |
| Skilled Hands Abroad | Lend 2 × Dexterity≥6 | 16 Iron + 4 Diamond | FRIENDLY |
| Medicine for the Realm | Deliver 16 Hipokute Grass | High Potion ×4 + Hipokute Seeds + 12 Silver Coin | FRIENDLY |
| Content People | Deliver 8 Cake + 32 Cookie | Full Potion + 8 Silver Coin | FRIENDLY |
| Caravan Tolls | Deliver 32 Emerald + 8 Gold | 1 Coin Pouch (A) + 12 Silver Coin | FRIENDLY |
| Tempest Serpents | Slay 8 Tempest Serpent | 4 Cooked Serpent Meat + 2 Low Magisteel + 2 Gold Coin | ALLIED |
| A Joyful Haven | Deliver 8 Golden Apple | Bucket of Slime + 8 Diamond + 4 Gold Coin | ALLIED |
| **Rimuru's Blessing ★** | Deliver 1 Slime Core + 8 Slime Ball | **Staff of Slime** + 16 Diamond *(grants Self-Regeneration)* | ALLIED |
| A Share of the Harvest | Deliver 4 Carrot + 4 Bread + 4 Beetroot | 16 Book + 4 Sugar Cane | NEUTRAL |
| Food for the Mind | Deliver 8 Bookshelf | 1 Golden Apple + 24 Lapis | NEUTRAL |
| A Library's Worth | Deliver 32 Book | 16 Lapis + 4 XP Bottle | FRIENDLY |
| A Grimoire for the Academy | Deliver 1 Grimoire (D) | 8 XP Bottle + 24 Lapis + 6 Silver Coin | FRIENDLY |
| Halls of Knowledge | Deliver 16 Bookshelf | Ancient Tome + 8 Silver Coin | FRIENDLY |
| Scholars Abroad | Lend 2 × Knowledge≥8 | 16 Lapis + 8 XP + 8 Magic Stone | FRIENDLY |
| Focused Minds Abroad | Lend 2 × Focus≥6 | 8 XP + 1 Ancient Tome | FRIENDLY |
| Sages for the Academy | Lend 2 × Intelligence≥8 | 16 XP + 16 Lapis + 4 Diamond + Ancient Tome | ALLIED |
| Higher Learning | Lend 2 × Knowledge≥6 | 16 XP Bottle + 4 Diamond + Ancient Tome + 1 Gold Coin | ALLIED |
| Forbidden Knowledge | Deliver 1 Grimoire (A) | **Enchanted Book: Mending** + 8 Silver Coin | ALLIED |
| A Scholar's Reward | Deliver 1 Grimoire (C) | **Enchanted Book: Unbreaking III** + 6 Silver Coin | FRIENDLY |
| KATANA?!? | Deliver 2 Pure Magisteel + 4 High Crystal | **Engraved Pure Magisteel Katana (`swift`)** + 2 Gold Coin | ALLIED |

*Skill note:* the ★ = Tempest's capstone skill (Self-Regeneration) now rides
**Rimuru's Blessing** (the catalog slime deal), moved off Medicine for the Realm.
This is the CATALOG capstone, not the `COVENANT_DEALS` milestone (`cov_tempest`).

**Luminous** (Tier III — premium holy; milestone deals converted to active
deals 2026-07-06 — grimoires, holy healing, the Orc-Disaster crusade):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Light for the Cathedral | Deliver 64 Glowstone | 16 Glowstone + 8 Gold | NEUTRAL |
| The Golden Tithe | Deliver 32 Gold Block | 3 Gold Block + 8 Diamond | NEUTRAL |
| Tribute to the Luminary | Deliver 32 Diamond | 8 Diamond + 16 Gold | NEUTRAL |
| The Diamond Offering | Deliver 16 Diamond Block | 16 Diamond + 2 Diamond Block | ALLIED |
| A Light of Learning | Deliver 2 Grimoire (C) | Recovery Tome + 8 Diamond + 6 Silver Coin | FRIENDLY |
| Sanctified Halls | Deliver 8 Gold Block | 12 Diamond + 2 Gold Coin | ALLIED |
| A Cathedral of Light | Deliver 2 Enchanted Golden Apple | Barrier Tome + 16 Diamond + 2 Gold Coin | ALLIED |
| Sanctuary of Healing | Deliver 16 Hipokute Grass | Revival Elixir + 8 Silver Coin | FRIENDLY |
| A Devout Congregation ★ | Slay the Orc Disaster | Anti-Magic Mask + Enchanted Golden Apple + 2 Gold Coin | ALLIED |
| The Faithful Abroad | Lend 2 × Mana≥8 | 8 Diamond + 1 Enchanted Golden Apple | ALLIED |
| Blessed Grass | Deliver 32 Hipokute Grass | High Arcane Potion ×2 + Full Potion + 8 Silver Coin | FRIENDLY |
| Grimoire of Light | Deliver 1 Grimoire (B) | Barrier Tome + 8 Diamond + 6 Silver Coin | FRIENDLY |
| The Purest Offering | Deliver 4 Enchanted Golden Apple | 2 Diamond Block + Recovery Tome + 2 Gold Coin | ALLIED |
| The Crusader's Blade | Deliver 4 Diamond Block + 16 Gold Block | **Netherite Sword (Smite V + Looting III + Unbreaking III)** | ALLIED |
| Blessed Aegis | Deliver 8 Diamond Block | **Diamond Chestplate (Protection IV + Unbreaking III)** | ALLIED |
| A Sacred Verse | Deliver 1 Grimoire (C) | **Enchanted Book: Smite V** + 6 Silver Coin | FRIENDLY |

*(Deferred: "Crusader's Trial" — slay 24 undead in one night → Anti-Magic Mask
Schematic — needs the time-windowed-slay mechanic; see future-ideas.md.)*

**Falmuth** (Tier II — war):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| The Iron Quota | Deliver 64 Iron | 8 Gold + 20 Bronze Coin | NEUTRAL |
| Arrows for the Levy | Deliver 64 Arrow | 8 Iron + 4 Gold + 15 Bronze Coin | NEUTRAL |
| I Need More Steel! | Deliver 10 Iron Block | 5 Gold Block + **Diamond Sword (Sharpness III + Looting + Unbreaking)** + 15 Bronze Coin | NEUTRAL |
| Blades of Magisteel | Deliver 4 Low Magisteel | 4 Gold Block + Enhancement Tome + 8 Silver Coin | FRIENDLY |
| Powder for the Cannons | Deliver 32 Gunpowder | 8 Gold + 4 Diamond + 20 Bronze Coin | NEUTRAL |
| Walls and Watchmen | Deliver 32 Iron Block | 3 Low Magisteel | ALLIED |
| Towers and Bowmen | Deliver 128 Arrow | 1 Short Bow + 8 Gold + 20 Bronze Coin | NEUTRAL |
| A Mighty Defense ★ | Slay 30 raiders | Long Sword Schematic + Battlewill Manual + 2 Gold Coin | ALLIED |
| A Standing Garrison | Lend 3 × Stamina≥10 | 3 Iron Block | FRIENDLY |
| Hands for the Fields | Lend 5 × Stamina≥10 | 16 Iron + 8 Gold | FRIENDLY |
| Shock Troops Abroad | Lend 3 × Strength≥8 | 3 High Magisteel + 8 Diamond | ALLIED |

(The I Need More Steel! Diamond Sword is granted ENCHANTED — the enchant/engrave
-at-grant mechanic is now built; see decisions.md "Enchanted/engraved rewards".)

**Milim** (Tier III — feast/brawl):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| A Feast Worthy of Me! | Deliver 64 Cooked Porkchop | 16 Golden Carrot + 8 Gold | NEUTRAL |
| More Meat! | Deliver 64 Cooked Beef | 16 Golden Carrot | NEUTRAL |
| Sweets for Milim | Deliver 64 Cookie | 2 Golden Apple + 8 Gold | NEUTRAL |
| Cake, and Lots of It! | Deliver 8 Cake | 2 Golden Apple + 8 Gold + Gravity Tome | FRIENDLY |
| A Place to Brawl | Build barracks 3 | 4 Diamond + 8 Gold | FRIENDLY |
| Show Me Your Strength | Pop 20 | 6 Diamond + 16 Gold | FRIENDLY |
| Show Me MORE Strength! | Pop 25 | 12 Diamond + 2 Golden Apple | ALLIED |
| Keep Them Cheerful | Happy ≥ 8 | 4 Diamond + 16 Golden Carrot | FRIENDLY |
| Champions for Milim | Lend 2 × Strength≥10 | 6 Diamond + 2 Golden Apple + Battlewill Manual | FRIENDLY |
| Warriors to Spar ★ | Lend 2 × Athletics≥8 | 8 Diamond + 1 Enchanted Golden Apple | ALLIED |

**Eurazania** (Tier II — beast kingdom):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Hides for the Beastfolk | Deliver 48 Leather | 16 Leather + 8 Cooked Beef | NEUTRAL |
| Meat for the Pack | Deliver 64 Cooked Beef | 16 Cooked Beef | NEUTRAL |
| Bones for the Den | Deliver 64 Bone | 32 Bone Meal + 8 Leather | NEUTRAL |
| Sinew for Snares | Deliver 48 String | 16 String + 16 Leather | NEUTRAL |
| Dens for the Beasts | Build stable 3 | 24 Leather + 8 Gold + 4 Compost | FRIENDLY |
| A Great Pack | Pop 18 | 24 Leather + 16 Cooked Beef + 4 Compost | FRIENDLY |
| A Thriving Pack | Happy ≥ 8 | 16 Leather + 4 Diamond | FRIENDLY |
| A Wild Haven ★ | Happy ≥ 9 | 6 Diamond + 24 Leather + Battlewill Manual | ALLIED |
| Hunters Abroad | Lend 2 × Agility≥8 | 16 Leather + 8 Gold + 4 Magic Stone | FRIENDLY |
| Keen Trackers | Lend 2 × Focus≥6 | 4 Diamond + 16 Leather | ALLIED |

**Clayman** (Tier I — schemer):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Crystals for the Scheme | Deliver 16 Low Crystal | 4 Low Crystal + 16 Redstone | NEUTRAL |
| Whispers and Wires | Deliver 64 Redstone | 32 Redstone + 8 Gold | NEUTRAL |
| Gold to Grease Palms | Deliver 32 Gold | 4 Medium Crystal + 16 Gold | NEUTRAL |
| Magicule Tithe | Deliver 8 Medium Crystal | 1 High Crystal + 2 Medium Crystal + Illusion Tome | FRIENDLY |
| A Site of Dark Power | Build mysticalsite 3 | 3 Medium Crystal + 8 Gold | FRIENDLY |
| The Puppet-Maker's Workshop | Build enchanter 3 | 8 Lapis + 16 Redstone + Buff Scroll | FRIENDLY |
| More Pawns | Pop 20 | 3 Medium Crystal + 1 Slime Core | FRIENDLY |
| Obedient Subjects | Happy ≥ 7 | 16 Redstone + 8 Gold | NEUTRAL |
| Spies Abroad | Lend 2 × Focus≥6 | 1 High Crystal + 8 Redstone + 8 Magic Stone | FRIENDLY |
| Enforcers for the Cause ★ | Lend 2 × Strength≥8 | 2 High Crystal + 4 Diamond | ALLIED |

**Leon** (Tier III — fire/martial; expanded in Phase 3, now re-ranked to III):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Stones of Fire | Deliver 32 Magma Block | 8 Magma Cream + 8 Gold | NEUTRAL |
| Cinders for the Flame Lord | Deliver 32 Blaze Powder | 8 Blaze Rod + 16 Glowstone | NEUTRAL |
| Fuel for the Furnaces | Deliver 64 Coal | 16 Blaze Powder + 8 Iron | NEUTRAL |
| Obsidian for the Keep | Deliver 16 Obsidian | 16 Gold + 4 Diamond | NEUTRAL |
| A Hearth of Flame | Build smeltery 3 | 6 Blaze Rod + 16 Gold + Fire Tome | FRIENDLY |
| The Flame Legion | Pop 20 | 6 Diamond + 1 Gold Block + 8 Blaze Rod | FRIENDLY |
| A Burning Devotion | Happy ≥ 8 | 6 Diamond + 8 Blaze Rod | FRIENDLY |
| Flamebearers Abroad ★ | Lend 2 × Mana≥6 | 4 Blaze Rod + 4 Diamond | FRIENDLY |
| The Greater Forge | Build smeltery 5 | 3 High Magisteel + 8 Diamond + Enhancement Tome | ALLIED |
| Flame Knights Abroad | Lend 2 × Strength≥8 | 3 High Magisteel + 8 Diamond | ALLIED |

**Eastern Empire** (Tier II — magitech/imperial; expanded in Phase 3):
| Deal | Task | Reward | Tier |
|---|---|---|---|
| Curios from Your World | Deliver 32 Glass | 16 Copper + 8 Amethyst | NEUTRAL |
| Strange Contraptions | Deliver 16 Redstone Block | 24 Redstone + 8 Iron | NEUTRAL |
| Resonant Crystals | Deliver 32 Amethyst Shard | 4 Diamond + 24 Redstone | NEUTRAL |
| Copper for the Engines | Deliver 16 Copper Block | 24 Redstone + 16 Iron | NEUTRAL |
| Settlers from Afar | Pop 15 | 8 Iron + 8 Emerald + Area-TP Scroll | FRIENDLY |
| The Magitech Foundry | Build smeltery 3 | 8 Iron Block + 8 Amethyst + 16 Redstone | FRIENDLY |
| A Well-Ordered City | Happy ≥ 8 | 4 Diamond + 16 Amethyst | FRIENDLY |
| Specialists Abroad ★ | Lend 2 × Intelligence≥6 | 8 Amethyst + 4 Diamond + Summoning Tome | FRIENDLY |
| An Imperial Garrison | Build barracks 4 | 6 Diamond + 8 Iron Block + 8 Amethyst | ALLIED |
| Engineers Abroad | Lend 2 × Dexterity≥8 | 8 Diamond + 1 High Crystal | ALLIED |

### 7B. Covenant milestone deals

**What it is / in game:** one capstone deal per faction, offered only once you're
at PACT and your standing has crawled up to the Covenant threshold. Completing
it FORGES the COVENANT (the top relations tier, which unlocks the strongest
ongoing perks) and pays the listed emerald reward. It's the "graduation" deal of
each relationship.

| Faction | Deal | Task | Reward | Tier value |
|---|---|---|---|---|
| Dwargon | The Masterwork Commission | Deliver Hihiirokane Katana + 8 Pure Magisteel + 1 Masterwork Forging Core | 64 Emerald | II ⚠ 64→48 |
| Luminous | The Grand Offering | Deliver 8 Diamond Block + 16 Gold Block | 64 Emerald | III |
| Tempest | A Thriving Metropolis | Pop 25 | 48 Emerald | II |
| Milim | Apito's Jelly | Deliver 1 Apito's Jelly | 48 Emerald | III ⚠ 48→64 |
| Falmuth | Prove Your Might | Slay the Wither | 48 Emerald | II |
| Leon | Tribute to the Platinum Saber | Deliver 16 Gold Block + 16 Blaze Rod + 1 Netherite Ingot | 48 Emerald | III ⚠ 48→64 |
| Eastern Empire | The Imperial Compact | Deliver 4 Diamond Block + 32 Amethyst + 16 Redstone Block | 48 Emerald | II |
| Eurazania | The Great Hunt | Slay 3 great beasts (Wither / Warden / Elder Guardian / Charybdis / Ifrit) | 48 Emerald | II |
| Clayman | Souls for the Core | Slay 10 Villagers | 32 Emerald | I |

Plus one **Covenant-only training deal**: Tempest's *Warrior Training* — a lend
(Strength≥5, returns trained in Stamina + Adaptability) offered only AFTER the
Covenant is forged → 16 Emerald.

⚠ Note: the Dwargon covenant is the only deal that still consumes a **Masterwork
Forging Core** — a known dead-end item (see future-ideas.md). Cross-axis: the
emerald amount matches the tier (III=64, II=48, I=32), but the *task difficulty*
varies a lot (Pop 25 vs. slay the Wither) — a Phase 4 cross-check candidate.

### 7C. Skill rewards

**What it is / in game:** each faction has ONE catalog deal (the ★ rows above)
that, on completion, also teaches you the faction's signature Tensura skill — you
LEARN it (or it master-upgrades if you already have it; resistances simply no-op
if owned). The exact same skill is granted by FORCE if you instead CONQUER the
faction militarily (rival-colony Stage D), so the two routes converge on the same
capstone skill.

| Faction | Granting deal | Skill | Skill type |
|---|---|---|---|
| Dwargon | Forge a Sentinel | Body Armor | Intrinsic |
| Tempest | A Joyful Haven | Self-Regeneration | Common |
| Luminous | A Devout Congregation | Holy Attack Resistance | Resistance |
| Falmuth | A Mighty Defense | Physical Attack Resistance | Resistance |
| Milim | Warriors to Spar | Strength | Common |
| Eurazania | A Wild Haven | Giantification | Intrinsic |
| Clayman | Enforcers for the Cause | Charm | Intrinsic |
| Leon | Flamebearers Abroad | Flame Attack Resistance | Resistance |
| Eastern Empire | Specialists Abroad | Eye of Truth | Intrinsic |

(Shizu's old `sh_pupils → Heat Resistance` was purged in Phase 0; Tempest's
old second skill `ja_sages → Thought Communication` was dropped so Tempest has
exactly one.)

### 7D. Caravan goods

**What it is / in game:** a PACT-tier perk. Once allied (PACT or Covenant), you
can claim a daily caravan — once per in-game day — which delivers the faction's
wares straight to you with a chat line "A caravan from <faction> delivers its
wares."

| Faction | Daily caravan |
|---|---|
| Dwargon | 12 Iron + 4 Gold |
| Tempest | 16 Bread + 4 Emerald |
| Luminous | 6 Gold + 2 Diamond |
| Falmuth | 16 Iron + 4 Emerald |
| Milim | 16 Cooked Porkchop + 4 Emerald |
| Eurazania | 12 Leather + 4 Emerald |
| Leon | 8 Gold + 4 Blaze Rod |
| Eastern Empire | 12 Iron + 6 Amethyst |
| Clayman | 6 Emerald + 4 Ender Pearl |

### 7E. Alliance buff

**What it is / in game:** a PACT-tier passive perk. While the alliance holds, you
constantly carry a potion effect (its icon shows; it's re-applied every second,
amplifier I / level 1, and lapses on its own if the alliance ends). No action
needed beyond staying allied.

| Faction | Effect (level I) |
|---|---|
| Dwargon | Haste |
| Tempest | Regeneration |
| Luminous | Resistance |
| Falmuth | Strength |
| Milim | Strength |
| Eurazania | Speed |
| Leon | Fire Resistance |
| Eastern Empire | Absorption |
| Clayman | Night Vision |

(Falmuth and Milim currently share Strength — a Phase 3 distinctness candidate
if we want every buff unique.)
