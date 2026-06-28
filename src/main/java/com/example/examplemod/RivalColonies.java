package com.example.examplemod;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
import com.minecolonies.api.util.EntityUtils;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.registry.entity.HumanEntityTypes;
import io.github.manasmods.tensura.registry.entity.MonsterEntityTypes;
import io.github.manasmods.tensura.registry.attribute.TensuraAttributes;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.util.EnergyHelper;
import io.github.manasmods.tensura.util.SubordinateHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.tslat.smartbrainlib.util.BrainUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Rival-colony arc — Stage A: settlement GENERATION (the structural
 * foundation Stages B–E hook). See docs/rival-colony-investigation.md.
 *
 * <p>Stage A delivers: per-faction themed faux-towns built instantly
 * from MineColonies building schematics; the WILD-vs-COLONY boss split;
 * the ALL/SOME/NONE config; physical-vs-abstract faction classification;
 * the {@link SettlementSavedData} record; and a debug command. It does
 * NOT build garrisons (B), the war flow (C), conquest (D), or betrayal
 * scaling (E) — those extend the record this stage lays down.
 *
 * <p>Everything is gated behind {@code enableFactionSystem} (the
 * settlement layer is part of the faction system).
 */
public final class RivalColonies {

    private static final Logger LOGGER = LoggerFactory.getLogger(RivalColonies.class);

    // ------------------------------------------------------------------
    // Physical factions: anchor boss + themed MineColonies pack.
    // A faction is PHYSICAL iff it appears here (its anchor mob exists);
    // every other faction is ABSTRACT — no settlement ever generates.
    // Pack names are the StructurePacks DISPLAY names (verified ids).
    // ------------------------------------------------------------------

    /** faction id → anchor boss entity-type supplier (lazy; registries).
     *  All PHYSICAL factions (the 6 town factions + Dwargon-village). */
    private static final Map<String, Supplier<? extends EntityType<?>>> ANCHORS = new LinkedHashMap<>();
    /** faction id → MineColonies structure-pack display name (theme).
     *  ONLY the 6 MINECOLONIES_CLUSTER (town) factions — Dwargon is NOT
     *  here (it uses existing Tensura dwarven villages, not a generated
     *  town; see {@link #DWARGON}). */
    private static final Map<String, String> PACKS = new LinkedHashMap<>();
    /** Dwargon's id — the DWARVEN_VILLAGE-type faction (Change 1). */
    static final String DWARGON = "dwargon";
    static {
        // Luminous — holy marble city.
        ANCHORS.put("luminous", HumanEntityTypes.HINATA_SAKAGUCHI);
        PACKS.put("luminous", "Ancient Athens");
        // Falmuth — militaristic human kingdom.
        ANCHORS.put("falmuth", HumanEntityTypes.FOLGEN);
        PACKS.put("falmuth", "Fortress");
        // Shizu — DEPRECATED (soft-retired, step 3). No anchor, no pack:
        // the Pagoda settlement is retired and she generates nothing. The
        // enum value + standing data remain valid but inert (see BossFaction).
        // Leon — a demon lord's grand keep.
        ANCHORS.put("leon", MonsterEntityTypes.IFRIT);
        PACKS.put("leon", "Caledonia");
        // Eastern Empire (re-themed from otherworlders) — the major eastern
        // military power. Anchor MAI_FURUKI is a DELIBERATE PLACEHOLDER (no
        // real Empire-leadership entity exists yet — swap when one does). The
        // "Space Wars" pack's futuristic look reads as Empire magitech.
        ANCHORS.put("eastern_empire", HumanEntityTypes.MAI_FURUKI);
        PACKS.put("eastern_empire", "Space Wars");
        // Jura-Tempest Federation — the forest nation (merged tempest +
        // jura_alliance; the body is the old Jura settlement). The anchor
        // boss is a buffed BOSS-TIER SLIME (Rimuru's kin) — it casts natively
        // (verified), so it gets a strong kit + heavy buff, NOT an autocaster.
        // (Shin Ryusei moved to the Eastern Empire roster.)
        ANCHORS.put("tempest", MonsterEntityTypes.SLIME);
        PACKS.put("tempest", "Jungle Treehouse");
        // Dwargon — DWARVEN_VILLAGE type: anchor exists (Gazel) but NO
        // town pack; SOME existing dwarf villages become its settlements.
        ANCHORS.put(DWARGON, HumanEntityTypes.GAZEL_DWARGO);
        // ABSTRACT (no anchor mob, never settle): eurazania, milim,
        // clayman (his orcs roam as calamities, not a settled town).
    }

