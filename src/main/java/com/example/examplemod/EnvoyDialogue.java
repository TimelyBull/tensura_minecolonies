package com.example.examplemod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Race-flavoured dialogue copy for envoy interactions.
 *
 * One table keyed by {@link ColonyMember}, plus extra entries for races
 * that don't have a {@link ColonyMember} value yet (Dwarf, Lizardman) so
 * the copy is already in place when those envoys become reachable in a
 * later stage. Stage 2 only invokes the {@link ColonyMember}-keyed
 * lookups (GOBLIN, ORC, COLONIST).
 *
 * Both client and server read this — no wire serialisation of the body
 * text, just the {@code memberId} byte travels in the open-dialogue
 * payload and each side looks up its local copy.
 */
public final class EnvoyDialogue {

    /** Nameplate label rendered above the envoy's head (e.g. "Goblin Envoy"). */
    public static final EnumMap<ColonyMember, String> NAMEPLATE = new EnumMap<>(ColonyMember.class);
    /** Nameplate text colour. Distinct per race so the player can tell envoys apart from a distance. */
    public static final EnumMap<ColonyMember, ChatFormatting> NAMEPLATE_COLOR = new EnumMap<>(ColonyMember.class);
    /** Dialogue title shown in the Screen header. */
    public static final EnumMap<ColonyMember, String> DIALOGUE_TITLE = new EnumMap<>(ColonyMember.class);
    /** Dialogue body — race-flavoured personality, ends with the diplomacy ask. */
    public static final EnumMap<ColonyMember, String> DIALOGUE_BODY = new EnumMap<>(ColonyMember.class);

    /** String-keyed table for races without a {@link ColonyMember} entry yet
     *  (Dwarf, Lizardman) — copy is ready for when those envoys ship. */
    public static final Map<String, String> EXTRA_NAMEPLATE = new java.util.HashMap<>();
    public static final Map<String, ChatFormatting> EXTRA_NAMEPLATE_COLOR = new java.util.HashMap<>();
    public static final Map<String, String> EXTRA_DIALOGUE_TITLE = new java.util.HashMap<>();
    public static final Map<String, String> EXTRA_DIALOGUE_BODY = new java.util.HashMap<>();

