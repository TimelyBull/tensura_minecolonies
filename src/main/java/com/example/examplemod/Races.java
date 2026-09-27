package com.example.examplemod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Central registry mapping each {@link Race} to the Tensura entity type
 * that represents it in the world.
 *
 * Authoritative source — every site that needs to ask "is this entity
 * naming-eligible?" or "which race does this entity type belong to?"
 * MUST consult {@link #of(EntityType)} or {@link #idFor(Race)} here, not
 * a local hardcoded check. Adding a new race is a one-line entry in
 * the {@link #IDS} populator.
 *
 * Forward and reverse maps are pre-built at class load so per-call
 * lookups are O(1) and don't allocate.
 */
public final class Races {

    /** Forward: race → "tensura:goblin" / "tensura:orc" / etc. */
    private static final EnumMap<Race, ResourceLocation> IDS = new EnumMap<>(Race.class);

    /** Reverse: ResourceLocation → race. Lookup by entity-type key. */
    private static final Map<ResourceLocation, Race> BY_ID = new HashMap<>();

    /**
     * Entity types that are NOT eligible to become citizens, even though
     * they're in Tensura's evolution chain for a registered race.
     * Currently: orc lord and orc disaster — they're separate Tensura
     * EntityTypes (with their own renderers / GeoModels) that emerge
     * via evolution from a base orc. Allowing them to be sent to the
     * colony would require a separate shadow-entity pool per tier, which
     * is deferred. The send chokepoint surfaces an advisory and aborts.
     */
    private static final Set<ResourceLocation> BLOCKED = Set.of(
            ResourceLocation.fromNamespaceAndPath("tensura", "orc_lord"),
            ResourceLocation.fromNamespaceAndPath("tensura", "orc_disaster")
    );

    /** Registry paths of the eight otherworlder characters. */
    private static final String[] OTHERWORLDER_PATHS = {
            "kirara_mizutani", "kyoya_tachibana", "shogo_taguchi", "mai_furuki",
            "mark_lauren", "shinji_tanimura", "shin_ryusei", "folgen"};

    static {
        register(Race.GOBLIN,    "tensura", "goblin");
        register(Race.ORC,       "tensura", "orc");
        register(Race.LIZARDMAN, "tensura", "lizardman");
        register(Race.DWARF,     "tensura", "dwarf");
        // Otherworlders: ONE race, EIGHT entity types — Tensura's summonable
        // otherworlders (its otherworlder_spawn_distribution data; Hinata and
        // Shizu are story characters and excluded). The first registered is the
        // canonical idFor(OTHERWORLDER); callers that create a specific
        // character pass its type explicitly (see OTHERWORLDER_TYPES).
        for (String path : OTHERWORLDER_PATHS) register(Race.OTHERWORLDER, "tensura", path);
    }

    /** The eight otherworlder character ids, in a stable order. */
    public static java.util.List<ResourceLocation> otherworlderTypes() {
        java.util.List<ResourceLocation> out = new java.util.ArrayList<>();
        for (String path : OTHERWORLDER_PATHS) out.add(ResourceLocation.fromNamespaceAndPath("tensura", path));
        return out;
    }

    private static void register(Race race, String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        IDS.putIfAbsent(race, id);
        BY_ID.put(id, race);
    }

    private Races() {}

    /** ResourceLocation for a known race. Never null for a registered race. */
    public static ResourceLocation idFor(Race race) {
        return IDS.get(race);
    }

    /** Race for an entity type, or {@code null} if the type isn't registered
     *  as a race-eligible mob. The naming + send filters early-out on null. */
    public static Race of(EntityType<?> type) {
        if (type == null) return null;
        return BY_ID.get(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    /** Race for an entity-type ResourceLocation, or {@code null}. */
    public static Race of(ResourceLocation id) {
        return BY_ID.get(id);
    }

    /** Is this entity type explicitly excluded from the citizen pipeline?
     *  Currently only orc lord and orc disaster — see {@link #BLOCKED}. */
    public static boolean isBlocked(EntityType<?> type) {
        if (type == null) return false;
        return BLOCKED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }
}
