package com.example.examplemod;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
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
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.Level;
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
 * <p>Everything is gated behind {@code factionSystemEnabled} (the
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
        // Shizu — Japanese-aesthetic (her origin).
        ANCHORS.put("shizu", HumanEntityTypes.SHIZU);
        PACKS.put("shizu", "Pagoda");
        // Leon — a demon lord's grand keep.
        ANCHORS.put("leon", MonsterEntityTypes.IFRIT);
        PACKS.put("leon", "Caledonia");
        // Otherworlders — summoned-from-elsewhere, sci-fi.
        ANCHORS.put("otherworlders", HumanEntityTypes.MAI_FURUKI);
        PACKS.put("otherworlders", "Space Wars");
        // Jura Alliance — the forest nation.
        ANCHORS.put("jura_alliance", HumanEntityTypes.SHIN_RYUSEI);
        PACKS.put("jura_alliance", "Jungle Treehouse");
        // Dwargon — DWARVEN_VILLAGE type: anchor exists (Gazel) but NO
        // town pack; SOME existing dwarf villages become its settlements.
        ANCHORS.put(DWARGON, HumanEntityTypes.GAZEL_DWARGO);
        // ABSTRACT (no anchor mob, never settle): tempest, carrion,
        // milim, clayman (his orcs roam as calamities, not a settled town).
    }

    /** Cached structure key for Tensura's dwarf village. */
    static final net.minecraft.resources.ResourceKey<
            net.minecraft.world.level.levelgen.structure.Structure> DWARF_VILLAGE_KEY =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            "tensura", "dwarf_village"));

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
    // these are the rank-and-file around it. Abstract factions (Tempest,
    // Carrion, Milim, Clayman) have no settlement, so no roster.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob>[] garrisonRoster(String factionId) {
        return switch (factionId) {
            // Luminous — the Holy Empire: elite human knights + a holy
            // construct (no dedicated "holy" mob exists; Bone Golem reads
            // as a sanctified guardian).
            case "luminous" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get(), HumanEntityTypes.CLONE.get(),
                    HumanEntityTypes.BONE_GOLEM.get() };
            // Falmuth — militaristic kingdom: Folgen's named knights.
            case "falmuth" -> new EntityType[] {
                    HumanEntityTypes.FALMUTH_KNIGHT.get(), HumanEntityTypes.KIRARA_MIZUTANI.get(),
                    HumanEntityTypes.KYOYA_TACHIBANA.get(), HumanEntityTypes.SHOGO_TAGUCHI.get() };
            // Shizu — Ifrit's fire (her possessed origin): fire beasts.
            case "shizu" -> new EntityType[] {
                    MonsterEntityTypes.IFRIT_CLONE.get(), MonsterEntityTypes.SALAMANDER.get(),
                    MonsterEntityTypes.HELL_CATERPILLAR.get(), MonsterEntityTypes.HELL_MOTH.get() };
            // Leon — a demon lord's keep: daemons + fire.
            case "leon" -> new EntityType[] {
                    MonsterEntityTypes.IFRIT_CLONE.get(), MonsterEntityTypes.SALAMANDER.get(),
                    MonsterEntityTypes.ARCH_DAEMON.get(), MonsterEntityTypes.GREATER_DAEMON.get(),
                    MonsterEntityTypes.LESSER_DAEMON.get() };
            // Otherworlders — summoned-from-elsewhere: a cohort of humans.
            case "otherworlders" -> new EntityType[] {
                    HumanEntityTypes.CLONE.get(), HumanEntityTypes.MARK_LAUREN.get(),
                    HumanEntityTypes.SHINJI_TANIMURA.get(), HumanEntityTypes.KIRARA_MIZUTANI.get() };
            // Jura Alliance — the forest nation: serpents, slimes, kin.
            case "jura_alliance" -> new EntityType[] {
                    MonsterEntityTypes.TEMPEST_SERPENT.get(), MonsterEntityTypes.GOBLIN.get(),
                    MonsterEntityTypes.LIZARDMAN.get(), MonsterEntityTypes.SLIME.get() };
            // Dwargon — the dwarven kingdom: dwarves + gnomes.
            case "dwargon" -> new EntityType[] {
                    HumanEntityTypes.DWARF.get(), MonsterEntityTypes.WAR_GNOME.get(),
                    MonsterEntityTypes.BEAST_GNOME.get() };
            default -> null;
        };
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
    private static final int GRID = 22;
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

    // --- natural generation tuning (all named) ---
    /** Per online player, per day: chance to seed a settlement nearby. */
    static final double NATURAL_GEN_CHANCE_PER_DAY = 0.05;
    /** Settlement spawns this far (blocks) from the triggering player. */
    static final int NATURAL_GEN_DISTANCE = 220;
    /** No two settlements within this distance (blocks). */
    static final int MIN_SETTLEMENT_SPACING = 400;
    /** Hard cap on naturally-generated settlements per world. */
    static final int MAX_NATURAL_SETTLEMENTS = 12;

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

    /**
     * Generate a faction boss near {@code center}, choosing wild vs
     * colony per config. PHYSICAL factions only (abstract → nothing).
     * Returns the created Settlement (colony) or null (wild / abstract /
     * disabled).
     */
    static Settlement generate(ServerLevel level, ServerPlayer placer, String factionId,
                               BlockPos center) {
        if (!WorldReputationManager.isFactionSystemEnabled()) return null;
        if (!isPhysical(factionId)) return null;
        // Dwargon never generates a TOWN — it adopts existing dwarven
        // villages (the village poll). Route any stray call to a WILD
        // Gazel rather than a faux-town.
        if (!isTownFaction(factionId)) {
            spawnAnchorBoss(level, factionId, center, false);
            return null;
        }
        if (Config.RIVAL_SETTLEMENT_MODE.get() == Config.SettlementMode.NONE) {
            // Layer disabled — still allow the WILD boss (free roaming).
            spawnAnchorBoss(level, factionId, center, false);
            return null;
        }
        if (rollColony(level)) {
            return generateColony(level, placer, factionId, center);
        }
        spawnAnchorBoss(level, factionId, center, false); // WILD
        LOGGER.info("[TM] rival: {} generated WILD (unmarked) at {}", factionId, center);
        return null;
    }

    /** Force the COLONY version (the debug command + ALL mode). */
    static Settlement generateColony(ServerLevel level, ServerPlayer placer, String factionId,
                                     BlockPos rawCenter) {
        if (!WorldReputationManager.isFactionSystemEnabled() || !isTownFaction(factionId)) return null;
        String pack = PACKS.get(factionId);
        BlockPos center = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, rawCenter);

        Settlement s = new Settlement();
        SettlementSavedData data = SettlementSavedData.get(level);
        s.id = data.allocateId();
        s.structureType = Settlement.StructureType.MINECOLONIES_CLUSTER;
        s.factionId = factionId;
        s.dimension = level.dimension();
        s.center = center;
        s.packName = pack;

        for (Building b : LAYOUT) {
            BlockPos at = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                    center.offset(b.dx(), 0, b.dz()));
            placeBuilding(level, placer, pack, b.path(), at);
            s.buildingPositions.add(at);
        }

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
        return boss;
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
    // Natural generation — our own scheduler pass (NOT vanilla world-gen)
    // ------------------------------------------------------------------

    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long>
            lastNaturalDay = new java.util.HashMap<>();

    /** Called once per second from the server tick (the shared cadence).
     *  Rolls a rare daily per-player chance to seed a settlement in the
     *  world near a player — capped, spaced, config-gated. */
    public static void tick(MinecraftServer server) {
        if (!WorldReputationManager.isFactionSystemEnabled()) return;
        boolean settlementsOn = Config.RIVAL_SETTLEMENT_MODE.get() != Config.SettlementMode.NONE;

        for (ServerLevel level : server.getAllLevels()) {
            // Stage B — keep each garrison tethered to its settlement.
            // Runs whenever the faction system is on (independent of the
            // natural-gen toggle) so debug-spawned garrisons behave too.
            tickGarrison(level);
            // Stage C — proximity discovery + drive any active assault.
            tickDiscovery(level);
            tickAssaults(level);

            if (!settlementsOn) continue;

            // Dwargon: poll every tick for players standing in an
            // unevaluated dwarf village (cheap — a structure lookup).
            tickDwarvenVillages(level);

            // Natural TOWN scatter — gated by the natural-gen toggle.
            if (!Config.RIVAL_NATURAL_GEN.get()) continue;
            long day = level.getDayTime() / 24_000L;
            Long prev = lastNaturalDay.put(level.dimension(), day);
            if (prev == null || prev == day) continue; // once per in-game day

            SettlementSavedData data = SettlementSavedData.get(level);
            if (data.all().size() >= MAX_NATURAL_SETTLEMENTS) continue;

            for (ServerPlayer player : level.players()) {
                if (level.getRandom().nextDouble() >= NATURAL_GEN_CHANCE_PER_DAY) continue;
                String factionId = randomPhysicalFaction(level);
                if (factionId == null) continue;
                BlockPos center = scatterNear(level, player.blockPosition());
                if (tooCloseToExisting(data, level, center)) continue;
                generate(level, player, factionId, center);
                break; // at most one seed per dimension per day
            }
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

    /** Natural TOWN gen picks only from the 6 MINECOLONIES_CLUSTER
     *  factions — Dwargon is excluded (it adopts dwarf villages via the
     *  {@link #tickDwarvenVillages} poll, not faux-town scattering). */
    private static String randomPhysicalFaction(ServerLevel level) {
        List<String> ids = new ArrayList<>(PACKS.keySet());
        if (ids.isEmpty()) return null;
        return ids.get(level.getRandom().nextInt(ids.size()));
    }

    private static BlockPos scatterNear(ServerLevel level, BlockPos around) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        int dx = (int) (Math.cos(angle) * NATURAL_GEN_DISTANCE);
        int dz = (int) (Math.sin(angle) * NATURAL_GEN_DISTANCE);
        return around.offset(dx, 0, dz);
    }

    private static boolean tooCloseToExisting(SettlementSavedData data, ServerLevel level,
                                              BlockPos center) {
        for (Settlement s : data.all()) {
            if (!s.dimension.equals(level.dimension())) continue;
            if (s.center.distSqr(center) < (long) MIN_SETTLEMENT_SPACING * MIN_SETTLEMENT_SPACING) {
                return true;
            }
        }
        return false;
    }

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
        double scale = scaleForBoss(bossEP);
        int count = countForScale(scale);
        double statFactor = statFactorForScale(scale);

        // The boss is part of the garrison — stamp it so its death routes
        // through the tally as the boss-down flag (it keeps its
        // FactionMarkTag for the Layer-1 fan-out; the two coexist).
        if (boss != null) {
            boss.setData(Attachments.GARRISON_TAG.get(), new GarrisonTag(s.id, true));
        }

        s.garrisonUuids.clear();
        for (int i = 0; i < count; i++) {
            EntityType<? extends Mob> type = roster[i % roster.length];
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
        // Heal/revive the boss first (so the topped-up garrison rallies to
        // a whole anchor).
        reviveOrHealBoss(level, s);

        // Heal surviving defenders; count them for the top-up.
        int alive = 0;
        for (UUID uuid : s.garrisonUuids) {
            Entity e = level.getEntity(uuid);
            if (e instanceof Mob mob && mob.isAlive()) {
                mob.setHealth(mob.getMaxHealth());
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
                EntityType<? extends Mob> type = roster[i % roster.length];
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
            return "the faction system is disabled (factionSystemEnabled=false)";
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

        // Snapshot the garrison (count + zero kills + ASSAULTED).
        beginAssault(settlementLevel, s);

        // Teleport the player to the settlement edge, then bring the party.
        BlockPos arrival = settlementLevel.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                s.center.offset(GARRISON_SPAWN_RADIUS + 6, 0, 0));
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

        // Turn the garrison hostile on the invaders right away.
        steerGarrisonToInvaders(settlementLevel, s, player);
        data.markChanged();

        BossFaction f = BossFaction.byId(s.factionId);
        LOGGER.info("[TM] rival: {} DECLARED WAR on settlement #{} — party {}, {} defenders + boss",
                player.getName().getString(), s.id, s.warParty.size(), s.defenderCountAtStart);
        return "War declared on the " + (f != null ? f.displayName() : s.factionId)
                + " settlement #" + s.id + " — " + s.warParty.size() + " in your war party. "
                + "Kill the boss and " + (int) (GARRISON_WIN_FRACTION * 100) + "% of "
                + s.defenderCountAtStart + " defenders to conquer it.";
    }

    private static BlockPos partyArrivalPos(ServerLevel level, BlockPos around, int index) {
        int dx = (index % 5 - 2) * WAR_PARTY_SPREAD;
        int dz = (index / 5 - 1) * WAR_PARTY_SPREAD;
        return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, around.offset(dx, 0, dz));
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
            }
        }
    }

    /**
     * WIN — conquest-eligible reached (bossDead && ≥60% defenders). C
     * resolves the assault: flag {@code conquestReached} (Stage D hooks
     * this for the payoff), bring the player + survivors home, return to
     * IDLE. The garrison is NOT reset — it has been beaten.
     */
    private static void resolveWin(ServerLevel level, Settlement s, ServerPlayer player) {
        bringPartyHome(level, s, player);
        teleportPlayerHome(s, player);
        s.conquestReached = true;
        BossFaction f = BossFaction.byId(s.factionId);
        player.sendSystemMessage(Component.literal("The " + (f != null ? f.displayName() : s.factionId)
                + " settlement has fallen! (The spoils await — Stage D.)")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        LOGGER.info("[TM] rival: settlement #{} CONQUERED by {} — conquestReached flagged (payoff = Stage D)",
                s.id, player.getName().getString());
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
            return "the faction system is disabled (factionSystemEnabled=false)";
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
}
