package com.thanhdatpb.listingguard.api.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * What a report covers (ADR-018): a period of whole UTC calendar days, both ends included, and optionally one marketplace.
 *
 * @param from        the first day
 * @param to          the last day, not before {@code from}
 * @param marketplace the canonical marketplace code to count, or null for all of them
 */
public record ReportQuery(LocalDate from, LocalDate to, String marketplace) {

    /** Four-digit years only, so that the end of the period can always be computed. */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public ReportQuery {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A report period needs a first day and a last day that is not before it");
        }
    }

    /** How many days the period covers, both ends counted. */
    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** The first instant of the period: 00:00:00 UTC of the first day. */
    public Instant start() {
        return from.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The first instant after the period: 00:00:00 UTC of the day after the last day. */
    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Reads and checks the two dates of a request.
     *
     * @param marketplace the canonical marketplace code, already checked by the caller, or null
     * @param maxDays     {@code listing-guard.reports.max-days}
     * @throws InvalidReportPeriodException  if a date is missing or malformed, or {@code from} is after {@code to}
     * @throws ReportPeriodTooLongException  if the period covers more than {@code maxDays} days
     */
    public static ReportQuery parse(String from, String to, String marketplace, int maxDays) {
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        if (last.isBefore(first)) {
            throw new InvalidReportPeriodException("from must not be after to.");
        }
        long days = ChronoUnit.DAYS.between(first, last) + 1;
        if (days > maxDays) {
            throw new ReportPeriodTooLongException(maxDays, days);
        }
        return new ReportQuery(first, last, marketplace);
    }

    private static LocalDate date(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new InvalidReportPeriodException(name + " is required: a date written as YYYY-MM-DD.");
        }
        String trimmed = text.trim();
        if (!DATE.matcher(trimmed).matches()) {
            throw new InvalidReportPeriodException(name + " is not a date written as YYYY-MM-DD.");
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new InvalidReportPeriodException(name + " is not a real calendar date written as YYYY-MM-DD.");
        }
    }
}
