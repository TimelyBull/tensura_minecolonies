# Investigation: Lizardman, Dwarf, and per-race citizen-skill profiles

**Status:** investigation only, no code written yet.

Build order after this investigation: Lizardman race fully (with its skill
profile), then Dwarf, then apply skill profiles to existing Goblin / Orc.
The Race / ColonyMember plumbing for the new races and the skill-profile
work are independent — they can land in either order — but doing
Lizardman race first gives a real consumer for the skill-profile
infrastructure when it lands.

All findings confirmed by decompiling `tensura-neoforge-2.0.1.0.jar` and
`minecolonies-1.1.1319-1.21.1.jar` with Vineflower 1.11.2 and `javap`.

---

## PART A — Lizardman

### A1. Existence
**Confirmed.** `tensura:lizardman` → `io.github.manasmods.tensura.entity.monster.LizardmanEntity`
extends `TensuraMerchantEntity` (same parent chain as Goblin / Orc).

### A2. Naming
- Implements `INameEvolution`.
- In Tensura's `can_be_named` entity-type tag (`data/tensura/tags/entity_type/can_be_named.json`).
- Both gates `RequestNamingKeyPacket.isNotNameable` checks pass → naming
  works through `onRaceNamed` unchanged. No tag override or mixin needed.

### A3. Rendering — GECKOLIB (shadow-entity / orc-style)
`LizardmanRenderer extends GeoEntityRenderer<LizardmanEntity>` — GeckoLib,
not vanilla biped. **Reuse the orc shadow-entity pattern** (`OrcCitizenRenderHandler`):
per-citizen shadow `LizardmanEntity` never `tick()`'d, fed variant +
equipment state each frame, drawn through Tensura's own `LizardmanRenderer`.

The investment from the orc work pays off here — the
`AnimatableInstanceCache`-keyed-by-`entity.getId()` gotcha is already
solved by the per-citizen-shadow design. Orc layer subclasses we
referenced (`OrcLayer.*` typed to `OrcEntity`) won't work for lizardman;
we re-target to lizardman's equivalent layers (likely `LizardmanLayer.*`
in `tensura.client.entity.layer`).

**Non-humanoid features.** Lizardmen have tails and reptilian skin in the
GeoModel. The shadow approach doesn't care — the model bake is whatever
Tensura's renderer draws; we just feed it state. The bbHeight of the
SHADOW entity stays at Tensura's default and is irrelevant (shadow not
in world). The CITIZEN body's hitbox stays standard ~1.95.

### A4. Variant fields
`LizardmanVariant.Hair` and `.Top` (enum classes) — slimmer variant set
than goblin / orc. EntityDataAccessors (decompiled from `LizardmanEntity.java`):