    static {
        NAMEPLATE.put(ColonyMember.GOBLIN,   "Goblin Envoy");
        NAMEPLATE.put(ColonyMember.ORC,       "Orc Envoy");
        NAMEPLATE.put(ColonyMember.COLONIST,  "Colonist Envoy");
        NAMEPLATE.put(ColonyMember.LIZARDMAN, "Lizardman Envoy");
        NAMEPLATE.put(ColonyMember.DWARF,     "Dwarven Envoy");

        NAMEPLATE_COLOR.put(ColonyMember.GOBLIN,    ChatFormatting.GREEN);       // forest, primitive — small green humanoid
        NAMEPLATE_COLOR.put(ColonyMember.ORC,       ChatFormatting.DARK_RED);    // brute strength, savage-but-friendly
        NAMEPLATE_COLOR.put(ColonyMember.COLONIST,  ChatFormatting.AQUA);        // cool, civilised, peaceful
        NAMEPLATE_COLOR.put(ColonyMember.LIZARDMAN, ChatFormatting.YELLOW);      // sand-scale, swamp warmth
        NAMEPLATE_COLOR.put(ColonyMember.DWARF,     ChatFormatting.GOLD);        // craftsmanship, wealth, mountain-hold

        DIALOGUE_TITLE.put(ColonyMember.GOBLIN,    "A goblin envoy approaches");
        DIALOGUE_TITLE.put(ColonyMember.ORC,       "An orc envoy approaches");
        DIALOGUE_TITLE.put(ColonyMember.COLONIST,  "A colonist envoy approaches");
        DIALOGUE_TITLE.put(ColonyMember.LIZARDMAN, "A lizardman envoy approaches");
        DIALOGUE_TITLE.put(ColonyMember.DWARF,     "A dwarven envoy approaches");

        // Goblin voice: humble, eager, warm, deeply grateful — the first and
        // most faithful followers, who remember being saved and protected.
        DIALOGUE_BODY.put(ColonyMember.GOBLIN,
                "Great one! Word of your protection has reached even us — and goblins "
                + "never forget who shelters the weak. We are small, and not strong, but "
                + "we will work hard and follow you with all our hearts. Please, will you "
                + "let our tribe live here at your side?");

        // Orc voice: dutiful, disciplined, solemn — service-minded, with a note
        // of atonement (orcs were starving and leaderless before being given
        // purpose); they speak of repaying through labour and loyalty.
        DIALOGUE_BODY.put(ColonyMember.ORC,
                "I come for my people, great one. Once we orcs were starving and without "
                + "a leader, and we lost our way for it. Now we seek a worthy ruler to "
                + "serve, and to make amends through honest labour and loyalty. We are "
                + "strong — we will carry, build, and fight for you without complaint. "
                + "Will you have us?");

        DIALOGUE_BODY.put(ColonyMember.COLONIST,
                "Good day. I speak for settlers seeking a new home — your colony came "
                + "well-recommended. We bring skilled hands. Would you welcome us?");

        // Lizardman voice: proud, formal, a touch grandiose about their people's
        // strength and lineage — but earnest and sincere in their allegiance.
        DIALOGUE_BODY.put(ColonyMember.LIZARDMAN,
                "I greet you, great one, on behalf of the lizardmen. Ours is an old and "
                + "mighty people — our warriors strong, our bloodline proud. Yet strength "
                + "knows strength, and yours far surpasses our own; it would honour us to "
                + "pledge our spears to one such as you. Will you accept our allegiance?");

        // Dwarf voice: gruff, hearty, blunt — craftsman's pride, fond of good
        // work and a good drink, with warmth beneath the bluntness.
        DIALOGUE_BODY.put(ColonyMember.DWARF,
                "Right then — I'll speak plain, that's the dwarf way. We hear there's a "
                + "ruler here worth the working for, and we don't set our hammers to just "
                + "any forge. Give us good stone, a hot fire, and a strong drink at day's "
                + "end, and you'll find no better smiths nor harder workers anywhere. So "
                + "— do we have a deal?");

        // Pre-written for later stages — not reachable in Stage 2.
        // Stored under string keys since Dwarf/Lizardman aren't ColonyMember
        // values yet; the table is consulted only when those envoys exist.
        EXTRA_NAMEPLATE.put("dwarf", "Dwarf Envoy");
        EXTRA_NAMEPLATE.put("lizardman", "Lizardman Envoy");
        EXTRA_NAMEPLATE_COLOR.put("dwarf", ChatFormatting.GOLD);          // craftsmanship, wealth
        EXTRA_NAMEPLATE_COLOR.put("lizardman", ChatFormatting.YELLOW);    // sand-scale, swamp warmth

        EXTRA_DIALOGUE_TITLE.put("dwarf",     "A dwarven envoy approaches");
        EXTRA_DIALOGUE_TITLE.put("lizardman", "A lizardman envoy approaches");

        EXTRA_DIALOGUE_BODY.put("dwarf",
                "Right then — I'll speak plain, that's the dwarf way. We hear there's a "
                + "ruler here worth the working for, and we don't set our hammers to just "
                + "any forge. Give us good stone, a hot fire, and a strong drink at day's "
                + "end, and you'll find no better smiths nor harder workers anywhere. So "
                + "— do we have a deal?");

        EXTRA_DIALOGUE_BODY.put("lizardman",
                "I greet you, great one, on behalf of the lizardmen. Ours is an old and "
                + "mighty people — our warriors strong, our bloodline proud. Yet strength "
                + "knows strength, and yours far surpasses our own; it would honour us to "
                + "pledge our spears to one such as you. Will you accept our allegiance?");
    }

    private EnvoyDialogue() {}

    /** Build the colored nameplate Component for an envoy of {@code member}. */
    public static Component nameplate(ColonyMember member) {
        String label = NAMEPLATE.getOrDefault(member, member.name() + " Envoy");
        ChatFormatting color = NAMEPLATE_COLOR.getOrDefault(member, ChatFormatting.WHITE);
        return Component.literal(label).withStyle(color);
    }

