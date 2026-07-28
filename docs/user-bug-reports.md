# User bug reports (unresolved)

Bug reports from players, captured as reported. These are UNRESOLVED — open
issues awaiting investigation or a fix. When one is fixed, note the resolution
and move it out (or mark it RESOLVED with the fix reference). Distinct from
`docs/user-suggestions.md` (feature requests) — this file is for things that
are broken.

---

## 2026-07-27 — Interacting with the Magicule Barrier block crashes the game

**Status:** UNRESOLVED — reported 2026-07-27. **Awaiting crash report / logs.**

**Report (as phrased):** "Hello, I have discovered a bug, where interacting with
the magicule barrier block, crashes the game."

**Reporter note:** "might have something to do with servers or tensura version."

**What this means:** interacting with the Barrier Core block (right-click, which
is supposed to open the core menu) hard-crashes the game instead of opening the
UI. The reporter suspects it may be environment-specific (dedicated/LAN server
vs. singleplayer) and/or tied to a particular Tensura version. NOTE: distinct
from the 2026-07-04 "Subordinates attack the magicule barrier" entry below —
that's mob behaviour, this is a crash on player interaction.

**Where to look (pending logs):** `BarrierBlock` (the right-click /
`useWithoutItem` handler that opens the core menu), `BarrierBlockEntity`, and the
`OpenBarrierMenuPayload` → `BarrierCoreScreen` path. A server/client mismatch on
the menu-open (client-only code reached server-side, or a payload built from data
present on only one side) is the leading class of cause for an "open menu =
crash" symptom. Cannot triage further until a crash log is provided.

---

## 2026-07-27 — Game crashes when opening the Restaurant / dining hall food menu

**Status:** DIAGNOSED (2026-07-27) — **NOT our mod.** Root cause is a mixin
injection failure in a THIRD-PARTY MineColonies addon, **`mctier_engine`
("MineColonies Food Tier Engine" v1.0.0)**, incompatible with the pack's
MineColonies **snapshot** build. `tensura_minecolonies` appears nowhere in the
crash. Fix is on the pack/player side (update or remove `mctier_engine`, or use a
MineColonies version it supports). See "ROOT CAUSE (verified against the crash
report)" below.

### ROOT CAUSE (verified against the crash report `crash-2026-07-27_00.56.21-client.txt`)

The crash is `Ticking screen` → a `MixinTransformerError` thrown while
class-loading MineColonies' `RestaurantMenuModuleWindow`. That class is loaded the
instant the player opens the restaurant food menu — the trace is
`RestaurantMenuModuleWindow.onOpened → updateStockList` (line 327). Hence "crashes
every time I open the dining hall food menu."

The `Caused by` is exact and unambiguous:

> `InjectionError: Critical injection failure: Redirector`
> `proxyItemForResourceList(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/Item;`
> `in mctier_engine.mixins.client.json:RestaurantResourceTooltipMixin from mod`
> `mctier_engine failed injection check, (0/1) succeeded. Scanned 0 target(s).`
> `No refMap loaded.`

Reading it: the mod **`mctier_engine`** ships a mixin (`RestaurantResourceTooltipMixin`
+ `RestaurantWindowMixin`) that `@Redirect`s a method inside MineColonies'
restaurant window. The redirect **scanned 0 targets** — the method it targets no
longer exists / changed signature in the pack's MineColonies build — and
**"No refMap loaded"** means the mixin's obfuscation-mapping file is missing from
its jar. Either way the mixin cannot apply, so mixin aborts class-load with a
critical error, and the game dies the moment that screen is opened.

**Version mismatch that causes it:** the pack runs MineColonies
**`1.1.1358-1.21.1-snapshot`** (a snapshot, NEWER than our supported 1.1.1319),
while `mctier_engine v1.0.0` was built against an older MineColonies whose
restaurant window still had the redirected method. `mctier_engine` is simply out
of date for this MineColonies.

**Exact broken injection point + 1.1.1319 compatibility (bytecode-verified):**
Decompiled `mctier_engine-1.0.0.jar`. Its `RestaurantResourceTooltipMixin`
is `@Mixin(targets="…RestaurantMenuModuleWindow$2")` with
`@Redirect(method="updateElement", at=@At(target="Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;"))`
(handler `proxyItemForResourceList` → `FoodHelper.getGuiItem`). The sibling
`RestaurantTooltipMixin` is the same on `$1`; `RestaurantWindowMixin` redirects
`ItemStorage.getItem()` in `updateStockList`. Checked these targets against our
pinned **`minecolonies-1.1.1319`** jar: `RestaurantMenuModuleWindow$1` and `$2`
both **have `updateElement(int, Pane)` and both call `ItemStack.getItem()`**, and
`updateStockList` exists with the `ItemStorage.getItem()` call — i.e. EVERY
injection point the mod requires is present in 1.1.1319. The jar ships **no
refmap**, but NeoForge 1.21.1 runs Mojmap names at runtime and the mixins use
Mojmap names directly, so the names already match — the "No refMap loaded" warn
is harmless AT 1.1.1319. On **1.1.1358-snapshot** MineColonies refactored that
inner-class `updateElement` / call site, so the `@Redirect` binds 0 targets and
(with `required=true`, `defaultRequire=1`) aborts class-load → the crash.
CONCLUSION: `mctier_engine v1.0.0` is compatible with MineColonies **1.1.1319**
(and up to whatever build in `(1319,1358]` performed that refactor); it is
incompatible with **1.1.1358-snapshot**. Fix = pin MineColonies to a pre-refactor
build (e.g. 1.1.1319), or update/remove `mctier_engine`.

**What `mctier_engine` is (verified):** a THIRD-PARTY, publicly published
MineColonies food-tier addon — "MineColonies Food Tier Engine" / the "MCTier
Library" ecosystem by author **zackman634**. Its whole job is slotting modded
food items into MineColonies' tiered food (restaurant) system, which is why it
mixins the restaurant window. Verified from the pack's `latest.log` (jar
`mctier_engine-1.0.0.jar` loaded from the "Tempest Protocol" instance; two mixin
configs; refmap `mixins.mctier_engine.refmap.json` "could not be read") and from
its public listing. The pack bundles its companion patches too
(`minecoloniesvanillafoodcompat`, `minecoloniesphfe`, `minecoloniesphfc`,
`minecolonies_solcarrot`). Nothing to do with our mod.

### CONFIRMED NOT OUR MOD

- `tensura_minecolonies` (v0.2.1 in this pack) appears **zero** times in the crash
  stack trace. The only mixins annotated on the crashing class are
  `mctier_engine`'s.
- Our mod ships **no restaurant / food-menu mixin** (grep of
  `tensura_minecolonies.mixins.json` + `mixin/` = none), and does not touch
  `RestaurantMenuModuleWindow` or MC's food list. Our earlier "where to look" hunch
  (our custom consumables appearing in the food list) is **ruled out** — the crash
  is in mixin *application*, before any item/food logic runs.
- The reporter's environment is the confounder that made this look like it could be
  ours: an edited Tensura ("Tempest Protocol"), a snapshot MineColonies, and a
  large addon stack (`betterwithminecolonies`, `stylecolonies`, `mctier_engine`, …)
  from the `uknRoPdy` pack.

### FIX (player / pack side — nothing to change in our mod)

Tell the reporter (any one of these resolves it):
1. **Update `mctier_engine`** to a build compatible with MineColonies
   1.1.1358-snapshot (preferred if the author has released one), OR
2. **Remove `mctier_engine`** (MineColonies Food Tier Engine) — the restaurant menu
   then uses MineColonies' own vanilla behaviour and opens fine, OR
3. **Use the MineColonies version `mctier_engine v1.0.0` was built for** (i.e. don't
   run it against a newer snapshot).

