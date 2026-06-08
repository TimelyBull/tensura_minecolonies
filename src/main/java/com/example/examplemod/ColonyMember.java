package com.example.examplemod;

import java.util.Optional;

/**
 * One slot in a colony's composition set.
 *
 * Distinct from {@link Race}, which is a TENSURA-mob race (GOBLIN, ORC).
 * A {@code ColonyMember} can be one of those Tensura races OR the
 * vanilla-citizen "COLONIST" — the latter isn't a Tensura mob but IS a
 * legitimate first-class population option (vanilla citizens) that the
 * eventual envoy system can mix with race-mobs (e.g. {@code {COLONIST,
 * GOBLIN}} for a diplomacy-style mixed colony).
 *
 * Storage: serialised as a byte id so adding new members later doesn't
 * break existing saves — unknown ids decode to {@link #COLONIST} via
 * {@link #byId(int)}.
 *
 * IDs are deliberately NOT the same as {@link Race#getId()} so the two
 * type spaces stay independent. Conversion goes through {@link #toRace}
 * (race-typed code paths) or {@link #fromRace} (member-typed code paths).
 */
public enum ColonyMember {
    COLONIST(0),
    GOBLIN(1),
    ORC(2),
    LIZARDMAN(3),
    DWARF(4);

    private final int id;

    ColonyMember(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static ColonyMember byId(int id) {
        for (ColonyMember m : values()) {
            if (m.id == id) return m;
        }
        return COLONIST; // safe fallback for unknown / legacy
    }

    /**
     * The corresponding Tensura race for this member, or empty for
     * {@link #COLONIST}. The spawn hook uses this to decide: empty →
     * leave the vanilla citizen alive; present → discard + spawn the
     * race mob.
     */
    public Optional<Race> toRace() {
        return switch (this) {
            case COLONIST -> Optional.empty();
            case GOBLIN -> Optional.of(Race.GOBLIN);
            case ORC -> Optional.of(Race.ORC);
            case LIZARDMAN -> Optional.of(Race.LIZARDMAN);
            case DWARF -> Optional.of(Race.DWARF);
        };
    }

    public static ColonyMember fromRace(Race race) {
        return switch (race) {
            case GOBLIN -> GOBLIN;
            case ORC -> ORC;
            case LIZARDMAN -> LIZARDMAN;
            case DWARF -> DWARF;
        };
    }
}
