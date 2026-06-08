package com.example.examplemod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Parallel of {@link Races} for {@link Beast}s — namable creatures
 * that become beast-guard citizens (not worker races). Same shape as
 * {@link Races} so callers can do a two-step probe at naming time:
 * <pre>
 *   Beast beast = Beasts.of(type);
 *   if (beast != null) { ... beast-guard path ... }
 *   Race race = Races.of(type);
 *   if (race != null) { ... worker-race path ... }
 * </pre>
 *
 * <p>Beasts NEVER appear in {@link Races}, and races never appear
 * here — disjoint registries.
 */
public final class Beasts {

    private static final EnumMap<Beast, ResourceLocation> IDS = new EnumMap<>(Beast.class);
    private static final Map<ResourceLocation, Beast> BY_ID = new HashMap<>();

    static {
        register(Beast.KNIGHT_SPIDER, "tensura", "knight_spider");
    }

    private static void register(Beast beast, String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        IDS.put(beast, id);
        BY_ID.put(id, beast);
    }

    private Beasts() {}

    public static ResourceLocation idFor(Beast beast) {
        return IDS.get(beast);
    }

    /** @return the {@link Beast} for this entity type, or {@code null}
     *  if the type isn't registered as a beast. */
    public static Beast of(EntityType<?> type) {
        if (type == null) return null;
        return BY_ID.get(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    public static Beast of(ResourceLocation id) {
        return BY_ID.get(id);
    }
}
