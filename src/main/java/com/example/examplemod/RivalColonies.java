package com.example.examplemod;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.util.CreativeBuildingStructureHandler;
import io.github.manasmods.tensura.registry.entity.HumanEntityTypes;
import io.github.manasmods.tensura.registry.entity.MonsterEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** faction id → anchor boss entity-type supplier (lazy; registries). */
    private static final Map<String, Supplier<? extends EntityType<?>>> ANCHORS = new LinkedHashMap<>();
    /** faction id → MineColonies structure-pack display name (theme). */
    private static final Map<String, String> PACKS = new LinkedHashMap<>();
    static {
        // Luminous — holy marble city.
        ANCHORS.put("luminous", HumanEntityTypes.HINATA_SAKAGUCHI);
        PACKS.put("luminous", "Ancient Athens");
        // Dwargon — the dwarven kingdom.
        ANCHORS.put("dwargon", HumanEntityTypes.GAZEL_DWARGO);
        PACKS.put("dwargon", "Stalactite Caves");
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
        // ABSTRACT (no anchor mob, never settle): tempest, carrion,
        // milim, clayman (his orcs roam as calamities, not a settled town).
    }

    /** True when the faction can generate a physical settlement. */
    public static boolean isPhysical(String factionId) {
        return ANCHORS.containsKey(factionId);
    }

    public static String packFor(String factionId) {
        return PACKS.get(factionId);
    }

    // ------------------------------------------------------------------
    // The shared town layout — (blueprint path, dx, dz). Pack-relative
    // paths present in EVERY candidate pack (verified). Only the PACK
    // differs per faction; the footprint is shared. ~10 buildings; the
    // town hall anchors the center. Spacing is generous to limit overlap
    // (tunable; a foundation pad is deferred polish).
    // ------------------------------------------------------------------

    private record Building(String path, int dx, int dz) {}

    private static final int GRID = 22;
    private static final List<Building> LAYOUT = List.of(
            new Building("fundamentals/townhall1", 0, 0),
            new Building("fundamentals/builder1", -GRID, 0),
            new Building("fundamentals/tavern1", GRID, 0),
            new Building("craftsmanship/metallurgy/blacksmith1", 0, -GRID),
            new Building("education/library1", 0, GRID),
            new Building("military/barracks1", -GRID, -GRID),
            new Building("fundamentals/residence1", GRID, -GRID),
            new Building("fundamentals/residence1", -GRID, GRID),
            new Building("fundamentals/residence1", GRID, GRID),
            new Building("fundamentals/residence1", 0, GRID * 2));

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
        if (!WorldReputationManager.isFactionSystemEnabled() || !isPhysical(factionId)) return null;
        String pack = PACKS.get(factionId);
        BlockPos center = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, rawCenter);

        Settlement s = new Settlement();
        SettlementSavedData data = SettlementSavedData.get(level);
        s.id = data.allocateId();
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

    /** Instant complete-build of one MineColonies building schematic
     *  (the verified CreativeBuildingStructureHandler path). */
    private static void placeBuilding(ServerLevel level, ServerPlayer placer, String pack,
                                      String path, BlockPos pos) {
        try {
            var future = StructurePacks.getBlueprintFuture(pack, path, level.registryAccess());
            CreativeBuildingStructureHandler.loadAndPlaceStructureWithRotation(
                    level, future, pos, RotationMirror.NONE, true, placer);
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
        if (!Config.RIVAL_NATURAL_GEN.get()) return;
        if (Config.RIVAL_SETTLEMENT_MODE.get() == Config.SettlementMode.NONE) return;

        for (ServerLevel level : server.getAllLevels()) {
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

    private static String randomPhysicalFaction(ServerLevel level) {
        List<String> ids = new ArrayList<>(ANCHORS.keySet());
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
        // Place ~40 blocks ahead of the player so they don't stand in it.
        BlockPos center = player.blockPosition().relative(player.getDirection(), 40);
        if (wild) {
            spawnAnchorBoss(level, factionId, center, false);
            return faction.displayName() + " WILD boss spawned (unmarked, free kill).";
        }
        Settlement s = generateColony(level, player, factionId, center);
        if (s == null) return "generation failed (check the log)";
        return faction.displayName() + " settlement #" + s.id + " generated (pack '"
                + s.packName + "', " + s.buildingPositions.size() + " buildings) at " + s.center;
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
            out.add("  #" + s.id + " " + (f != null ? f.displayName() : s.factionId)
                    + " @ " + s.center + " [" + s.dimension.location() + "] — "
                    + s.buildingPositions.size() + " buildings"
                    + (s.conquered ? ", CONQUERED" : "")
                    + (s.discoveredBy.isEmpty() ? "" : ", discovered×" + s.discoveredBy.size()));
        }
        return out;
    }
}