This is a pack-maintainer issue (a snapshot MineColonies paired with an addon that
hasn't updated to it). No action in `tensura_minecolonies`.

### Original triage notes (kept for reference)

**Report (as phrased):** "Game crashes whenever I try to open the dining hall
food menu."
- **Singleplayer or Server:** Server (Bisect Hosting), 2 players.
- **Other mods installed:** Edited "Tempest Protocol" Tensura, MineColonies, and
  others. CurseForge modpack code: `uknRoPdy`.
- **What happened:** game crashes whenever trying to open the dining hall food
  menu.
- **Expected:** food menu should open, allowing selection of restaurant foods.
- **Steps to reproduce:** open the food menu at any dining hall.
- **Every time?** Always (100% reproducible).

**What this means:** opening the Restaurant hut's food-selection menu crashes
every time. Reproducible, so the crash log's stack trace should pinpoint the
cause directly.

**Note on the environment:** the reporter is on an EDITED Tensura ("Tempest
Protocol") and a modpack (`uknRoPdy`), NOT our pinned/supported jars — a
Tensura-version or third-mod interaction is on the table alongside our own code.

**Where to look:** read the provided crash log's stack trace FIRST — a "crash on
a specific screen open" trace usually names the offending class directly.
Consider whether anything we ship reaches the Restaurant/food menu — our custom
consumables (Apito Nectar, Apito's Jelly, Drago Nova) could appear in a food
list; a malformed food component could crash the menu open. Confirm whether it
reproduces WITHOUT this mod (isolates ours vs. the edited Tensura / modpack).

---

## 2026-07-26 — [HIGH PRIORITY] Losing a raid WIPES the colony ("This block is missing its respective building, try restarting or loading a backup")

**Status:** INVESTIGATED (2026-07-26) — NOT an intended feature, and NOT caused by
our raid code. Traced to a MineColonies colony-data DESYNC (colony record present,
building objects gone). Definitive root-cause of the desync needs a `latest.log`
from a wipe. See "INVESTIGATION (2026-07-26)" below.

### SECOND, INDEPENDENT REPORT (2026-07-27) — with an isolating detail

A second player reported the same symptom independently:

"whenever your colony is attacked, if you lose the raid, you have to remake your
whole colony because none of the buildings will allow you access anymore. this is
especially annoying when you're on a server and can't be on to protect it 24/7.
Interacting with any building gives the line 'This block is missing its
respective building, try restarting or loading a backup'. **I tried minecolonies
without this mod and this issue wasn't there.**"

**Why this matters:** the 2026-07-26 investigation concluded the wipe is a
MineColonies-side save/load DESYNC, NOT our raid code. This reporter's last
sentence — that MineColonies WITHOUT our mod does not show the issue — is
evidence pointing back at our mod (or at our mod being the trigger that surfaces
the desync). It does not overturn the investigation (they may still have other
mods, and "without this mod" may also mean without the whole pack), but it
raises the priority of getting a `latest.log` and a clean MC + Tensura + our-mod
ONLY repro. Both reporters are on servers/LAN.

### INVESTIGATION (2026-07-26) — verified against the minecolonies-1.1.1319 jar + our source

**The error identifies the failure category precisely.** The string is the
`com.minecolonies.coremod.gui.nobuilding` key, emitted by MineColonies'
`GetColonyInfoMessage` (the packet the client sends when you right-click a hut to
open its GUI). MC has TWO distinct messages:
- `gui.nocolony` — "…missing its colony…" → the block resolves to NO colony at all.
- `gui.nobuilding` — the one reported → the colony **is found**, but
  `colony.getBuildingManager().getBuilding(pos)` returns **null**.

So the colony record still exists; its **building objects are gone** (or the hut
blocks point at a colony whose building map came up empty). That is a
save/load DESYNC or partial data loss — not a designed "you lost, colony
deleted" mechanic.

**Neither MineColonies nor our mod deletes a colony/buildings on a lost raid:**
- MC's `RaidManager` (bytecode) has no colony/building deletion on loss — losing a
  raid only adjusts future difficulty (`LOST_CITIZEN_DIFF_REDUCE_PCT` /
  `_INCREASE_PCT`, `nightsSinceLastRaid`) and kills some citizens. No lang string
  for "colony deleted after losing a raid" exists.
- Our `TensuraRaidEvent` explicitly defers building damage ("Multi-wave, lore
  variants, and building damage are deferred"). Our `TensuraRaids` never calls
  `getBuildingManager()` / `removeBuilding` / any colony/citizen deletion — the
  loss path (`resolveTimeout`) just withdraws leftover mobs. Confirmed by grep.
- Our only touch on colony SAVE/LOAD is `EventManagerMixin`, a read-side
  `@WrapOperation` that recovers our foreign-namespace event id; on a genuinely
  unknown event it returns null and defers to MC's original null-handling (it does
  not throw), so it can't zero out a building map.

**One real MC building-loss path exists but doesn't match the symptom:**
`raidersbreakblocks` defaults to **TRUE** (`defineBoolean("raidersbreakblocks",
true)`), so MC's OWN raider entities (`AbstractEntityMinecolonies{Raider,Monster}`,
pirates) physically break blocks — including hut blocks, which really removes the
building. BUT that also removes the BLOCK, and the report is about interacting
with hut blocks that are still standing → this is desync, not physical demolition.
(Our Tensura raid mobs are plain Tensura entities, not `AbstractEntityMinecolonies*`,
so they never use this griefing path at all.)

**Most likely trigger of the desync (needs a log to confirm):** the colony NBT
isn't saving/loading cleanly, and a lost raid is simply WHEN it surfaces —
a lost raid is a heavy-load / player-death / rage-quit / force-close moment, and on
an **Essential LAN host** an unclean shutdown mid-raid means the world (and colony
data) never saves cleanly → buildings missing on next load. Aggravating suspects in
the attached mod list: `PureSuffering` (forces extra invasions ON TOP of MC + our
Tensura raids — three raid sources stacking), `MineColonies_Tweaks` /
`betterwithminecolonies` / `smallcolonies` (colony-behaviour mods), or a mid-raid
crash from any mod. A per-tick exception during colony tick/save would produce
exactly this.

### NEXT STEP TO CLOSE THIS OUT

The category is certain (desync/partial data loss); the exact trigger is not, and
a `latest.log` / `debug.log` from around a wipe is what pins it — look for an
exception during colony save/load or a raid tick, or a "colony X couldn't load
building at …" line. Ask whether it reproduces on MC + Tensura + this mod ALONE
(isolating `PureSuffering` / `MineColonies_Tweaks`), and whether the host ever
crashed/force-closed during the losing raid.

**Reporter answer / mitigation given (2026-07-26):** it is not intended and not our
raid code; recover via MineColonies' automatic backup (world-save `backup/`
folder — the message's own "load a backup" advice); prevent recurrence by clean
shutdowns, keeping MC auto-backups, not stacking three raid systems (consider
`enableRaids=false` on our side and/or taming PureSuffering), and optionally
`raidersbreakblocks=false`.

---

**Original triage (kept):** Reporter asks whether this is intended. It is **not**
intended: failing to defend against a raid must not destroy colony data.

**Report (as phrased):** "Whenever I failed to protect my colony from a raid,
the colony data would get wiped, resulting in the need to restart the entire
colony. This line appears when interacting with buildings: *'This block is
missing its respective building, try restarting or loading a backup.'* Is this
an intended feature?"

**Attached:** the reporter supplied their **server mod list** (an Essentials LAN
server; file in Downloads — `message-4.txt`) — useful for spotting a mod
conflict as part of triage. Notable colony/AI/faction-adjacent mods in that
list to keep in mind: MineColonies 1.1.1319 + `MineColonies_Tweaks-3.30` +
`betterwithminecolonies` + `smallcolonies 1.8`, `easy_factions`, `WarNTaxes`,
`PureSuffering` (raid/invasion mod), plus our own `tensura_minecolonies-0.2.1`.

**What this means:** after a raid the reporter *couldn't* defend, their colony
became unusable — the town-hall/building data appears gone, and interacting with
placed hut blocks throws MineColonies' *"This block is missing its respective
building…"* error (MC's message for a hut block whose backing `IBuilding` no
longer exists in colony data). The practical result was having to rebuild the
whole colony.

**Leading hypotheses (to verify — none confirmed yet):**
- Our `TensuraRaidEvent` / `TensuraRaids` corrupts or desyncs colony data on a
  LOST raid (vs. a repelled one). The victory/timeout paths are exercised; the
  "colony overrun / raid lost" path is worth auditing for anything that could
  remove buildings or the colony record. Note `TensuraRaidEvent implements
  IColonyRaidEvent` and is registered in MC's `colonyeventtypes` registry — a
  bad NBT (de)serialization or event-cleanup step could damage the colony save.
- A **MineColonies-side** raid-loss consequence (MC can raze/damage buildings on
  a lost raid) compounded by another mod. `MineColonies_Tweaks`,
  `PureSuffering`, or `easy_factions` could be interacting. Confirm whether the
  wipe reproduces with only MineColonies + our mod, or needs the full pack.
- Save/backup timing on a **LAN-hosted** world (the report is from an Essentials
  LAN server) — a mid-raid crash or unclean shutdown could truncate the colony
  save independently of raid logic.

**Where to look:** `TensuraRaidEvent` (NBT persistence + the
`onFinish`/cleanup path), `TensuraRaids` (raid resolution — victory vs.
loss/timeout branches), `RaidSavedData`. Cross-reference MineColonies' own
raid-loss building-damage behaviour and whether it's amplified by a pack mod.

**Ask the reporter to confirm:** (1) does it happen on a *repelled* raid too, or
only a lost one; (2) is it MC's own raid or our Tensura raid that precedes the
wipe; (3) a `latest.log` from around the wipe; (4) does it reproduce with just
MineColonies + Tensura + this mod (to isolate from `PureSuffering` /
`MineColonies_Tweaks` / `easy_factions`).

---

## 2026-07-26 — Faction settlement generation still buggy — a building spawned at the bottom of the sea

**Status:** UNRESOLVED — needs investigation. Sibling of the 2026-06-30
below-bedrock/above-bedrock placement bug (RESOLVED 2026-07-04) and the
placement-polish work in `docs/future-ideas.md` (Stage 6) — the dimension gate
and terrain-following passes fixed the dimension + slope cases but **water**
sites are still landing wrong.

**Report (as phrased):** faction generation "still has some bugs" — one building
spawned "at the bottom of the sea."

**What this means:** a faction settlement building was placed on the **sea
floor** (underwater) instead of on dry land. The 2026-07-04 fix stopped
buildings anchoring on bedrock/ceilings; the Stage-6 terrain-following pass laid
per-building ground-matched pads. Neither guarantees the chosen site isn't
**submerged** — `groundSurfaceY` scans down to true ground, which under an ocean
is the seabed, so a settlement (or a single stray building of it) can generate
underwater.

**Leading hypothesis (to verify):** the settlement center / per-building
placement in `RivalColonies` (`findBuildableCenter` / `groundSurfaceY` /
`levelBuildingPad`) does not reject **water columns** — it accepts the seabed as
"ground." Needs a water/ocean-biome rejection (or a search-for-dry-land retry)
before committing a building's Y, applied both to the town center pick and to
each individual building's local ground Y (the Stage-6 per-building placement is
where a single building could end up over water even if the center is on land).