    public static String title(ColonyMember member) {
        return DIALOGUE_TITLE.getOrDefault(member, "An envoy approaches");
    }

    /** Legacy single-arg overload — base body only, no condition flavour.
     *  Retained for any caller that doesn't have a condition set yet (debug
     *  spawns, logging). The dialogue Screen always uses the
     *  condition-aware overload below. */
    public static String body(ColonyMember member) {
        return body(member, java.util.EnumSet.noneOf(EnvoyCondition.class));
    }

    /**
     * Condition-aware dialogue body. Composition is:
     * <pre>
     *   base(member) + " " + snippet(member, condA) + " " + snippet(member, condB) + ...
     * </pre>
     * Each snippet is a complete, self-contained sentence starting with a
     * capital and ending with punctuation. They're joined with a single
     * space — readable as a sequence of observations the envoy makes,
     * regardless of how many or in what order. Unsupported (member,
     * condition) pairs (e.g. COLONIST + IFRIT) produce no snippet and are
     * silently skipped.
     *
     * <p>Iteration order is the {@link EnvoyCondition} enum declaration
     * order, which keeps the dialogue stable across runs (no random
     * sorting) and groups related snippets sensibly (COUNT / TIMER first,
     * boss kills second, status flags last for DWARF).
     */
    public static String body(ColonyMember member,
                              java.util.Set<EnvoyCondition> conditions) {
        return body(member, conditions, ReputationTier.NEUTRAL);
    }

    /**
     * Reputation-aware dialogue body. Same composition as the
     * condition-aware overload, with one extra tone sentence appended at
     * the end reflecting the colony's {@link ReputationTier} — a DEVOTED
     * colony's envoy closes warm, a WARY one closes guarded.
     * {@code NEUTRAL} appends nothing, so default-reputation dialogue is
     * byte-identical to the pre-reputation text.
     */
    public static String body(ColonyMember member,
                              java.util.Set<EnvoyCondition> conditions,
                              ReputationTier tier) {
        StringBuilder out = new StringBuilder();
        out.append(DIALOGUE_BODY.getOrDefault(member,
                "An envoy seeks to join your colony."));
        if (conditions != null && !conditions.isEmpty()) {
            // Enum-declaration order is the stable presentation order.
            for (EnvoyCondition c : EnvoyCondition.values()) {
                if (!conditions.contains(c)) continue;
                String snippet = conditionSnippet(member, c);
                if (snippet == null || snippet.isEmpty()) continue;
                out.append(' ').append(snippet);
            }
        }
        String tone = reputationTone(tier);
        if (tone != null) {
            out.append(' ').append(tone);
        }
        return out.toString();
    }

    /**
     * Per-tier tone sentence appended after the base + condition snippets.
     * Written race-neutrally in the envoys' shared reverent register so
     * one line per tier covers all five races (per-race tone variants are
     * a future polish pass, not a v1 requirement).
     *
     * <p>NEUTRAL returns {@code null} — no tone line — which keeps
     * default-reputation dialogue identical to the pre-reputation copy
     * (and means legacy / fresh colonies see no text change at all).
     */
    public static String reputationTone(ReputationTier tier) {
        return switch (tier) {
            case HOSTILE ->
                    "I will not pretend otherwise — dark things are said of this "
                    + "place, and many counselled against my coming at all.";
            case PASSIVEAGGRESSIVE ->
                    "I will say only that not all we hear of this colony is kind "
                    + "— though we chose to come regardless.";
            case WARY ->
                    "I confess we have heard mixed accounts of this place. We "
                    + "watch, and we hope to be proven wrong.";
            case NEUTRAL -> null;
            case LOYAL ->
                    "Word of this colony's good name travels far — it is no small "
                    + "part of why we came.";
            case DEVOTED ->
                    "Your colony's name is spoken with reverence in every camp "
                    + "from here to the far hills — it is an honour simply to "
                    + "stand within its walls.";
        };
    }

