package com.example.examplemod;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's per-citizen {@link BeastTag}
 * attachment — parallel to {@link RaceTagClientStore} but for beasts.
 *
 * <p>Populated by {@link Networking.SyncBeastTagPayload} (S2C), keyed
 * by the citizen entity's UUID. The shadow render handler
 * ({@code KnightSpiderCitizenRenderHandler}) consults {@link #get} on
 * each frame.
 */
@OnlyIn(Dist.CLIENT)
public final class BeastTagClientStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, BeastTag> TAGS = new ConcurrentHashMap<>();

    private BeastTagClientStore() {}

    public static void onPayload(Networking.SyncBeastTagPayload p) {
        if (p.present()) {
            BeastTag tag = new BeastTag(p.identityId(), Beast.byId(p.beastId() & 0xFF));
            TAGS.put(p.entityUuid(), tag);
            LOGGER.info("[TM] client beast tag SET: entity={} identity={} beast={}",
                    p.entityUuid(), p.identityId(), tag.beast());
        } else {
            BeastTag removed = TAGS.remove(p.entityUuid());
            if (removed != null) {
                LOGGER.info("[TM] client beast tag CLEARED: entity={}", p.entityUuid());
            }
        }
    }

    public static BeastTag get(UUID entityUuid) { return TAGS.get(entityUuid); }
    public static boolean has(UUID entityUuid) { return TAGS.containsKey(entityUuid); }

    public static void removeForEntity(UUID entityUuid) { TAGS.remove(entityUuid); }

    public static void clearAll() {
        if (!TAGS.isEmpty()) {
            LOGGER.info("[TM] client beast tag store cleared ({} entries)", TAGS.size());
            TAGS.clear();
        }
    }

    public static int size() { return TAGS.size(); }
}
