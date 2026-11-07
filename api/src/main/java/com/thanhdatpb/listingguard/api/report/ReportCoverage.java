package com.thanhdatpb.listingguard.api.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code coverageNote} of a report (ADR-018): the packs that delete their results after a retention period cannot show
 * the days before that, so a period that starts earlier has holes for them. Everything is computed from the configured
 * periods, none is written here.
 */
final class ReportCoverage {

    /** A pack that deletes its results, and how long it keeps them. */
    record Retained(String pack, Duration period) {
    }

    private ReportCoverage() {
    }

    /**
     * A result made at {@code t} is still there at {@code now} when {@code t + period > now}, so a day is complete only when
     * its first instant is after {@code now - period}: the first complete day is the day after the date of that instant.
     */
    static HealthReport.CoverageView of(Instant now, LocalDate periodStart, List<Retained> retained) {
        List<HealthReport.RetentionView> retention = new ArrayList<>();
        List<HealthReport.RetentionView> incomplete = new ArrayList<>();
        for (Retained pack : retained) {
            LocalDate coveredFrom = LocalDate.ofInstant(now.minus(pack.period()), ZoneOffset.UTC).plusDays(1);
            HealthReport.RetentionView view = new HealthReport.RetentionView(pack.pack(), isoPeriod(pack.period()), coveredFrom);
            retention.add(view);
            if (periodStart.isBefore(coveredFrom)) {
                incomplete.add(view);
            }
        }
        return new HealthReport.CoverageView(incomplete.isEmpty(), text(retention, incomplete), List.copyOf(retention));
    }

    /** {@code P30D} for whole days, the {@link Duration} form otherwise. */
    static String isoPeriod(Duration period) {
        return period.toMillis() % Duration.ofDays(1).toMillis() == 0 ? "P" + period.toDays() + "D" : period.toString();
    }

    private static String text(List<HealthReport.RetentionView> all, List<HealthReport.RetentionView> incomplete) {
        if (incomplete.isEmpty()) {
            return "Every pack covers the whole period. Results of "
                    + all.stream().map(view -> view.pack() + " (kept " + view.period() + ")").collect(Collectors.joining(", "))
                    + " are deleted after the time in brackets; listing-rules results are not deleted.";
        }
        return "The period starts before the retention of some packs allows: "
                + incomplete.stream().map(view -> view.pack() + " (kept " + view.period() + ", complete from "
                        + view.coveredFrom() + ")").collect(Collectors.joining(", "))
                + ". Their numbers for earlier days are missing or incomplete. listing-rules results are not deleted and "
                + "cover the whole period.";
    }
}