    /**
     * Per-(race, condition) flavour snippet. Returns {@code null} when no
     * snippet has been written for that pair — the composer skips it.
     *
     * <p>Each snippet is one complete sentence in the race's voice:
     * <ul>
     *   <li>GOBLIN — humble, eager, warm, deeply grateful.</li>
     *   <li>ORC — dutiful, disciplined, solemn; a note of atonement;
     *       speaks of repaying through service.</li>
     *   <li>COLONIST — neutral, polite, business-formal.</li>
     *   <li>LIZARDMAN — proud, formal, a touch grandiose, but earnest and
     *       sincere in allegiance.</li>
     *   <li>DWARF — gruff, hearty, blunt; craftsman's pride; plain-spoken
     *       and warm beneath the bluntness.</li>
     * </ul>
     */
    public static String conditionSnippet(ColonyMember member, EnvoyCondition condition) {
        return switch (member) {
            case GOBLIN -> switch (condition) {
                case COUNT ->
                        "The goblins already at your side send back only happy words — "
                        + "that is why we come to you so gladly.";
                default -> null;
            };
            case COLONIST -> switch (condition) {
                case TIMER ->
                        "Your colony has stood the test of time, and word of that "
                        + "has reached us.";
                default -> null;
            };
            case ORC -> switch (condition) {
                case COUNT ->
                        "Your settlement has grown great and strong — a place, we "
                        + "think, where many hands like ours could do honest work.";
                case ORC_DISASTER_DEFEATED ->
                        "And there is the Orc Disaster — the calamity not one of us "
                        + "could stand against. You laid it low. For that, my people "
                        + "owe you a debt we mean to repay with our service.";
                default -> null;
            };
            case LIZARDMAN -> switch (condition) {
                case COUNT ->
                        "Your settlement has grown to a size worthy of our notice — "
                        + "and we lizardmen offer our strength only where it is earned.";
                case IFRIT_DEFEATED ->
                        "And we have not failed to mark that you felled Ifrit — a feat "
                        + "few could ever claim. Such power leaves us in genuine awe; "
                        + "we would be proud to stand beneath it.";
                default -> null;
            };
            case DWARF -> switch (condition) {
                case COUNT ->
                        "Word of your craftwork's reached us — and a dwarf marks a "
                        + "well-kept forge before near anything else. Yours speaks well "
                        + "of you.";
                case TIMER ->
                        "Twenty days you've held this place without once falling — "
                        + "that's the kind of steady hand a dwarf can respect.";
                case DWARVEN_VILLAGE ->
                        "And we hear you've walked among our kin in their own village "
                        + "— and left a good impression, which isn't easily done.";
                case TRUE_DEMON_LORD ->
                        "And we know full well you bear the mantle of a true demon lord "
                        + "— a dwarf doesn't offer his hand to such a one lightly, but "
                        + "offer it we do.";
                case TRUE_HERO ->
                        "And your name's known to us as that of a true hero — there's "
                        + "no finer company a dwarf could send his craftsfolk to keep, "
                        + "and that's the plain truth.";
                default -> null;
            };
        };
    }

    /** Race-flavoured confirmation when the player accepts (no-condition
     *  overload — used by debug paths). */
    public static String acceptMessage(ColonyMember member) {
        return acceptMessage(member, java.util.EnumSet.noneOf(EnvoyCondition.class));
    }

