package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;

/**
 * One finding as the console's finding card prints it (ADR-041), whichever page it is on: the editor, a bulk row or an edit
 * comparison. {@link FindingCards} builds it from each page's own record, so the card is one piece of markup
 * ({@code console/findings :: card}) and the pages differ only in what they put in it.
 *
 * <p>What a finding says ({@code message}, {@code suggestion}, the fix, the evidence) is the API's own text and passes through
 * unchanged; what the page adds ({@code explanation}, {@code sourceLine}, the labels) is a message of the page's language (ADR-028).
 *
 * @param kind        {@code rules}, {@code trademark} or {@code variation}
 * @param level       {@code error}, {@code warning}, {@code info}, {@code high}, {@code medium} or {@code low}
 * @param pillLabel   the text of the level pill ("Error", "Trademark - High risk")
 * @param pillTone    the pill's modifier: {@code error}, {@code warn}, {@code trademark}, {@code clean}, or empty for a plain pill
 * @param problem     draws the red diamond: an ERROR, or a HIGH trademark risk that has not been resolved
 * @param explanation what the rule checks, or null
 * @param detail      what the finding is about beyond its message (the catalog mark), or null
 * @param sourceLine  where the rule's threshold comes from and how well it is backed ("Source: ... , verified"), or null
 * @param evidence    the text the finding points at, with the part that matters marked; or null
 * @param fix         a verified suggestion of the rules: shown, never applied; or null
 * @param fixId       the number the editor's "Apply suggestion" button carries; null everywhere else (the button is the editor's alone)
 * @param value       the value the finding is about (the editor's apply button sends it back); or null
 * @param llmReason   the reason of a language model that called the word descriptive (ADR-036): empty when it gave none, null when
 *                    the finding has no LLM label
 * @param before      the message the finding had before an edit, when the edit changed it (edit mode); or null
 * @param target      the address the feedback buttons answer (ADR-034); null when the finding is not stored
 */
public record FindingCard(String kind, String level, String pillLabel, String pillTone, boolean problem, String ruleId, String field,
                          String message, String explanation, String suggestion, String detail, String sourceLine, Evidence evidence,
                          Fix fix, Integer fixId, String value, String llmReason, String before, FeedbackTarget target) {

    /** A span of text with the part that matters in the middle; the template marks {@code match}. */
    public record Evidence(String before, String match, String after, boolean cutBefore, boolean cutAfter) {
    }

    /** A suggestion of the rules; {@code proposedValue} is null for a fix that removes the value. */
    public record Fix(String action, String description, String proposedValue) {
    }

    /** How long the heading of a card may be: about two lines of a card. */
    static final int TITLE_CHARS = 90;

    /**
     * The heading of the card: the first sentence of the message, cut at a word when it is longer than {@link #TITLE_CHARS}. What is left
     * of the message goes in the body ({@link #more()}); nothing is dropped.
     */
    public String title() {
        String text = message == null ? "" : message.strip();
        int end = firstSentenceEnd(text);
        String first = end < 0 ? text : text.substring(0, end);
        if (first.codePointCount(0, first.length()) <= TITLE_CHARS) {
            return first;
        }
        String cut = first.substring(0, first.offsetByCodePoints(0, TITLE_CHARS - 1));
        int space = cut.lastIndexOf(' ');
        return (space > TITLE_CHARS / 2 ? cut.substring(0, space) : cut).stripTrailing() + "\u2026";
    }

    /** What the heading leaves of the message: the sentences after the first, or the whole message when the heading was cut; else null. */
    public String more() {
        String text = message == null ? "" : message.strip();
        int end = firstSentenceEnd(text);
        String first = end < 0 ? text : text.substring(0, end);
        if (first.codePointCount(0, first.length()) > TITLE_CHARS) {
            return text;
        }
        return end < 0 ? null : text.substring(end).strip();
    }

    /** The index just after the first sentence (a full stop, ! or ? followed by a space), or -1 when the text is one sentence. */
    private static int firstSentenceEnd(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** True for a finding in the error ground (the highlight is red); trademark risk and warnings are the warning ground. */
    public boolean isError() {
        return "error".equals(level);
    }
}
