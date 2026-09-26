package com.example.examplemod;

/**
 * Hidden developer gate for everything built around TR: Nightmare that isn't
 * ready for players yet (2026-09-26, developer decision).
 *
 * <p>Off by default. It is a JVM system property — NOT a config option — so
 * players never see or toggle it. With it off, the mod behaves as if the TR:N
 * work didn't exist, EVEN IF TR: Nightmare is installed. To work on this
 * content in dev, launch with the Gradle property:
 * <pre>./gradlew runClient -PtrnDev=true</pre>
 * (build.gradle forwards it to {@value #PROPERTY} on every run config).
 *
 * <p>What sits behind it — three groups, each with its own check so the
 * intent at each call site is readable:
 * <ul>
 *   <li>{@link #trnActive()} — code that only works WITH TR:N installed: Greed
 *       steals + owner-clear veto, Deal-Maker contract naming, TR:N bosses
 *       (Frey / Yuuki / Milim's wrath), TR:N garrison lieutenants, TR:N
 *       covenant bonus skills + Leon's Scorch Nucleation Core, TR:N skills on
 *       the info-skill / control-rank ladders. Off → base Tensura fallbacks.</li>
 *   <li>{@link #newFactions()} — the two factions added alongside TR:N:
 *       Fulbrosia (hidden entirely — treated like the retired Shizu) and the
 *       Dragon Faithful (Milim's SETTLEMENT; Milim stays a normal
 *       diplomacy-only faction), plus the Milim/Eurazania/Fulbrosia standing
 *       bloc.</li>
 *   <li>{@link #espionage()} — the player-facing mind-control / deceit suite:
 *       controller roster rows, [Plant]/[Strike]/[Steal]/[Debrief],
 *       suspicion marks, interrogation, stolen-subordinate requests +
 *       Release, the Barrier Core's Cleanse button, and settlement scouting.
 *       The core {@code MindControlTracker.tick} reconcile STAYS ON: it keeps
 *       a subordinate's record consistent when base Tensura's own charm skills
 *       hand it to another player, and unwinds any control state an old dev
 *       world already holds.</li>
 * </ul>
 * All three share the one switch; they're separate methods only so each call
 * site says which kind of content it is hiding.
 *
 * <p>Read on BOTH sides (the client hides its buttons with it): in
 * singleplayer/dev the client and server share the JVM, so one flag covers
 * both; a dedicated test server needs it on the server AND the client.
 */
public final class TrnGate {

    public static final String PROPERTY = "tensura_minecolonies.dev.trnightmare";

    /** The switch. Read once at class load. */
    public static final boolean DEV_ENABLED = Boolean.getBoolean(PROPERTY);

    private TrnGate() {}

    /** TR:N-only integrations: the dev switch is on AND TR:N is installed. */
    public static boolean trnActive() {
        return DEV_ENABLED && net.neoforged.fml.ModList.get().isLoaded("trnightmare");
    }

    /** Fulbrosia, the Dragon Faithful settlement, and the Milim standing bloc. */
    public static boolean newFactions() {
        return DEV_ENABLED;
    }

    /** The player-facing mind-control / deceit / scouting suite. */
    public static boolean espionage() {
        return DEV_ENABLED;
    }
}
