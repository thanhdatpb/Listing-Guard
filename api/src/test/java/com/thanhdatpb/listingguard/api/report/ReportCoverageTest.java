package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.report.ReportCoverage.Retained;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code coverageNote} (ADR-018): computed from the configured retention periods, never from a constant. */
class ReportCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    private static List<Retained> thirtyDays() {
        return List.of(new Retained("trademark-risk", Duration.ofDays(30)), new Retained("variation-checks", Duration.ofDays(30)),
                new Retained("personalization-screening", Duration.ofDays(30)));
    }

    @Test
    void theFirstCompleteDayIsTheDayAfterTheOneThatHoldsTheRetentionBoundary() {
        HealthReport.CoverageView note = ReportCoverage.of(NOW, LocalDate.of(2026, 10, 11), thirtyDays());

        // 2026-11-09T03:00Z minus 30 days is 2026-10-10T03:00Z: the early rows of the 10th are gone, the 11th is whole
        assertThat(note.retention()).extracting(HealthReport.RetentionView::coveredFrom).containsOnly(LocalDate.of(2026, 10, 11));
        assertThat(note.retention()).extracting(HealthReport.RetentionView::period).containsOnly("P30D");
        assertThat(note.complete()).isTrue();
        assertThat(note.text()).startsWith("Every pack covers the whole period.");
    }

    @Test
    void aPeriodThatStartsEarlierIsNotCompleteAndSaysWhichPacksAndFromWhen() {
        HealthReport.CoverageView note = ReportCoverage.of(NOW, LocalDate.of(2026, 10, 10), thirtyDays());

        assertThat(note.complete()).isFalse();
        assertThat(note.text()).contains("trademark-risk (kept P30D, complete from 2026-10-11)")
                .contains("variation-checks (kept P30D, complete from 2026-10-11)")
                .contains("personalization-screening (kept P30D, complete from 2026-10-11)")
                .contains("listing-rules results are not deleted");
    }

    @Test
    void aBoundaryExactlyAtMidnightStillLeavesThatDayIncomplete() {
        // a row made at 2026-10-10T00:00:00Z expires at 2026-11-09T00:00:00Z, which is not after "now", so it is gone
        Instant midnight = Instant.parse("2026-11-09T00:00:00Z");

        HealthReport.CoverageView note = ReportCoverage.of(midnight, LocalDate.of(2026, 10, 10), thirtyDays());

        assertThat(note.retention()).extracting(HealthReport.RetentionView::coveredFrom).containsOnly(LocalDate.of(2026, 10, 11));
        assertThat(note.complete()).isFalse();
    }

    @Test
    void everyPackUsesItsOwnConfiguredPeriodAndOnlyTheIncompleteOnesAreNamed() {
        List<Retained> retained = List.of(new Retained("trademark-risk", Duration.ofDays(10)),
                new Retained("variation-checks", Duration.ofDays(20)), new Retained("personalization-screening", Duration.ofDays(60)));

        HealthReport.CoverageView note = ReportCoverage.of(NOW, LocalDate.of(2026, 10, 25), retained);

        assertThat(note.retention()).extracting(HealthReport.RetentionView::pack)
                .containsExactly("trademark-risk", "variation-checks", "personalization-screening");
        assertThat(note.retention()).extracting(HealthReport.RetentionView::period).containsExactly("P10D", "P20D", "P60D");
        assertThat(note.retention()).extracting(HealthReport.RetentionView::coveredFrom)
                .containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 9, 11));
        assertThat(note.complete()).isFalse();
        assertThat(note.text()).contains("trademark-risk (kept P10D, complete from 2026-10-31)")
                .doesNotContain("variation-checks (kept").doesNotContain("personalization-screening (kept");
    }

    @Test
    void aPeriodThatIsNotWholeDaysIsWrittenAsADuration() {
        assertThat(ReportCoverage.isoPeriod(Duration.ofDays(30))).isEqualTo("P30D");
        assertThat(ReportCoverage.isoPeriod(Duration.ofHours(24))).isEqualTo("P1D");
        assertThat(ReportCoverage.isoPeriod(Duration.ofHours(36))).isEqualTo("PT36H");
    }
}
