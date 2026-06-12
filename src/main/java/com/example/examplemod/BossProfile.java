package com.example.examplemod;

/**
 * What a boss entity means to the faction layer: which faction it
 * belongs to and how much the world cares when something happens to it
 * (the lore-importance WEIGHT every two-sided mover is scaled by).
 * Grown from the bare {@code factionOf} entity→faction map — see
 * docs/faction-model.md #2.
 *
 * <p>The map itself ({@code BOSS_PROFILES}) lives in
 * {@link WorldReputationManager} (the same lazy-init spot the old map
 * had, since {@code EntityType} suppliers need registries built).
 */
public record BossProfile(BossFaction faction, Importance importance) {

    /**
     * Lore importance — how hard a marked kill moves the boss's
     * faction. KEYSTONE = the faction's anchor figure; MAJOR = a
     * faction scheme/calamity; NOTABLE = a named lieutenant;
     * MINOR = fodder (kept SMALL — killing mobs is core Minecraft).
     */
    public enum Importance {
        KEYSTONE(1.0),
        MAJOR(0.6),
        NOTABLE(0.3),
        MINOR(0.1);

        private final double weight;

        Importance(double weight) { this.weight = weight; }

        public double weight() { return weight; }
    }
}
