package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The period of a report (ADR-018): whole UTC days, both ends included, a limit that comes from the settings. */
class ReportQueryTest {

    @Test
    void coversWholeUtcDaysWithBothEndsIncluded() {
        ReportQuery query = ReportQuery.parse("2026-11-02", "2026-11-08", null, 92);

        assertThat(query.days()).isEqualTo(7);
        assertThat(query.start()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(query.endExclusive()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
        assertThat(query.marketplace()).isNull();
    }

    @Test
    void oneDayIsAPeriodAndSurroundingSpacesAreIgnored() {
        ReportQuery query = ReportQuery.parse(" 2026-11-02 ", "2026-11-02", "ETSY", 92);

        assertThat(query.days()).isEqualTo(1);
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(query.marketplace()).isEqualTo("ETSY");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "2026-1-2", "26-11-02", "2026/11/02", "2026-02-30", "2026-13-01", "+12026-11-02",
            "2026-11-02T00:00:00Z", "tomorrow", "２０２６-11-02"})
    void aMissingOrMalformedDateIsRefusedWithoutRepeatingIt(String bad) {
        assertThatThrownBy(() -> ReportQuery.parse(bad, "2026-11-08", null, 92)).isInstanceOf(InvalidReportPeriodException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("from ").doesNotContain(bad == null ? "\u0000" : bad.isBlank() ? "\u0000" : bad));
        assertThatThrownBy(() -> ReportQuery.parse("2026-11-02", bad, null, 92)).isInstanceOf(InvalidReportPeriodException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("to "));
    }

    @Test
    void aReversedPeriodIsRefused() {
        assertThatThrownBy(() -> ReportQuery.parse("2026-11-08", "2026-11-02", null, 92))
                .isInstanceOf(InvalidReportPeriodException.class).hasMessage("from must not be after to.");
    }

    @Test
    void thePeriodCannotBeLongerThanTheLimitAndTheLimitComesFromTheSettings() {
        // 2026-01-01 to 2026-04-02 is 92 days, to 2026-04-03 is 93
        assertThat(ReportQuery.parse("2026-01-01", "2026-04-02", null, 92).days()).isEqualTo(92);
        assertThatThrownBy(() -> ReportQuery.parse("2026-01-01", "2026-04-03", null, 92))
                .isInstanceOfSatisfying(ReportPeriodTooLongException.class, e -> {
                    assertThat(e.maxDays()).isEqualTo(92);
                    assertThat(e.requestedDays()).isEqualTo(93);
                });

        assertThat(ReportQuery.parse("2026-11-01", "2026-11-10", null, 10).days()).isEqualTo(10);
        assertThatThrownBy(() -> ReportQuery.parse("2026-11-01", "2026-11-11", null, 10))
                .isInstanceOfSatisfying(ReportPeriodTooLongException.class, e -> {
                    assertThat(e.maxDays()).isEqualTo(10);
                    assertThat(e.requestedDays()).isEqualTo(11);
                });
    }

    @Test
    void theWidestPossiblePeriodIsRefusedAsTooLongWithoutOverflow() {
        assertThatThrownBy(() -> ReportQuery.parse("0001-01-01", "9999-12-31", null, 92))
                .isInstanceOf(ReportPeriodTooLongException.class);
    }

    @Test
    void theEndOfTheLastRepresentableDayCanStillBeComputed() {
        assertThat(ReportQuery.parse("9999-12-31", "9999-12-31", null, 92).endExclusive())
                .isEqualTo(Instant.parse("+10000-01-01T00:00:00Z"));
    }
}
