package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.report.HealthReport;
import java.time.LocalDate;
import java.util.List;

/**
 * What the console home page shows (ADR-034), already counted. Nothing in it is text a client typed: counts, rule ids and pack ids.
 *
 * @param weekFrom          the Monday of the current ISO week (UTC)
 * @param today             the last day counted
 * @param empty             no stored result and no verdict in the eight weeks: the page shows how to start instead of zeros
 * @param checked           listings checked this week (stored listing-rules results)
 * @param withError         of those, the results with at least one ERROR finding
 * @param trademarkHolds    retained trademark results of the week with a HIGH finding, whatever its source
 * @param holdsCatalog      of those, the ones with a HIGH finding that matched a catalog mark
 * @param holdsRule         of those, the ones with a HIGH finding of a context rule (no mark)
 * @param verdicts          verdicts given this week
 * @param falsePositives    of those, FALSE_POSITIVE
 * @param falsePositiveRate {@code falsePositives / verdicts}, or null with no verdict
 * @param topFalsePositives the rules marked FALSE_POSITIVE most often over the eight weeks
 */
public record HomeView(LocalDate weekFrom, LocalDate today, boolean empty, long checked, long withError, long trademarkHolds,
                       long holdsCatalog, long holdsRule, long verdicts, long falsePositives, Double falsePositiveRate,
                       WeeklyChart chart, List<HealthReport.FalsePositiveRuleView> topFalsePositives) {

    /** {@code withError / checked}, or null when nothing was checked. */
    public Double errorRate() {
        return checked == 0 ? null : (double) withError / checked;
    }
}
