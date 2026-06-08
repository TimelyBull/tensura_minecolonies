package com.example.examplemod;

/**
 * Namable Tensura creatures that become citizen-GUARDS (not worker
 * races). Parallel to {@link Race} but conceptually disjoint —
 * beasts are non-humanoid, locked to the PATROL guard task, and
 * never join the race-picker / envoy / skill-profile systems.
 *
 * <p>Stable byte ids — never renumber existing values; new beasts
 * append.
 */
public enum Beast {
    /** Tensura's knight spider — {@code tensura:knight_spider}.
     *  Largest namable creature (5.0w × 3.75h). Citizen body keeps
     *  humanoid hitbox (SCALE 1.0) — spider is visual-only. */
    KNIGHT_SPIDER(0);

    private final int id;
    Beast(int id) { this.id = id; }
    public int getId() { return id; }
    public static Beast byId(int id) {
        for (Beast b : values()) if (b.id == id) return b;
        return KNIGHT_SPIDER;
    }
}
