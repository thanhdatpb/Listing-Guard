package com.thanhdatpb.listingguard.api.report;

/** The period of a report is longer than {@code listing-guard.reports.max-days} (ADR-018). Splitting the request helps. */
public class ReportPeriodTooLongException extends RuntimeException {

    /** The stable problem code of the answer (HTTP 400). */
    public static final String CODE = "report-period-too-long";

    private final int maxDays;
    private final long requestedDays;

    public ReportPeriodTooLongException(int maxDays, long requestedDays) {
        super("The period covers " + requestedDays + " days, more than the " + maxDays
                + " a report may cover (both ends count). Ask for a shorter period.");
        this.maxDays = maxDays;
        this.requestedDays = requestedDays;
    }

    public int maxDays() {
        return maxDays;
    }

    public long requestedDays() {
        return requestedDays;
    }
}
