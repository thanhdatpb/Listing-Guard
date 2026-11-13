package com.thanhdatpb.listingguard.api.report;

/**
 * The period of a report is missing, is not a date written as {@code YYYY-MM-DD}, or ends before it starts (ADR-018). The
 * message names what is wrong and never repeats what the caller sent.
 */
public class InvalidReportPeriodException extends RuntimeException {

    /** The stable problem code of the answer (HTTP 400). */
    public static final String CODE = "invalid-report-period";

    /**
     * What is wrong with the period, so that the console can say it in the language of its page (ADR-028). The message of the
     * exception, and so the answer of the API, is the same whatever the reason.
     */
    public enum Reason {
        FROM_AFTER_TO, REQUIRED, NOT_A_DATE, NOT_A_CALENDAR_DATE, OTHER
    }

    private final Reason reason;
    private final String parameter;

    public InvalidReportPeriodException(String message) {
        this(message, Reason.OTHER, null);
    }

    /** {@code parameter} is the request parameter the reason is about ({@code from} or {@code to}), or null. */
    public InvalidReportPeriodException(String message, Reason reason, String parameter) {
        super(message);
        this.reason = reason;
        this.parameter = parameter;
    }

    public Reason reason() {
        return reason;
    }

    public String parameter() {
        return parameter;
    }
}