| Field | Type | Notes |
|---|---|---|
| `DATA_ID_TYPE_VARIANT` | Integer | skin / scale colour (the lizardman's "variant") |
| `HAIR` | Integer | enum id, lookup via `LizardmanVariant.Hair.byId` |
| `HAIR_COLOR` | Integer | ARGB tint |
| `TOP` | Integer | enum id (single layer) |
| `TOP_COLOR` | Integer | ARGB tint |
| `BOTTOM_COLOR` | Integer | colour only, no Bottom enum |
| `BANDAGE` | Boolean | overlay flag |
| `EVOLUTION_STATE` | Integer | tier (Lizardman→Dragonewt→TrueDragonewt→Divine Dragon) |
| `EVOLVING` | Integer | transient flag during evolution |

**No -1 sentinel risk visible** — the variant enums all appear to use
positive ids only (unlike goblin's `Head` which uses -1 for "no head
accessory"). Worth a quick decompile of `LizardmanVariant$Hair` /
`LizardmanVariant$Top` `byId` impls at code time, but no smoke from the
EntityDataAccessor declarations.

Storage record will mirror the existing pattern: a new
`LizardmanVariantData` record sealed-permitted under `RaceVariantData`,
~12 bytes:

    type_variant: byte
    hair_id: byte
    hair_color: int (4 bytes)
    top_id: byte
    top_color: int (4 bytes)
    bottom_color: int (4 bytes)   (wait — total is now > 12)

Final wire size ~17 bytes including a bandage bit + evolution byte. Less
than goblin (25) and orc (26).

### A5. Height / SCALE attribute
Standard humanoid height (~1.95) per the parent TensuraMerchantEntity.
**No SCALE work needed for lizardman** — the citizen hitbox already matches.

### A6. Evolution — NOT separate EntityTypes
Lizardman evolution tiers (`DragonewtRace`, `TrueDragonewtRace`,
`DivineDragonRace`) are **ManasCore `Race` entries**, not separate
EntityTypes. Same `LizardmanEntity` handles all tiers via internal race
state. **No `Races.isBlocked`-style exclusion required** — this is unlike
orc, which has separate `tensura:orc_lord` and `tensura:orc_disaster`
EntityTypes we explicitly block.

---

## PART A — Dwarf

### A1. Existence
**Confirmed.** `tensura:dwarf` → `io.github.manasmods.tensura.entity.human.DwarfEntity`
extends `TensuraMerchantEntity`. Note: under `entity/human/` not
`entity/monster/` — Tensura categorises dwarves as humans, not monsters.

### A2. Naming — REQUIRES A TAG OVERRIDE
**Dwarf does NOT implement `INameEvolution`** AND **`tensura:dwarf` is NOT
in `can_be_named`** (verified by reading the JSON: dwarves are absent
from the values list).

Re-tracing `RequestNamingKeyPacket.isNotNameable`:

    if (!sub.getType().is(TensuraEntityTags.NAMEABLE)) {
        return true;   // <-- bounces dwarf here
    } else {
        ...
    }

Dwarves bounce at the tag check. The whole "name a wild Tensura mob →
becomes citizen" pipeline does NOT work for them out of the box.

**Fix path — datapack tag merge, NO MIXIN.** Same trick as the citizen
hostility tag (`tensura:animal_prey` + `minecolonies:citizen` we already
ship): add a tag JSON at `data/tensura/tags/entity_type/can_be_named.json`
with `"values": ["tensura:dwarf"]` + `"replace": false`. NeoForge merges
the values additively. Tag-only fix, no Java touched.

Once dwarf is in the tag, `isNotNameable` falls through to the
`INameEvolution` check, which short-circuits to `false` (instanceof
fails) → dwarf becomes nameable. Confirmed by reading the gate's logic
top-to-bottom.

### A3. Rendering — VANILLA BIPED (goblin-style) + 0.5F SCALE
`DwarfRenderer extends PlayerLikeRenderer<DwarfEntity>` — exactly the
parent class goblin uses. Constructor:

    super(pContext, new PlayerLikeModel(pContext.bakeLayer(ModelLayers.PLAYER), false), 0.5F);

**0.5F is the dwarf shrink** — passed as the `shadowRadius` arg to
`LivingEntityRenderer`. Same vanilla `ModelLayers.PLAYER` rig goblin
uses → reuse `GoblinCitizenRenderer`'s pattern with a different texture
resolver and overlay-layer set.

Renderer adds 13 layers: `HumanoidArmorLayer`, `Face`, `Hair`, `HairBody`,
`FacialHair`, `Chest`, `Top`, `Bottom`, `Feet`, `Armor`, `Helmet`,
`RoyalGuardArmor.Chest`, `ProfessionClothes`, `ProfessionClothes.Chest`.
**The richest accessory set of any race we've shipped** — more than
goblin's 8 overlays. Each maps to a `ModelLayerLocation` from
`DwarfLayer.*` (Tensura registers them at init).

We can reuse our generic `GoblinOverlayLayer<…, PlayerModel>` parameterised
by `(modelLayer, textureFn, colorFn, shouldRenderFn)` — covers all 13
overlays without per-class subclasses, same as goblin's 8.

### A4. Variant fields
`DwarfVariant` has Bottom, Face, FacialHair, Feet, Gender, Hair, Skin,
Top (8 enums) — richer than goblin (7) or orc (6). EntityDataAccessor
list to confirm at code time, but pattern matches existing races.
Estimate ~28-byte variant record.

### A5. Height — uses Attributes.SCALE
The renderer's 0.5F scale shrinks visual rendering. For the CITIZEN body
to also have a shrunken HITBOX (so dwarves don't visually clip into
blocks because the hitbox is full-citizen-size around their tiny model),
we use **`entity.getAttribute(Attributes.SCALE).setBaseValue(0.5)`** on
the `AbstractEntityCitizen`.

In MC 1.21+, the SCALE attribute is the canonical "shrink this entity"
knob — it affects:
- visual rendering (`LivingEntityRenderer.getRenderScale()` reads via
  `entity.getScale()` which reads the attribute)
- hitbox (`Entity.getDimensions(Pose)` applies the scale)
- AI navigation (path radius)
- damage / attack range

So one attribute set covers all the dwarf-is-small surface. **This is
the SCALE-shrink path that's been "verified working but never actually
used" — dwarf is the first concrete consumer.**

Where to set it: at citizen materialization (in `sendNamedMobToColony`
on the destination side, right after the citizen body spawns), keyed
off the identity's race. Persists on the entity's `AttributeInstance` —
no extra storage needed.

Verified previously in the project that vanilla `LivingEntityRenderer`
honours the entity scale attribute end-to-end. The goblin / orc
renderers already inherit from `LivingEntityRenderer`, so they
auto-respect the scale. No renderer-side change needed for dwarf —
the citizen-renderer path is identical, only the attribute differs.

### A6. Evolution — NOT separate EntityTypes
Tiers `EnlightenedDwarfRace`, `DwarfSaintRace`, `DivineDwarfRace` are
ManasCore Race entries on the same `DwarfEntity`. **No
`Races.isBlocked`-style exclusion needed**, unlike orc lord / orc
disaster. Same as lizardman.

---

## PART B — MineColonies citizen skills

### B7. The skill system

**`com.minecolonies.api.entity.citizen.Skill`** is the 11-value enum:
`Athletics, Dexterity, Strength, Agility, Stamina, Mana, Adaptability,
Focus, Creativity, Knowledge, Intelligence`.

**Storage.** `ICitizenSkillHandler` (impl: `CitizenSkillHandler`) holds
`Map<Skill, SkillData>` (`EnumMap`). `SkillData` is a small POJO with
public mutators:

    int getLevel(); void setLevel(int);
    double getExperience(); void setExperience(double);

Levels are clamped to `[1, 99]` by `incrementLevel`, but `setLevel` is
unguarded — caller responsible for clamping.

**Default initialisation.** `CitizenSkillHandler.init(int levelCap)`:

    for each Skill {
        skillMap.put(skill, new SkillData(rand.nextInt(levelCap - 1) + 1, 0.0));
    }

Each skill independent and uniform in `[1, levelCap-1]`. Called from
`CitizenData.initForNewCivilian()` which is called by
`colony.getCitizenManager().createAndRegisterCivilianData()`.

The 4-arg overload `init(colony, parent1, parent2, rand)` is used for
BORN citizens (lineage from two parents). Not relevant for our naming
pipeline — we're always the INITIAL / first-time path.

### B8. The hook — POST-creation, before any other observer

In `onRaceNamed`, the sequence is:

    ICitizenData citizenData = colony.getCitizenManager().createAndRegisterCivilianData();
    citizenData.setName(name.get());
    // <-- INSERT here: applyRaceSkillProfile(citizenData, race, rand)
    colony.getTravellingManager().startTravellingTo(...);

The `createAndRegisterCivilianData()` call returns with skills already
randomised by `init(levelCap)`. We OVERWRITE those values with our race
profile:

    SkillHandler handler = citizenData.getCitizenSkillHandler();
    for each Skill: handler.getSkills().get(skill).setLevel(profileValue);

Or use `handler.incrementLevel(skill, delta)` if we want to nudge from
the random baseline rather than absolute-set.

The hook fires ONCE at naming time. No other code path mutates skills
en-masse after that.

### B9. Randomised-around-a-mean

**Fully supported by the API.** Race profile shape:

    record RaceSkillProfile(Map<Skill, Range>)
    record Range(int meanLevel, int spread)

Apply:

    int level = clamp(profile.mean[skill] + rand.nextInt(spread*2+1) - spread, 1, 99);
    handler.getSkills().get(skill).setLevel(level);

Each skill rolled independently with its own mean / spread. Per the
brief: orcs lean tanky (high Strength / Stamina mean, low Intelligence /
Focus mean) but each individual varies. The numeric profile values are
the user-provided design — this investigation only covers HOW.

The XP-level path (`addXpToSkill`) is also exposed but irrelevant for
init — XP just buys further level-ups during gameplay; the initial level
is the baseline we set.

### B10. Interaction with our swap / stat-sync

**MC skills and Tensura stats are fully separate storage layers.**

| Aspect | MC skills | Tensura stats |
|---|---|---|
| Storage | `CitizenSkillHandler` → `Map<Skill, SkillData>` on `CitizenData` | `IExistence` (ManasCore Storage) attached to the entity |
| Persistence | `CitizenData.serializeNBT` (colony save) | Entity NBT (citizen body / goblin mob) |
| What we copy on swap | NOT touched by `copyStats` | Aura / Magicule / SpiritualHealth / HP — copied via `copyStats` |
| What persists across swap | Yes (`CitizenData` survives) | Yes (entity-NBT round-trip via the snapshot) |

`copyStats` reads / writes `IExistence` fields. It never touches the
skill handler. Confirmed by re-reading `copyStats` in `ExampleMod` —
exclusively `srcExist.getAura()` / `dstExist.setMagicule()` style calls.

**No re-randomisation on swap.** Tracing `CitizenSkillHandler.init`
call sites in the MC jar:
1. `CitizenData.initForNewCivilian()` — fresh citizen creation (we hook
   here).
2. `CitizenData.deserializeNBT()` line 985 — gated on `levelMap` present
   AND `newSkills` absent → only fires for pre-1.x save migration.
   Modern saves write `newSkills` and skip this branch.
3. The 4-arg init (parent lineage) — only called from
   `CitizenManager.spawnOrCreateCitizen` for BORN citizens. Our citizens
   never take that path (we always materialise from a named identity).

So once we set the profile at naming time, the skill levels read from
NBT on every subsequent load and stay through send → summon → send
cycles forever.

**Confirm: skill profile applied ONCE at citizen creation, not
re-randomised each swap.** ✓

---

## Pivotal answers

**A1 — both entities exist.** `tensura:lizardman` and `tensura:dwarf`.
Lizardman categorised as `monster`, dwarf as `human`. Tier evolutions
for both are ManasCore Race entries, NOT separate EntityTypes — so
neither has the orc-lord / orc-disaster exclusion problem.

**A3 — render paths:**
- **Lizardman → GeckoLib shadow-entity (orc pattern).** Reuse
  `OrcCitizenRenderHandler` architecture with a per-citizen shadow
  `LizardmanEntity` fed to Tensura's `LizardmanRenderer`. Same
  HARD-RULE-no-tick guarantees apply.
- **Dwarf → vanilla biped (goblin pattern) + Attributes.SCALE = 0.5.**
  Reuse `GoblinCitizenRenderer`'s `PlayerModel` + `GoblinOverlayLayer`
  generic pattern; dwarf adds 5 more overlay layers (13 total) but the
  generic class scales. The 0.5 scale handled by the citizen entity's
  vanilla SCALE attribute — works for hitbox AND rendering in one
  knob, no per-renderer scale needed.

**B7 / B8 — skill setting:**
- API: `ICitizenSkillHandler.getSkills().get(Skill.X).setLevel(int)`
  per skill, direct setter on `SkillData`.
- Hook point: immediately after
  `colony.getCitizenManager().createAndRegisterCivilianData()` returns
  inside `onRaceNamed`. Skills are already randomised at that point;
  we overwrite.
- Randomised-around-a-mean trivially supported via per-Skill (mean,
  spread) records.

## Biggest risk

**Dwarf naming requires the `can_be_named` tag override.** A datapack
JSON ships the dwarf entry. Same pattern as our existing
`animal_prey` tag merge — proven mechanism. Risk is low (tag merge is
NeoForge-supported and the existing pattern works), but it IS an extra
step beyond what lizardman / goblin / orc need. If we forget to ship
the tag, dwarf naming silently fails at the Tensura gate and the
player sees "you cannot name this entity" — investigatable from logs
but easy to miss.

**Second-biggest risk: dwarf SCALE attribute on the citizen body.**
This is the first production use of the SCALE-attribute shrink path.
The integration points to verify in implementation:
1. Setting `entity.getAttribute(Attributes.SCALE).setBaseValue(0.5)`
   on the citizen body at materialisation.
2. The send-side `copyStats` not clobbering the SCALE attribute (it
   only touches `IExistence` fields, but worth a re-verification).
3. MineColonies' citizen AI handling a 0.5-scale citizen path-finding
   through doors and 1-block gaps (might it try to walk under blocks
   that the renderer-vs-collider sees differently?). Vanilla path
   planner respects the attribute, but MC's custom citizen pathing
   uses its own path-jobs — risk of MC not consulting the same scale
   knob. Verify by spawning a dwarf citizen and watching pathing
   behaviour.

If MC pathing ignores SCALE, fallback is to NOT shrink the hitbox
and accept the visual "tiny dwarf in normal-size citizen volume"
result — cosmetic clip, not gameplay-breaking. Dwarf would look small
but path / collide / interact as a normal citizen.

Both risks are diagnosable from a single dwarf-citizen test session;
neither is blocking for the design.

---

# Implementation — landed

All four pieces shipped: the skill-profile infrastructure (Part B), then
Lizardman (Part A), then Dwarf, then a subordinate trade tab. The
build order matched the investigation's recommendation — infra first
so each race-add was a small consumer-only diff.

## Part B — Race skill profile infrastructure

**`RaceSkillProfile`** — per-skill `(meanBias, spread)` map.
{@link RaceSkillProfile#apply} reads each skill's current level from MC's
already-randomised handler and adds the bias:

    newLevel = clamp(currentLevel + meanBias + randInt[-spread, +spread], 1, 99)

Skills absent from the profile map are untouched.

**Locked design**: bias on top of MC's baseline — NEVER an absolute
override. Progression (XP from working, level-ups) continues normally
after application; we don't touch `init()` or any flag, just mutate
`SkillData.level` once. The "eroding head-start" falls out naturally as
work XP pushes everyone toward the per-skill cap over a career.

**Apply sites**: two — `onRaceNamed` (normal naming) and `onColonyCreated`
(pending-pool drain). Both at the same lifecycle moment: immediately
after `colony.getCitizenManager().createAndRegisterCivilianData()`
returns. Never re-applied on swaps; `CitizenData` NBT preserves the
levels through send/summon cycles.

**Bias presets** (defined in `RaceSkillProfile.SkillBias`):

| Label | mean | spread | Effective range added |
|---|---|---|---|
| HIGH | +8 | ±2 | +6 to +10 |
| LOW | −3 | ±2 | −5 to −1 (clamped at 1) |
| MILD | +3 | ±1 | +2 to +4 |
| NONE | 0 | 0 | unchanged |

**Per-race profiles** (in `RaceSkillProfiles`), post-Stage-K
re-partition (STARTERS = Colonist + Goblin flat; EARNED = Orc +
Dwarf + Lizardman, three-way lane partition):

| Skill | ORC | GOBLIN | LIZARDMAN | DWARF | COLONIST |
|---|---|---|---|---|---|
| Strength | HIGH | NONE | LOW | LOW | NONE |
| Athletics | HIGH | NONE | NONE | LOW | NONE |
| Stamina | HIGH | MILD | LOW | NONE | NONE |
| Agility | NONE | NONE | HIGH | LOW | NONE |
| Dexterity | NONE | NONE | HIGH | NONE | NONE |
| Focus | NONE | NONE | HIGH | NONE | NONE |
| Mana | NONE | NONE | HIGH | NONE | NONE |
| Intelligence | LOW | NONE | NONE | HIGH | NONE |
| Knowledge | LOW | NONE | NONE | HIGH | NONE |
| Creativity | LOW | NONE | NONE | HIGH | NONE |
| Adaptability | NONE | MILD | NONE | NONE | NONE |

COLONIST profile is empty by design — vanilla colonists keep MC's
baseline; the apply pipeline early-outs on empty profiles.

**Stage K shifts from the original Stage I1 build** (recorded for
historical context — the partition wasn't right first time):

- DWARF — moved off the Dexterity lane (Dexterity HIGH → removed)
  and gained `Intelligence HIGH` to own the full MENTAL domain
  (Knowledge + Intelligence + Creativity). Added `Strength LOW`
  alongside the existing Athletics + Agility LOWs so dwarves can't
  double as heavy-labour.
- LIZARDMAN — gained `Mana HIGH` (the niche expansion). Lizardman
  is now the sole earned race covering Mana, which gates Healer /
  Alchemist / Enchanter; previously no race had bias parity for
  those jobs. Asymmetry 4↑/2↓ kept by design — Mana is a narrow
  gate (3 jobs), peer-equal in practice.
- ORC — unchanged.
- GOBLIN / COLONIST — unchanged (starters stay flat).

See [docs/decisions.md](decisions.md) "Earned-race skill partition"
entry for the rationale and balance read.

## Part A — Lizardman

Mirrored the orc shadow-entity render path. New files: `LizardmanVariantData`,
`LizardmanCitizenRenderHandler`. Added LIZARDMAN to `Race`,
`ColonyMember`, `Races` registry (`tensura:lizardman`), the sealed
`RaceVariantData` permits, every Race / ColonyMember switch (envoy
eligibility, kill-gate, spawnEnvoy, captureRaceVariant, variant-fingerprint
pattern-switch, etc.).

**Variant data**: 9 EntityDataAccessors (variantId, hair + colour, top
+ colour, bottom colour, bandage, evolution state, evolving), 18-byte
record. No -1 sentinel found in lizardman's variant enums, but
`safeOrcEnumId` defensive wrapping applied to the three enum getters
all the same.

**Render**: per-citizen shadow `LizardmanEntity` fed to Tensura's own
`LizardmanRenderer`, never `tick()`'d. Animation-driver state mirroring
+ variant + equipment sync — identical pattern to orc.

**Envoy unlock condition** (Stage 3a hook): citizen count ≥ 15 — below
the orc bar of 25, marsh-tribe scouts earlier than orcs commit.
Kill-gate shape: current-value snapshot (same as orc).

**Dialogue**: nameplate `YELLOW`. (Superseded — the body / accept /
decline copy was later rewritten to the Tensura canon voice:
**proud, formal, a touch grandiose, but earnest and sincere in
allegiance**, revering the player as a protector-ruler. See
`docs/decisions.md` → "Canon-voice rewrite of envoy dialogue".)

**Skill profile** (post-Stage-K): precision / mystic carrier
archetype — `+Agility / +Dexterity / +Focus / +Mana HIGH`,
`-Strength / -Stamina LOW`. The Mana HIGH was added in Stage K as
the niche expansion: lizardman is now the sole earned race covering
the Healer / Alchemist / Enchanter lane.

## Part A — Dwarf (with three new wrinkles)

Mirrored the goblin biped render path. New files: `DwarfVariantData`,
`DwarfTextures`, `DwarfOverlayLayer`, `DwarfCitizenRenderer`,
`DwarfCitizenRenderHandler`.

### Wrinkle 1 — `can_be_named` tag merge (landed first)

Dwarf does NOT implement `INameEvolution` and was NOT in Tensura's
`can_be_named` tag — Tensura's naming gate would silently reject any
dwarf naming attempt without the override.

**Fix**: datapack tag merge at
`data/tensura/tags/entity_type/can_be_named.json` (`replace: false`,
values: `["tensura:dwarf"]`). Same mechanism as the existing
`animal_prey` tag merge. No mixin needed — `isNotNameable` falls
through to the `INameEvolution` check, which short-circuits to `false`
(instanceof fails) so dwarf becomes nameable.

### Wrinkle 2 — SCALE attribute / renderer-only scaling

Original plan was `Attributes.SCALE = 0.5` on the citizen body for
shrunk hitbox + renderer. In practice this **caused dwarf citizens to
render INVISIBLE**: vanilla `LivingEntityRenderer.render` applies a
hardcoded `-1.5` Y translate AFTER `scale()` fires — that translate
runs in scaled space, so SCALE=0.5 placed the rendered model at
~-0.75 below the entity origin (half-sunken in the ground). Plus the
texture-proxy `DwarfEntity` was being asked to resolve textures
before its variant fields were valid, sometimes returning nulls that
crashed deeper in the pipeline.

**Final approach (corrected after a third pass — per-citizen scale
capture)**: drop the SCALE attribute on the destination citizen body
entirely; do the scaling client-side in the renderer ONLY. Two
sub-steps:

1. **Base value `0.9375F`** — Tensura's own `PlayerLikeRenderer.scale()`
   value, verified by decompile. An earlier pass used `0.5F` on the
   mistaken assumption that the `0.5F` in Tensura's `DwarfRenderer`
   constructor was the model scale; it's actually the **shadow
   radius** (third arg of `LivingEntityRenderer(Context, EntityModel,
   float)`).
2. **Per-citizen multiplier** — Tensura's `DwarfEntity.finalizeSpawn`
   randomises `Attributes.SCALE` per-spawn (royal-guard = 1.0, others
   `0.7 + rand³ × 0.3`, biased low in [0.7, 1.0]). A flat `0.9375F`
   renderer scale erased this variation, so every citizen dwarf
   rendered at the same size regardless of the wild dwarf it was
   named from — visible as a size pop on the subordinate-to-citizen
   swap. Fix: capture the source dwarf's `Attributes.SCALE` at
   `captureDwarfVariant` time, extend `DwarfVariantData` to **29
   bytes** with a trailing `float scale` field (25-byte legacy
   payloads decode with `scale = 0.9375F`, the median), and have
   `DwarfCitizenRenderer.scale()` multiply the captured value by
   `0.9375F`. Final per-citizen render scale lands in
   `(0.7..1.0) × 0.9375 ≈ (0.656..0.9375)` — exactly the size of the
   wild dwarf the citizen was named from.

Hitbox stays at the regular citizen size since the citizen body's
own SCALE attribute is left at 1.0 — the scale lives entirely on
the renderer's `PoseStack`. `applyRaceScaleAttribute` retained as a
no-op-clearing helper that force-sets SCALE to 1.0 for all races so
a Tensura tier-scale doesn't leak onto the destination citizen body.

Side-defensive measures: each `DwarfOverlayLayer` wraps `bakeLayer`
in try/catch (one failed overlay no longer kills the whole renderer);
the handler tolerates 5 consecutive render failures before
invalidating (was 1); `getTextureLocation` falls back to vanilla
`steve.png` rather than returning null.

### Wrinkle 3 — texture resolution without a `DwarfEntity`

Tensura's `DwarfVariant.<X>.getTextureLocation` static helpers all
take a `DwarfEntity` and read its `texture` field through the
package-private path. We render an `AbstractEntityCitizen`, not a
`DwarfEntity`, so the helpers can't be called directly.

**Solution**: a single lazy "texture proxy" `DwarfEntity` in
`DwarfTextures`. Built once on first call, never added to the world,
never ticked. Each lookup mutates its variant fields, calls
Tensura's static getter, returns the path. For lookups where Tensura
exposes a public per-instance accessor (`Skin.getTextures()`,
`Hair.getTextures()`) we skip the proxy and call the enum directly.

### Other dwarf pieces

**Variant data**: 13 fields (9 enum-id bytes including raw `scar` int
+ 4 colour ints), 25-byte record. Defensive `safeOrcEnumId` wrapper
applied to the 8 enum getters.

**Overlays**: 7 `DwarfOverlayLayer` instances (Face, Hair, HairBody,
FacialHair, Top, Bottom, Feet) reusing Tensura's per-overlay
`ModelLayerLocation` constants from `DwarfLayer.*`. `Chest` (Tensura
uses `HumanoidChestModel` — not PlayerModel-compatible) and the
RoyalGuardArmor / ProfessionClothes overlays excluded.

**Envoy unlock condition**: PLACEHOLDER — needs ≥30 citizens AND at
least one Miner / Miner's Hut building in the colony. The real
conditions (dwarven village found / 20 in-game days / true demon
lord) are deferred-content. The placeholder gates dwarf as the
late-game envoy with a thematic stoneworking-interest signal.
Surfaced in `/envoystate` with a `[PLACEHOLDER condition]` marker.
Kill-gate shape: current-value snapshot (same as orc / lizardman).

**Dialogue**: nameplate `GOLD` (craftsmanship / wealth / mountain-hold).
(Superseded — the body / accept / decline copy was later rewritten to
the Tensura canon voice: **gruff, hearty, blunt; craftsman's pride and
a love of a good drink, warm beneath the bluntness**. See
`docs/decisions.md` → "Canon-voice rewrite of envoy dialogue".)

**Skill profile** (post-Stage-K): mental / craft archetype —
`+Knowledge / +Intelligence / +Creativity HIGH`,
`-Athletics / -Strength / -Agility LOW`. Stage K swapped Dexterity
HIGH for Intelligence HIGH (so the mental domain — Knowledge +
Intelligence + Creativity — is owned cleanly) and added Strength
LOW (so dwarves stay head-not-hands and don't double as heavy
labour).

## Subordinate trade tab (post-races polish)

After all four races landed, added a "Trade" button injected onto
Tensura's `HumanoidMainScreen` (the armor + weapons page — 4 armor
slots, 2 weapon slots, the page the player lands on when right-
clicking a subordinate) for named subordinates that extend
`TensuraMerchantEntity` (goblin, lizardman, dwarf — NOT orc).
Lives entirely in `SubordinateTradeButtonHandler.java` plus one
networking payload. No mixin.

**Screen choice — `HumanoidMainScreen`, not `HumanoidInventoryScreen`.**
Tensura's subordinate inventory is split across two distinct Screen
classes: `HumanoidMainScreen` for the armor / weapons page, and
`HumanoidInventoryScreen` for the chest-overflow paged view reached
by the nav arrows. The tab anchors to `HumanoidMainScreen` so it's
always visible on the page the player opens to and never clutters
the chest-scroll views. (An earlier revision hooked
`HumanoidInventoryScreen` with a `page == 0` guard, but that placed
the button on the chest page — wrong page entirely. Switched to
`HumanoidMainScreen`; the page-index guard is no longer needed
because `HumanoidMainScreen` has no pages.)

**Mechanism**: `ScreenEvent.Init.Post` fires when any screen opens; if
it's `HumanoidMainScreen` and the (reflectively-read) `humanoid`
field is a `TensuraMerchantEntity`, the handler adds a yellow
"Trade" button 4 px right of the inventory texture. Click → C2S
`OpenSubordinateTradePayload(entityId)`. Server validates ownership
via the identity store, then calls
`merchant.openTradingScreen(player, displayName, merchantLevel)` —
vanilla `Merchant` interface default method, opens the standard
merchant menu.

**Why this works without losing state**: `TensuraMerchantEntity`
already round-trips Profession, MerchantLevel, Offers, Xp, and
Gossips through its `addAdditionalSaveData` / `readAdditionalSaveData`.
Naming sets the tame flag but doesn't clear merchant state — Tensura's
own `handleCommanding` just routes tame entities to the inventory
screen instead of trading. We sidestep that branch with a separate
button that calls the merchant flow directly. Trading XP, level-ups,
gossip changes, and restocking continue to fire because they're
driven by the standard merchant pipeline, which runs from
`customServerAiStep` regardless of tame state.

Server-side validation: entity exists, is a `TensuraMerchantEntity`,
identity-store ownership matches, not asleep / baby / dead, not
already trading with someone else, offers non-empty. Each failure
sends a green-italic advisory via the standard
`sendAdvisoryNotice` chokepoint.