**Where to look:** `RivalColonies` — `groundSurfaceY`, `findBuildableCenter`,
`surfaceRange`, `levelBuildingPad`, and the per-building Y computation added in
the Stage-6 terrain-following rework (see `docs/future-ideas.md` "Settlement
placement polish — Stage 6"). Cross-reference the 2026-06-30 placement bug entry
below for the Y-resolution code paths. Consider: reject sites whose surface
block is water / that are in an ocean or river biome, and/or lift a submerged
building to the water surface or relocate it to the nearest dry column.

---

## 2026-07-22 — Naming a colony-born baby (or a grown-up one) leaves a PHANTOM citizen; summoned babies arrive as adults

**Status:** RESOLVED (fix implemented 2026-07-22, 0.2.1) — awaiting the
reporter's confirmation.

> "if you have a baby monster born in your village and you summon them to your
> side and name them, then send them back you can end up having a phantom
> citizen — one that doesn't exist, cannot be sent to the colony, as they are
> stuck by your side and are effectively dead weight, eating up a citizen slot.
> Summoned babies also get summoned as adults. it also seems to do this when a
> baby grows up and you summon it to name it."

### ROOT CAUSE 1 (the phantom) — naming an EXISTING citizen minted a second one

`onRaceNamed` (the `NAMING_EVENT` handler) assumed every named mob was a
stranger. It unconditionally ran `createAndRegisterCivilianData()` + built a new
`RaceIdentity`. But a colony-BORN citizen (minted by `mintRaceChildCitizen`)
that the player has summoned to their side is still a fully registered citizen —
it owns an identity and a `CitizenData` holding a housing slot — and it has no
Tensura name yet, so the naming menu opens on it perfectly happily.

The kill step is `RaceIdentitySavedData.addIdentity`:

```java
mobUUIDToIdentityId.put(identity.mobEntityUUID, identity.identityId);
```

One entry per mob UUID. Registering the second identity **displaced the first**
in the reverse index. From then on every lookup for that body
(`getByMobUUID` — the send trigger, the death hook, the roster) resolved to the
NEW record, and the original was unreachable: its `CitizenData` sat in the colony
permanently `startTravellingTo(…, Integer.MAX_VALUE)`-suppressed, so no body ever
spawned for it, it could not be summoned (nothing to swap) and could not be sent
(the mob answers to the other identity) — a citizen slot with nothing behind it.
Exactly the reported phantom. The same thing happened on the grown-up path, and
on any citizen the player summoned and then named.

**Fix:** `onRaceNamed` now looks the mob up FIRST. If it already has an identity,
naming is just a rename (`renameExistingCitizen` — same citizen id, same housing
slot, same skills, same happiness modifier, no second registration) and Tensura's
own naming still completes normally. The pending pool (mobs named before the
player had a colony) got the same guard: re-naming updates the queued entry via
`RaceIdentitySavedData.renamePending` instead of queueing the mob twice, which
would have promoted to two citizens on colony creation. A non-owner naming
someone else's citizen creates no duplicate either; it just can't change the name.

### ROOT CAUSE 2 (adult babies) — the snapshot was never a baby

A colony-born child's `entitySnapshot` is captured in `mintRaceChildCitizen` from
a TRANSIENT mob built with `EntityType.create` + `finalizeSpawn` purely to roll
an appearance — and that mob is an ADULT. Nothing ever wrote the baby state into
it, because a bred child is never "sent" (the send path is what normally captures
a real snapshot). So summoning one reconstructed an adult. The existing comment in
the send path — "Summon path round-trips the baby state automatically via the
entity NBT snapshot" — is true only for a mob that was sent at least once.

**Fix:** the summon no longer trusts the snapshot's age. It syncs from
`citizenData.isChild()`, which is the durable source of truth, so a child citizen
always materialises as a baby and a grown citizen always as an adult regardless
of how stale the snapshot is.

### FOLLOW-UP (reported 2026-07-22, same session) — "they look like an adult for a moment and change when the animation is complete"

Not the animation. `EntityCitizen.isBaby()` returns a **private cached field**
(`child`), not the synced `DATA_IS_CHILD` value, and on the CLIENT that field is
assigned in exactly one place: `CitizenColonyHandler.updateColonyClient()`. That
runs from the entity's `ACTIVE_CLIENT` state, which a freshly spawned body only
reaches after leaving `EntityState.INIT` — a transition checked on a **40-tick
timer**. So for up to two seconds the client believes a child is an adult, and
`LivingEntityRenderer.render` (which our citizen renderers extend) sets
`model.young = entity.isBaby()` every frame. The ~1s rise animation just happened
to finish around the same moment.

**Fix, two halves:**

- `mixin/EntityCitizenBabyMixin` — `@ModifyReturnValue` on `isBaby()`: on the
  CLIENT, when the cached field says "adult", fall back to the synced
  `DATA_IS_CHILD`. It can only ever turn adult into child, never the reverse, and
  the server is untouched (there both are written together by `setIsChild`, so
  they cannot disagree). Growing up stays safe — when a citizen matures BOTH go
  false.
- The send path now writes the CitizenData child flag **before** spawning the
  body (step 2c). MineColonies stamps a new body from the CitizenData in the same
  tick as the spawn (`addFreshEntity` → `registerWithColony` → `registerCivilian`
  → `setEntity` → `setCivilianData` → `initEntityValues` →
  `citizen.setIsChild(this.isChild())`), so the very first packet the client
  receives already describes a child. Doing it afterwards, as the original fix
  did, meant the body was briefly the wrong size on the server side too.

Ordinary MineColonies children get the same benefit — this is upstream behaviour,
not something our pipeline introduced.

### ALSO FIXED (found while tracing) — babies could never grow up

The send path only ever set the child flag ON (`if (goblin.isBaby()) setIsChild(true)`),
and only on the ENTITY. Two problems: a baby that grew up while it was out with
the player came back and was re-marked a child forever, and the ENTITY flag isn't
the durable one — MineColonies keeps `EntityCitizen.setIsChild` and
`CitizenData.setIsChild` completely independent, and only the latter survives a
body rebuild. Both are now written, in both directions.

### EXISTING SAVES

Phantoms already created by this bug are repaired with `/recoverorphans`:
`confirm` restores each one as a working colonist (they all have a snapshot), or
`purge` deletes it and frees the housing slot. The orphan scan was widened to
recognise a **displaced** identity — one whose mob UUID is now claimed by a
different identity — so it finds these even while the mob is still alive and
standing next to the player, instead of only after the body is gone.

---

## 2026-07-22 — Masterwork / Absolute Annihilator: on-hit engravings never trigger, and the right-click ability "doesn't scale with anything and always deals like 2 damage"

**Status:** RESOLVED (fix implemented 2026-07-22, 0.2.1) — awaiting the
reporter's confirmation. Two independent root causes, both verified against the
`tensura-neoforge-2.0.1.0.jar` bytecode + datapack.

### ROOT CAUSE 1 (engravings) — our weapons are in NO item tags

Every Tensura engraving declares `"supported_items": "#tensura:handheld_enchantable"`,
which resolves down to the VANILLA item tags (`#minecraft:swords`, `#minecraft:axes`,
`#minecraft:enchantable/*`). Tensura puts each of its own weapons into those tags
by datapack (`data/minecraft/tags/item/swords.json` in the Tensura jar lists
`#tensura:katanas`, `#tensura:short_swords`, …). We shipped **no item tags at all**
for the 12 Masterwork weapons or the Absolute Annihilator, so:

- `Enchantment.canEnchant(stack)` is FALSE for every engraving (and for every
  vanilla enchantment — no Sharpness, Unbreaking or Mending either);
- `EngravingHelper.getRandomEngraving()` filters the candidate list with exactly
  that check, so the EP-driven engraving grant in
  `DeathHandler.gearGetEP → EngravingHelper.grantRandomEngraving()` always came
  back empty. The weapon absorbed EP and levelled its damage, but **never gained
  a single engraving** at the 50k / 250k / 1M EP milestones the way a Tensura
  weapon does.

Engravings that were forced on regardless (the Annihilator's Holy Coat, applied
by our `gear_existence` entry, which calls `stack.enchant` directly and skips the
tag check) *did* fire on a normal left-click — engraving effects run from
`TensuraEnchantmentHelper.doAdditionalAfterAttack`, driven by Tensura's mixin on
`Player.attack`, which is item-agnostic. So the symptom was "the engravings I
expect never appear / never do anything", not "the hook is broken".

**Fix:** ship the item tags, matching each Masterwork weapon to the tags its
hihiirokane counterpart is in — `data/tensura/tags/item/{katanas, kodachis,
tachis, odachis, short_swords, long_swords, great_swords, spears, scythes,
sickles}.json` plus `data/minecraft/tags/item/{swords, axes}.json` for
`masterwork_sword`, `masterwork_axe` and `absolute_annihilator`.

### ROOT CAUSE 2 (ability damage) — the preceding melee swing eats the ability's damage

`LivingEntity.hurt` only lets the BIGGEST hit inside a 10-tick window through:

```java
if (this.invulnerableTime > 10.0F && !source.is(BYPASSES_COOLDOWN)) {
   if (amount <= this.lastHurt) return false;      // dropped entirely
   this.actuallyHurt(source, amount - this.lastHurt);
}
```

Players swing and then immediately right-click, so the ability's damage arrived
inside the swing's invulnerability window and was charged `amount - lastHurt`.
Both the ability and the swing scale off the same attack-damage attribute
(sweep = 0.6×, slice = 0.8×, and a melee swing = 1.0× × the attack-strength
charge), so the leftover is a small difference of two proportional numbers —
**it stays tiny no matter how strong the weapon gets, which is exactly "doesn't
scale with anything and always deals like 2 damage"**, and lands on 0 (no hit at
all) after a fully-charged swing. Tensura's own Battlewill arts manage
`invulnerableTime` explicitly for this reason.

Three smaller problems rode along:

- The abilities dealt damage straight through `hurt()`, skipping the on-hit
  pipeline Tensura's arts run afterwards, so **no engraving fired from an
  ability** even on a properly engraved weapon, and the weapon's own on-hit
  effect (Masterwork lifesteal / regeneration) never fired either.
- The magic slice, the Annihilator shockwave and the Drago Nova blast used an
  OWNERLESS `damageSources().magic()` — environmental damage as far as Tensura
  is concerned: no kill credit, no EP gain, no ally/subordinate checks, and no
  way for the wielder's magicule to push through a target's magic interference.
- The Annihilator's nova dealt a hardcoded 150 that never grew with the weapon.

**Fix:** new `WeaponAbilities` helper — clears the invulnerability frames before
an ability hit, runs Tensura's full on-hit pipeline (`hurtEnemy` +
`EnchantmentHelper.doPostAttackEffectsWithItemSource` +
`TensuraEnchantmentHelper.doAdditionalAfterDamage/AfterAttack`), and hands out
attacker-credited damage sources. Ability damage now also floors at the weapon's
own attack damage read off the stack, so it keeps scaling with the EP evolutions
even in the off-hand. The Annihilator's nova adds 4× the weapon's attack damage
on top of the base blast.

⚠ Note for follow-up: the PHYSICAL sweep uses `minecraft:player_attack`, which is
in `tensura:is_physical` — so against **spiritual** entities it still takes the
1% physical multiplier described in the 2026-07-10 Ifrit entry below. The MAGIC
slice (now `tensura:magic`) is unaffected by that and hits spirits for full.

---

## 2026-07-10 — A Hihiirokane sword does almost no damage to Ifrit (reported as "Ifrit won't take damage from a late-game weapon")

**Status:** NOT A BUG — WORKING AS DESIGNED (Tensura mechanic). Verified against
the `tensura-neoforge-2.0.1.0.jar` bytecode + datapack tags. **We are keeping
the mechanic** (decision 2026-07-10). Recorded here so it isn't re-triaged as a
bug; the war-boss *compounding* it (below) is the only part worth a future
balance pass. Player originally hit this on Ifrit-as-a-faction-boss (Leon's
anchor); the underlying cause is independent of the faction system.

### ROOT CAUSE (verified against the Tensura jar bytecode + tags)

Ifrit is a **spiritual** entity, and Tensura makes spiritual entities take only
**1% of ordinary physical-weapon damage**. A Hihiirokane sword is an ordinary
physical weapon, so it deals ~1% — its huge base damage doesn't matter because
the reduction is *multiplicative*.

The chain, each link confirmed:

1. **Ifrit is tagged spiritual.** `data/tensura/tags/entity_type/spiritual.json`
   lists `tensura:ifrit` (+ `ifrit_clone`). Its class extends
   `GreaterSpiritEntity`, which implements the `ISpiritual` interface.
