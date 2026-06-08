package com.example.examplemod;

/**
 * Race identifier carried in {@link RaceTag} so the client renderer
 * knows which model to draw for a tagged citizen.
 *
 * Only GOBLIN and ORC are wired. The byte id is what travels on the
 * wire and in NBT, so adding new races later does not break existing
 * records (unknown ids decode as {@link #GOBLIN} via {@link #byId(int)}).
 *
 * Authoritative ResourceLocation mapping lives in {@link Races}, NOT
 * inline here — adding a new race is one entry there.
 */
public enum Race {
    GOBLIN(0),
    ORC(1),
    LIZARDMAN(2),
    DWARF(3);

    private final int id;

    Race(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /** {@code /raceflip} debug cycle. Now a 4-cycle: Goblin → Orc →
     *  Lizardman → Dwarf → Goblin. */
    public Race other() {
        return switch (this) {
            case GOBLIN -> ORC;
            case ORC -> LIZARDMAN;
            case LIZARDMAN -> DWARF;
            case DWARF -> GOBLIN;
        };
    }

    public static Race byId(int id) {
        for (Race r : values()) {
            if (r.id == id) return r;
        }
        return GOBLIN; // safe fallback for unknown / legacy
    }
}
