package com.thanhdatpb.listingguard.api.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * A share with the counts it comes from (ADR-032). A rate is never shown without them: {@code 3/4} and {@code 300/400} are
 * not the same evidence. With nothing to count it has no value ({@link #value()} is null), never 0 or 100%.
 */
public record Rate(int numerator, int denominator) {

    public Rate {
        if (numerator < 0 || denominator < 0 || numerator > denominator) {
            throw new IllegalArgumentException("not a rate: " + numerator + "/" + denominator);
        }
    }

    /** The share between 0 and 1, rounded to 4 decimals; null when the denominator is 0. */
    @JsonProperty("value")
    public Double value() {
        if (denominator == 0) {
            return null;
        }
        return Math.round(10000.0 * numerator / denominator) / 10000.0;
    }

    /** For example {@code 3/5 (60.0%)}, or {@code n/a (0/0)}. */
    public String text() {
        if (denominator == 0) {
            return "n/a (0/0)";
        }
        return numerator + "/" + denominator + String.format(Locale.ROOT, " (%.1f%%)", 100.0 * numerator / denominator);
    }
}
