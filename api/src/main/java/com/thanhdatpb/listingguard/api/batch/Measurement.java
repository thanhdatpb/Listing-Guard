package com.thanhdatpb.listingguard.api.batch;

import java.util.Locale;

/**
 * How the trademark check did on listings that carry a label (for example "was taken down"): a confusion matrix, the
 * detection rate and the false alarm rate, each with the number of listings it rests on.
 *
 * <p>A listing is <em>flagged</em> when its trademark risk level is MEDIUM or HIGH (the same line ADR-009 draws for its
 * evaluation) and <em>clear</em> when it is LOW. A listing whose risk could not be determined (UNKNOWN, a catalog that
 * is not configured, a check that failed) is counted on its own and left out of both rates, because it is neither a
 * hit nor a miss; the report shows how many there are. A listing without a label, or that could not be read, is not
 * measured.
 */
final class Measurement {

    enum Verdict { FLAGGED, CLEAR, UNDETERMINED }

    /** A rate with the counts it comes from. */
    record Rate(int numerator, int denominator) {

        /** For example {@code 3/5 (60.0%)}, or {@code n/a (0/0)} when nothing can be said. */
        String text() {
            if (denominator == 0) {
                return "n/a (0/0)";
            }
            return numerator + "/" + denominator + String.format(Locale.ROOT, " (%.1f%%)", 100.0 * numerator / denominator);
        }
    }

    private int truePositive;
    private int falseNegative;
    private int trueUndetermined;
    private int falsePositive;
    private int trueNegative;
    private int falseUndetermined;
    private int unlabeled;
    private int notChecked;

    void add(Boolean label, Verdict verdict) {
        if (label == null) {
            unlabeled++;
        } else if (label) {
            switch (verdict) {
                case FLAGGED -> truePositive++;
                case CLEAR -> falseNegative++;
                case UNDETERMINED -> trueUndetermined++;
            }
        } else {
            switch (verdict) {
                case FLAGGED -> falsePositive++;
                case CLEAR -> trueNegative++;
                case UNDETERMINED -> falseUndetermined++;
            }
        }
    }

    /** A row that was not checked because its data could not be read: it cannot be measured. */
    void addNotChecked() {
        notChecked++;
    }

    /** Labelled true and flagged. */
    int truePositive() {
        return truePositive;
    }

    /** Labelled true and not flagged: a miss. */
    int falseNegative() {
        return falseNegative;
    }

    int trueUndetermined() {
        return trueUndetermined;
    }

    /** Labelled false and flagged: a false alarm. */
    int falsePositive() {
        return falsePositive;
    }

    int trueNegative() {
        return trueNegative;
    }

    int falseUndetermined() {
        return falseUndetermined;
    }

    int unlabeled() {
        return unlabeled;
    }

    int notChecked() {
        return notChecked;
    }

    int labelledTrue() {
        return truePositive + falseNegative + trueUndetermined;
    }

    int labelledFalse() {
        return falsePositive + trueNegative + falseUndetermined;
    }

    /** Of the listings labelled true whose risk was determined, how many were flagged. */
    Rate detectionRate() {
        return new Rate(truePositive, truePositive + falseNegative);
    }

    /** Of the listings labelled false whose risk was determined, how many were flagged anyway. */
    Rate falseAlarmRate() {
        return new Rate(falsePositive, falsePositive + trueNegative);
    }
}
