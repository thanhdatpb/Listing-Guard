package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.batch.Measurement.Verdict;
import org.junit.jupiter.api.Test;

/** The confusion matrix and the two rates, on counts worked out by hand. */
class MeasurementTest {

    private static void add(Measurement measurement, Boolean label, Verdict verdict, int times) {
        for (int i = 0; i < times; i++) {
            measurement.add(label, verdict);
        }
    }

    @Test
    void theMatrixAndTheRatesFollowTheCounts() {
        Measurement m = new Measurement();
        add(m, true, Verdict.FLAGGED, 6);       // hits
        add(m, true, Verdict.CLEAR, 2);         // misses
        add(m, true, Verdict.UNDETERMINED, 1);
        add(m, false, Verdict.FLAGGED, 1);      // false alarms
        add(m, false, Verdict.CLEAR, 9);
        add(m, false, Verdict.UNDETERMINED, 3);
        add(m, null, Verdict.FLAGGED, 4);       // no label: not measured
        m.addNotChecked();
        m.addNotChecked();

        assertThat(m.truePositive()).isEqualTo(6);
        assertThat(m.falseNegative()).isEqualTo(2);
        assertThat(m.trueUndetermined()).isEqualTo(1);
        assertThat(m.falsePositive()).isEqualTo(1);
        assertThat(m.trueNegative()).isEqualTo(9);
        assertThat(m.falseUndetermined()).isEqualTo(3);
        assertThat(m.unlabeled()).isEqualTo(4);
        assertThat(m.notChecked()).isEqualTo(2);
        assertThat(m.labelledTrue()).isEqualTo(9);
        assertThat(m.labelledFalse()).isEqualTo(13);
        assertThat(m.detectionRate()).isEqualTo(new Measurement.Rate(6, 8));
        assertThat(m.falseAlarmRate()).isEqualTo(new Measurement.Rate(1, 10));
        assertThat(m.detectionRate().text()).isEqualTo("6/8 (75.0%)");
        assertThat(m.falseAlarmRate().text()).isEqualTo("1/10 (10.0%)");
    }

    @Test
    void aRateWithNothingToDivideByIsNotAvailableInsteadOfZero() {
        Measurement m = new Measurement();
        add(m, true, Verdict.UNDETERMINED, 3);

        assertThat(m.detectionRate().text()).isEqualTo("n/a (0/0)");
        assertThat(m.falseAlarmRate().text()).isEqualTo("n/a (0/0)");
    }

    @Test
    void theTextUsesAPointWhateverTheDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("vi-VN"));
            assertThat(new Measurement.Rate(1, 3).text()).isEqualTo("1/3 (33.3%)");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
