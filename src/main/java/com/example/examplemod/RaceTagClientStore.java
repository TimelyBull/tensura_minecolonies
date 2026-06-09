package com.example.examplemod;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's per-citizen {@link RaceTag}
 * attachment. Populated by {@link Networking.SyncRaceTagPayload}
 * (S2C), keyed by the citizen entity's UUID.
 *
 * Renderers consult {@link #get(UUID)} during {@code RenderLivingEvent.Pre}
 * to decide goblin / orc / default rendering.
 *
 * Cleanup:
 *  - explicit "present=false" payload removes a single entry (summon path)
 *  - {@code EntityLeaveLevelEvent} removes entries for entities that
 *    leave the client world (chunk unload, dimension change, discard)
 *  - {@code ClientPlayerNetworkEvent.LoggingOut} wipes the whole map so
 *    a relog or world switch starts clean
 */
@OnlyIn(Dist.CLIENT)
public final class RaceTagClientStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, RaceTag> TAGS = new ConcurrentHashMap<>();

    private RaceTagClientStore() {}

    /** Handler installed into {@link Networking#raceTagClientHandler} by
     *  {@link ClientEvents#init}. {@code present=true} sets; {@code false} clears. */
    public static void onPayload(Networking.SyncRaceTagPayload p) {
        if (p.present()) {
            RaceTag tag = RaceTag.fromWire(p.identityId(), p.raceId() & 0xFF, p.variant(), p.profession());
            TAGS.put(p.entityUuid(), tag);
            LOGGER.info("[TM] client tag SET: entity={} identity={} race={}",
                    p.entityUuid(), p.identityId(), tag.race());
        } else {
            RaceTag removed = TAGS.remove(p.entityUuid());
            if (removed != null) {
                LOGGER.info("[TM] client tag CLEARED: entity={}", p.entityUuid());
            }
        }
    }

    public static RaceTag get(UUID entityUuid) {
        return TAGS.get(entityUuid);
    }

    public static boolean has(UUID entityUuid) {
        return TAGS.containsKey(entityUuid);
    }

    /** Goblin-specific typed accessor — returns the decoded variant
     *  only if the tag is GOBLIN race. Returns null for ORC-tagged
     *  citizens (so a stale call from the goblin renderer on an orc
     *  citizen fails closed, not as a ClassCastException). */
    public static GoblinVariantData getGoblinVariant(UUID entityUuid) {
        RaceTag t = TAGS.get(entityUuid);
        if (t != null && t.variant() instanceof GoblinVariantData g) return g;
        return null;
    }

    /** Orc-specific typed accessor — symmetric to {@link #getGoblinVariant}. */
    public static OrcVariantData getOrcVariant(UUID entityUuid) {
        RaceTag t = TAGS.get(entityUuid);
        if (t != null && t.variant() instanceof OrcVariantData o) return o;
        return null;
    }

    /** Lizardman-specific typed accessor — symmetric to {@link #getGoblinVariant}. */
    public static LizardmanVariantData getLizardmanVariant(UUID entityUuid) {
        RaceTag t = TAGS.get(entityUuid);
        if (t != null && t.variant() instanceof LizardmanVariantData l) return l;
        return null;
    }

    /** Dwarf-specific typed accessor — symmetric to {@link #getGoblinVariant}. */
    public static DwarfVariantData getDwarfVariant(UUID entityUuid) {
        RaceTag t = TAGS.get(entityUuid);
        if (t != null && t.variant() instanceof DwarfVariantData d) return d;
        return null;
    }

    /** Villager-profession registry name for this citizen (e.g.
     *  {@code "minecraft:butcher"}), or {@code ""} if jobless / no tag.
     *  Drives {@link DwarfProfessionLayer}. */
    public static String getProfession(UUID entityUuid) {
        RaceTag t = TAGS.get(entityUuid);
        return t != null ? t.profession() : "";
    }

    /** Called from {@link ClientEvents}' {@code EntityLeaveLevelEvent} hook
     *  to drop entries for entities that have left the client world. */
    public static void removeForEntity(UUID entityUuid) {
        TAGS.remove(entityUuid);
    }

    /** Called from {@link ClientEvents}' logout hook to wipe state between
     *  sessions. Prevents cross-world contamination on relog. */
    public static void clearAll() {
        if (!TAGS.isEmpty()) {
            LOGGER.info("[TM] client tag store cleared ({} entries)", TAGS.size());
            TAGS.clear();
        }
    }

    public static int size() {
        return TAGS.size();
    }
}
