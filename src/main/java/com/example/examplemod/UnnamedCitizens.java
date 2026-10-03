package com.example.examplemod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * "Unnamed" race citizens — grown arrivals (free immigration, envoy seeds)
 * that the player has not named yet.
 *
 * MineColonies requires every citizen record to have SOME name (it is shown
 * in the town hall lists, chat messages, graves...), so an unnamed race
 * citizen carries a PLACEHOLDER name: just its race, e.g. "Goblin". That
 * placeholder is also the marker for "not named yet" — there is no separate
 * flag to keep in sync:
 *
 *   - citizen name == placeholder  → unnamed: nameplate hidden, and the
 *     summoned mob arrives WITHOUT a Tensura name so the player can name it.
 *   - anything else                → named, normal behaviour.
 *
 * Naming the summoned mob goes through the normal rename path, which
 * replaces the placeholder with the real name, so the citizen becomes
 * "named" by itself.
 *
 * The placeholder is deliberately ONE word. MineColonies builds a newborn's
 * surname from its parents' names; a one-word parent name makes it fall back
 * to a normal random name instead of producing children called "... Goblin".
 *
 * Known edge case: a player who names a goblin exactly "Goblin" gets the
 * unnamed treatment for it (hidden nameplate, arrives unnamed on summon).
 *
 * This class has no client-only imports, so both sides can use it.
 */
public final class UnnamedCitizens {

    private UnnamedCitizens() {}

    /** The placeholder citizen name for an unnamed citizen of {@code race}. */
    public static String placeholderName(Race race) {
        return switch (race) {
            case GOBLIN -> "Goblin";
            case ORC -> "Orc";
            case LIZARDMAN -> "Lizardman";
            case DWARF -> "Dwarf";
            case OTHERWORLDER -> "Otherworlder";
        };
    }

    /** True if {@code name} is the placeholder for {@code race}, i.e. the
     *  citizen has not been named by the player yet. */
    public static boolean isPlaceholderName(Race race, String name) {
        if (race == null || name == null) return false;
        return placeholderName(race).equals(name);
    }

    /**
     * The nameplate text a race renderer should show for {@code citizen}:
     * its display name, or {@code null} (show nothing) while it is unnamed.
     * Used by the renderers that draw a stand-in mob and copy the citizen's
     * name onto it each frame.
     */
    public static Component nameplateOrNull(LivingEntity citizen, Race race) {
        Component name = citizen.getCustomName() != null
                ? citizen.getCustomName()
                : citizen.getName();
        if (isPlaceholderName(race, name.getString())) return null;
        return name;
    }
}