    /** Cached structure key for Tensura's dwarf village. */
    static final net.minecraft.resources.ResourceKey<
            net.minecraft.world.level.levelgen.structure.Structure> DWARF_VILLAGE_KEY =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            "tensura", "dwarf_village"));

    /** Our OWN worldgen faction structures → the faction each one populates.
     *  A player near an unpopulated one triggers the real settlement build
     *  ({@link #tickWorldgenSettlements}). Each structure is a separate data
     *  JSON (shared {@code faction_anchor} type) with its own structure_set +
     *  themed biome tag. Dwargon is NOT here — it adopts Tensura's existing
     *  {@code dwarf_village} structure via {@link #tickDwarvenVillages}. */
    private static final Map<net.minecraft.resources.ResourceKey<
            net.minecraft.world.level.levelgen.structure.Structure>, String> WORLDGEN_STRUCTURES =
            new LinkedHashMap<>();
    static {
        putWorldgenStructure("faction_anchor_falmuth", "falmuth");
        putWorldgenStructure("faction_anchor_leon", "leon");
        putWorldgenStructure("faction_anchor_tempest", "tempest");
        putWorldgenStructure("faction_anchor_eastern_empire", "eastern_empire");
        putWorldgenStructure("faction_anchor_luminous", "luminous");
    }

    private static void putWorldgenStructure(String structureName, String factionId) {
        WORLDGEN_STRUCTURES.put(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.STRUCTURE,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                ExampleMod.MODID, structureName)),
                factionId);
    }

    /** True when the faction can generate a physical settlement (the 6
     *  town factions + Dwargon-village). */
    public static boolean isPhysical(String factionId) {
        return ANCHORS.containsKey(factionId);
    }

    /** True for the 6 MINECOLONIES_CLUSTER (generated-town) factions. */
    public static boolean isTownFaction(String factionId) {
        return PACKS.containsKey(factionId);
    }

    public static String packFor(String factionId) {
        return PACKS.get(factionId);
    }

    // ------------------------------------------------------------------
    // STAGE B — the GARRISON: per-faction themed defenders.
    //
    // Each physical faction fields a themed defender roster drawn from
    // mobs already in the mod (the TensuraRaids.rosters() shape). The
    // anchor BOSS (spawned + marked in Stage A) is part of the garrison;
    // these are the rank-and-file around it. Abstract factions (Eurazania,
    // Milim, Clayman) have no settlement, so no roster.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob>[] garrisonRoster(String factionId) {
        return switch (factionId) {
            // Luminous — the Holy Empire. The anchor BOSS is Hinata Sakaguchi
            // (spawned in Stage A); the garrison is purely her Holy-Knight
            // soldiers, modelled as FALMUTH_KNIGHT (no dedicated holy-knight
            // mob exists). Kyoya was moved out — he belongs to Falmuth's
            // roster (where he already is), not here. (CloneEntity is avoided —
            // it renders missing-texture without a source entity to copy.)
            case "luminous" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get() };
            // Falmuth — militaristic kingdom: Folgen's knights + the summoned
            // Otherworlder heroes Kirara / Kyoya / Shogo. (Mark Lauren +
            // Shinji Tanimura moved to the Eastern Empire roster.)
            case "falmuth" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get(), HumanEntityTypes.KIRARA_MIZUTANI.get(),
                    HumanEntityTypes.KYOYA_TACHIBANA.get(), HumanEntityTypes.SHOGO_TAGUCHI.get() };
            // Shizu — DEPRECATED (soft-retired): no garrison roster.
            // Leon — PLACEHOLDER ROSTER (2026-06-26). The rank-and-file are
            // FALMUTH_KNIGHT soldiers standing in for the demon-lord's real
            // garrison until a proper Leon roster lands. The anchor BOSS is
            // unchanged (still the high-EP Ifrit in ANCHORS). The previous
            // daemon + Salamander fire roster was swapped out (the fire mobs
            // roamed far and read as out-of-place). NOTE: Leon still grants
            // FLAME/HEAT resistance to defenders in assignFactionDefenderSkills
            // — now thematically odd on human knights but harmless (fire-only,
            // doesn't affect melee); revisit when the real roster lands.
            case "leon" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get() };
            // Eastern Empire — magitech military power: FALMUTH_KNIGHT
            // rank-and-file soldiers (listed twice so they're the bulk of the
            // round-robin garrison) led by the imperial lieutenants Shin
            // Ryusei, Mark Lauren and Shinji Tanimura (all native casters; NO
            // granted skills — see isSkillUntouched). HIGH power tier via
            // factionPowerMultiplier + the Mai boss-buff in spawnGarrison.
            // (BONE_GOLEM soldiers removed — golems are player-POSSESSED and
            // won't fight their owner.) ⚠ FUTURE CANON: these three later
            // defect to the Jura-Tempest Federation (see faction-model.md).
            case "eastern_empire" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get(), HumanEntityTypes.FALMUTH_KNIGHT.get(),
                    HumanEntityTypes.SHIN_RYUSEI.get(),
                    HumanEntityTypes.MARK_LAUREN.get(), HumanEntityTypes.SHINJI_TANIMURA.get() };
            // Jura-Tempest Federation — the forest nation's kin (the boss is
            // the buffed anchor SLIME; rank-and-file are goblins + lizardmen).
            case "tempest" -> new EntityType[] {
                    MonsterEntityTypes.GOBLIN.get(), MonsterEntityTypes.LIZARDMAN.get() };
            // Dwargon — the dwarven kingdom: dwarf rank-and-file under Gazel.
            case "dwargon" -> new EntityType[] {
                    HumanEntityTypes.DWARF.get() };
            default -> null;
        };
    }

    /** Unique named characters (Otherworlder heroes / imperial lieutenants)
     *  that must spawn AT MOST ONCE per garrison — there shouldn't be two
     *  Kyoyas standing side by side. Generic troops (knights, daemons,
     *  salamander, goblins, dwarves, …) repeat freely. */
    private static boolean isUniqueGarrisonMob(EntityType<?> type) {
        return type == HumanEntityTypes.KIRARA_MIZUTANI.get()
                || type == HumanEntityTypes.KYOYA_TACHIBANA.get()
                || type == HumanEntityTypes.SHOGO_TAGUCHI.get()
                || type == HumanEntityTypes.MARK_LAUREN.get()
                || type == HumanEntityTypes.SHINJI_TANIMURA.get()
                || type == HumanEntityTypes.SHIN_RYUSEI.get();
    }

    /** First repeatable (non-unique) type in a roster — used to substitute a
     *  would-be duplicate Otherworlder. Null only if the roster is all-unique. */
    private static EntityType<? extends Mob> firstRepeatableGarrisonMob(
            EntityType<? extends Mob>[] roster) {
        for (EntityType<? extends Mob> t : roster) {
            if (!isUniqueGarrisonMob(t)) return t;
        }
        return null;
    }

    /** Round-robin pick for garrison slot {@code i}, but never spawn a second
     *  copy of a unique character: a duplicate Otherworlder is replaced by the
     *  first repeatable roster type (kept only if the roster is all-unique).
     *  Records uniques actually used in {@code usedUniques}. */
    private static EntityType<? extends Mob> pickGarrisonType(
            EntityType<? extends Mob>[] roster, int i, java.util.Set<EntityType<?>> usedUniques) {
        EntityType<? extends Mob> type = roster[i % roster.length];
        if (isUniqueGarrisonMob(type)) {
            if (usedUniques.contains(type)) {
                EntityType<? extends Mob> repeatable = firstRepeatableGarrisonMob(roster);
                if (repeatable != null) return repeatable;
            } else {
                usedUniques.add(type);
            }
        }
        return type;
    }

    // ------------------------------------------------------------------
    // GARRISON SCALING — derived from the BOSS's EP, not the player.
    // ⚠⚠ ALL BALANCE GUESSES — no combat playtest yet. Tune after the
    // first siege test (see docs/rival-colony-investigation.md Stage B).
    //
    //   ratio  = bossEP / GARRISON_BASELINE_EP
    //   scale  = clamp(ratio ^ GARRISON_SCALE_EXPONENT,
    //                  GARRISON_SCALE_MIN, GARRISON_SCALE_MAX)
    //   count  = clamp(round(GARRISON_BASE_COUNT × scale),
    //                  GARRISON_MIN_COUNT, GARRISON_MAX_COUNT)
    //   stat×  = min(GARRISON_STAT_FACTOR_MAX,
    //                1 + (scale − 1) × GARRISON_STAT_PER_SCALE)
    // applied to MAX_HEALTH / ATTACK_DAMAGE / MAX_MAGICULE / MAX_AURA.
    // The sqrt-ish exponent dampens EP (a 100× boss → ~10× scale, not
    // 100×) so a Demon-Lord settlement is hard but not a brick wall.
    // ------------------------------------------------------------------

    /** Boss EP that yields scale 1.0 (a "weak" anchor). */
    static final double GARRISON_BASELINE_EP = 5_000.0;
    /** Dampening exponent on the EP ratio (0.5 = square root). */
    static final double GARRISON_SCALE_EXPONENT = 0.5;
    static final double GARRISON_SCALE_MIN = 1.0;
    static final double GARRISON_SCALE_MAX = 6.0;
    /** Defenders at scale 1.0, before clamp. */
    static final int GARRISON_BASE_COUNT = 6;
    static final int GARRISON_MIN_COUNT = 4;
    static final int GARRISON_MAX_COUNT = 20;
    /** Per-unit-of-scale stat multiplier growth above the 1.0 floor. */
    static final double GARRISON_STAT_PER_SCALE = 0.5;
    static final double GARRISON_STAT_FACTOR_MAX = 4.0;
    /** EP assumed when a boss's existence can't be read (scale-1 fallback). */
    static final double GARRISON_FALLBACK_BOSS_EP = GARRISON_BASELINE_EP;
    /** Fraction of the starting garrison that must fall for conquest. */
    static final double GARRISON_WIN_FRACTION = 0.60;
    /** Defenders spawn within this radius (blocks) of the settlement center. */
    static final int GARRISON_SPAWN_RADIUS = 18;
    /** A defender that strays beyond this (blocks) from center is walked
     *  back — the settlement tether (the patrol/steering containment idiom). */
    static final int GARRISON_TETHER_RADIUS = 40;
    private static final float GARRISON_WALK_SPEED = 1.0f;
    private static final int GARRISON_CLOSE_ENOUGH = 3;

    /** Stable modifier ids for the garrison stat-bump (remove-first, never
     *  compounds — the assassin multiplyAttribute idiom). */
    private static final ResourceLocation GARRISON_HP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "garrison_hp");
    private static final ResourceLocation GARRISON_DMG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "garrison_dmg");
    private static final ResourceLocation GARRISON_MAG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "garrison_mag");
    private static final ResourceLocation GARRISON_AURA_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "garrison_aura");

    /** Stable modifier ids for the Rimuru (Tempest anchor) boss buff. Distinct
     *  from the GARRISON_* ids so the two never collide. */
    private static final ResourceLocation RIMURU_HP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rimuru_hp");
    private static final ResourceLocation RIMURU_DMG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rimuru_dmg");
    private static final ResourceLocation RIMURU_SPIRIT_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rimuru_spirit");
    private static final ResourceLocation RIMURU_MAG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rimuru_mag");
    private static final ResourceLocation RIMURU_AURA_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rimuru_aura");

    // ------------------------------------------------------------------
    // STAGE E — BETRAYAL SCALING. Declaring war on a faction you have
    // DIPLOMATIC relations with (OPEN/PACT/COVENANT) scales the garrison
    // UP as a betrayal punishment — deeper ally = harder fight — AND the
    // war-declaration standing crash shatters the relationship (the
    // existing below-WARY collapse, no new code).
    // ⚠ ALL multipliers are BALANCE GUESSES — tune at the polish playtest.
    // ------------------------------------------------------------------

    /** Extra stat multiplier on the garrison, on TOP of B's boss-EP
     *  stat-bump, by the betrayed relationship tier. */
    static final double BETRAYAL_MULT_OPEN = 1.25;      // Diplomacy
    static final double BETRAYAL_MULT_PACT = 1.6;       // Alliance
    static final double BETRAYAL_MULT_COVENANT = 2.0;   // Covenant
    /** Standing delta written on war declaration — a large negative so the
     *  effective standing clamps to 0 (well below WARY), tripping the
     *  diplomacy collapse next tick. */
    static final double BETRAYAL_STANDING_CRASH = -1000.0;

    /** Betrayal stat-bump ids — SEPARATE from the B garrison ids so the
     *  betrayal factor stacks on top (both ADD modifiers apply). */
    private static final ResourceLocation BETRAYAL_HP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "betrayal_hp");
    private static final ResourceLocation BETRAYAL_DMG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "betrayal_dmg");
    private static final ResourceLocation BETRAYAL_MAG_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "betrayal_mag");
    private static final ResourceLocation BETRAYAL_AURA_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "betrayal_aura");

    // ------------------------------------------------------------------
    // The shared town layout — (blueprint path, dx, dz). Pack-relative
    // paths present in EVERY candidate pack (verified). Only the PACK
    // differs per faction; the footprint is shared. ~10 buildings; the
    // town hall anchors the center. Spacing is generous to limit overlap
    // (tunable; a foundation pad is deferred polish).
    // ------------------------------------------------------------------

    private record Building(String path, int dx, int dz) {}

    // Pack-relative blueprint paths — MUST include the ".blueprint"
    // extension: StructurePacks resolves packRoot.resolve(path) and the
    // path normalizer does NOT append it.
    // Widened from 22 → 32 so adjacent/diagonal buildings can't intersect
    // (the MC schematics here run ~16–20 wide; 32 leaves clear margin). #3
    private static final int GRID = 32;
    private static final List<Building> LAYOUT = List.of(
            new Building("fundamentals/townhall1.blueprint", 0, 0),
            new Building("fundamentals/builder1.blueprint", -GRID, 0),
            new Building("fundamentals/tavern1.blueprint", GRID, 0),
            new Building("craftsmanship/metallurgy/blacksmith1.blueprint", 0, -GRID),
            new Building("education/library1.blueprint", 0, GRID),
            new Building("military/barracks1.blueprint", -GRID, -GRID),
            new Building("fundamentals/residence1.blueprint", GRID, -GRID),
            new Building("fundamentals/residence1.blueprint", -GRID, GRID),
            new Building("fundamentals/residence1.blueprint", GRID, GRID),
            new Building("fundamentals/residence1.blueprint", 0, GRID * 2));

    // (Stage 4) The old proximity-scatter natural-gen tuning constants
    // (NATURAL_GEN_CHANCE_PER_DAY / NATURAL_GEN_DISTANCE / MIN_SETTLEMENT_SPACING
    // / MAX_NATURAL_SETTLEMENTS) were removed — worldgen structure_sets now own
    // placement (spacing/separation/salt in the JSON).

    // --- #2 site selection + foundation leveling (tunable) ---
    /** Candidate centers sampled when siting a settlement; the flattest
     *  wins (rejecting cliff/steep spots). */
    private static final int SITE_SAMPLES = 8;
    /** How far (blocks) candidate centers scatter from the requested spot. */
    private static final int SITE_SCATTER = 24;
    /** A site whose surface-height RANGE over the footprint is ≤ this is
     *  "flat enough" — the search stops early when one is found. */
    private static final int SITE_FLAT_ENOUGH = 4;
    /** Half-extent (blocks) of the flat foundation pad laid under each
     *  building before it places (clears slope, fills holes). */
    private static final int BUILDING_PAD_HALF = 12;
    /** Blocks of terrain cleared to air above each pad's base. */
    private static final int PAD_CLEAR_HEIGHT = 12;

    private RivalColonies() {}

    // ------------------------------------------------------------------
    // The wild/colony split — the generation decision
    // ------------------------------------------------------------------

    /** Should a physical-faction boss generation become the COLONY
     *  version (settlement) under the current config? */
    private static boolean rollColony(ServerLevel level) {
        return switch (Config.RIVAL_SETTLEMENT_MODE.get()) {
            case ALL -> true;
            case NONE -> false;
            case SOME -> level.getRandom().nextDouble() < Config.RIVAL_SETTLEMENT_SOME_CHANCE.get();
        };
    }

    // (Stage 4) The old `generate()` wrapper — the proximity-scatter entry that
    // rolled WILD-boss vs COLONY per config — was removed with the scatter. For
    // TOWN factions, a worldgen anchor now ALWAYS populates as a COLONY
    // (populateSettlementAt → generateColony). ⚠ BEHAVIOR CHANGE: the
    // wild-boss-alone variant + RIVAL_SETTLEMENT_SOME_CHANCE no longer apply to
    // town natural-gen (RIVAL_SETTLEMENT_MODE NONE still disables via the
    // settlementsOn gate; the wild/colony roll survives ONLY for Dwargon dwarf
    // villages, which still call rollColony). Re-add a wild variant later if
    // wanted (e.g. roll at populate time). The debug command + ALL/colony
    // callers use generateColony directly.

    /** Force the COLONY version (the debug command + ALL mode). */
    static Settlement generateColony(ServerLevel level, ServerPlayer placer, String factionId,
                                     BlockPos rawCenter) {
        if (!WorldReputationManager.isFactionSystemEnabled() || !isTownFaction(factionId)) return null;
        String pack = PACKS.get(factionId);
        // PACK-READINESS GUARD. Structurize registers/indexes its structure
        // packs ASYNC on a worker thread (and reloads them on relog). Generating
        // before the blueprint index is ready 404s every building
        // (NoSuchFileException) and leaves an EMPTY town — observed in-game:
        // 7/10 buildings failed when generation ran ~2 min after world load,
        // while the SAME pack/paths succeeded later (and post-relog). Probe the
        // town-hall blueprint first; if the pack isn't ready, DEFER (return
        // null) instead of building a husk. Callers leave any pending marker in
        // place, so a retry / the next attempt succeeds once the pack settles.
        if (!isPackBlueprintsReady(level, pack)) {
            LOGGER.warn("[TM] rival: pack '{}' blueprints not ready yet — deferring "
                    + "{} settlement generation (retry once Structurize finishes loading)",
                    pack, factionId);
            return null;
        }
        // #2 — site selection: pick a reasonably flat nearby spot (rejects
        // cliff faces) — but exact flatness matters less now that buildings
        // follow the terrain individually (below).
        BlockPos center = findBuildableCenter(level, rawCenter);

        Settlement s = new Settlement();
        SettlementSavedData data = SettlementSavedData.get(level);
        s.id = data.allocateId();
        s.structureType = Settlement.StructureType.MINECOLONIES_CLUSTER;
        s.factionId = factionId;
        s.dimension = level.dimension();
        s.center = center;
        s.packName = pack;

        // Stage 6 (Part B) — terrain-FOLLOWING placement. Each building sits at
        // its OWN local ground height (true ground beneath any trees) so the
        // town drapes naturally over the land — different buildings on different
        // Y levels, like a hillside settlement — and each gets its own
        // biome-matched, edge-graded foundation pad. Heights are computed FIRST
        // (from natural terrain) so an earlier pad can't skew a later one.
        int[] buildingY = new int[LAYOUT.size()];
        for (int i = 0; i < LAYOUT.size(); i++) {
            Building b = LAYOUT.get(i);
            buildingY[i] = groundSurfaceY(level, center.getX() + b.dx(),
                    center.getZ() + b.dz()) + 1;
        }
        for (int i = 0; i < LAYOUT.size(); i++) {
            Building b = LAYOUT.get(i);
            BlockPos at = new BlockPos(center.getX() + b.dx(), buildingY[i], center.getZ() + b.dz());
            levelBuildingPad(level, at);
            placeBuilding(level, placer, pack, b.path(), at);
            s.buildingPositions.add(at);
        }
        // #5 — strip vestigial MineColonies hut blocks once placement
        // settles (placement is queued async over a few ticks).
        queueHutStrip(level, s);

        // The anchor boss at the town center, MARKED (rep-affecting).
        Mob boss = spawnAnchorBoss(level, factionId, center.above(), true);
        if (boss != null) s.bossUuid = boss.getUUID();

        data.put(s);
        // Stage B — raise the themed garrison around the town.
        spawnGarrison(level, s);
        LOGGER.info("[TM] rival: {} COLONY settlement #{} generated at {} (pack '{}', {} buildings)",
                factionId, s.id, center, pack, s.buildingPositions.size());
        return s;
    }

    /** Spawn the faction's anchor boss; mark it for COLONY (the existing
     *  FactionMarkTag path) or leave it unmarked for WILD (free kill). */
    private static Mob spawnAnchorBoss(ServerLevel level, String factionId, BlockPos pos,
                                       boolean colony) {
        Supplier<? extends EntityType<?>> supplier = ANCHORS.get(factionId);
        if (supplier == null) return null;
        EntityType<?> type = supplier.get();
        if (!(type.create(level) instanceof Mob boss)) return null;
        BlockPos at = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos);
        boss.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        boss.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.SPAWN_EGG, null);
        boss.setPersistenceRequired();
        if (!level.addFreshEntity(boss)) return null;
        if (colony) {
            // Marked → the Layer-1 marked-kill consequences apply (the
            // settlement is the rep-affecting version). WILD stays
            // unmarked = the Stage-3 spare-boss free-kill behavior.
            WorldReputationManager.markBoss(boss, factionId, "rival_colony", true);
        }
        // Tempest's anchor Slime becomes "Rimuru" — a demon-lord-tier boss.
        // Applied HERE (not in spawnGarrison's boss block) on purpose: it runs
        // BEFORE spawnGarrison reads the boss EP, so the boss's raised EP scales
        // the garrison on the FIRST encounter; and it covers every boss-creation
        // path (initial colony, resetGarrison revive, wild). It also overrides
        // markBoss's "Slime" nameplate set just above.
        if ("tempest".equals(factionId)) {
            buffRimuruBoss(boss);
        }
        return boss;
    }

    /**
     * Promote the Tempest anchor Slime into "Rimuru" — a credible
     * demon-lord-tier boss. Replaces the old {@code SLIME_BOSS_BUFF ×8}.
     * HP ×100 (5→500), attack ×40 (0.5→20), spiritual HP ×10; magicule/aura
     * CAPS set to absolute 100,000 / 10,000 and the CURRENT pools filled to
     * those caps (so it can actually cast, and so EP = magicule + aura ≈ 110,000
     * drives the garrison scale). All ⚠ BALANCE GUESSES.
     *
     * <p>NOTE: setting the current pools raises EP to ~110k, which makes the
     * garrison scale to its 20-defender cap at ~×2.85 stats (intended — strong
     * subordinates). EP-derived Tensura side effects (large death drops, aura
     * intimidation) are expected for a boss of this tier.
     */
    private static void buffRimuruBoss(Mob boss) {
        // Name it Rimuru (overrides markBoss's type-name nameplate; keep visible).
        boss.setCustomName(net.minecraft.network.chat.Component.literal("Rimuru")
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        boss.setCustomNameVisible(true);

        // Combat stats — multipliers off the weak slime base (5 HP / 0.5 atk /
        // 100 spiritual). multiplyAttribute is remove-first, non-compounding.
        multiplyAttribute(boss, Attributes.MAX_HEALTH, RIMURU_HP_ID, 100.0);    // 5   -> 500
        multiplyAttribute(boss, Attributes.ATTACK_DAMAGE, RIMURU_DMG_ID, 40.0); // 0.5 -> 20
        try {
            multiplyAttribute(boss, TensuraAttributes.MAX_SPIRITUAL_HEALTH, RIMURU_SPIRIT_ID, 10.0);
        } catch (Throwable ignored) { }
        boss.setHealth(boss.getMaxHealth());

        // Energy CAPS → absolute values. Raise the cap BEFORE filling current,
        // or setMagicule/setAura would clamp to the slime's old ~980 / 10 cap.
        setAttributeAbsolute(boss, TensuraAttributes.MAX_MAGICULE, RIMURU_MAG_ID, 100_000.0);
        setAttributeAbsolute(boss, TensuraAttributes.MAX_AURA, RIMURU_AURA_ID, 10_000.0);

        // Fill the CURRENT pools to the new caps.
        ExistenceStorage exist = ExampleMod.readExistence(boss);
        if (exist != null) {
            exist.setMagicule(100_000.0);
            exist.setAura(10_000.0);
            try {
                exist.setSpiritualHealth(boss.getAttributeValue(TensuraAttributes.MAX_SPIRITUAL_HEALTH));
            } catch (Throwable ignored) { }
            exist.markDirty();
        }
    }

    /** Set an attribute's EFFECTIVE value to an absolute target via a stable-id
     *  ADD modifier (remove-first, never compounds — the multiplyAttribute idiom,
     *  but absolute instead of ×factor). Used for the Rimuru magicule/aura caps. */
    private static void setAttributeAbsolute(LivingEntity mob, Holder<Attribute> attr,
                                             ResourceLocation id, double target) {
        AttributeInstance instance = mob.getAttribute(attr);
        if (instance == null) return;
        instance.removeModifier(id);
        double delta = target - instance.getValue();
        instance.addPermanentModifier(new AttributeModifier(id, delta,
                AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * Place one complete MineColonies building schematic.
     *
     * <p>The blueprint is loaded SYNCHRONOUSLY ({@code getBlueprint}, not
     * {@code getBlueprintFuture}) — the async future isn't resolved when
     * {@code loadAndPlaceStructureWithRotation} checks {@code
     * hasBluePrint()}, so that path silently no-ops. We resolve the
     * blueprint, build the handler via its BLUEPRINT ctor (so
     * {@code hasBluePrint()} is true), and queue placement through the
     * Structurize {@code Manager} (ticked server-side, places over a few
     * ticks). A null blueprint logs exactly which pack/path failed.
     */
    /** True when {@code pack}'s blueprints are resolvable right now. Probes the
     *  first LAYOUT entry (the town hall); a null result or a throw means
     *  Structurize hasn't finished indexing the pack yet (async load / pending
     *  relog reload), so generation should defer rather than build an empty
     *  town. Cheap belt for the timing race observed in-game. */
    private static boolean isPackBlueprintsReady(ServerLevel level, String pack) {
        if (pack == null) return false;
        if (LAYOUT.isEmpty()) return true;
        String probe = LAYOUT.get(0).path(); // the town hall
        try {
            return StructurePacks.getBlueprint(pack, probe, level.registryAccess()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void placeBuilding(ServerLevel level, ServerPlayer placer, String pack,
                                      String path, BlockPos pos) {
        try {
            Blueprint bp = StructurePacks.getBlueprint(pack, path, level.registryAccess());
            if (bp == null) {
                LOGGER.warn("[TM] rival: blueprint NOT FOUND — pack '{}' path '{}' (pack resolved: {})",
                        pack, path, StructurePacks.getStructurePack(pack) != null);
                return;
            }
            CreativeBuildingStructureHandler handler = new CreativeBuildingStructureHandler(
                    level, pos, bp, RotationMirror.NONE, true);
            StructurePlacer structurePlacer = new StructurePlacer(handler);
            Manager.addToQueue(new PlaceStructureOperation(structurePlacer, placer));
        } catch (Throwable t) {
            LOGGER.warn("[TM] rival: failed to place '{}' from pack '{}' at {}", path, pack, pos, t);
        }
    }

    // ------------------------------------------------------------------
    // #2 — site selection + foundation leveling
    // ------------------------------------------------------------------

    /** Pick a reasonably flat nearby surface for a settlement center: sample a
     *  handful of candidates and keep the one with the smallest GROUND-height
     *  range over the footprint (stops early on a flat-enough spot). Rejects
     *  cliff faces; gentle slopes are fine now that buildings follow the terrain
     *  individually. Never fails — falls back to the requested spot. */
    private static BlockPos findBuildableCenter(ServerLevel level, BlockPos raw) {
        BlockPos best = null;
        int bestRange = Integer.MAX_VALUE;
        for (int i = 0; i < SITE_SAMPLES; i++) {
            BlockPos cand = (i == 0) ? raw : raw.offset(
                    level.getRandom().nextInt(SITE_SCATTER * 2 + 1) - SITE_SCATTER, 0,
                    level.getRandom().nextInt(SITE_SCATTER * 2 + 1) - SITE_SCATTER);
            BlockPos surf = new BlockPos(cand.getX(),
                    groundSurfaceY(level, cand.getX(), cand.getZ()) + 1, cand.getZ());
            int range = surfaceRange(level, surf);
            if (range < bestRange) { bestRange = range; best = surf; }
            if (range <= SITE_FLAT_ENOUGH) break;
        }
        return best != null ? best : new BlockPos(raw.getX(),
                groundSurfaceY(level, raw.getX(), raw.getZ()) + 1, raw.getZ());
    }

    /** Max−min GROUND height (true terrain, ignoring trees) over a coarse grid
     *  spanning the layout footprint — the "how steep is this site" metric. */
    private static int surfaceRange(ServerLevel level, BlockPos center) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int ext = GRID + BUILDING_PAD_HALF;
        for (int dx = -ext; dx <= ext; dx += 8) {
            for (int dz = -ext; dz <= ext + GRID; dz += 8) {
                int y = groundSurfaceY(level, center.getX() + dx, center.getZ() + dz);
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        return max - min;
    }

    /** True ground surface Y at a column — the highest SOLID terrain block,
     *  scanning DOWN past trees/leaves/foliage/snow/water so a building doesn't
     *  end up perched at tree-canopy height. */
    private static int groundSurfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int floor = level.getMinBuildHeight();
        while (y > floor) {
            if (isGroundSurface(level.getBlockState(new BlockPos(x, y, z)))) return y;
            y--;
        }
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1; // fallback
    }

    /** A solid terrain block to stand a building on (excludes air, fluids,
     *  replaceable plants/snow-layers, leaves and logs). */
    private static boolean isGroundSurface(net.minecraft.world.level.block.state.BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced()) return false;
        if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)) return false;
        return true;
    }

    /** Width (blocks) of the graded edge ring that tapers a building's pad down
     *  to the surrounding terrain instead of a vertical cliff. */
    private static final int FOUNDATION_SKIRT = 6;

    /**
     * Stage 6 (Part B) — lay ONE building's foundation pad at its own local Y and
     * grade its edges into the terrain, so each building drapes onto the land at
     * its own height (a terraced/hillside look). For every column in the pad:
     *   - the visible top reuses the column's own surface block (grass / sand /
     *     snow / …) so it matches the biome instead of bare stone;
     *   - holes below the target are filled (dirt) so the building never floats;
     *   - terrain above is cleared so it isn't buried in a hillside;
     *   - across the {@link #FOUNDATION_SKIRT} ring the height tapers from the
     *     building's base toward the natural GROUND (ignoring trees), softening
     *     the edge. ⚠ Aesthetic + tunable — wants in-game visual iteration.
     */
    private static void levelBuildingPad(ServerLevel level, BlockPos at) {
        int half = BUILDING_PAD_HALF;
        int coreTop = at.getY() - 1; // ground-surface Y under this building
        net.minecraft.world.level.block.state.BlockState dirt = Blocks.DIRT.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState air = Blocks.AIR.defaultBlockState();

        for (int dx = -half - FOUNDATION_SKIRT; dx <= half + FOUNDATION_SKIRT; dx++) {
            for (int dz = -half - FOUNDATION_SKIRT; dz <= half + FOUNDATION_SKIRT; dz++) {
                int outside = Math.max(0, Math.max(Math.abs(dx) - half, Math.abs(dz) - half));
                if (outside > FOUNDATION_SKIRT) continue;
                int x = at.getX() + dx, z = at.getZ() + dz;

                int naturalTop = groundSurfaceY(level, x, z);
                int targetTop = (outside == 0) ? coreTop
                        : (int) Math.round(coreTop + (naturalTop - coreTop)
                                * (outside / (double) FOUNDATION_SKIRT));

                net.minecraft.world.level.block.state.BlockState surface =
                        level.getBlockState(new BlockPos(x, naturalTop, z));
                if (surface.isAir() || !surface.getFluidState().isEmpty()) surface = dirt;

                // Fill any gap below the target with dirt (no floating pads).
                for (int y = Math.min(naturalTop, targetTop); y < targetTop; y++) {
                    level.setBlock(new BlockPos(x, y, z), dirt, 2);
                }
                level.setBlock(new BlockPos(x, targetTop, z), surface, 2);

                // Clear terrain above (no buried/cut building), up to the natural
                // surface plus headroom in the core (also removes trees on the pad).
                int worldTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int clearTo = Math.max(worldTop, targetTop + (outside == 0 ? PAD_CLEAR_HEIGHT : 1));
                for (int y = targetTop + 1; y <= clearTo; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, 2);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // #5 — strip vestigial MineColonies hut blocks (deferred, post-placement)
    // ------------------------------------------------------------------

    private record HutStrip(net.minecraft.resources.ResourceKey<Level> dim,
                            java.util.List<BlockPos> positions, long execTick) {}

    private static final java.util.List<HutStrip> pendingHutStrips = new java.util.ArrayList<>();
    /** Placement is queued async over a few ticks; strip after it settles. */
    private static final long HUT_STRIP_DELAY_TICKS = 100L;

    private static void queueHutStrip(ServerLevel level, Settlement s) {
        pendingHutStrips.add(new HutStrip(level.dimension(),
                new java.util.ArrayList<>(s.buildingPositions),
                level.getGameTime() + HUT_STRIP_DELAY_TICKS));
    }

    /** Process due hut-strips: replace every MineColonies hut block in each
     *  building's footprint with stone bricks. Settlements are decorative
     *  (conquest is rewards-only — no colony is founded), so the functional
     *  huts are vestigial. */
    private static void tickHutStrips(MinecraftServer server) {
        if (pendingHutStrips.isEmpty()) return;
        java.util.Iterator<HutStrip> it = pendingHutStrips.iterator();
        while (it.hasNext()) {
            HutStrip hs = it.next();
            ServerLevel level = server.getLevel(hs.dim());
            if (level == null) { it.remove(); continue; }
            if (level.getGameTime() < hs.execTick()) continue;
            for (BlockPos at : hs.positions()) stripHutsAround(level, at);
            it.remove();
        }
    }

    private static void stripHutsAround(ServerLevel level, BlockPos at) {
        int h = BUILDING_PAD_HALF + 2;
        net.minecraft.world.level.block.state.BlockState sub = Blocks.STONE_BRICKS.defaultBlockState();
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                for (int dy = -1; dy < PAD_CLEAR_HEIGHT; dy++) {
                    BlockPos p = new BlockPos(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (level.getBlockState(p).getBlock()
                            instanceof com.minecolonies.api.blocks.AbstractBlockHut) {
                        level.setBlock(p, sub, 2);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Settlement generation cadence (Stage 4: natural placement is now VANILLA
    // WORLDGEN — see tickWorldgenSettlements + the worldgen/ data JSON. The old
    // per-dimension lastNaturalDay scatter throttle was removed with the scatter.)
    // ------------------------------------------------------------------

    /** Cadence for the AMBIENT sub-passes (garrison tether, proximity
     *  discovery, dwarven-village polling, natural gen). 100 ticks (5 s) —
     *  these are slow/proximity/daily processes; only the active-assault
     *  drive stays on the per-second cadence. */
    private static final long AMBIENT_PERIOD_TICKS = 100L;

    /** Called every second from the server tick. The active-assault drive
     *  runs every call (combat targeting); the garrison tether, proximity
     *  discovery, village polling, and rare natural generation run every
     *  {@link #AMBIENT_PERIOD_TICKS} (no per-second granularity needed). */
    public static void tick(MinecraftServer server) {
        if (!WorldReputationManager.isFactionSystemEnabled()) return;
        // #5 — run any due post-placement hut strips (cheap when none queued).
        tickHutStrips(server);
        boolean settlementsOn = Config.RIVAL_SETTLEMENT_MODE.get() != Config.SettlementMode.NONE;
        boolean ambient = server.getTickCount() % AMBIENT_PERIOD_TICKS == 0;

        for (ServerLevel level : server.getAllLevels()) {
            // Stage C — drive any active assault EVERY second (combat
            // targeting / win check are timing-sensitive).
            tickAssaults(level);

            // Everything below is ambient — throttled to AMBIENT_PERIOD_TICKS.
            if (!ambient) continue;

            // Stage B — keep each garrison tethered to its settlement.
            // Runs whenever the faction system is on (independent of the
            // natural-gen toggle) so debug-spawned garrisons behave too.
            tickGarrison(level);
            // Stage C — proximity discovery.
            tickDiscovery(level);

            if (!settlementsOn) continue;

            // Dwargon: poll for players standing in an unevaluated dwarf
            // village (a structure lookup per player).
            tickDwarvenVillages(level);

            // Stage 4 — worldgen is now the ONLY natural settlement source.
            // The old proximity scatter (which dropped towns right next to the
            // player, the original complaint) is RETIRED. RIVAL_NATURAL_GEN
            // keeps its meaning: true = our worldgen faction anchors auto-
            // populate into full settlements as players explore; false = anchors
            // stay dormant markers and only the debug commands populate them.
            if (!Config.RIVAL_NATURAL_GEN.get()) continue;
            tickWorldgenSettlements(level);
        }
    }

    // ------------------------------------------------------------------
    // Dwargon — adopting EXISTING Tensura dwarven villages (Change 1).
    // Gazel already spawns in dwarf villages, so a separate MC town is
    // redundant. Instead, the FIRST time a player stands in a dwarf
    // village we roll the wild/colony split ONCE for that village: on a
    // COLONY roll it becomes a DWARVEN_VILLAGE-type Dwargon settlement
    // (Gazel marked as anchor, the village's own buildings stand); a WILD
    // roll leaves it a plain village. Either way the village center is
    // remembered so it's never re-rolled.
    // ------------------------------------------------------------------

    /** Resolve {@code tensura:dwarf_village}, or null if absent (Tensura
     *  disabled / datapack override) — caller skips the pass. */
    private static net.minecraft.world.level.levelgen.structure.Structure dwarfVillageStructure(
            ServerLevel level) {
        return level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .get(DWARF_VILLAGE_KEY);
    }

    /** Per-tick poll: any player standing in an unevaluated dwarf village
     *  triggers a one-time wild/colony roll for that village. */
    private static void tickDwarvenVillages(ServerLevel level) {
        if (Config.RIVAL_SETTLEMENT_MODE.get() == Config.SettlementMode.NONE) return;
        net.minecraft.world.level.levelgen.structure.Structure dwarfVillage =
                dwarfVillageStructure(level);
        if (dwarfVillage == null) return;
        SettlementSavedData data = SettlementSavedData.get(level);

        for (ServerPlayer player : level.players()) {
            net.minecraft.world.level.levelgen.structure.StructureStart start =
                    level.structureManager().getStructureAt(player.blockPosition(), dwarfVillage);
            if (!start.isValid()) continue;
            net.minecraft.world.level.levelgen.structure.BoundingBox box = start.getBoundingBox();
            BlockPos villageCenter = box.getCenter();
            if (data.isVillageEvaluated(villageCenter)) continue;
            data.markVillageEvaluated(villageCenter);
            if (rollColony(level)) {
                registerDwarvenVillage(level, data, villageCenter, box);
            } else {
                LOGGER.info("[TM] rival: dwarf village @ {} rolled WILD (plain village)", villageCenter);
            }
        }
    }

    /**
     * Stage 2 — the WORLDGEN settlement populate trigger. For each of our own
     * faction structures ({@link #WORLDGEN_STRUCTURES}), poll whether any player
     * is standing inside a generated start; the first time one is, build the real
     * settlement there ({@link #populateSettlementAt} → generateColony, with the
     * config gate + pack-readiness guard + double-populate guard by start
     * center). Mirrors the proven {@link #tickDwarvenVillages} poll — runs on the
     * server thread (safe, unlike the removed ChunkEvent.Load spike). The
     * structure's own marker keeps it {@code /locate}-able; this only adds the
     * boss/garrison/buildings on first visit.
     */
    /** How many chunks around a player to scan for an unpopulated anchor's start. */
    private static final int WORLDGEN_DETECT_CHUNK_RADIUS = 4; // ~64 blocks

    private static void tickWorldgenSettlements(ServerLevel level) {
        if (WORLDGEN_STRUCTURES.isEmpty()) return;
        var registry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Map.Entry<net.minecraft.resources.ResourceKey<
                net.minecraft.world.level.levelgen.structure.Structure>, String> e
                : WORLDGEN_STRUCTURES.entrySet()) {
            net.minecraft.world.level.levelgen.structure.Structure structure = registry.get(e.getKey());
            if (structure == null) continue; // datapack override / not loaded
            String factionId = e.getValue();
            for (ServerPlayer player : level.players()) {
                // Scan the loaded chunks around the player for this structure's
                // START. This is BB-INDEPENDENT (unlike getStructureAt, which
                // needs the player inside the start's bounding box) — the start
                // is recorded in its origin chunk, so a marker of any footprint
                // is found, and already-generated anchors work too.
                net.minecraft.world.level.ChunkPos pc = player.chunkPosition();
                int r = WORLDGEN_DETECT_CHUNK_RADIUS;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        net.minecraft.world.level.chunk.LevelChunk chunk =
                                level.getChunkSource().getChunkNow(pc.x + dx, pc.z + dz);
                        if (chunk == null) continue; // not loaded — skip (don't force-load)
                        net.minecraft.world.level.levelgen.structure.StructureStart start =
                                chunk.getStartForStructure(structure);
                        if (start == null || !start.isValid()) continue;
                        BlockPos center = start.getBoundingBox().getCenter();
                        if (data.isPopulated(center.asLong())) continue;
                        populateSettlementAt(level, center, factionId, data);
                    }
                }
            }
        }
    }

    /** Register an existing dwarf village as a DWARVEN_VILLAGE-type
     *  Dwargon settlement: find Gazel within the village bounds (spawn one
     *  at the center if absent) and MARK him as the anchor boss. No
     *  buildings are placed — the village's own structures stand. */
    private static Settlement registerDwarvenVillage(
            ServerLevel level, SettlementSavedData data, BlockPos center,
            net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        Settlement s = new Settlement();
        s.id = data.allocateId();
        s.structureType = Settlement.StructureType.DWARVEN_VILLAGE;
        s.factionId = DWARGON;
        s.dimension = level.dimension();
        s.center = center;
        s.packName = ""; // no MC pack — the village's own buildings
        // buildingPositions stays empty; B/C operate on center + boss.

        Mob gazel = findOrSpawnGazel(level, center, box);
        if (gazel != null) {
            s.bossUuid = gazel.getUUID();
            WorldReputationManager.markBoss(gazel, DWARGON, "rival_colony", true);
        }

        data.put(s);
        // Stage B — raise the themed garrison among the village.
        spawnGarrison(level, s);
        LOGGER.info("[TM] rival: dwarf village @ {} adopted as Dwargon settlement #{} (Gazel {})",
                center, s.id, gazel != null ? "marked" : "MISSING");
        return s;
    }

    /** Find a Gazel already inside the village bounds, else spawn one at
     *  the village center. */
    private static Mob findOrSpawnGazel(
            ServerLevel level, BlockPos center,
            net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        EntityType<?> gazelType = ANCHORS.get(DWARGON).get();
        net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(
                box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        for (Mob m : level.getEntitiesOfClass(Mob.class, aabb,
                m -> m.getType() == gazelType && m.isAlive())) {
            return m; // adopt the village's existing Gazel
        }
        // No Gazel present — spawn one unmarked; the caller marks it once.
        return spawnAnchorBoss(level, DWARGON, center.above(), false);
    }

    // (Stage 4) randomPhysicalFaction / scatterNear / tooCloseToExisting were
    // removed with the proximity scatter — vanilla worldgen now decides where
    // settlements appear (per-faction structure_set placement in the data JSON).

    // ==================================================================
    // STAGE B — garrison raise / scale / tether / reset / win-tracking
    // ==================================================================

    /** Boss EP → garrison scale factor (the dampened-EP-ratio formula). */
    private static double scaleForBoss(double bossEP) {
        double ratio = Math.max(bossEP, 1.0) / GARRISON_BASELINE_EP;
        double scale = Math.pow(ratio, GARRISON_SCALE_EXPONENT);
        return Math.max(GARRISON_SCALE_MIN, Math.min(GARRISON_SCALE_MAX, scale));
    }

    private static int countForScale(double scale) {
        int n = (int) Math.round(GARRISON_BASE_COUNT * scale);
        return Math.max(GARRISON_MIN_COUNT, Math.min(GARRISON_MAX_COUNT, n));
    }

    private static double statFactorForScale(double scale) {
        return Math.min(GARRISON_STAT_FACTOR_MAX, 1.0 + (scale - 1.0) * GARRISON_STAT_PER_SCALE);
    }

    /** Explicit stat-buff factor for a PLACEHOLDER boss whose native EP is
     *  too modest for its faction's intended power tier (e.g. Mai anchoring
     *  the Eastern Empire). ⚠ BALANCE GUESS. */
    static final double EMPIRE_BOSS_BUFF = 3.5;

    // The Jura-Tempest anchor Slime ("Rimuru") is buffed with absolute,
    // demon-lord-tier values in buffRimuruBoss (not a flat multiplier) — see
    // spawnAnchorBoss. The old SLIME_BOSS_BUFF ×8 was removed.

    /** Per-faction POWER TIER multiplier applied to the EP-driven garrison
     *  scale (boosts both count and stat factor). Lets a major power field a
     *  strong garrison independent of its (possibly placeholder) boss's EP.
     *  ⚠ ALL BALANCE GUESSES — no combat playtest. */
    private static double factionPowerMultiplier(String factionId) {
        return switch (factionId) {
            // Eastern Empire — a major military power, comparable to or
            // stronger than the Holy bloc: one of the strongest factions.
            case "eastern_empire" -> 1.6;
            default -> 1.0;
        };
    }

    /** Read a boss mob's EP, with a scale-1 fallback. */
    private static double readBossEP(Mob boss) {
        ExistenceStorage exist = ExampleMod.readExistence(boss);
        return exist != null && exist.getEP() > 0 ? exist.getEP() : GARRISON_FALLBACK_BOSS_EP;
    }

    /**
     * Raise the settlement's garrison: read the boss's EP, derive the
     * scale (count + stat multiplier), stamp the boss with GARRISON_TAG,
     * and spawn the themed defenders around the center — buffed,
     * persistent, tagged, tethered. Works for BOTH structure types (it
     * only needs the center). Snapshots {@code defenderCountAtStart} and
     * resets the assault counters. No-op if the faction has no roster.
     */
    static void spawnGarrison(ServerLevel level, Settlement s) {
        EntityType<? extends Mob>[] roster = garrisonRoster(s.factionId);
        if (roster == null || roster.length == 0) return;

        Mob boss = resolveBoss(level, s);
        double bossEP = boss != null ? readBossEP(boss) : GARRISON_FALLBACK_BOSS_EP;
        // Per-faction POWER TIER multiplier on top of the EP-driven scale —
        // lets a faction be strong regardless of its (possibly placeholder)
        // boss's native EP. ⚠ BALANCE GUESS.
        double scale = Math.min(GARRISON_SCALE_MAX,
                scaleForBoss(bossEP) * factionPowerMultiplier(s.factionId));
        int count = countForScale(scale);
        double statFactor = statFactorForScale(scale);

        // The boss is part of the garrison — stamp it so its death routes
        // through the tally as the boss-down flag (it keeps its
        // FactionMarkTag for the Layer-1 fan-out; the two coexist).
        if (boss != null) {
            boss.setData(Attachments.GARRISON_TAG.get(), new GarrisonTag(s.id, true));
            assignFactionDefenderSkills(boss, s.factionId);
            // Sentient driver on the anchor boss (replaces the slime-boss
            // autocaster + lets every faction boss use its learned active kit).
            // Self-scoping: passive-only / native-caster bosses (Ifrit, Mai)
            // have no learned ACTIVE skills, so Sentient is a no-op for them;
            // the Slime (learned Water Blade + Corrosion) is the one it drives —
            // which is the whole point. Skip the untouched set (e.g. Hinata).
            if (!isSkillUntouched(boss.getType())) {
                ExampleMod.grantSentient(boss);
            }
            // Leon's anchor Ifrit NATIVE-CASTS its fire kit (verified) — so we
            // grant NO active fire skills (that would duplicate/conflict).
            // Instead it gets boss-tier PASSIVE fire IMMUNITY (nullification
            // supersedes the rank-and-file resistance) + Self-Regeneration
            // sustain. Its offensive mastery is native; the EP-driven scaler
            // already makes it among the strongest bosses.
            if ("leon".equals(s.factionId)) {
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.FLAME_ATTACK_NULLIFICATION);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.HEAT_NULLIFICATION);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
            }
            // Eastern Empire — Mai is a PLACEHOLDER boss, so she is explicitly
            // scaled up to a credible major-power commander (her native EP is
            // modest). Stat-buff + magitech-armour / sustain skills.
            if ("eastern_empire".equals(s.factionId)) {
                buffDefender(boss, EMPIRE_BOSS_BUFF);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.IntrinsicSkills.BODY_ARMOR);
            }
            // Jura-Tempest — the anchor SLIME ("Rimuru") gets a canon slime kit
            // (Predator / Water Blade / Corrosion + self-regen). Its STATS are
            // set earlier, in buffRimuruBoss (called from spawnAnchorBoss, before
            // the garrison is scaled) — NOT here — which is why the old
            // buffDefender(SLIME_BOSS_BUFF) call is gone.
            //
            // The Slime does NOT native-cast (its brain is melee/leap only —
            // jar-verified), so the active part of the kit (Water Blade /
            // Corrosion) is driven by the Sentient skill granted to the anchor
            // boss above. Predator (analytic utility) and Self-Regeneration
            // (passive) are learn-only and Sentient leaves them be.
            if ("tempest".equals(s.factionId)) {
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.UniqueSkills.PREDATOR);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.WATER_BLADE);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.CORROSION);
                grantDefenderSkill(boss,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
                // Verification — read the kit back out of the boss's SkillStorage
                // so the grant's success is confirmable in the log (Sentient can
                // only fire LEARNED skills).
                logLearnedKit(boss, "tempest slime boss",
                        io.github.manasmods.tensura.registry.skill.CommonSkills.WATER_BLADE,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.CORROSION,
                        io.github.manasmods.tensura.registry.skill.UniqueSkills.PREDATOR,
                        io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
            }
        }

        s.garrisonUuids.clear();
        java.util.Set<EntityType<?>> usedUniques = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            EntityType<? extends Mob> type = pickGarrisonType(roster, i, usedUniques);
            Mob defender = spawnDefender(level, type, s, statFactor);
            if (defender != null) s.garrisonUuids.add(defender.getUUID());
        }
        s.defenderCountAtStart = s.garrisonUuids.size();
        s.defenderKills = 0;
        s.bossDead = false;
        s.assaulted = false;
        SettlementSavedData.get(level).markChanged();
        LOGGER.info("[TM] rival: settlement #{} ({}) garrison raised — {} defenders, bossEP {} → scale {} (stat×{})",
                s.id, s.factionId, s.defenderCountAtStart, String.format("%.0f", bossEP),
                String.format("%.2f", scale), String.format("%.2f", statFactor));
    }

    /** Spawn one defender near the settlement center: the raid-engine
     *  path (create + finalizeSpawn(SPAWN_EGG) + persist), GARRISON_TAG,
     *  and the boss-EP-scaled stat-bump. */
    private static Mob spawnDefender(ServerLevel level, EntityType<? extends Mob> type,
                                     Settlement s, double statFactor) {
        Mob mob = type.create(level);
        if (mob == null) return null;
        int dx = level.getRandom().nextInt(GARRISON_SPAWN_RADIUS * 2 + 1) - GARRISON_SPAWN_RADIUS;
        int dz = level.getRandom().nextInt(GARRISON_SPAWN_RADIUS * 2 + 1) - GARRISON_SPAWN_RADIUS;
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                s.center.offset(dx, 0, dz));
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null);
        mob.setPersistenceRequired();
        mob.setData(Attachments.GARRISON_TAG.get(), new GarrisonTag(s.id, false));
        buffDefender(mob, statFactor);
        // #9 — a bone golem gets ONE random element's casting skill, at a
        // mastery scaled to its faction's lore power.
        if (type == HumanEntityTypes.BONE_GOLEM.get()) {
            assignBoneGolemElement(mob, s.factionId);
        }
        assignFactionDefenderSkills(mob, s.factionId);
        // Sentient driver (replaces the bone-golem autocaster + drives every
        // garrison caster's kit). Skipped for the skill-untouched set, same as
        // the faction-skill pass. Self-scoping: a mob with only passive skills
        // just won't cast — only mobs with learned ACTIVE skills (e.g. the bone
        // golem's element) actually fire.
        if (!isSkillUntouched(type)) {
            ExampleMod.grantSentient(mob);
        }
        if (!level.addFreshEntity(mob)) return null;
        return mob;
    }

    /** Apply the boss-EP-scaled stat multiplier across the four combat
     *  attributes (the assassin multiplyAttribute idiom; Tensura attrs
     *  guarded — not every mob has magicule/aura). */
    private static void buffDefender(Mob mob, double factor) {
        multiplyAttribute(mob, Attributes.MAX_HEALTH, GARRISON_HP_ID, factor);
        multiplyAttribute(mob, Attributes.ATTACK_DAMAGE, GARRISON_DMG_ID, factor);
        try {
            multiplyAttribute(mob, TensuraAttributes.MAX_MAGICULE, GARRISON_MAG_ID, factor);
        } catch (Throwable ignored) { }
        try {
            multiplyAttribute(mob, TensuraAttributes.MAX_AURA, GARRISON_AURA_ID, factor);
        } catch (Throwable ignored) { }
        mob.setHealth(mob.getMaxHealth());
    }

    /** ×factor as a stable-id ADD modifier — delta = current × (factor−1),
     *  remove-first so it never compounds (the Assassins idiom). */
    private static void multiplyAttribute(LivingEntity mob, Holder<Attribute> attr,
                                          ResourceLocation id, double factor) {
        AttributeInstance instance = mob.getAttribute(attr);
        if (instance == null) return;
        instance.removeModifier(id);
        double delta = instance.getValue() * (factor - 1.0);
        instance.addPermanentModifier(new AttributeModifier(id, delta,
                AttributeModifier.Operation.ADD_VALUE));
    }

    /** Resolve the live boss entity for a settlement, or null (unloaded
     *  / dead). */
    private static Mob resolveBoss(ServerLevel level, Settlement s) {
        if (s.bossUuid == null) return null;
        Entity e = level.getEntity(s.bossUuid);
        return e instanceof Mob mob && mob.isAlive() ? mob : null;
    }

    // ==================================================================
    // #9 — BONE GOLEM combat kit (one random element skill)
    // ==================================================================
    // Each garrison bone golem learns ONE random element's attack skill at
    // spawn; the Sentient skill (granted in spawnDefender) drives it to cast
    // that skill in combat. The skill's mastery scales with faction lore power.

    private static final int BONE_GOLEM_ELEMENT_COUNT = 5;

    /** The single attack skill for each element (0=darkness 1=wind 2=earth
     *  3=fire 4=water). Wind uses Voice Cannon (sonic); earth uses Earth
     *  Manipulation (the only earth attack — less battle-tested as a mob
     *  cast than the others). */
    private static io.github.manasmods.manascore.skill.api.ManasSkill boneGolemElementSkill(int element) {
        return switch (element) {
            case 0 -> io.github.manasmods.tensura.registry.skill.ExtraSkills.BLACK_FLAME.get();
            case 1 -> io.github.manasmods.tensura.registry.skill.CommonSkills.VOICE_CANNON.get();
            case 2 -> io.github.manasmods.tensura.registry.skill.ExtraSkills.EARTH_MANIPULATION.get();
            case 3 -> io.github.manasmods.tensura.registry.skill.ExtraSkills.HEAT_WAVE.get();
            default -> io.github.manasmods.tensura.registry.skill.CommonSkills.WATER_BLADE.get();
        };
    }

    /** Faction lore-power → cast skill mastery as a fraction of max.
     *  ⚠ BALANCE GUESS / proposed ranking (pending approval). */
    private static double boneGolemMasteryFraction(String factionId) {
        return switch (factionId) {
            case "milim" -> 1.0;                                          // apex power
            case "leon", "eurazania", "luminous", "clayman",
                 "eastern_empire" -> 0.8;                                 // demon lords / great powers
            case "dwargon" -> 0.6;                                        // strong realms
            default -> 0.4;                                               // falmuth, tempest…
        };
    }

    /** Teach a freshly-spawned bone golem one random element's attack skill
     *  at a faction-scaled mastery. */
    private static void assignBoneGolemElement(Mob golem, String factionId) {
        int element = golem.getRandom().nextInt(BONE_GOLEM_ELEMENT_COUNT);
        io.github.manasmods.manascore.skill.api.ManasSkill skill = boneGolemElementSkill(element);
        try {
            var storage = io.github.manasmods.manascore.skill.api.SkillAPI.getSkillsFrom(golem);
            if (storage.getSkill(skill.getRegistryName()).isEmpty()) {
                storage.learnSkill(skill.createDefaultInstance(), Component.literal(""));
            }
            double frac = boneGolemMasteryFraction(factionId);
            storage.getSkill(skill.getRegistryName()).ifPresent(inst -> {
                inst.setMastery((int) Math.max(1, inst.getMaxMastery() * frac));
                storage.updateSkill(inst, true);
            });
        } catch (Throwable ignored) { }
    }

    /**
     * Per-second tether: keep each loaded defender from wandering past
     * {@link #GARRISON_TETHER_RADIUS} of its settlement center — the
     * patrol/steering containment idiom (feed WALK_TARGET back toward
     * center; native combat owns the mob while it has a live target).
     * Also prunes confirmed-gone UUIDs (e.g. /kill without a death event)
     * so the live count stays honest.
     */
    private static void tickGarrison(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        boolean changed = false;
        for (Settlement s : data.all()) {
            if (!s.dimension.equals(level.dimension())) continue;
            if (s.conquered) continue; // husk — inert, no garrison
            if (s.garrisonUuids.isEmpty()) continue;
            long tetherSq = (long) GARRISON_TETHER_RADIUS * GARRISON_TETHER_RADIUS;
            Iterator<UUID> it = s.garrisonUuids.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                Entity e = level.getEntity(uuid);
                if (e == null) continue; // unloaded — keep
                if (e.isRemoved() || !(e instanceof Mob mob) || !mob.isAlive()) {
                    it.remove(); // gone without a death event — drop, no tally
                    changed = true;
                    continue;
                }
                if (mob.getTarget() != null && mob.getTarget().isAlive()) continue; // fighting
                if (mob.blockPosition().distSqr(s.center) > tetherSq) {
                    mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(s.center, GARRISON_WALK_SPEED, GARRISON_CLOSE_ENOUGH));
                }
            }
        }
        if (changed) data.markChanged();
    }

    // --- assault state machine + the 60%-win primitives ----------------

    /** Defenders that must fall for conquest (ceil of the win fraction). */
    static int requiredDefenderKills(Settlement s) {
        return (int) Math.ceil(GARRISON_WIN_FRACTION * s.defenderCountAtStart);
    }

    /** The conquest condition Stage C checks: boss down AND ≥60% of the
     *  starting garrison killed. */
    static boolean isConquestEligible(Settlement s) {
        return s.bossDead && s.defenderKills >= requiredDefenderKills(s);
    }

    /** Begin an assault (Stage C entry): snapshot the TRACKED garrison
     *  roster ({@code garrisonUuids} — the persisted living count, robust
     *  to the settlement chunks being unloaded when war is declared from
     *  afar) as the denominator, zero the kill tally, clear the boss-down
     *  flag, flip to ASSAULTED. */
    static void beginAssault(ServerLevel level, Settlement s) {
        if (s.conquered) return; // husk — never re-assaulted
        s.defenderCountAtStart = s.garrisonUuids.size();
        s.defenderKills = 0;
        s.bossDead = false;
        s.assaulted = true;
        SettlementSavedData.get(level).markChanged();
        LOGGER.info("[TM] rival: settlement #{} ASSAULT begun — {} defenders, need {} kills + boss",
                s.id, s.defenderCountAtStart, requiredDefenderKills(s));
    }

    /**
     * The RESET primitive (Stage C calls this on an INCOMPLETE assault —
     * the player retreated or died without conquering): top the garrison
     * back up to {@code defenderCountAtStart}, heal every survivor, and
     * REVIVE-or-HEAL the boss (full HP + refilled magicule/aura pools).
     * Each assault therefore starts fresh. Clears the assault counters
     * and returns to IDLE.
     *
     * <p>⚠ Tracked risk — the boss EP/pool restore: a boss may have
     * outright DIED in an incomplete assault (killed but &lt;60% defenders
     * cleared), so this respawns a fresh marked boss when the old one is
     * gone; when it survives we refill HP + magicule + aura (guarded —
     * not every anchor has Tensura energy pools).
     */
    static void resetGarrison(ServerLevel level, Settlement s) {
        if (s.conquered) return; // husk — never respawns its garrison
        // Heal/revive the boss first (so the topped-up garrison rallies to
        // a whole anchor).
        reviveOrHealBoss(level, s);

        // Heal surviving defenders + strip any Stage-E betrayal stat-bump
        // (betrayal is per-assault; a fresh assault re-derives it, so the
        // survivors must not carry the last assault's multiplier).
        int alive = 0;
        // Seed the unique-tracker with Otherworlders that are STILL ALIVE, so
        // the top-up below doesn't respawn a second copy of a living unique.
        java.util.Set<EntityType<?>> usedUniques = new java.util.HashSet<>();
        for (UUID uuid : s.garrisonUuids) {
            Entity e = level.getEntity(uuid);
            if (e instanceof Mob mob && mob.isAlive()) {
                stripBetrayalBuff(mob);
                mob.setHealth(mob.getMaxHealth());
                if (isUniqueGarrisonMob(mob.getType())) usedUniques.add(mob.getType());
                alive++;
            }
        }
        // Respawn the shortfall back to the starting count.
        EntityType<? extends Mob>[] roster = garrisonRoster(s.factionId);
        if (roster != null && roster.length > 0) {
            double scale = scaleForBoss(s.bossUuid != null && resolveBoss(level, s) != null
                    ? readBossEP(resolveBoss(level, s)) : GARRISON_FALLBACK_BOSS_EP);
            double statFactor = statFactorForScale(scale);
            for (int i = alive; i < s.defenderCountAtStart; i++) {
                EntityType<? extends Mob> type = pickGarrisonType(roster, i, usedUniques);
                Mob defender = spawnDefender(level, type, s, statFactor);
                if (defender != null) s.garrisonUuids.add(defender.getUUID());
            }
        }
        s.defenderKills = 0;
        s.bossDead = false;
        s.assaulted = false;
        SettlementSavedData.get(level).markChanged();
        LOGGER.info("[TM] rival: settlement #{} RESET — garrison restored to {} (had {} alive), boss healed",
                s.id, s.defenderCountAtStart, alive);
    }

    /** Heal the boss to full (HP + magicule + aura), or respawn+mark a
     *  fresh one if it died. */
    private static void reviveOrHealBoss(ServerLevel level, Settlement s) {
        Mob boss = resolveBoss(level, s);
        if (boss == null) {
            // Boss died (or unloaded-and-gone) — raise a fresh marked one.
            Mob fresh = spawnAnchorBoss(level, s.factionId, s.center.above(), true);
            if (fresh != null) {
                fresh.setData(Attachments.GARRISON_TAG.get(), new GarrisonTag(s.id, true));
                s.bossUuid = fresh.getUUID();
            }
            return;
        }
        boss.setHealth(boss.getMaxHealth());
        try {
            EnergyHelper.gainMagicule(boss, EnergyHelper.getMaxMagicule(boss),
                    EnergyHelper.GainType.NORMAL);
        } catch (Throwable ignored) { }
        try {
            EnergyHelper.gainAura(boss, EnergyHelper.getMaxAura(boss),
                    EnergyHelper.GainType.NORMAL);
        } catch (Throwable ignored) { }
    }

    private static int liveDefenderCount(ServerLevel level, Settlement s) {
        int alive = 0;
        for (UUID uuid : s.garrisonUuids) {
            Entity e = level.getEntity(uuid);
            if (e instanceof Mob mob && mob.isAlive()) alive++;
        }
        return alive;
    }

    /**
     * Garrison death bookkeeping — called from
     * {@code ExampleMod.onLivingDeath}. A boss death sets the boss-down
     * flag; a defender death increments the kill tally and drops the UUID
     * from the live set. (The tally counts continuously so Stage B is
     * testable; Stage C's {@link #beginAssault} re-zeroes it.)
     */
    static void onGarrisonMobDeath(ServerLevel level, LivingEntity victim) {
        if (!victim.hasData(Attachments.GARRISON_TAG.get())) return;
        GarrisonTag tag = victim.getData(Attachments.GARRISON_TAG.get());
        if (tag == null) return;
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement s = data.get(tag.settlementId());
        if (s == null) return;
        if (tag.isBoss()) {
            s.bossDead = true;
            LOGGER.info("[TM] rival: settlement #{} BOSS down", s.id);
        } else {
            s.garrisonUuids.remove(victim.getUUID());
            s.defenderKills++;
            LOGGER.info("[TM] rival: settlement #{} defender killed — tally {}/{} (need {})",
                    s.id, s.defenderKills, s.defenderCountAtStart, requiredDefenderKills(s));
        }
        data.markChanged();
    }

    // ==================================================================
    // STAGE C — discovery + Declare-War + the teleport-assault loop
    // ==================================================================

    /** A player within this many blocks of a settlement center DISCOVERS
     *  it (per-player; unlocks Declare War for them). */
    static final int DISCOVERY_RANGE = 80;
    /** Max war-party size taken into an assault. */
    static final int WAR_PARTY_CAP = 15;
    /** Party members materialize within this radius of the player on
     *  teleport-in / teleport-back. */
    private static final int WAR_PARTY_SPREAD = 3;

    // --- discovery -----------------------------------------------------

    /** Per-second proximity discovery: any player within
     *  {@link #DISCOVERY_RANGE} of a settlement center records it in the
     *  settlement's {@code discoveredBy} (the per-player Declare-War
     *  unlock). The dwarven-village/envoy per-player pass shape. */
    private static void tickDiscovery(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.all().isEmpty()) return;
        boolean changed = false;
        long rangeSq = (long) DISCOVERY_RANGE * DISCOVERY_RANGE;
        for (Settlement s : data.all()) {
            if (!s.dimension.equals(level.dimension())) continue;
            // Defensive: a pre-existing settlement of a soft-retired faction
            // (e.g. an old-save Shizu Pagoda) is never (re)discovered, so it
            // can't be warred on — it just sits inert. No save migration.
            if (!BossFaction.isActiveId(s.factionId)) continue;
            for (ServerPlayer player : level.players()) {
                if (s.discoveredBy.contains(player.getUUID())) continue;
                if (player.blockPosition().distSqr(s.center) <= rangeSq) {
                    s.discoveredBy.add(player.getUUID());
                    changed = true;
                    BossFaction f = BossFaction.byId(s.factionId);
                    player.sendSystemMessage(Component.literal("You have discovered a "
                            + (f != null ? f.displayName() : s.factionId)
                            + " settlement — you may Declare War on it from the roster.")
                            .withStyle(f != null ? f.color() : net.minecraft.ChatFormatting.GOLD));
                    LOGGER.info("[TM] rival: settlement #{} discovered by {}",
                            s.id, player.getName().getString());
                }
            }
        }
        if (changed) data.markChanged();
    }

    static boolean isDiscoveredBy(Settlement s, java.util.UUID player) {
        return s.discoveredBy.contains(player);
    }

    // --- Declare War + teleport-in -------------------------------------

    /** The player's LOADED Tensura subordinates (the war-party candidate
     *  pool) — owned mobs in the player's current level. */
    static List<Mob> loadedSubordinates(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<Mob> out = new ArrayList<>();
        java.util.UUID me = player.getUUID();
        AABB scan = player.getBoundingBox().inflate(256);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, scan, m -> m.isAlive()
                && m instanceof ISubordinate
                && me.equals(SubordinateHelper.getSubordinateOwnerUUID(m)))) {
            out.add(mob);
        }
        return out;
    }

    /**
     * Declare War on a discovered settlement: validate, record the return
     * origin, snapshot the garrison ({@link #beginAssault}), teleport the
     * player + the chosen war party in, and turn the garrison hostile on
     * the invaders. {@code partyEntityIds} are network ids of LOADED owned
     * subordinates (resolved server-side). Returns a player-facing string.
     */
    static String declareWar(ServerPlayer player, int settlementId, List<Integer> partyEntityIds) {
        if (!WorldReputationManager.isFactionSystemEnabled()) {
            return "the faction system is disabled (enableFactionSystem=false)";
        }
        ServerLevel originLevel = player.serverLevel();
        SettlementSavedData data = SettlementSavedData.get(originLevel);
        Settlement s = data.get(settlementId);
        if (s == null) return "no settlement #" + settlementId + ".";
        if (!isDiscoveredBy(s, player.getUUID())) return "you haven't discovered that settlement yet.";
        if (s.conquered) return "that settlement is already a conquered husk.";
        if (s.assaulted) {
            return s.assaultingPlayer != null && s.assaultingPlayer.equals(player.getUUID())
                    ? "you are already assaulting that settlement."
                    : "that settlement is already under assault.";
        }
        ServerLevel settlementLevel = originLevel.getServer().getLevel(s.dimension);
        if (settlementLevel == null) return "the settlement's dimension isn't loaded.";

        // Record the return trip BEFORE moving the player.
        s.assaultingPlayer = player.getUUID();
        s.assaultOrigin = player.blockPosition();
        s.assaultOriginDim = originLevel.dimension();
        s.pendingReturn = false;
        s.conquestReached = false;

        // Stage E — BETRAYAL: if the player has diplomatic relations with
        // this faction, scale the garrison up by the relationship tier and
        // crash standing (which shatters the relationship via the existing
        // below-WARY collapse). Always sets the faction hostile.
        BossFaction factionEnum = BossFaction.byId(s.factionId);
        if (factionEnum != null) {
            RelationsState rel = DiplomacyManager.getState(originLevel, player.getUUID(), factionEnum);
            s.betrayalFactor = betrayalMultFor(rel);
            s.betrayalTier = rel.name();
            // War-declaration standing crash → hostile; the diplomacy
            // collapse pass shatters any relations next tick (no new code).
            WorldReputationManager.modifyStanding(originLevel, player.getUUID(), factionEnum,
                    BETRAYAL_STANDING_CRASH, WorldRepReason.WAR_DECLARED);
            if (s.betrayalFactor > 1.0) {
                player.sendSystemMessage(Component.literal("You turn on the "
                        + factionEnum.displayName() + " — they remember every kindness. "
                        + "Their garrison fights with a betrayed fury (×"
                        + String.format("%.2f", s.betrayalFactor) + ").")
                        .withStyle(net.minecraft.ChatFormatting.DARK_RED));
            }
        } else {
            s.betrayalFactor = 1.0;
            s.betrayalTier = "";
        }

        // Snapshot the garrison (count + zero kills + ASSAULTED).
        beginAssault(settlementLevel, s);

        // Teleport the player to the settlement edge, then bring the party.
        // FORCE-LOAD the destination chunk first: declaring war from afar leaves
        // the settlement chunk unloaded, and a heightmap read on an unloaded
        // column returns the BOTTOM of the world → "teleported beneath the
        // world". Then guard the result against a void read by falling back to
        // the settlement's known surface Y (from generation).
        BlockPos arrivalXZ = s.center.offset(GARRISON_SPAWN_RADIUS + 6, 0, 0);
        settlementLevel.getChunk(arrivalXZ.getX() >> 4, arrivalXZ.getZ() >> 4); // load to FULL
        BlockPos arrival = settlementLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, arrivalXZ);
        if (arrival.getY() <= settlementLevel.getMinBuildHeight() + 1) {
            // No terrain at the query — use the settlement's stored surface Y so
            // the player never lands in the void.
            arrival = new BlockPos(arrivalXZ.getX(), s.center.getY() + 1, arrivalXZ.getZ());
        }
        player.teleportTo(settlementLevel, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
                player.getYRot(), player.getXRot());

        s.warParty.clear();
        if (partyEntityIds != null) {
            int taken = 0;
            for (Integer id : partyEntityIds) {
                if (taken >= WAR_PARTY_CAP) break;
                if (id == null) continue;
                net.minecraft.world.entity.Entity e = originLevel.getEntity(id);
                if (!(e instanceof Mob mob) || !mob.isAlive()) continue;
                if (!player.getUUID().equals(SubordinateHelper.getSubordinateOwnerUUID(mob))) continue;
                BlockPos at = partyArrivalPos(settlementLevel, arrival, taken);
                mob.teleportTo(settlementLevel, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                        java.util.Set.of(), mob.getYRot(), mob.getXRot());
                SubordinateHelper.setAggressive(mob);
                s.warParty.add(mob.getUUID());
                taken++;
            }
        }

        // Turn the garrison hostile on the invaders + apply the betrayal
        // buff to whoever's loaded (the tick covers late-loaders).
        steerGarrisonToInvaders(settlementLevel, s, player);
        data.markChanged();

        BossFaction f = BossFaction.byId(s.factionId);
        LOGGER.info("[TM] rival: {} DECLARED WAR on settlement #{} — party {}, {} defenders + boss (betrayal ×{} tier {})",
                player.getName().getString(), s.id, s.warParty.size(), s.defenderCountAtStart,
                String.format("%.2f", s.betrayalFactor), s.betrayalTier);
        return "War declared on the " + (f != null ? f.displayName() : s.factionId)
                + " settlement #" + s.id + " — " + s.warParty.size() + " in your war party. "
                + "Kill the boss and " + (int) (GARRISON_WIN_FRACTION * 100) + "% of "
                + s.defenderCountAtStart + " defenders to conquer it."
                + (s.betrayalFactor > 1.0 ? " (BETRAYAL — garrison ×"
                        + String.format("%.2f", s.betrayalFactor) + ")" : "");
    }

    /** Garrison stat multiplier for a betrayed relationship tier (Stage
     *  E). 1.0 = no betrayal (NONE / no relations). */
    static double betrayalMultFor(RelationsState rel) {
        return switch (rel) {
            case OPEN -> BETRAYAL_MULT_OPEN;
            case PACT -> BETRAYAL_MULT_PACT;
            case COVENANT -> BETRAYAL_MULT_COVENANT;
            default -> 1.0; // NONE — not a betrayal
        };
    }

    /**
     * Apply the Stage-E betrayal buff to one loaded garrison member if it
     * hasn't been applied yet (idempotent — checks the betrayal HP
     * modifier). Stacks ON TOP of B's boss-EP stat-bump (separate
     * modifier ids), then grants the tier's defender skills. Called from
     * {@link #steerGarrisonToInvaders} (per tick) so late-loading
     * defenders get buffed when they appear.
     */
    private static void ensureBetrayalBuffed(Mob mob, Settlement s) {
        if (s.betrayalFactor <= 1.0) return;
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null && hp.getModifier(BETRAYAL_HP_ID) != null) return; // already buffed
        // Stat buff (separate ids → stacks on the B garrison buff).
        multiplyAttribute(mob, Attributes.MAX_HEALTH, BETRAYAL_HP_ID, s.betrayalFactor);
        multiplyAttribute(mob, Attributes.ATTACK_DAMAGE, BETRAYAL_DMG_ID, s.betrayalFactor);
        try {
            multiplyAttribute(mob, TensuraAttributes.MAX_MAGICULE, BETRAYAL_MAG_ID, s.betrayalFactor);
        } catch (Throwable ignored) { }
        try {
            multiplyAttribute(mob, TensuraAttributes.MAX_AURA, BETRAYAL_AURA_ID, s.betrayalFactor);
        } catch (Throwable ignored) { }
        mob.setHealth(mob.getMaxHealth());
        // Tier defender skills (PASSIVE / RESISTANCE — applied by being
        // learned; no cast driver needed for a whole garrison).
        for (var supplier : betrayalSkillsFor(s.betrayalTier, s.factionId)) {
            grantDefenderSkill(mob, supplier);
        }
    }

    /** PASSIVE/RESISTANCE skills granted to defenders by betrayed tier.
     *  OPEN → none (stats only); PACT → physical resistance; COVENANT →
     *  physical resistance + self-regen + the faction's own capstone. */
    private static List<java.util.function.Supplier<
            ? extends io.github.manasmods.manascore.skill.api.ManasSkill>> betrayalSkillsFor(
            String tier, String factionId) {
        List<java.util.function.Supplier<
                ? extends io.github.manasmods.manascore.skill.api.ManasSkill>> out = new ArrayList<>();
        if ("PACT".equals(tier) || "COVENANT".equals(tier)) {
            out.add(io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
        }
        if ("COVENANT".equals(tier)) {
            out.add(io.github.manasmods.tensura.registry.skill.CommonSkills.SELF_REGENERATION);
            var capstone = DealSpec.covenantSkillFor(factionId);
            if (capstone != null) out.add(capstone);
        }
        return out;
    }

    /** Remove the betrayal stat-bump from a defender (the skills learned
     *  stay — Tensura can't cleanly unlearn, and a leftover passive on a
     *  survivor is harmless). Called on reset so betrayal is per-assault. */
    private static void stripBetrayalBuff(Mob mob) {
        removeModifier(mob, Attributes.MAX_HEALTH, BETRAYAL_HP_ID);
        removeModifier(mob, Attributes.ATTACK_DAMAGE, BETRAYAL_DMG_ID);
        try { removeModifier(mob, TensuraAttributes.MAX_MAGICULE, BETRAYAL_MAG_ID); } catch (Throwable ignored) { }
        try { removeModifier(mob, TensuraAttributes.MAX_AURA, BETRAYAL_AURA_ID); } catch (Throwable ignored) { }
    }

    private static void removeModifier(Mob mob, Holder<Attribute> attr, ResourceLocation id) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst != null) inst.removeModifier(id);
    }

    /** Canon-appropriate PASSIVE/RESISTANCE skills granted to a faction's
     *  garrison defenders at spawn (passives work with no cast driver — the
     *  betrayal/Covenant skill path). A seam: add cases as factions earn
     *  signature kits.
     *
     *  <p>LEON (the Platinum Saber's domain of flame): his minions resist
     *  fire — Flame Attack Resistance + Heat Resistance. The boss-anchor Ifrit
     *  brings its own native fire offence; these make the garrison fire-proof
     *  and on-theme. */
    /** Entity types we DELIBERATELY do not touch with granted skills:
     *  Hinata (sufficient as-is) + the canon-uncertain summoned Otherworlder
     *  heroes — Kirara/Kyoya/Shogo (Falmuth) and Shin Ryusei/Mark Lauren/
     *  Shinji Tanimura (Eastern Empire). Their specific canon skills are
     *  uncertain, so they're left as-is (membership-only); handled in a later
     *  reviewed batch. All are native casters. */
    private static boolean isSkillUntouched(EntityType<?> type) {
        return type == HumanEntityTypes.HINATA_SAKAGUCHI.get()
                || type == HumanEntityTypes.KIRARA_MIZUTANI.get()
                || type == HumanEntityTypes.KYOYA_TACHIBANA.get()
                || type == HumanEntityTypes.SHOGO_TAGUCHI.get()
                || type == HumanEntityTypes.MARK_LAUREN.get()
                || type == HumanEntityTypes.SHINJI_TANIMURA.get()
                || type == HumanEntityTypes.SHIN_RYUSEI.get();
    }

    /** Per-faction PASSIVE/RESISTANCE skills (Pass 0). Passives work the
     *  moment they're learned — no autocaster needed and no conflict with a
     *  mob's native casting. Thematic on-element resists per faction. The
     *  untouched set above is skipped entirely. */
    private static void assignFactionDefenderSkills(Mob mob, String factionId) {
        if (isSkillUntouched(mob.getType())) return;
        switch (factionId) {
            case "leon" -> { // fire domain
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.FLAME_ATTACK_RESISTANCE);
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.HEAT_RESISTANCE);
            }
            case "eastern_empire" -> // magitech war-constructs: armored
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
            case "tempest" -> { // forest / water / storm kin
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.WATER_ATTACK_RESISTANCE);
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.WIND_ATTACK_RESISTANCE);
            }
            case "dwargon" -> { // tough forge-dwarves
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.HEAT_RESISTANCE);
            }
            case "luminous" -> { // holy crusaders — armoured, resist darkness
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.DARKNESS_ATTACK_RESISTANCE);
            }
            case "falmuth" -> // armoured human soldiery
                grantDefenderSkill(mob,
                        io.github.manasmods.tensura.registry.skill.ResistanceSkills.PHYSICAL_ATTACK_RESISTANCE);
            default -> { /* abstract / no roster — nothing */ }
        }
    }

    /** Teach a mob a skill (idempotent — the Covenant learnSkill path,
     *  applied to a garrison defender). */
    private static void grantDefenderSkill(Mob mob,
            java.util.function.Supplier<? extends io.github.manasmods.manascore.skill.api.ManasSkill> supplier) {
        try {
            var skill = supplier.get();
            var storage = io.github.manasmods.manascore.skill.api.SkillAPI.getSkillsFrom(mob);
            if (storage.getSkill(skill.getRegistryName()).isEmpty()) {
                storage.learnSkill(skill.createDefaultInstance(), Component.literal(""));
            }
        } catch (Throwable ignored) { }
    }

    /** Read the given skills back out of a mob's SkillStorage and log which
     *  LANDED vs MISSING — a runtime confirmation that {@link #grantDefenderSkill}
     *  (which swallows failures) actually stuck. The autocaster can only fire
     *  LEARNED skills, so this is the verification handle for "is the kit on the
     *  boss". */
    @SafeVarargs
    private static void logLearnedKit(Mob mob, String label,
            java.util.function.Supplier<? extends io.github.manasmods.manascore.skill.api.ManasSkill>... skills) {
        try {
            var storage = io.github.manasmods.manascore.skill.api.SkillAPI.getSkillsFrom(mob);
            StringBuilder sb = new StringBuilder();
            for (var sup : skills) {
                var sk = sup.get();
                boolean present = storage.getSkill(sk.getRegistryName()).isPresent();
                sb.append(sk.getRegistryName().getPath())
                  .append('=').append(present ? "LEARNED" : "MISSING").append(' ');
            }
            LOGGER.info("[TM] rival: {} kit verification — {}", label, sb.toString().trim());
        } catch (Throwable t) {
            LOGGER.warn("[TM] rival: {} kit verification could not read SkillStorage", label, t);
        }
    }

    private static BlockPos partyArrivalPos(ServerLevel level, BlockPos around, int index) {
        int dx = (index % 5 - 2) * WAR_PARTY_SPREAD;
        int dz = (index / 5 - 1) * WAR_PARTY_SPREAD;
        BlockPos xz = around.offset(dx, 0, dz);
        level.getChunk(xz.getX() >> 4, xz.getZ() >> 4); // load for a valid heightmap read
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xz);
        if (pos.getY() <= level.getMinBuildHeight() + 1) {
            // void read — drop next to the player's (already-validated) arrival Y
            pos = new BlockPos(xz.getX(), around.getY(), xz.getZ());
        }
        return pos;
    }

    // --- the assault drive + resolution --------------------------------

    /** Per-second: drive every ASSAULTED settlement — resolve a WIN the
     *  moment B's {@link #isConquestEligible} trips, else keep the
     *  garrison locked onto the invaders (the raid steer, inverted). */
    private static void tickAssaults(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Settlement s : data.all()) {
            if (!s.assaulted) continue;
            if (!s.dimension.equals(level.dimension())) continue;
            if (s.assaultingPlayer == null) { s.assaulted = false; continue; }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(s.assaultingPlayer);
            if (player == null) continue; // offline — the logout handler resolves it
            if (isConquestEligible(s)) {
                resolveWin(level, s, player);
                continue;
            }
            steerGarrisonToInvaders(level, s, player);
        }
    }

    /** Make every loaded garrison member (defenders + boss) target the
     *  nearest invader (player or war-party mob) when it has no live
     *  target — the raid {@code steerRaider} dual-write, inverted. */
    private static void steerGarrisonToInvaders(ServerLevel level, Settlement s, ServerPlayer player) {
        List<net.minecraft.world.entity.LivingEntity> invaders = new ArrayList<>();
        invaders.add(player);
        for (java.util.UUID u : s.warParty) {
            if (level.getEntity(u) instanceof Mob m && m.isAlive()) invaders.add(m);
        }
        java.util.List<java.util.UUID> members = new ArrayList<>(s.garrisonUuids);
        if (s.bossUuid != null) members.add(s.bossUuid);
        for (java.util.UUID u : members) {
            if (!(level.getEntity(u) instanceof Mob mob) || !mob.isAlive()) continue;
            // Stage E — buff late-loading defenders the first time we see
            // them (idempotent; declareWar covered any already loaded).
            ensureBetrayalBuffed(mob, s);
            // #7 — highlight defenders so the player can see their targets.
            mob.setGlowingTag(true);
            // Bone golems cast via the Nightmare's Tensura Utils autocaster
            // (registered once at setup); it reads mob.getTarget(), which the
            // target-lock below sets — so no per-tick cast call here.
            net.minecraft.world.entity.LivingEntity cur = mob.getTarget();
            if (cur != null && cur.isAlive()) continue; // already fighting
            net.minecraft.world.entity.LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (net.minecraft.world.entity.LivingEntity inv : invaders) {
                if (!inv.isAlive()) continue;
                double d = inv.distanceToSqr(mob);
                if (d < best) { best = d; nearest = inv; }
            }
            if (nearest != null) {
                BrainUtils.setTargetOfEntity(mob, nearest);
                mob.setTarget(nearest);
                // Bone Golems (and other Tensura constructs) are NeutralMobs:
                // their brain DROPS any hostile target it isn't ANGRY at on the
                // next tick, so setTarget alone never sticks — the golem just
                // stands there (and its autocaster, which reads getTarget(),
                // never fires). Make it genuinely angry at the invader so the
                // target holds for the assault.
                if (mob instanceof net.minecraft.world.entity.NeutralMob neutral) {
                    neutral.setPersistentAngerTarget(nearest.getUUID());
                    neutral.startPersistentAngerTimer();
                }
            }
        }
    }

    /** Turn off the war-highlight on a settlement's garrison + boss (called
     *  when the assault ends, however it ends). */
    private static void clearGarrisonGlow(ServerLevel level, Settlement s) {
        java.util.List<java.util.UUID> members = new ArrayList<>(s.garrisonUuids);
        if (s.bossUuid != null) members.add(s.bossUuid);
        for (java.util.UUID u : members) {
            if (level.getEntity(u) instanceof Mob mob) mob.setGlowingTag(false);
        }
    }

    /**
     * WIN — conquest-eligible reached (bossDead && ≥60% defenders). C
     * resolves the assault: bring the player + survivors home, apply the
     * Stage-D conquest PAYOFF (citizens + Covenant skill + loot), convert
     * the settlement to a DEFEATED HUSK, return to IDLE. The garrison is
     * NOT reset — it has been beaten.
     */
    private static void resolveWin(ServerLevel level, Settlement s, ServerPlayer player) {
        bringPartyHome(level, s, player);
        teleportPlayerHome(s, player);
        s.conquestReached = true;
        BossFaction f = BossFaction.byId(s.factionId);
        player.sendSystemMessage(Component.literal("The " + (f != null ? f.displayName() : s.factionId)
                + " settlement has fallen!")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        LOGGER.info("[TM] rival: settlement #{} CONQUERED by {} — applying conquest payoff",
                s.id, player.getName().getString());
        // Stage D — the conquest payoff + husk conversion (the boss's
        // marked-kill world-rep fan-out already fired on its death; D
        // layers rewards on top, no double-apply).
        ConquestPayoff.apply(level, s, player);
        clearGarrisonGlow(level, s);
        clearAssaultState(s, true); // keep conquestReached
        SettlementSavedData.get(level).markChanged();
    }

    /**
     * RETREAT — an INCOMPLETE assault (player pressed Retreat). Bring the
     * party + player home and RESET the garrison (respawn defenders + heal
     * /revive the boss). The player is alive and online here.
     *
     * <p>⚠ TRACKED RISK — {@link #resetGarrison} may REVIVE the boss as a
     * NEW entity (new UUID). We never cache the old bossUuid across the
     * reset: reset writes the fresh uuid into {@code s.bossUuid}, and any
     * later read goes through {@link #resolveBoss}.
     */
    static void resolveRetreat(ServerLevel level, Settlement s, ServerPlayer player) {
        clearGarrisonGlow(level, s); // #7 — un-highlight before the garrison resets
        bringPartyHome(level, s, player);
        teleportPlayerHome(s, player);
        resetGarrison(level, s); // respawns garrison + revives/heals boss (updates bossUuid)
        BossFaction f = BossFaction.byId(s.factionId);
        if (player != null) {
            player.sendSystemMessage(Component.literal("You retreat from the "
                    + (f != null ? f.displayName() : s.factionId)
                    + " settlement — its garrison regroups.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        LOGGER.info("[TM] rival: settlement #{} assault ABORTED (retreat) — garrison reset, boss re-resolved {}",
                s.id, s.bossUuid);
        clearAssaultState(s, false);
        SettlementSavedData.get(level).markChanged();
    }

    /**
     * The assaulting player DIED or LOGGED OUT mid-assault → treat as a
     * retreat: reset the garrison and send the party home, but the player
     * can't be teleported now, so mark {@link Settlement#pendingReturn}
     * and keep the origin until they come back (login / respawn).
     */
    static void onAssaultingPlayerDown(ServerLevel level, java.util.UUID playerId) {
        // SettlementSavedData is overworld-global — one instance covers all
        // dimensions; fetch once and scan its settlements.
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Settlement s : data.all()) {
            if (!s.assaulted || !playerId.equals(s.assaultingPlayer)) continue;
            ServerLevel sLevel = level.getServer().getLevel(s.dimension);
            if (sLevel == null) continue;
            clearGarrisonGlow(sLevel, s); // #7 — un-highlight before reset
            bringPartyHome(sLevel, s, null); // no player to anchor; sent to origin
            resetGarrison(sLevel, s);
            s.assaulted = false;
            s.pendingReturn = true; // teleport the player home on return
            data.markChanged();
            LOGGER.info("[TM] rival: settlement #{} assailant down (death/logout) — garrison reset, return pending",
                    s.id);
        }
    }

    /** On respawn / login: if this player owes a return trip from an
     *  interrupted assault, teleport them to the stored origin and clear
     *  the assault link. */
    static void onPlayerReturn(ServerPlayer player) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            SettlementSavedData data = SettlementSavedData.get(level);
            for (Settlement s : data.all()) {
                if (!s.pendingReturn || !player.getUUID().equals(s.assaultingPlayer)) continue;
                teleportPlayerHome(s, player);
                clearAssaultState(s, false);
                data.markChanged();
                LOGGER.info("[TM] rival: returned {} to assault origin from settlement #{}",
                        player.getName().getString(), s.id);
                return;
            }
        }
    }

    private static void teleportPlayerHome(Settlement s, ServerPlayer player) {
        if (player == null || s.assaultOrigin == null) return;
        ServerLevel dest = s.assaultOriginDim != null
                ? player.getServer().getLevel(s.assaultOriginDim) : player.serverLevel();
        if (dest == null) dest = player.serverLevel();
        player.teleportTo(dest, s.assaultOrigin.getX() + 0.5, s.assaultOrigin.getY(),
                s.assaultOrigin.getZ() + 0.5, player.getYRot(), player.getXRot());
    }

    /** Bring the loaded war party back to the assault origin (near the
     *  player if given, else to the stored origin). */
    private static void bringPartyHome(ServerLevel assaultLevel, Settlement s, ServerPlayer player) {
        if (s.assaultOrigin == null || s.assaultOriginDim == null) return;
        ServerLevel dest = assaultLevel.getServer().getLevel(s.assaultOriginDim);
        if (dest == null) return;
        int i = 0;
        for (java.util.UUID u : s.warParty) {
            net.minecraft.world.entity.Entity e = assaultLevel.getEntity(u);
            if (!(e instanceof Mob mob) || !mob.isAlive()) { i++; continue; }
            BlockPos at = partyArrivalPos(dest, s.assaultOrigin, i++);
            mob.teleportTo(dest, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                    java.util.Set.of(), mob.getYRot(), mob.getXRot());
            SubordinateHelper.removeTarget(mob);
        }
    }

    /** Reset the per-assault link. {@code keepConquest} preserves the
     *  {@link Settlement#conquestReached} flag for Stage D. */
    private static void clearAssaultState(Settlement s, boolean keepConquest) {
        s.assaultingPlayer = null;
        s.assaultOrigin = null;
        s.assaultOriginDim = null;
        s.warParty.clear();
        s.assaulted = false;
        s.pendingReturn = false;
        s.betrayalFactor = 1.0;   // Stage E — betrayal is per-assault
        s.betrayalTier = "";
        if (!keepConquest) s.conquestReached = false;
    }

    /** Find the settlement this player is actively assaulting, or null. */
    static Settlement findAssaultFor(ServerLevel level, java.util.UUID player) {
        for (Settlement s : SettlementSavedData.get(level).all()) {
            if (s.assaulted && player.equals(s.assaultingPlayer)) return s;
        }
        return null;
    }

    // --- UI data builders (the roster Wars tab) ------------------------

    /** Build the war-list snapshot for the player's DISCOVERED settlements
     *  (the roster Wars window). Per row: id, faction, coords, state, and
     *  the canDeclare / canRetreat flags the button-swap reads. */
    static net.minecraft.nbt.CompoundTag buildWarListTag(ServerPlayer player) {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putString("mode", "list");
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        for (Settlement s : data.all()) {
            if (!isDiscoveredBy(s, player.getUUID())) continue;
            BossFaction f = BossFaction.byId(s.factionId);
            net.minecraft.nbt.CompoundTag e = new net.minecraft.nbt.CompoundTag();
            e.putInt("id", s.id);
            e.putString("faction", f != null ? f.displayName() : s.factionId);
            e.putString("where", "[" + s.center.getX() + ", " + s.center.getZ() + "] "
                    + s.dimension.location().getPath());
            boolean mine = player.getUUID().equals(s.assaultingPlayer);
            String state = s.conquered ? "conquered husk"
                    : s.conquestReached ? "fallen (awaiting spoils)"
                    : mine && s.assaulted ? "you are assaulting it"
                    : s.assaulted ? "under assault"
                    : "garrison " + liveDefenderCount(player.serverLevel(), s) + "/"
                            + s.defenderCountAtStart + " + boss";
            e.putString("state", state);
            e.putBoolean("canDeclare", !s.conquered && !s.conquestReached && !s.assaulted);
            e.putBoolean("canRetreat", mine && s.assaulted);
            list.add(e);
        }
        root.put("settlements", list);
        return root;
    }

    /** Build the war-party picker snapshot for one settlement — the
     *  player's LOADED subordinates as candidates (entity id, name, EP). */
    static net.minecraft.nbt.CompoundTag buildWarPickerTag(ServerPlayer player, int settlementId) {
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        Settlement s = data.get(settlementId);
        if (s == null) return null;
        BossFaction f = BossFaction.byId(s.factionId);
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putString("mode", "picker");
        root.putInt("settlementId", settlementId);
        root.putString("faction", f != null ? f.displayName() : s.factionId);
        root.putInt("cap", WAR_PARTY_CAP);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Mob mob : loadedSubordinates(player)) {
            net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
            c.putInt("id", mob.getId());
            c.putString("name", mob.hasCustomName()
                    ? mob.getCustomName().getString() : mob.getType().getDescription().getString());
            ExistenceStorage ex = ExampleMod.readExistence(mob);
            c.putInt("ep", ex != null ? (int) ex.getEP() : 0);
            list.add(c);
        }
        root.put("candidates", list);
        return root;
    }

    // ------------------------------------------------------------------
    // Debug command support (/rivalcolony …)
    // ------------------------------------------------------------------

    static String debugSpawn(ServerPlayer player, String factionId, boolean wild) {
        if (!WorldReputationManager.isFactionSystemEnabled()) {
            return "the faction system is disabled (enableFactionSystem=false)";
        }
        BossFaction faction = BossFaction.byId(factionId);
        if (faction == null) return "unknown faction '" + factionId + "'";
        if (!isPhysical(factionId)) {
            return faction.displayName() + " is ABSTRACT — it has no anchor mob, no settlement.";
        }
        ServerLevel level = player.serverLevel();

        // Dwargon is DWARVEN_VILLAGE-type: it adopts the dwarf village the
        // player is standing in, rather than generating a faux-town.
        if (!isTownFaction(factionId)) {
            net.minecraft.world.level.levelgen.structure.Structure dwarfVillage =
                    dwarfVillageStructure(level);
            if (dwarfVillage == null) {
                return "tensura:dwarf_village isn't in the structure registry (Tensura disabled?).";
            }
            net.minecraft.world.level.levelgen.structure.StructureStart start =
                    level.structureManager().getStructureAt(player.blockPosition(), dwarfVillage);
            if (!start.isValid()) {
                return "stand inside a dwarven village to mark it as a Dwargon settlement.";
            }
            net.minecraft.world.level.levelgen.structure.BoundingBox box = start.getBoundingBox();
            BlockPos villageCenter = box.getCenter();
            SettlementSavedData data = SettlementSavedData.get(level);
            if (wild) {
                data.markVillageEvaluated(villageCenter);
                return "this dwarf village marked as WILD (won't become a Dwargon settlement).";
            }
            data.markVillageEvaluated(villageCenter);
            Settlement vs = registerDwarvenVillage(level, data, villageCenter, box);
            return faction.displayName() + " settlement #" + vs.id
                    + " registered on this dwarf village @ " + vs.center
                    + " (Gazel " + (vs.bossUuid != null ? "marked" : "MISSING") + ", garrison "
                    + vs.defenderCountAtStart + ").";
        }

        // Place ~40 blocks ahead of the player so they don't stand in it.
        BlockPos center = player.blockPosition().relative(player.getDirection(), 40);
        if (wild) {
            spawnAnchorBoss(level, factionId, center, false);
            return faction.displayName() + " WILD boss spawned (unmarked, free kill).";
        }
        Settlement s = generateColony(level, player, factionId, center);
        if (s == null) return "generation failed (check the log)";
        return faction.displayName() + " settlement #" + s.id + " generated (pack '"
                + s.packName + "', " + s.buildingPositions.size() + " buildings, garrison "
                + s.defenderCountAtStart + ") at " + s.center;
    }

    /** {@code /rivalcolony garrison <id>} — report a settlement's garrison
     *  state: defender count/kills, boss HP, the conquest check. */
    static List<String> debugGarrison(ServerPlayer player, int id) {
        ServerLevel level = player.serverLevel();
        Settlement s = SettlementSavedData.get(level).get(id);
        if (s == null) return List.of("No settlement #" + id + ".");
        BossFaction f = BossFaction.byId(s.factionId);
        List<String> out = new ArrayList<>();
        out.add("Settlement #" + s.id + " — " + (f != null ? f.displayName() : s.factionId)
                + " [" + (s.structureType == Settlement.StructureType.DWARVEN_VILLAGE
                ? "dwarven village" : "town") + "] @ " + s.center);
        out.add("  state: " + (s.assaulted ? "ASSAULTED" : "IDLE")
                + (s.conquered ? " (CONQUERED)" : ""));
        out.add("  defenders alive: " + liveDefenderCount(level, s) + " / start "
                + s.defenderCountAtStart + "  (UUIDs tracked: " + s.garrisonUuids.size() + ")");
        out.add("  defender kills: " + s.defenderKills + " / need "
                + requiredDefenderKills(s) + " (" + (int) (GARRISON_WIN_FRACTION * 100) + "%)");
        Mob boss = resolveBoss(level, s);
        if (boss != null) {
            out.add("  boss: " + String.format("%.0f/%.0f HP", boss.getHealth(), boss.getMaxHealth())
                    + ", EP " + String.format("%.0f", readBossEP(boss)));
        } else {
            out.add("  boss: " + (s.bossDead ? "DEAD" : "not loaded"));
        }
        out.add("  conquest-eligible: " + isConquestEligible(s)
                + "  (bossDead=" + s.bossDead + ")");
        out.add("  discovered-by-you: " + isDiscoveredBy(s, player.getUUID())
                + "  (total " + s.discoveredBy.size() + ")");
        if (s.assaultingPlayer != null) {
            out.add("  assault: by " + s.assaultingPlayer + ", party " + s.warParty.size()
                    + ", origin " + s.assaultOrigin
                    + (s.pendingReturn ? " (RETURN PENDING)" : ""));
        }
        if (s.betrayalFactor > 1.0) {
            out.add("  betrayal: ×" + String.format("%.2f", s.betrayalFactor)
                    + " (tier " + s.betrayalTier + ")");
        }
        if (s.conquestReached) out.add("  conquestReached=true (awaiting Stage-D payoff)");
        return out;
    }

    /** {@code /rivalcolony declare <id>} — declare war with NO picker
     *  (debug): take the player's loaded subordinates (≤cap) as the party
     *  and teleport in. The UI path uses the war-party picker. */
    static String debugDeclare(ServerPlayer player, int id) {
        List<Integer> party = new ArrayList<>();
        for (Mob m : loadedSubordinates(player)) {
            if (party.size() >= WAR_PARTY_CAP) break;
            party.add(m.getId());
        }
        return declareWar(player, id, party);
    }

    /** {@code /rivalcolony win <id>} — force the WIN resolution (debug):
     *  flag the boss dead + the kills past 60%, then resolve. */
    static String debugForceWin(ServerPlayer player, int id) {
        ServerLevel level = player.serverLevel();
        Settlement s = SettlementSavedData.get(level).get(id);
        if (s == null) return "No settlement #" + id + ".";
        if (!s.assaulted || !player.getUUID().equals(s.assaultingPlayer)) {
            return "You aren't assaulting settlement #" + id + " (declare war first).";
        }
        s.bossDead = true;
        s.defenderKills = Math.max(s.defenderKills, requiredDefenderKills(s));
        ServerLevel sLevel = level.getServer().getLevel(s.dimension);
        resolveWin(sLevel != null ? sLevel : level, s, player);
        return "Forced WIN on settlement #" + id + " — conquestReached, teleported home (payoff = Stage D).";
    }

    /** {@code /rivalcolony retreat [id]} — force the RETREAT resolution
     *  (debug): teleport home + reset garrison. With no id, resolves the
     *  settlement the player is currently assaulting. */
    static String debugForceRetreat(ServerPlayer player, Integer id) {
        ServerLevel level = player.serverLevel();
        Settlement s = id != null ? SettlementSavedData.get(level).get(id)
                : findAssaultFor(level, player.getUUID());
        if (s == null) return id != null ? "No settlement #" + id + "." : "You aren't assaulting anything.";
        if (!s.assaulted || !player.getUUID().equals(s.assaultingPlayer)) {
            return "You aren't assaulting settlement #" + s.id + ".";
        }
        ServerLevel sLevel = level.getServer().getLevel(s.dimension);
        resolveRetreat(sLevel != null ? sLevel : level, s, player);
        return "Retreated from settlement #" + s.id + " — garrison reset, boss re-resolved.";
    }

    /** {@code /rivalcolony assault <id>} — begin an assault (Stage-C entry
     *  primitive; debug-callable to test the win check before C exists). */
    static String debugBeginAssault(ServerPlayer player, int id) {
        ServerLevel level = player.serverLevel();
        Settlement s = SettlementSavedData.get(level).get(id);
        if (s == null) return "No settlement #" + id + ".";
        beginAssault(level, s);
        return "Settlement #" + id + " assault begun — " + s.defenderCountAtStart
                + " defenders, need " + requiredDefenderKills(s) + " kills + boss.";
    }

    /** {@code /rivalcolony reset <id>} — run the RESET primitive (respawn
     *  garrison + heal/revive boss), as Stage C will on an incomplete
     *  assault. */
    static String debugReset(ServerPlayer player, int id) {
        ServerLevel level = player.serverLevel();
        Settlement s = SettlementSavedData.get(level).get(id);
        if (s == null) return "No settlement #" + id + ".";
        resetGarrison(level, s);
        return "Settlement #" + id + " reset — garrison restored to " + s.defenderCountAtStart
                + ", boss healed/revived.";
    }

    static List<String> debugList(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        if (data.all().isEmpty()) {
            out.add("No settlements generated yet.");
            return out;
        }
        out.add("Generated settlements (" + data.all().size() + "):");
        for (Settlement s : data.all()) {
            BossFaction f = BossFaction.byId(s.factionId);
            String form = s.structureType == Settlement.StructureType.DWARVEN_VILLAGE
                    ? "dwarven village"
                    : s.buildingPositions.size() + " buildings";
            out.add("  #" + s.id + " " + (f != null ? f.displayName() : s.factionId)
                    + " @ " + s.center + " [" + s.dimension.location() + "] — " + form
                    + ", garrison " + s.garrisonUuids.size() + "/" + s.defenderCountAtStart
                    + (s.assaulted ? " ASSAULTED" : "")
                    + (s.conquered ? ", CONQUERED" : "")
                    + (s.discoveredBy.isEmpty() ? "" : ", discovered×" + s.discoveredBy.size()));
        }
        return out;
    }

    // ====================================================================
    // Worldgen settlement populate STEP (production).
    // Trigger: tickWorldgenSettlements (structure-start detection) above.
    // (The Stage-0 /factiongen spike command + its pending-site map were
    // removed in Stage 5; /rivalcolony spawn remains for debug spawning.)
    // ====================================================================

    /** The shared populate STEP: config-gate AT POPULATE TIME (the crux of
     *  Option C) + double-populate guard (by site center) + the existing
     *  generateColony blueprint/boss/garrison pipeline (with its pack-readiness
     *  guard). Returns true only when a settlement was actually built. */
    static boolean populateSettlementAt(ServerLevel level, BlockPos center, String factionId,
                                        SettlementSavedData data) {
        long key = center.asLong();
        if (data.isPopulated(key)) return false;
        // CONFIG GATE AT POPULATE TIME. When OFF: do NOT mark populated, so the
        // structure stays a dormant marker and populates later once enabled.
        if (!WorldReputationManager.isFactionSystemEnabled()) {
            LOGGER.info("[TM] rival: populate suppressed — faction system OFF (center {})", center);
            return false;
        }
        // Structurize's queued building placement wants a player (placer); a
        // player is present at populate time (detection is player-proximity
        // driven). Null-safe regardless.
        ServerPlayer placer = level.players().isEmpty() ? null : level.players().get(0);
        Settlement s = generateColony(level, placer, factionId, center);
        if (s == null) {
            // null = pack not ready yet, or generation declined — leave UNmarked
            // so the next detection tick retries.
            LOGGER.warn("[TM] rival: generateColony returned null for {} at {} "
                    + "(placer={}) — will retry", factionId, center,
                    placer != null ? placer.getName().getString() : "none");
            return false;
        }
        data.markPopulated(key);
        LOGGER.info("[TM] rival: populated settlement #{} ({}) at {} via first-load "
                + "({} buildings, bossUuid={})", s.id, factionId, s.center,
                s.buildingPositions.size(), s.bossUuid);
        return true;
    }
}