2. **Spiritual entities gut physical-attack damage.** Damage path:
   `hurt()` → `getDamageReductionMultiplier()` →
   `GreaterSpiritEntity.getPhysicalAttackInput()` →
   `RaceUtils.getPhysicalAttackInputMultiplier()`. That method returns:
   - `1.0` (full) if the damage is **not** a physical attack — i.e. magic /
     elemental / spiritual / mental damage hurts Ifrit normally;
   - `1.0` if the **attacker** has Divine Ki Release, Anti-Skill active, or
     Haki Coat (amplifier ≥ 1);
   - `0.5` if the attacker has Haki Coat (amp 0), the Magic Aura effect, or Cook
     toggled;
   - **`0.01` otherwise** — the catch-all a plain weapon falls into.
   Note the multiplier keys off the **attacker's** aura/skill state, not the
   weapon.
3. **Every vanilla-style weapon hit is a "physical attack."**
   `data/tensura/tags/damage_type/is_physical.json` includes
   `minecraft:player_attack`, `mob_attack`, `arrow`, `trident`, `thrown`, plus
   Tensura's own kunai/spear/severer_blade/bullet.
4. **The Hihiirokane sword is a plain `SwordItem`.** Traced through
   `TensuraToolItems`: `hihiirokane_sword` / `_long_sword` / `_katana` /
   `_great_sword` / `_odachi` etc. are constructed as `SimpleSwordItem` /
   `SimpleLongSwordItem` / `SimpleKatanaItem` — vanilla `SwordItem` subclasses.
   None override `hurtEnemy`; none apply aura or convert the damage type. (Only
   IceBlade / SpiderDagger / CentipedeDagger override `hurtEnemy` in the whole
   mod — Hihiirokane is not among them.) Hihiirokane is just the top metal
   *tier* = big raw physical damage. The `*_inactive` item models are the
   sheathed/drawn cosmetic state, not an aura mode.

Result: `Hihiirokane hit → player_attack → isPhysicalAttack = true → attacker
has no aura/Haki/Ki → ×0.01 → Ifrit takes 1%.` Also for context, Ifrit is fully
**immune to fire** damage and reduces **heat** to 5% (`isInvulnerableTo` + the
heat branch of `getDamageReductionMultiplier`), and it **evaporates**
projectiles tagged `CAN_EVAPORATE`.

**Intended way to damage a spirit:** attack with magic / spiritual / elemental /
mental damage, OR give the *player* aura (Magic Aura effect, Haki Coat, Divine
Ki Release, or Anti-Skill). The weapon's metal tier is irrelevant to that gate.

### WHY OUR WAR SYSTEM MAKES IT WORSE (the only part worth a future balance pass)

When Ifrit is Leon's garrison/anchor boss (`RivalColonies.java`) we stack three
things on top of the 1%-physical floor:
- `buffDefender` → `multiplyAttribute(boss, MAX_HEALTH, …, statFactor)` (up to
  ~4×) plus `MAX_SPIRITUAL_HEALTH` scaling — the 1%-damage grind now runs
  against a much larger health pool;
- for `"leon"` we grant `FLAME_ATTACK_NULLIFICATION` + `HEAT_NULLIFICATION` +
  `SELF_REGENERATION` (RivalColonies.java ~1157–1163) — the regen heals back the
  trickle a physical attacker manages.

Net effect for a player with only physical gear: effectively unkillable.

### DECISION (2026-07-10) — keep the mechanic

Spiritual physical-immunity is core Tensura identity and we are keeping it as-is
(no vulnerability floor, no boss swap). Deferred / optional follow-ons (NOT
scheduled): ease the war-boss HP scaling and/or drop-or-cap the
`SELF_REGENERATION` grant on Leon's Ifrit so end-game players aren't hitting an
unwinnable wall, and signpost to players (wiki + maybe an in-game hint) that
spirit bosses need aura or magic rather than raw weapons. Until then this stays
recorded as "working as designed."

---

## 2026-07-10 — Tensura raids spawn ~10 monsters instantly INSIDE the colony (inside a house), leaving the player unable to respond

**Status:** RESOLVED (2026-07-10) — fix implemented in `TensuraRaids`
(`computeSpawnPos` fallback rework + barrier-footprint rejection). Awaiting
player confirmation. See "ROOT CAUSE" and "FIX (implemented)" below.

### ROOT CAUSE (verified against the minecolonies-1.1.1319 jar bytecode)

The spawn chokepoint (`TensuraRaids.computeSpawnPos`, used by both generic
raids and the Orc Disaster lore raid) tries MC's own
`IRaiderManager.calculateSpawnLocation()` first. Decompiling RaidManager shows
that method is genuinely perimeter-safe when it succeeds: it averages the
loaded buildings, picks a random direction ~500 blocks out, walks outward from
the edge-most building in 16-block steps, and only accepts a point ≥ 35 blocks
(more for guard towers / homes / town hall, scaled by level) from EVERY built
building. So MC's math can never produce an in-colony spawn.

**But it returns `null` in several real situations** — no loaded buildings, no
"best building" toward the chosen direction, all 8 direction attempts failing
the solid-ground search — and our fallbacks were:
1. `EntityUtils.getSpawnPoint(level, colony.getCenter().offset(32, 0, 32))` —
   32 blocks diagonally from the TOWN HALL, i.e. deep inside the built-up area;
   `getSpawnPoint` then hunts for a free air-above-solid spot near that point,
   which can be a HOUSE INTERIOR. That is literally the reported symptom.
2. Heightmap at the same center+32 offset (roof of the built-up area).

The whole wave (up to 14 mobs) is placed in one tick, scattered only ±4 blocks
around that single point — hence "10 monsters instantly inside a house."

