package com.thanhdatpb.listingguard.api.evaluation.backtest;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a claim or takedown was about, as the person who prepared the file labelled it (ADR-032). The list is closed: a label
 * that is not on it is refused for its row, never guessed, so a typo cannot move a claim into another group.
 *
 * <p>{@link #coverage()} says which part of Listing Guard looks at this kind of problem at all. A miss of a kind no part
 * looks at is reported as {@link MissReason#NOT_COVERED}, so the report does not blame a rule for what no rule checks.
 */
enum IssueType {

    TRADEMARK("trademark", Coverage.TRADEMARK),
    LISTING_POLICY("listing-policy", Coverage.RULES),
    PROHIBITED("prohibited", Coverage.RULES),
    COPYRIGHT("copyright", Coverage.NONE),
    COUNTERFEIT("counterfeit", Coverage.NONE),
    IMAGE("image", Coverage.NONE),
    OTHER("other", Coverage.NONE),
    /** the listing was claimed, and the file does not say what about (no issue column, or a blank cell) */
    UNSPECIFIED("unspecified", Coverage.ANY);

    /** Which part of the check is expected to catch a problem of this kind. */
    enum Coverage { RULES, TRADEMARK, ANY, NONE }

    private final String code;
    private final Coverage coverage;

    IssueType(String code, Coverage coverage) {
        this.code = code;
        this.coverage = coverage;
    }

    String code() {
        return code;
    }

    Coverage coverage() {
        return coverage;
    }

    /** The codes a cell may hold, for a message. */
    static String accepted() {
        return String.join(", ", Arrays.stream(values()).filter(type -> type != UNSPECIFIED).map(IssueType::code).toList());
    }

    /** The type of a label, case and the separators {@code _} and space ignored; empty for a label not on the list. */
    static Optional<IssueType> parse(String label) {
        String text = label.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return Arrays.stream(values()).filter(type -> type != UNSPECIFIED && type.code.equals(text)).findFirst();
    }
}