    /**
     * Condition-aware accept message. For DWARF specifically, if the
     * captured set includes {@link EnvoyCondition#TRUE_HERO} or
     * {@link EnvoyCondition#TRUE_DEMON_LORD} the dwarven envoy
     * acknowledges the title in their parting words — those are the
     * conditions that materially honour the player; other dwarf
     * conditions fall back to the standard parting line.
     *
     * <p>HERO takes precedence over DEMON_LORD when both are somehow
     * captured simultaneously (rare: a player who is both true hero AND
     * true demon lord). Order chosen because the hero title is the
     * narratively more "honourable" frame; demon-lord acknowledgements
     * lean weighted/reverent rather than honoured.
     *
     * <p>Other races: not condition-aware here — none of their unlock
     * conditions reframe the accept message meaningfully (orc-disaster
     * is already woven into the greeting; ifrit similarly; the
     * count/timer alternatives don't warrant a parting-line variant).
     */
    public static String acceptMessage(ColonyMember member,
                                       java.util.Set<EnvoyCondition> conditions) {
        if (member == ColonyMember.DWARF && conditions != null) {
            if (conditions.contains(EnvoyCondition.TRUE_HERO)) {
                return "The dwarven envoy bows lower than any dwarf bows easily. \"To "
                        + "put our hammers to a true hero's cause — there's honour in "
                        + "that a dwarf doesn't find twice in a life. Our craftsfolk "
                        + "ride with the next caravan, and proud to. Fare you well, "
                        + "hero.\"";
            }
            if (conditions.contains(EnvoyCondition.TRUE_DEMON_LORD)) {
                return "The dwarven envoy inclines their head, grave and certain. \"A "
                        + "dwarf doesn't bind his hand to a true demon lord on a whim "
                        + "— but bind it we do, and gladly. The first caravan rides "
                        + "with the next moon. Until then, great one.\"";
            }
        }
        return switch (member) {
            case GOBLIN ->
                    "The goblin envoy bows again and again, beaming. \"Thank you, great one — thank you! We will not let you down!\" Goblins will make their way to your colony.";
            case ORC ->
                    "The orc envoy lowers their head solemnly, a fist over their heart. \"You honour us. We will repay this with every ounce of our strength.\" Orcs will make their way to your colony.";
            case COLONIST ->
                    "The colonist envoy nods gratefully. Settlers will make their way to your colony.";
            case LIZARDMAN ->
                    "The lizardman envoy bows with rigid, formal pride. \"You honour our people, great one. Our strongest will be sent to your side in due course.\"";
            case DWARF ->
                    "The dwarven envoy grins through their beard and claps you on the arm. \"Ha! A fine bargain, and no mistake. Our craftsfolk arrive with the next caravan — have a forge ready, and a barrel of ale colder still.\"";
        };
    }

    /** No-condition overload — debug / fallback. */
    public static String declineMessage(ColonyMember member) {
        return declineMessage(member, java.util.EnumSet.noneOf(EnvoyCondition.class));
    }

    /**
     * Condition-aware decline message. Same DWARF + TRUE_HERO /
     * TRUE_DEMON_LORD branching shape as {@link #acceptMessage}: a
     * dwarven envoy refused by a player they came specifically because
     * of a hero / demon-lord title parts with a more pointed
     * acknowledgement that the title makes the refusal more notable —
     * not insulting, but the envoy's voice carries the weight of the
     * occasion declined.
     */
    public static String declineMessage(ColonyMember member,
                                        java.util.Set<EnvoyCondition> conditions) {
        if (member == ColonyMember.DWARF && conditions != null) {
            if (conditions.contains(EnvoyCondition.TRUE_HERO)) {
                return "The dwarven envoy blinks, plainly caught off guard. \"Turned "
                        + "aside by a true hero, of all folk — there's a tale none "
                        + "back home will credit. Fare you well. Our doors stay open "
                        + "to you, always.\"";
            }
            if (conditions.contains(EnvoyCondition.TRUE_DEMON_LORD)) {
                return "The dwarven envoy regards you a long moment, then bows. \"As "
                        + "you will it, then. A dwarf remembers those who'd not be put "
                        + "in his debt — should the wind turn, send word.\"";
            }
        }
        return switch (member) {
            case GOBLIN ->
                    "The goblin envoy's ears droop. They nod, downcast, and shuffle away.";
            case ORC ->
                    "The orc envoy bows their head in quiet acceptance and turns to go, shoulders heavy.";
            case COLONIST ->
                    "The colonist envoy thanks you politely and departs.";
            case LIZARDMAN ->
                    "The lizardman envoy draws itself up and bows, proud even in refusal. \"Understood. Our offer stands, should you think better of it.\"";
            case DWARF ->
                    "The dwarven envoy gives a gruff nod. \"Bah — your loss, and ours. You know where to find us if you change your mind.\"";
        };
    }
}
