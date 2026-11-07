package com.thanhdatpb.listingguard.api.report;

/**
 * The period of a report is missing, is not a date written as {@code YYYY-MM-DD}, or ends before it starts (ADR-018). The
 * message names what is wrong and never repeats what the caller sent.
 */
public class InvalidReportPeriodException extends RuntimeException {

    /** The stable problem code of the answer (HTTP 400). */
    public static final String CODE = "invalid-report-period";

    public InvalidReportPeriodException(String message) {
        super(message);
    }
}