**Barrier interaction (the report's related question):** confirmed — raid mobs
spawn via direct `EntityType.create` + `addFreshEntity`, which never posts
`MobSpawnEvent.PositionCheck`, so the barrier's hostile-spawn prevention never
sees them. Worse, the barrier field only blocks hostiles from ENTERING (T1
traps inside-mobs), so a raider spawned inside a fueled barrier would be
trapped in there with the citizens.

### FIX (implemented 2026-07-10)

All in `TensuraRaids` (single chokepoint — covers generic + lore raids +
`/tensuraraid`):

- **The center-offset fallbacks are gone.** `computeSpawnPos` still prefers
  MC's `calculateSpawnLocation()`, but on null/throw it now calls a new
  `computeEdgeSpawnPos`: march outward from the colony center in one-chunk
  steps while `isCoordInColony` holds (the same claimed-border march
  `SubordinatePatrol` uses for its outskirts ring), then place the spawn
  `EDGE_SPAWN_MARGIN` (16) blocks PAST the claimed border, snapped to the
  surface heightmap. Claimed chunks always extend beyond the buildings, so a
  border-plus-margin point can never be inside the built-up area. 8 random
  bearings are tried; water-covered candidates are kept only as a last resort.
- **Fueled-barrier footprints are rejected everywhere:** MC's own result is
  discarded (→ edge fallback) if it lands inside a fueled barrier's square
  (`isInsideFueledBarrier`); edge-fallback bearings inside a barrier are
  skipped; and the per-raider ±4 scatter in `spawnRaider` re-checks and falls
  back to the validated wave point. Raiders now always materialize OUTSIDE the
  shield and must break through like any other attacker.
- Staggering the wave over time (the report's secondary QoL note) was NOT
  done — with the wave at the perimeter, all-at-once matches MineColonies'
  own raid behaviour. Recorded in docs/future-ideas.md.
- Compiles clean (`./gradlew compileJava`). CHANGELOG updated ("Raid monsters
  no longer appear in the middle of your colony").

**Report (as phrased):** "tensura raids shouldn't spawn 10 monsters instantly
inside a house, kinda cant actually do anything about this if they just spawn
inside my colony."

**What this means:** when a Tensura raid triggers, the whole wave appears at once
INSIDE the colony's built-up area (the player describes them materializing inside
a house / within the colony), rather than spawning at the perimeter and
approaching. Because they're already inside, the player has no time or space to
react — no chance to meet them at a wall, use the barrier, or organize a defense.

**Leading hypothesis (to verify):** the raid wave-spawn placement picks positions
inside the colony claim (or near the town hall / player) instead of at the colony
EDGE. Vanilla MineColonies raids spawn raiders at the colony border and path in;
our `TensuraRaids` wave spawn may be choosing spawn points too close to the
center, or using a surface/heightmap lookup around the colony core rather than the
outer ring. Also worth confirming whether the whole wave spawns in ONE tick (the
"10 monsters instantly" part) vs. staggered.

**Where to look:** `TensuraRaids` (the wave-spawn driver — spawn-point selection
and how many spawn per tick), cross-reference `TensuraRaidEvent`. Compare against
how MineColonies places its own raiders (perimeter spawn + path-in) and how
`RivalColonies` garrison spawns are positioned. The perimeter-vs-center placement
is the core fix; staggering the spawn over time is a secondary quality-of-life
improvement.

**Related mitigation (see user-suggestions.md 2026-07-10 #3):** the player also
asked whether the magicule barrier could PREVENT hostile/Tensura mobs from
spawning inside its field. CLAUDE.md documents "hostile-spawn prevention inside
fueled barriers" as already shipped — if that works and covers Tensura mob types,
a barrier would stop in-colony raid spawns. Worth verifying that path as part of
this fix (does raid-wave spawning bypass the barrier's spawn suppression?).

---

## 2026-07-04 — Subordinate stays aggressive after leaving the Patrol command (attacks all mobs, hostile or not)

**Status:** RESOLVED (2026-07-04) — fix implemented and **confirmed working by
the reporter (2026-07-04)**.

**Report (as phrased):** "once I give a command to the wolf past the patrol
colony outskirts (so when it loops back to the first command (follow)), the
direwolf goes off and attacks all mobs around me — hostile or not."

**Relation to the "defenders kill passive mobs" bug:** SAME CLASS (an aggressive
stance not being cleaned up when a special mode ends), DIFFERENT code path. The
earlier fixes covered the raid defense-swap; this one is the PATROL command
cycle. Reported as "still happening" because it's a sibling leak the defense-swap
fix didn't touch.

### ROOT CAUSE (verified by decompiling Tensura's SubordinateHelper)

Tensura subordinates have TWO independent stance axes:
- **command** — follow / wander / stay (`setFollow`/`setWander`/`setStay`; these
  set `setWandering`/`setOrderedToSit`, NOT behaviour).
- **behaviour** — neutral(0) / passive(1) / aggressive(2) / protect
  (`setNeutral`/`setPassive`/`setAggressive`/`setProtect`; these set
  `setBehaviour(int)`, NOT the command).

`SubordinatePatrol.beginPatrol` sets BOTH: `setWander` (command) + **`setAggressive`
(behaviour = 2)**. While patrolling, the aggressive stance is safe because the
`PATROL_ORDER` veto (`isPatrolTargetAllowed`) restricts targets to genuine
hostiles inside the colony. But BOTH exit paths only reset the COMMAND axis and
left behaviour stuck at aggressive, while ALSO removing the `PATROL_ORDER` (so
the hostile-only veto stops applying):
- `exitPatrolToFollow` (the PATROL → FOLLOW cycle edge) called only `setFollow`.
- the `onEntityTick` auto-cancel (native command-cycle moved it off wander)
  called only `removeData(PATROL_ORDER)`.

Result after leaving patrol: aggressive stance (`getBehaviour()==2`, which makes
Tensura's `shouldTarget` return true for ANY attackable non-ally) + no veto =
the creature attacks every nearby mob, peaceful included. Exactly the report.

### FIX (implemented 2026-07-04)

Both exit paths now also reset the behaviour axis with
`SubordinateHelper.setNeutral(mob)` (sets behaviour = 0 AND drops the current
target), mirroring the `setNeutral`-on-swap-back hygiene already used for the
raid defender:
- `SubordinatePatrol.exitPatrolToFollow` — `setNeutral` before `setFollow`.
- `SubordinatePatrol.onEntityTick` auto-cancel — `setNeutral` after removing the
  order.

Net: leaving patrol returns the creature to neutral (follows, only retaliates).
Compiles. CHANGELOG updated ("Subordinates no longer stay in 'attack everything'
mode after patrolling"). Note: a player who had DELIBERATELY set the creature
aggressive before patrolling will find it neutral afterward — acceptable trade
vs. the bug; we don't snapshot/restore the pre-patrol behaviour.

---

## 2026-07-04 — In-game config menu doesn't apply the faction-system toggle; had to edit the config file directly

**Status:** RESOLVED (2026-07-04) — moved `enableFactionSystem` to a per-world
SERVER config (`ModConfig.Type.SERVER`) marked `.worldRestart()`. See "FIX
(implemented)" below. **Confirmed working in-game by the reporter (2026-07-04).**

### FIX (implemented 2026-07-04)

Per the developer's decision, `enableFactionSystem` was moved out of the COMMON
spec into a new per-world **SERVER** spec and marked `worldRestart()`:
- `Config.java` — new `SERVER_BUILDER` / `SERVER_SPEC`; `ENABLE_FACTION_SYSTEM`
  (and `ENABLE_DEFENSE_SWAP`, the other in-game-facing world toggle) now built
  from `SERVER_BUILDER` with `.worldRestart()` before `.define(...)`. The
  remaining toggles (assassins, aggression, rival/Drago, MDK placeholders) stay
  in the COMMON spec.
- `ExampleMod.java` (~line 338) — registers the second spec:
  `modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC)`.
- `en_us.json` — added the `...section.tensura_minecolonies.server.toml`
  title keys so the new per-world section shows a friendly name. The per-value
  key (`...configuration.enableFactionSystem`) is unchanged and still resolves.
- All reads still go through `WorldReputationManager.isFactionSystemEnabled()`,
  which already catches the "config not loaded" case (SERVER configs aren't
  loaded at the main menu) and returns false.

Effect: the setting now lives in `saves/<world>/serverconfig/tensura_minecolonies-server.toml`,
is reloaded on every world load, and the in-game menu edit takes effect on world
re-entry (the screen prompts for a reload because of `worldRestart()`). Known
trade-offs, called out in the CHANGELOG: the value is per-world now (not global),
so existing setups see it back at the default (off); and on a dedicated server
the client menu shows it read-only (edit the world's serverconfig instead).
Compiles clean (`./gradlew compileJava`).

### ROOT CAUSE (verified against the neoforge-21.1.233 jar bytecode)

The mod's own code is fine — every faction gate reads the value LIVE through
`WorldReputationManager.isFactionSystemEnabled()` → `Config.ENABLE_FACTION_SYSTEM.get()`
(no mod-side field caches it). The break is entirely in how NeoForge's built-in
config screen propagates an edit to a running session:

1. `enableFactionSystem` is registered as a **COMMON** config
   ([ExampleMod.java:338](../src/main/java/com/example/examplemod/ExampleMod.java) —
   `registerConfig(ModConfig.Type.COMMON, Config.SPEC)`), default
   **RestartType.NONE**. A COMMON config lives in the GLOBAL `config/` folder
   (`tensura_minecolonies-common.toml` — the "tensuraminecolonie common 1" file
   in the report) and is loaded ONCE at game launch, not per-world.
2. `ModConfigSpec.ConfigValue.get()` returns a **cached** field (`cachedValue`);
   it's only refreshed when `clearCache()` runs, which is driven by
   `ModConfigSpec.afterReload()` → `resetCaches(NONE)` — i.e. only on a genuine
   **config reload**.
3. The in-game screen (`ConfigurationScreen`, registered in
   [ExampleModClient.java](../src/main/java/com/example/examplemod/ExampleModClient.java))
   writes the edit to the raw config tree + disk and, on close, calls
   `ModConfigSpec.save()` — which ONLY writes the file. It does **not** call
   `afterReload()`/`resetCaches()`, and the normal edit path does **not** call
   `ConfigValue.set()` (only the "reset to default" button does). So the live
   `cachedValue` the mod reads stays **stale** for the rest of the session.

That is the exact symptom: the in-game menu writes the file, but the running
code keeps returning the old cached value → "nothing changes." Editing the file
on disk and reloading the world forces a full config reload → `afterReload()` →
cache cleared → new value applied. Hence "only editing the file worked."

### RECOMMENDED FIX (pending decision)

The value is a world-gameplay master switch, so the cleanest correct home is a
**SERVER config** (`ModConfig.Type.SERVER`): stored per-world in
`saves/<world>/serverconfig/`, reliably (re)loaded on every world load, and the
config screen applies/syncs SERVER configs with proper reload semantics.
Trade-off: the setting moves from the global `config/` file to a per-world file
and resets to its default (false) for existing setups.

Lighter-touch alternative: keep it COMMON but mark it `.worldRestart()` so the
screen explicitly prompts the player to reload the world to apply — kills the
silent no-op, but cache-clear semantics for a COMMON + worldRestart value on
world re-entry are murkier than the SERVER path.

**Where to look:** `Config.java` (`ENABLE_FACTION_SYSTEM` + the `registerConfig`
type at `ExampleMod.java:338`). If moved to SERVER, the sibling gameplay toggles
(`enableAssassins`, `citizenAggression`, rival/Drago knobs) are candidates to
move with it for consistency.

### Original triage notes (kept for reference)

**Report (as phrased):** A user had to go into "tensuraminecolonie common 1" to
change the config. Changing it in-game via the configs menu did not change
anything.

**What this means:** the player edited the config **file** on disk (the
`tensura_minecolonies-common.toml` common config, shown in the report as
"tensuraminecolonie common 1") to change a setting — most likely
`enableFactionSystem` (ships **default FALSE**), since that's the toggle a player
would need to flip to turn the faction system on. Changing the same setting
through the **in-game NeoForge config menu** had no effect. So the in-game
config screen either isn't writing the value, isn't reloading it, or the running
code is reading a cached/old value that only the on-disk edit updates.

**Leading hypothesis (to verify):** the mod reads the config value in a way that
doesn't pick up the in-game menu's live change — e.g. the value is cached once at
load, or the config-reload event isn't being handled, so only a file edit (which
forces a reload on next world load) takes effect. Cross-reference the known
config-display debt: config lang keys were on the stale `examplemod.*` prefix
(fixed by re-prefixing to `tensura_minecolonies.*`) — worth confirming the
faction toggle is actually bound to the live config value the code reads, not a
placeholder. `enableFactionSystem` is documented as the single source of truth
(no gamerule/command), so if the menu write doesn't stick, the file is the only
way to change it — which matches the report.

**Where to look:** `Config.java` (the NeoForge config spec + how
`enableFactionSystem` is registered and read), any config-caching or
`ModConfigEvent` (reloading) handling, and confirm the in-game config screen is
editing the same key the runtime reads. Also verify whether the setting requires
a world reload to take effect and, if so, whether that's surfaced to the player.

---

## 2026-07-04 — Subordinates attack the magicule barrier on their own

**Status:** RESOLVED (2026-07-04) — fix shipped in `BarrierBlockEntity`. The
barrier now excludes the owner's own tamed creatures from the hostile set. See
"ROOT CAUSE" and "FIX (implemented)" below. **Confirmed working by the reporter
(2026-07-04).**

### ROOT CAUSE (verified against the Tensura jar bytecode)

The barrier's hostile classifier `BarrierBlockEntity.isBlockableHostile(Entity)`
decided "is this a raider?" purely from the entity's **type tag**
(`tensura:hostile_monster`) — with no check for whether the mob is TAMED and
owned. A dire wolf's entity type IS in that tag, so a **tamed** dire wolf at the
player's side was classified hostile exactly like a wild one. The per-tick field
sweep (`serverTick`) then, for that "hostile":
- pushed it back off the shell (`pushFromShell`),
- drove the **swing-at-block** animation (`mob.swing(...)` + crit particles once
  a second) — this is what reads as "attacking the barrier,"
- and drained fuel / chipped the pressed section.

So the pet wasn't *choosing* to attack — the barrier code was making it swing.

**Not dire-wolf-specific.** Class chain (confirmed by decompiling
`tensura-neoforge-2.0.1.0.jar`): `DirewolfEntity` → `TensuraMountEntity` →
`TensuraRideableEntity` → `TensuraTamableEntity` → vanilla `TamableAnimal`
(implements `OwnableEntity`). The dire wolf is **not** an `ISubordinate` (that
interface is only the named goblins/orcs), so an `ISubordinate`-based exclusion
would have missed it. Any tamed Tensura creature whose type is in the
hostile-monster tag would show the same behaviour.

### FIX (implemented 2026-07-04)

`BarrierBlockEntity.isBlockableHostile` now returns `false` up front for any
`net.minecraft.world.entity.OwnableEntity` with a **non-null owner UUID** (i.e.
a tamed, owned creature) — the general signal that covers BOTH named
`ISubordinate` subordinates and tamed mounts/beasts, since they all extend
`TamableAnimal`/`OwnableEntity`. A **wild** (untamed) mob has a null owner and is
still a valid target, so the barrier keeps stopping genuine threats. The
exclusion is a single chokepoint change, so it fixes the push, the swing
animation, AND the drain at once (all three route through
`isBlockableHostile`), and it covers the projectile-owner path too. Raid mobs /
MineColonies raiders are never tamed, so it can never accidentally spare a real
raider. Compiles clean (`./gradlew compileJava`).

**Report (as phrased):** "It seems that subordinates will just attack the barrier
on their own, happened with my dire wolf."

**What this means:** the player's OWN Tensura subordinate (a dire wolf in this
case) targets and attacks the **magicule barrier** (the Barrier Core block /
barrier field) unprovoked — i.e. a friendly, owner-controlled mob is treating the
player's own barrier as something to hit. This should not happen: the barrier's
pushback/drain is meant for `barrier_blocked`-tagged hostiles and raiders, not
the owner's own subordinates.

**Leading hypothesis (to verify):** either (a) the subordinate's targeting/AI is
picking the barrier block/field as an attack target (block-breaking or
melee-on-field behavior), or (b) the barrier field's pushback is shoving the
subordinate and the mob is "retaliating." Possibly related to the known
subordinate-targeting issues (see docs/subordinate-citizen-targeting.md) —
subordinate AI selecting things it shouldn't. Worth confirming whether the mob is
attacking the barrier CORE BLOCK specifically, or reacting to the barrier FIELD
(the square wall/roof render + pushback layer), and whether it only happens when
the field is active.

**Where to look:** `BarrierBlockEntity` (the field driver — pushback on
`barrier_blocked`-tagged hostiles + raiders, contact drain; confirm owner
subordinates are excluded from targeting/pushback), `BarrierBlock`, and the
subordinate targeting path (cross-reference docs/subordinate-citizen-targeting.md
for the recommended `LIVING_CHANGE_TARGET` veto approach — a similar veto may be
needed to stop subordinates targeting the owner's barrier).

---

## 2026-07-04 — Named creature "goes crazy" and attacks everything when assigned to a job

**Status:** RESOLVED (2026-07-04) — three fixes shipped across the sibling
aggressive-stance leaks: the `enableDefenseSwap` config toggle (full opt-out),
the `COLONY_DEFENDER` targeting veto + colony tether that keeps transformed
defenders on genuine hostiles, and the PATROL-command stance reset (its own entry
above, **player-confirmed working 2026-07-04**). The reported "ran off and killed
all passive mobs / kills everything" behaviour is addressed by the targeting veto
regardless of whether a raid was active; the patrol path — the one the reporter
actually reproduced with the dire wolf — is confirmed fixed. See "CONFIG TOGGLE
SHIPPED" and "TARGETING FIX SHIPPED" below. **Confirmed working by the reporter
(2026-07-04).**

**Report (as phrased):** "the named creature going crazy and killing
EVERYTHING in sight when assigned to a job in minecolonies."

### INVESTIGATION (2026-07-04) — traced the code paths

**Key structural fact that rules out the obvious culprits:** while a named
race-citizen is `IN_COLONY` with a job, there is **NO live Tensura mob in the
world** for it — its body is a plain MineColonies `AbstractEntityCitizen`
(rendered as a goblin/orc, but MC-AI-driven), and `mobEntityUUID` is null. Job
assignment itself hooks NOTHING in our code except `tickCitizenProfessions`
(DWARF-only, purely cosmetic profession-clothes; sets a villager profession for
rendering, never touches trades/targeting/AI). So the citizen body cannot
"go berserk" on its own — there is no Tensura combat AI on it. Confirmed there
is no `setJob`/`getJob` interception and no job-assignment event hook anywhere.

**Therefore the berserk body must be the Tensura SUBORDINATE form — which only
appears via `ColonyThreatResponse`'s defense-swap, and that only runs during a
raid** (`colony.getRaiderManager().isRaided()`, per-second tick). The chain:

1. A raid starts. `ColonyThreatResponse.tick` swaps every **non-guard**
   `IN_COLONY` Tensura citizen with **EP ≥ `FORM_SWAP_EP` (10,000)** into its
   full Tensura subordinate body ([ExampleMod.defenseSwapToSubordinate](../src/main/java/com/example/examplemod/ExampleMod.java:6890)).
   Guards are exempt (`shouldDefend` returns false for `getJob().isGuard()`).
   → This is exactly why the report ties it to "assigned to a JOB": a **named,
   strong (≥10k EP) creature given a NON-guard job** is precisely the set that
   place-swaps into a fighting Tensura body. A guard would be left alone.
2. The swapped body is granted the **"Sentient" skill** (Nightmare's Tensura
   Utils, [grantSentient](../src/main/java/com/example/examplemod/ExampleMod.java:7377)),
   which **autonomously drives the mob to cast its learned ACTIVE skills in
   combat**. A named creature's powerful, often **AoE** skills then fire at
   whatever it's fighting — and AoE collateral hits nearby citizens, animals,
   etc. regardless of lock-on target. That reads as "killing everything in
   sight."

**Two ways this produces the reported symptom:**

- **(A) In-the-moment, during the raid** — the defender's autonomous AoE casting
  devastates the area around it. The lock-on **veto** ([onSubordinateChangeTarget](../src/main/java/com/example/examplemod/ExampleMod.java:376))
  spares own-colony citizens and friendly races from being *targeted*, BUT it
  does **not** stop AoE splash damage, and it does **not** stop the defender
  locking onto **passive animals** (only citizens/friendly-races/patrol targets
  are vetoed). So "everything" = raiders + animals + AoE-caught citizens.
- **(B) PERSISTENT leak (worse, and NOT raid-gated at the moment of berserk)** —
  on swap-back, `defenseSwapToColony` calls `removeSentient` BEFORE snapshotting
  the body, precisely so Sentient doesn't persist ([ExampleMod.java:6948](../src/main/java/com/example/examplemod/ExampleMod.java:6948)).
  But `removeSentient` **swallows any exception** ([ExampleMod.java:7399](../src/main/java/com/example/examplemod/ExampleMod.java:7399)),
  and the swap-back **early-returns without stripping Sentient** if the body is
  unloaded / town-hall chunk is gone when the raid ends ([ExampleMod.java:6945](../src/main/java/com/example/examplemod/ExampleMod.java:6945)).
  If Sentient is still on the body when its snapshot is next captured, the
  identity **permanently carries Sentient**, so **every future normal summon of
  that named creature is an autonomous berserk caster** at the player's side —
  no raid needed. The code comment at [ExampleMod.java:7395](../src/main/java/com/example/examplemod/ExampleMod.java:7395)
  explicitly names this failure mode as the thing `removeSentient` exists to
  prevent.

**Secondary possibility to keep on the table (needs a check):** if the player
assigned the creature to a **MineColonies GUARD job**, the citizen body becomes
an MC guard that attacks everything in MC's monster set (`MobCategory.MONSTER`
or `minecolonies:hostile`). If the colony's own wild race-mobs (unnamed
goblins/orcs the colony spawns) are `MONSTER` category, a guard race-citizen
could attack them — looks like "kills everything." Separately, the
`citizenAggression` config ([TensuraBehaviourHelperMixin](../src/main/java/com/example/examplemod/mixin/TensuraBehaviourHelperMixin.java),
default OFF) makes WILD hostile Tensura mobs swarm citizens if the player raised
it to MEDIUM/HIGH — that's mobs attacking the citizen, not the reverse, but
could be conflated. Both are worth confirming but are less well-supported than
the defense-swap chain.

**Most likely conclusion:** this is the **threat-response defense-swap +
Sentient** behavior (path A during a raid, and/or path B as a persistent leak),
NOT a job-assignment bug per se — job assignment is just the gate that makes a
strong named creature *eligible* to swap (non-guard) and is when the player
noticed it. The single fact that confirms/refutes this: **was a raid happening?**
(If the reporter says "no raid, ever," pivot to the guard-job / config
secondary paths.)

**Recommended fixes (once confirmed):**
- Make `removeSentient` robust: strip Sentient on the body the instant a raid
  ends AND on reload reconciliation, not only inside the swap-back success path;
  and re-strip defensively before any snapshot capture. Consider verifying the
  skill is actually gone (re-read) rather than swallowing failure silently.
- Constrain the defender's targeting/AoE: extend the veto so a `COLONY_DEFENDER`
  body cannot lock onto non-raiders (passive animals, non-`RAID_TAG` mobs), and
  consider gating/suppressing AoE-heavy autonomous casts near friendly citizens
  (or accept collateral as a known raid cost — design call).
- Lower-effort mitigation: gate the whole defense-swap behind a config toggle so
  players who dislike it can disable the swap entirely.

### FOLLOW-UP (2026-07-04) — new detail + config toggle shipped + targeting confirmed

**New detail from the reporter:** "all I did was select them as a worker
(builder) and my Goblin ran off and killed ALL OF THE passive mobs in the area."
So the aggressor is a GOBLIN in its **Tensura monster form** (not the citizen
body), it hunted **passive animals** (pigs/cows), and it "ran off" (chased prey
away from the colony). Killing *passive* mobs is the signature of a Tensura
subordinate in **aggressive stance**, NOT of raid-target steering (which only
points the body at raiders).

**CONFIG TOGGLE SHIPPED (this is the first ask, done):** the whole defense
form-swap is now behind `enableDefenseSwap` (in-game name **"Citizens Transform
to Defend Raids"**, default true). When off, no citizen ever transforms to
fight, and anyone currently transformed reverts on the next tick.
- `Config.ENABLE_DEFENSE_SWAP` / `Config.enableDefenseSwap()`
  ([Config.java](../src/main/java/com/example/examplemod/Config.java)).
- `ColonyThreatResponse.tick` gates swap-IN on it (`raided && swapEnabled`); the
  swap-BACK path runs when disabled so flipping it off cleanly retracts active
  defenders ([ColonyThreatResponse.java:97](../src/main/java/com/example/examplemod/ColonyThreatResponse.java:97)).
- Lang entry + CHANGELOG updated. Compiles.

**TARGETING INVESTIGATION — does the swapped body target all mobs or just
hostiles? Answer: effectively ALL, and here's exactly why (verified by
decompiling Tensura + nightmareutils):**

1. **Our steering only points it at hostiles.** `steerDefender` sets the
   defender's target to the nearest `RAID_TAG` raider and nothing else. So the
   *intended* target set is hostile raiders only.
2. **But the body's OWN brain acquires its own targets** — our steer only
   *overrides* the target when a raider is in range. With no raider nearby (or
   between the per-second steers) the body free-hunts on its native Tensura AI.
3. **The "Sentient" skill does NOT acquire targets or set aggression.** Decompiled
   `SentientSkillService` only *toggles the mob's learned combat skills on* in
   waves (`maybeToggleCombatSkillsOn`); target selection is 100% the mob's brain.
   So Sentient makes it *cast*, the brain decides *at whom*.
4. **Tensura's `ISubordinate.shouldTarget(mob, target, prey)` (decompiled
   bytecode) has an aggressive-stance short-circuit:** when
   `getBehaviour() == 2` (AGGRESSIVE) it `return true` for **any** target that
   passes `canAttackDefault` + `Mob.canAttack` and isn't `owner.isAlliedTo(...)`.
   The player is NOT allied to wild pigs/cows, so **in aggressive stance the body
   targets every passive animal in range** — the prey predicate (which for
   goblins is hard-`false`) is never consulted. This is precisely the reported
   "ran off and killed all passive mobs."
5. **Our anti-friendly-fire veto does NOT cover animals.**
   `onSubordinateChangeTarget` only vetoes own-colony citizens + friendly Tensura
   races (goblin/lizardman/allied-orc), and hostile-only restriction applies to
   `PATROL_ORDER` bodies **only**. A defender is not patrol-tagged, so **passive
   animals pass the veto freely.**

**So the chain is: swapped body ends up in aggressive stance → its brain targets
ALL attackable non-ally entities (animals included) → the veto lets animals
through → Sentient makes it cast its (often AoE) skills → "kills everything."**
The one unconfirmed link is WHY the body is in aggressive stance
(`getBehaviour()==2`): our code never sets it, so it comes from the mob's
reconstructed snapshot NBT (the stance it had when last a subordinate) or the
GoblinEntity default. Worth confirming by logging `getBehaviour()` right after a
defense swap. (Whether a raid was actually active is still unconfirmed — but the
config toggle sidesteps it entirely.)

### TARGETING FIX SHIPPED (2026-07-04)

Implemented the veto-based fix (the reporter asked that defenders still fight
**hostiles in general**, not only the raid party — so the approach is "keep it
aggressive, filter non-hostiles" rather than "de-aggro it"):

- **`SubordinatePatrol.isGenuineHostile(Mob, LivingEntity)`** — new public
  no-tether wrapper over the existing `isHostileThreat` (always-hostile
  `tensura:hostile_monster` tag, or anything currently attacking the mob / a
  citizen / an ally).
- **`SubordinatePatrol.isDefenderTargetAllowed(Mob, LivingEntity, IColony)`** —
  new public tethered variant: `isGenuineHostile` AND within the defended
  colony's area (`isWithinColony` + `TARGET_AREA_BUFFER`, the SAME tether the
  patrol uses). Drops the tether only if the colony can't be resolved.
- **`ExampleMod.onSubordinateChangeTarget`** — new gate (0), checked BEFORE owner
  resolution so it holds even if the reconstructed body's owner can't be
  resolved: a `COLONY_DEFENDER`-tagged body may target ONLY (a) a `RAID_TAG`
  raider — always allowed, killed wherever it is — or (b) an
  `isDefenderTargetAllowed` hostile (genuine hostile AND tethered to the colony,
  resolved from the `ColonyDefenderTag.colonyId`). Everything else (passive
  animals, idle neutrals, hostiles lured far off) is vetoed (`interruptFalse`).
  Allowed targets fall through to the existing citizen / friendly-race vetoes.
- **`ExampleMod.defenseSwapToSubordinate`** — the swapped body is now explicitly
  put in the aggressive stance (`SubordinateHelper.setAggressive`) so it
  proactively engages ALL nearby hostiles (raiders + other hostile mobs), not
  just the one raider `steerDefender` nudges it toward. The gate above keeps that
  aggression pointed only at real threats.
- **`ExampleMod.defenseSwapToColony`** — mirrors the `removeSentient` hygiene:
  resets the stance to neutral (`SubordinateHelper.setNeutral`) BEFORE the
  swap-back snapshot is captured, so the forced defense-only aggression never
  leaks into a later player-summoned subordinate.
- Compiles. CHANGELOG updated (player-facing "Defending citizens no longer
  slaughter your livestock" + the toggle note).

Net effect: transformed defenders fight the raid AND any other genuine hostiles
near the colony, staying tethered to the claim rather than chasing a stray mob
off across the map — and the "ran off and killed all passive mobs" behaviour is
gone. The config toggle remains as the full opt-out.

**Why this needs clarification first:** as phrased, this looks DIFFERENT from
the already-documented assist-attack bug and could be one of several distinct
code paths. Don't assume it's the known issue.
- **Documented bug** (docs/subordinate-citizen-targeting.md): the *player's
  subordinate* (a mob at the player's side) assist-attacks colony citizens when
  the player hits one. Aggressor = a subordinate mob; trigger = the player
  swinging.
- **This report:** a *named creature assigned a job* — i.e. it's now a CITIZEN
  in the colony — turns hostile and attacks "everything." Aggressor appears to
  be the in-colony citizen body itself; trigger is job assignment. That's the
  race-citizen pipeline, not the subordinate side.
- Also worth ruling out: `ColonyThreatResponse`'s defense-swap (during a raid,
  Tensura citizens ≥10k EP place-swap to their subordinate body to FIGHT) —
  could look like "goes crazy" if it fires unexpectedly.

**Clarifying questions to ask the reporter:**

*Identity / setup*
1. Which race is the creature — goblin, orc, lizardman, dwarf?
2. How did it become a citizen — named then sent via the roster (G), spawned by
   the colony's chosen race, or arrived as an envoy?
3. What job/hut was it assigned to? Any job, or one specific profession?

*The trigger*
4. Hostile the INSTANT the job is assigned, or only later (nightfall, raid,
   being hit)?
5. Is a raid or the defense-swap active at the time?
6. Did the player hit anything first, or was it unprovoked? (This is the key
   separator from the documented assist-attack bug.)

*What "everything" means*
7. What is it actually attacking — other citizens, the player, passive animals,
   hostile mobs only?
8. Is it the citizen body attacking, or did it visibly swap into its wild
   Tensura mob form first?

*Reproduction / environment*
9. Reliable repro steps?
10. Mod versions (esp. MineColonies — 1.1.1319 vs 1.1.1340 has come up before),
    any other targeting/AI mods, and a `latest.log` around the incident.

**Most important:** #4 + #6. "Unprovoked, immediately on job assignment" would
be a genuinely NEW bug in the citizen pipeline; "after I hit something" would
just be the already-diagnosed assist-attack issue surfacing on a citizen-form
body (see docs/subordinate-citizen-targeting.md, Option (b) fix).

---

## 2026-06-30 — [HIGH PRIORITY] Auto-generated rival cities spawn below bedrock; citizens spawn above the top bedrock layer

**Status:** RESOLVED (2026-07-04) — fix written and **player-confirmed working**.
Settlement generation is now gated to the Overworld (`RivalColonies.isOverworld`),
and buildings can no longer anchor on a bedrock layer. See "ROOT CAUSE" and
"FIX (implemented)" below.

### ROOT CAUSE (verified against the code)

Every Y-position in the faction generator came from an open-sky Overworld
surface lookup, and there was **no dimension gate** — `RivalColonies.tick`
loops over `server.getAllLevels()` and ran generation in the Nether/End too:
- **Buildings** → `groundSurfaceY()` starts at `getHeight(WORLD_SURFACE)` and
  scans DOWN for the first solid block.
- **Boss + garrison + citizens** → `getHeightmapPos(WORLD_SURFACE)`.

In the Nether (a roofed dimension), `WORLD_SURFACE` resolves to the top of the
**bedrock ceiling** (~Y 128), not the playable floor. So `groundSurfaceY`
scanned down from the roof and the first solid block it hit was the ceiling
bedrock → buildings jammed into the roof ("below the bedrock"); and
`getHeightmapPos(WORLD_SURFACE)` returned roof-top → boss/garrison/citizens
spawned above it ("above the top bedrock layer"). The two symptoms land at
opposite extremes because the two code paths resolve Y differently. The
worldgen faction-anchor structures themselves only target Overworld biomes
(plains/taiga/savanna/jungle…), so the Nether path could only be reached via
the runtime scan + the `/rivalcolony spawn` debug command running in the wrong
dimension.

### FIX (implemented 2026-07-04)

- `RivalColonies.isOverworld(level)` = `level.dimension().equals(Level.OVERWORLD)`.
- Gated at three chokepoints: `generateColony` (covers ALL mode + retries +
  debug colony spawn) returns null with a warn log; the `tick` generation
  section skips `tickDwarvenVillages` + `tickWorldgenSettlements` in non-
  Overworld dimensions; `debugSpawn` returns a clear player-facing message.
- Defensive: `isGroundSurface` now excludes `Blocks.BEDROCK`, so a building can
  never anchor on the world-floor / ceiling bedrock layer even if a surface
  scan falls through to it (hardens the Overworld "below bedrock" symptom
  whatever its exact trigger).
- (The Overworld half of the report is expected to be resolved by the bedrock
  exclusion but should be reconfirmed in-game — the Nether half is the
  definitively reproduced mechanism.)

**Report (as phrased):** "When generating automatic cities, they are being
created below the bedrock layer. The same issue also occurs in the Nether, where
the cities generate below the bedrock as well. Additionally, the citizens are
spawning above the upper bedrock layer."

**What this means:** the rival-colony / faction settlement generator
(`RivalColonies` + `SettlementSavedData`, natural-generation pass) is placing
themed faux-towns at the wrong Y level:
- **Overworld + Nether:** the town structure is placed BELOW the bedrock floor
  instead of on the surface — the whole settlement ends up buried under / beneath
  bedrock.
- **Citizens/garrison** appear to be placed ABOVE the top bedrock layer (the
  Nether has a bedrock ceiling; the overworld world-height cap behaves like one)
  — i.e. the entity spawn Y is being derived differently from (and inconsistent
  with) the structure Y, so mobs land at the roof while the buildings land at the
  floor.

**Leading hypothesis (to verify):** the surface/anchor Y-height lookup used when
placing a settlement isn't dimension-aware. Likely using a heightmap /
`getHeight` result that returns an unexpected value in the Nether (roofed
dimension — the motion-blocking heightmap hits the bedrock ceiling), or a
hardcoded / world-gen-derived Y that doesn't match the actual buildable surface.
The structure-placement Y and the citizen/garrison-spawn Y are computed by
different code paths, which is why they land at opposite extremes (below floor
vs. above ceiling).

**Where to look:** the natural-generation placement pass in `RivalColonies`
(`tickNaturalGeneration` / the settlement-spawn site selection), the Y-height /
surface lookup it uses, and the citizen + garrison spawn positioning
(`ConquestPayoff` levy spawn, `RivalColonies` garrison spawn). Confirm the
placement path is dimension-aware and that structure Y and entity Y are derived
from the same surface anchor. Whether the natural-gen pass should even RUN in the
Nether is a separate design question worth resolving. Cross-reference
docs/rival-colony-investigation.md (Stage A placement — the synchronous-blueprint
placement bugfix from 2026-06-13).

---

## 2026-06-29 — [HIGH PRIORITY] Spawned race-mobs keep a random colonist name in the town hall; naming doesn't override it

**Status:** RESOLVED (2026-06-29) — fix written; **confirmed working by the
reporter (2026-07-04)**.
`ReproductionManagerMixin` now intercepts the reproduction-growth path (see
"FIX (implemented)" below). Reporter confirmed they're on the proper
(supported) version, so this is not the 1.1.1340 version confounder. The cause
is version-independent (the same in 1.1.1319, our pinned jar).

### ROOT CAUSE (verified against the 1.1.1319 jar bytecode)

Stage B only hooks `CitizenAddedModEvent`. MineColonies posts that event from
**only three** spawn paths:
- `CitizenManager` INITIAL — the town-hall "keep the colony topped up to
  `initialCitizenAmount`" path (default `initialcitizenamount` = **4**).
- `CitizenManager` RESURRECTED — reviving a dead/lent citizen.
- `RecruitmentInteraction` HIRED + `CommandCitizenSpawnNew` COMMANDS.

But the actual **population-growth** path posts **no event at all**:
`ReproductionManager.trySpawnChild()` (the couples-in-houses growth driver)
calls `createAndRegisterCivilianData()` + `spawnOrCreateCitizen()` directly and
never constructs a `CitizenAddedModEvent`. (`spawnCitizenOnPosition`, the shared
chokepoint every path funnels through, also posts nothing.)

`trySpawnChild` is gated by:
`initialCitizenAmount ≤ currentCitizenCount < maxCitizens`.

So the colony lifecycle in a race colony is:
1. **count < 4** → INITIAL path runs → our `onCitizenAdded` interception fires →
   discards the colonist, spawns a wild race-mob, count drops → keeps re-firing.
   These are "the mobs that spawn with no Tensura name." Correct so far.
2. **count ≥ 4** (e.g. after the player names a few race-mobs, or via any other
   intake) → `ReproductionManager` takes over and spawns plain **human
   colonists with random names**, with **no event**, so our interception never
   sees them. They register permanently in the town-hall roster.
3. Naming a wild race-mob runs `onRaceNamed`, which **always** calls
   `createAndRegisterCivilianData()` — a brand-new, separate CitizenData. It
   never touches the orphaned colonist entries. Hence "naming them wouldn't
   override the colonist name in the town hall."

So the report's three observations all follow from one gap: **Stage B covers
the INITIAL top-up but NOT the reproduction-driven growth path, because that
path is eventless.** The earlier hypothesis (`removeCivilian` not clearing the
roster) is ruled out — `removeCivilian` does remove from the `citizens` map,
unassign buildings, clear work orders, and recalc max citizens; it works fine.
The interception simply never runs for grown colonists.

### RECOMMENDED FIX (not yet implemented)

Because the growth path is eventless, an event subscriber can't catch it — this
needs a **mixin** (the infra already exists; see
`tensura_minecolonies.mixins.json` + the three existing mixins). Cleanest:
`@Inject(at = HEAD, cancellable = true)` into
`ReproductionManager.trySpawnChild()`:
- Resolve the colony; read `ColonyRaceConfigSavedData`.
- If the colony has no race members (legacy / COLONIST-only) → return without
  cancelling (vanilla behaviour).
- Otherwise `pickRandomMember`: COLONIST → let vanilla proceed; GOBLIN/ORC/etc.
  → **cancel** the vanilla child spawn and instead spawn a wild race-mob (reuse
  `ExampleMod.spawnWildRaceMob`) at a free bed / town-hall position.

This mirrors exactly what `onCitizenAdded` already does for the INITIAL path,
just driven from inside the growth call instead of reacting to an absent event.
Mixed colonies keep working via `pickRandomMember`'s proportional draw.
Cross-reference docs/decisions.md → "Stage B" (the deferred-mixin note already
anticipated needing a coremod for the clean interception).

### FIX (implemented 2026-06-29) — integrated "breed their own kind" route

First cut intercepted the birth and dropped an unnamed wild mob at the town
hall. Per the developer's decision (2026-06-29), this was upgraded to the more
integrated route: **let MineColonies create the child, then convert it into a
citizen of the colony's race** (a baby goblin/orc/dwarf/lizardman tied to its
real colony parents, that grows up). The native reproduction/family system
stays intact and no per-newcomer naming step is needed.

- `mixin/ReproductionManagerMixin` — `@WrapOperation` around the
  `createAndRegisterCivilianData()` call inside `ReproductionManager.trySpawnChild()`.
  Calls the original (child created/registered as vanilla expects), then hands
  the child to `ExampleMod.onReproductionChild`, then returns it unchanged so
  the rest of `trySpawnChild` (parents, name, child flag, body spawn) runs
  normally.
- `ExampleMod.onReproductionChild(IColony, ICitizenData)` — race-gates via
  `pickRandomMember` (pending / legacy / COLONIST draw → leave a human child)
  and otherwise calls `mintRaceChildCitizen`.
- `ExampleMod.mintRaceChildCitizen(...)` — mints a durable `RaceIdentity`
  (mode IN_COLONY) with a randomised appearance + body snapshot from a transient
  wild mob (`EntityType.create` + `finalizeSpawn(SPAWN_EGG)`, never added to the
  world), persists a `RaceTag` snapshot (so the body-join / reconcile pass
  stamps the captured variant), and applies the race skill profile + named
  happiness ("auto-named"). The child renders as a baby of its race (it's
  flagged `isChild` by reproduction) and grows up.
- Works for all four races (GOBLIN/ORC/DWARF/LIZARDMAN); mixed colonies breed
  in proportion via `pickRandomMember`.
- Registered in `tensura_minecolonies.mixins.json`. CHANGELOG under
  `[Unreleased] → Added/Fixed`. Future idea (bred children left UNNAMED for the
  player to evolve) recorded in docs/future-ideas.md.
- Debug: `/racegrow` (run real `trySpawnChild` once) and `/racegrow force`
  (create + convert a baby race-citizen, bypassing housing/couple gating).

**Report (as phrased):** "After I made the colony the mobs would spawn with no
tensura name but would have a random colonist name in the town hall block, and
naming them wouldn't override the colonist name in the town hall block."

**What this means / why it's serious:** Stage B race-aware spawn is supposed to
**discard** MineColonies' about-to-spawn citizen and **remove it from the colony
roster** (`citizenData.discard()` + `colony.getCitizenManager().removeCivilian(
citizenData)`), then spawn a WILD, UNNAMED mob with no citizen id — so the town
hall should show NOTHING for that mob until the player names it (naming then
runs `createAndRegisterCivilianData()` to create the real citizen). The report
describes the opposite:
1. The spawned mob has **no Tensura name** (expected — it's wild), BUT
2. A **random colonist entry still exists in the town hall** with that mob's
   slot — i.e. the `removeCivilian` / discard interception did NOT take effect,
   so MC's original colonist CitizenData persists in the roster.
3. **Naming the mob does not override that town hall name** — consistent with
   naming creating a SEPARATE new CitizenData while the orphaned colonist entry
   lingers (you'd end up with a duplicate / ghost citizen in the town hall).

**Leading hypothesis (to verify):** the `CitizenAddedModEvent(INITIAL)`
interception in `onCitizenAdded` ([ExampleMod.java:822](../src/main/java/com/example/examplemod/ExampleMod.java))
isn't firing, isn't matching (wrong `CitizenAddedSource`?), or
`removeCivilian` isn't actually clearing the roster entry on this
MineColonies build — leaving the vanilla colonist registered while a wild mob
spawns alongside it. A non-cancellable-event / API-shape change in the
player's MineColonies version, or the colony's composition not being read as a
race colony (`pickRandomMember` returning COLONIST/null), are both candidate
causes. Possibly related to the version mismatch noted in the roster bug below.

**Where to look:** `onCitizenAdded` + `spawnWildRaceMob` (Stage B interception),
`ColonyRaceConfigSavedData` (is the colony actually registered as a race
colony?), and `onRaceNamed` (does naming reuse the existing CitizenData or
always create a new one?). Cross-reference docs/decisions.md → Stage B.

---

## 2026-06-29 — Can't send some already-named subordinates from the Citizen roster

**Status:** RESOLVED (2026-07-04) — closed as a version mismatch. The reporter
was on MineColonies **1.1.1340**, newer than our pinned/supported **1.1.1319**
(and the newer version also corrupted their save). Since the issue is tied to the
unsupported version rather than our code, it's not being pursued further. Original
investigation notes kept below for reference.

**Report (as phrased):** "So I can't send some of my already named
subordinates in the Citizen roster."

**Follow-up details from the reporter:**
- MineColonies version is **1.1.1340** (newer than our pinned 1.1.1319).
- The affected subordinates **don't show in the roster at all** — but if they
  were made/named **after** the colony already existed, they DO show. (So the
  ones that won't send appear to be ones named BEFORE the colony existed — i.e.
  likely promoted from the pending pool.)
- "There isn't really a difference, it's goblin and orc" — affects both races;
  not race-specific.
- Their colony composition: **Otherworlders + Shizu**, and the player also has
  **Rimuru**.
- Side note from the reporter: the higher MineColonies version **corrupted
  their save** ("because of a data pack but I don't have a data pack"). Their
  workaround: back up the world, delete it, restore the backup.

**Leading hypothesis (to verify):** the non-showing subordinates are
**pending-pool** identities — goblins/orcs named before any colony existed,
which are supposed to be promoted to citizens on `ColonyCreatedModEvent`
(Stage 1b). If promotion didn't run, or the resulting identity lacks a proper
`citizenId` / `colonyId` / `ownerPlayerUUID`, the roster builder
(`Networking.sendRosterTo`) would filter them out so they never appear and
can't be sent. The "named after the colony exists → shows fine" detail points
squarely at the pre-colony / pending-pool path. The 1.1.1340 vs 1.1.1319
version mismatch (and the reported save corruption) is a confounder worth
ruling in/out — an API change in the newer MineColonies could break the
promotion hook or citizen registration.

**Reporter is waiting (2026-06-29):** the reporter wants to first confirm
whether this is a version-related issue (the 1.1.1340 vs 1.1.1319 mismatch /
the save corruption) before any fix is attempted. Hold investigation/fix work
until they report back.

**Where to look:** the pending-pool promotion on `ColonyCreatedModEvent`
(Stage 1b), `RaceIdentitySavedData` (pending pool + identity fields:
`citizenId`, `colonyId`, `ownerPlayerUUID`), and the roster filter in
`Networking.sendRosterTo` (skips identities with null owner / mismatched owner /
missing colony or citizen lookup).
