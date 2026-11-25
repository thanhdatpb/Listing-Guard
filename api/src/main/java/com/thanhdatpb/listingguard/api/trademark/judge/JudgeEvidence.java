package com.thanhdatpb.listingguard.api.trademark.judge;

import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The facts the program already knows about a matched mark, put in front of the language model with the sentence (ADR-051). It is
 * computed in plain code from data the program holds, and it makes no network call: a field the program does not have is
 * {@code null} and is shown as {@code unknown}, never guessed. It decides nothing: the judge can still only lower a MEDIUM catalog
 * finding, exactly as before.
 *
 * <p>Public data: the catalog's distinctiveness signals (unverified, ADR-046), the Nice classes of the mark and the last TSDR status
 * the catalog recorded. The client's own data: whether its most recent review of a finding with the same signature ended as a real
 * finding or a false alarm, and how many days ago. Neither holds text of a listing, a note, a reviewer or a catalog mark.
 *
 * @param tier                tier of the catalog entry, null when the catalog has none (a {@code jsonl-1} catalog)
 * @param sameMarkCount       live USPTO serial records with the same mark text; records, not owners (ADR-046)
 * @param sameMarkClassCount  Nice classes those records cover
 * @param markKind            kind of the mark
 * @param dictionaryWord      whether the mark text is a dictionary word, null when the catalog could not tell
 * @param wordCount           words of the mark: the catalog's count, or the tokens of the normalized mark
 * @param productClassOverlap whether a Nice class of the mark is a class of the declared product type; null without a product type
 *                            or without classes
 * @param tsdr                the last TSDR status the catalog recorded, null when none (or when the operator did not turn it on)
 * @param verifiedCase        the client's nearest verified decision, never null
 */
public record JudgeEvidence(DistinctivenessSignals.Tier tier, Long sameMarkCount, Integer sameMarkClassCount,
                            DistinctivenessSignals.MarkKind markKind, Boolean dictionaryWord, Integer wordCount,
                            Boolean productClassOverlap, TsdrState tsdr, VerifiedState verifiedCase) {

    public static final String UNKNOWN = "unknown";

    /** The last TSDR status of the mark's source records, as the table holds it. {@code statusClass} is LIVE, DEAD or UNKNOWN. */
    public record TsdrState(String statusClass, long ageDays) {
    }

    /**
     * The client's nearest verified decision for a finding with the same rule, kind of field and mark.
     * {@code UNKNOWN}: verified cases are off or the lookup failed. {@code NONE}: they are on and there is none. Only the outcome and
     * the age in days are kept: never the reason, a note or a reviewer.
     */
    public record VerifiedState(Kind kind, long ageDays) {

        public enum Kind { UNKNOWN, NONE, CONFIRMED, FALSE_ALARM }

        public static final VerifiedState NOT_KNOWN = new VerifiedState(Kind.UNKNOWN, 0);
        public static final VerifiedState NO_CASE = new VerifiedState(Kind.NONE, 0);

        String text() {
            return switch (kind) {
                case UNKNOWN -> UNKNOWN;
                case NONE -> "none";
                case CONFIRMED -> "confirmed, " + ageDays + " days ago";
                case FALSE_ALARM -> "false_alarm, " + ageDays + " days ago";
            };
        }
    }

    /** What the model is shown, in the order it is shown, and what the finding records afterwards: every field, {@code unknown} included. */
    public Map<String, String> asMap() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("distinctiveness_tier", tier == null ? UNKNOWN : tier.name());
        fields.put("same_mark_count", sameMarkCount == null ? UNKNOWN : sameMarkCount.toString());
        fields.put("same_mark_class_count", sameMarkClassCount == null ? UNKNOWN : sameMarkClassCount.toString());
        fields.put("mark_kind", markKind == null ? UNKNOWN : markKind.name());
        fields.put("dictionary_word", dictionaryWord == null ? UNKNOWN : dictionaryWord.toString());
        fields.put("word_count", wordCount == null ? UNKNOWN : wordCount.toString());
        fields.put("product_class_overlap", productClassOverlap == null ? UNKNOWN : productClassOverlap ? "yes" : "no");
        fields.put("tsdr_status", tsdr == null ? UNKNOWN : tsdr.statusClass() + ", checked " + tsdr.ageDays() + " days ago");
        fields.put("verified_case", (verifiedCase == null ? VerifiedState.NOT_KNOWN : verifiedCase).text());
        return fields;
    }

    /** The lines of the prompt's evidence block: {@code - name: value}. */
    public String block() {
        StringBuilder out = new StringBuilder();
        asMap().forEach((name, value) -> out.append(out.length() == 0 ? "" : "\n").append("- ").append(name).append(": ").append(value));
        return out.toString();
    }

    /** True when the program knows none of the fields: the model then gets the question of the old prompt and nothing else. */
    public boolean empty() {
        return asMap().values().stream().allMatch(value -> value.equals(UNKNOWN));
    }
}
