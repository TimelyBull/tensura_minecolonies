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

        DIALOGUE_BODY.put(ColonyMember.GOBLIN,
                "Honored chief. The Elder sent me. We are small and not strong, but we "
                + "work hard and follow well. Will you let our tribe live here with you?");

        // Orc voice: limited reasoning, not toddler vocabulary. Short
        // declarative sentences, plain "what we are / what we do," no
        // baby-talk intensifiers.
        DIALOGUE_BODY.put(ColonyMember.ORC,
                "Hello, friend. Your colony is one we noticed. Orcs are strong, and we "
                + "would carry for you and fight for you. We would like to stand with "
                + "your people. Will you have us?");

        DIALOGUE_BODY.put(ColonyMember.COLONIST,
                "Good day. I speak for settlers seeking a new home — your colony came "
                + "well-recommended. We bring skilled hands. Would you welcome us?");

        DIALOGUE_BODY.put(ColonyMember.LIZARDMAN,
                "Greetings, founder. The Marsh-Tribe sent me to assess your settlement. "
                + "We do not lend our hands lightly — but yours shows promise. Will you "
                + "have us?");

        DIALOGUE_BODY.put(ColonyMember.DWARF,
                "Greetings, founder. I speak for the Dwarven Holds. After due "
                + "deliberation we have judged your settlement worthy. We bring stonework "
                + "and smithing — for a fair share of your prosperity, of course. Will "
                + "you accept?");

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
                "Greetings, founder. I speak for the Dwarven Holds. After due "
                + "deliberation we have judged your settlement worthy. We bring stonework "
                + "and smithing — for a fair share of your prosperity, of course. Will "
                + "you accept?");

        EXTRA_DIALOGUE_BODY.put("lizardman",
                "Greetings, founder. The Marsh-Tribe sent me to assess your settlement. "
                + "We do not lend our hands lightly — but yours shows promise. Will you "
                + "have us?");
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
        StringBuilder out = new StringBuilder();
        out.append(DIALOGUE_BODY.getOrDefault(member,
                "An envoy seeks to join your colony."));
        if (conditions == null || conditions.isEmpty()) return out.toString();
        // Enum-declaration order is the stable presentation order.
        for (EnvoyCondition c : EnvoyCondition.values()) {
            if (!conditions.contains(c)) continue;
            String snippet = conditionSnippet(member, c);
            if (snippet == null || snippet.isEmpty()) continue;
            out.append(' ').append(snippet);
        }
        return out.toString();
    }

    /**
     * Per-(race, condition) flavour snippet. Returns {@code null} when no
     * snippet has been written for that pair — the composer skips it.
     *
     * <p>Each snippet is one complete sentence in the race's voice:
     * <ul>
     *   <li>GOBLIN — humble, deferential, short sentences.</li>
     *   <li>ORC — dumb-friendly, exclamation marks, "really" / "real" /
     *       "very" intensifiers.</li>
     *   <li>COLONIST — neutral, polite, business-formal.</li>
     *   <li>LIZARDMAN — condescending but courteous, "we do not lightly..."
     *       / "you have shown..." constructions.</li>
     *   <li>DWARF — well-spoken, formal, fond of "moreover", "indeed",
     *       and acknowledgements of stewardship / craft.</li>
     * </ul>
     */
    public static String conditionSnippet(ColonyMember member, EnvoyCondition condition) {
        return switch (member) {
            case GOBLIN -> switch (condition) {
                case COUNT ->
                        "The Elder said our kin who come to you do not return sad — "
                        + "that is why we trust you.";
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
                        "Your colony is large now — large enough that we want a "
                        + "place in it.";
                case ORC_DISASTER_DEFEATED ->
                        "And — the Orc Disaster. The one no orc could fight. You "
                        + "killed him. Our people are grateful. That is why we came.";
                default -> null;
            };
            case LIZARDMAN -> switch (condition) {
                case COUNT ->
                        "Your settlement has grown to a size we deem notable — we "
                        + "do not approach lesser holdings.";
                case IFRIT_DEFEATED ->
                        "And we have noted that you felled Ifrit — few warmbloods "
                        + "could. We are, reluctantly, impressed.";
                default -> null;
            };
            case DWARF -> switch (condition) {
                case COUNT ->
                        "Your colony's craft has drawn the Holds' attention — your "
                        + "smithfires speak well of you.";
                case TIMER ->
                        "Twenty days by our reckoning since you last fell — steady "
                        + "stewardship, and our council marks it well.";
                case DWARVEN_VILLAGE ->
                        "That you walked among our kin in their own hold was reported "
                        + "to us — and reported favourably.";
                case TRUE_DEMON_LORD ->
                        "Moreover, the Holds know you bear the mantle of a true demon "
                        + "lord — and we treat with such a one only with full honour.";
                case TRUE_HERO ->
                        "Moreover, our chroniclers have marked your name: a true hero "
                        + "— and the Holds account it a rare honour to send our "
                        + "craftsfolk to such company.";
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
                return "The dwarven envoy bows lower than ceremony requires. \"To set "
                        + "our hands to a true hero's work is no small honour. Our "
                        + "craftsfolk arrive with the next caravan — and proud. Fare "
                        + "you well, hero.\"";
            }
            if (conditions.contains(EnvoyCondition.TRUE_DEMON_LORD)) {
                return "The dwarven envoy inclines their head gravely. \"We do not "
                        + "lightly bind our hand to one bearing the demon lord's "
                        + "mantle — but we do so now, with the full weight of the "
                        + "Holds. The first caravan rides with the next moon. Until "
                        + "then, my lord.\"";
            }
        }
        return switch (member) {
            case GOBLIN ->
                    "The goblin envoy bows deeply. Goblins will make their way to your colony.";
            case ORC ->
                    "The orc envoy lets out a deep, glad sound. Orcs will make their way to your colony.";
            case COLONIST ->
                    "The colonist envoy nods gratefully. Settlers will make their way to your colony.";
            case LIZARDMAN ->
                    "The lizardman envoy gives a measured nod. \"Thank you, founder. The Marsh-Tribe will send its people in due course.\"";
            case DWARF ->
                    "The dwarven envoy strokes their beard. \"A fine bargain. Our craftsfolk arrive with the next caravan — have lodgings ready.\"";
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
                return "The dwarven envoy straightens, surprise barely concealed. "
                        + "\"That a true hero would turn us aside — few in our Holds "
                        + "will believe the tale. Fare you well. Our gates remain "
                        + "open to you.\"";
            }
            if (conditions.contains(EnvoyCondition.TRUE_DEMON_LORD)) {
                return "The dwarven envoy regards you long, then bows shallowly. "
                        + "\"As my lord wills. The Holds remember those who would "
                        + "not be obliged to us. Should the wind turn, send word.\"";
            }
        }
        return switch (member) {
            case GOBLIN ->
                    "The goblin envoy nods sadly and shuffles away.";
            case ORC ->
                    "The orc envoy shrugs and lumbers off — not seeming much hurt by it.";
            case COLONIST ->
                    "The colonist envoy thanks you politely and departs.";
            case LIZARDMAN ->
                    "The lizardman envoy gives a reserved bow. \"I understand. Perhaps another time.\"";
            case DWARF ->
                    "The dwarven envoy bows with measured dignity. \"A pity. Should you reconsider, our Holds are not so distant.\"";
        };
    }
}
