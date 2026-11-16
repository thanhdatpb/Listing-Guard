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

    /** True for a finding in the error ground (the highlight is red); trademark risk and warnings are the warning ground. */
    public boolean isError() {
        return "error".equals(level);
    }
}
