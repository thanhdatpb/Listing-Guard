package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.check.CheckStatus;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.TrademarkStatus;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fixed keys of the report are the values the tables can hold: each list is the Java enum (and so the CHECK constraint) it
 * mirrors, so a value added there cannot go missing from the report or appear in it only by accident.
 */
class ReportKeysTest {

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    @Test
    void theFixedKeysAreTheEnumsTheyMirror() {
        assertThat(HealthReportService.LISTING_STATUSES).containsExactlyInAnyOrderElementsOf(names(CheckStatus.values()));
        assertThat(HealthReportService.SEVERITIES).containsExactlyElementsOf(names(Severity.values()));
        assertThat(HealthReportService.RISK_LEVELS).containsExactlyInAnyOrderElementsOf(names(RiskLevel.values()));
        assertThat(HealthReportService.TRADEMARK_STATUSES).containsExactlyElementsOf(names(TrademarkStatus.values()));
        assertThat(HealthReportService.COMPLETENESS)
                .containsExactlyElementsOf(names(com.thanhdatpb.listingguard.trademark.Completeness.values()))
                .containsExactlyElementsOf(names(com.thanhdatpb.listingguard.variation.Completeness.values()))
                .containsExactlyElementsOf(names(com.thanhdatpb.listingguard.personalization.Completeness.values()));
    }
}
