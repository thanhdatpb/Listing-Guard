package com.thanhdatpb.listingguard.api.feedback;

import java.util.Locale;
import java.util.Optional;

/**
 * What a reviewer thought of one finding (ADR-034). Stored as its name; keep the list equal to the {@code CHECK} constraint
 * {@code ck_finding_verdict_verdict} of V37 (a test holds it).
 */
public enum FeedbackVerdict {
    /** The finding is right about this listing ("Đúng"). */
    CORRECT,
    /** The finding is wrong: the listing does not have this problem ("Báo nhầm"). */
    FALSE_POSITIVE,
    /** The finding may be right but does not help ("Không hữu ích"). */
    NOT_USEFUL;

    /** The verdict named by {@code text} in any case, or empty for anything else (never a default). */
    public static Optional<FeedbackVerdict> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String name = text.trim().toUpperCase(Locale.ROOT);
        for (FeedbackVerdict verdict : values()) {
            if (verdict.name().equals(name)) {
                return Optional.of(verdict);
            }
        }
        return Optional.empty();
    }

    /** The CSS class and the end of the message key of this verdict ({@code feedback.verdict.false-positive}). */
    public String css() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
