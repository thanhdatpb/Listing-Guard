package com.thanhdatpb.listingguard.api.batch;

import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;

/**
 * What the batch learned about one data row: either its data could not be read ({@code inputError}), or each of the two
 * checks has a {@link Part}. The two checks are independent: one that is not configured or fails leaves the other.
 *
 * @param line  the line of the input file where the row starts
 * @param id    the value of the id column; null when the row could not give one
 * @param label the takedown label, null when the batch has none or the cell is blank
 */
record ListingOutcome(int line, String id, Boolean label, String inputError, Part<CheckResult> rules,
                      Part<TrademarkCheckResult> trademark) {

    /** How one check ended for one listing. */
    record Part<T>(State state, T result, String message) {

        enum State {
            /** the check ran and has a result */
            DONE,
            /** the check cannot run on this deployment (a trademark catalog that is not served) */
            NOT_CONFIGURED,
            /** the check threw; the message says what */
            FAILED
        }

        static <T> Part<T> done(T result) {
            return new Part<>(State.DONE, result, null);
        }

        static <T> Part<T> notConfigured(String message) {
            return new Part<>(State.NOT_CONFIGURED, null, message);
        }

        static <T> Part<T> failed(String message) {
            return new Part<>(State.FAILED, null, message);
        }

        boolean isDone() {
            return state == State.DONE;
        }
    }

    /** A row whose data could not be read; it is reported with its line and not checked. */
    static ListingOutcome unreadable(int line, String id, String error) {
        return new ListingOutcome(line, id, null, error, null, null);
    }

    boolean isUnreadable() {
        return inputError != null;
    }
}
