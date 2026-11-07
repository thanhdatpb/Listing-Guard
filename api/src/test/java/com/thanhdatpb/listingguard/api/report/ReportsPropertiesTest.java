package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The settings of the listing health report (ADR-018): the defaults, and a value that makes no sense stops the start-up. */
class ReportsPropertiesTest {

    private static ReportsProperties bind(String... pairs) {
        java.util.Map<String, String> source = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            source.put("listing-guard.reports." + pairs[i], pairs[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("listing-guard.reports", ReportsProperties.class);
    }

    @Test
    void theDefaultsAre92DaysTenItemsAndSevenDays() {
        ReportsProperties defaults = bind();

        assertThat(defaults.maxDays()).isEqualTo(92);
        assertThat(defaults.topRules()).isEqualTo(10);
        assertThat(defaults.topMarks()).isEqualTo(10);
        assertThat(defaults.defaultDays()).isEqualTo(7);
    }

    @Test
    void everySettingCanBeChanged() {
        ReportsProperties changed = bind("max-days", "30", "top-rules", "5", "top-marks", "20", "default-days", "14");

        assertThat(changed).isEqualTo(new ReportsProperties(30, 5, 20, 14));
    }

    @Test
    void aValueThatMakesNoSenseIsRefused() {
        assertThatThrownBy(() -> new ReportsProperties(0, 10, 10, 1)).hasMessageContaining("max-days must be at least 1");
        assertThatThrownBy(() -> new ReportsProperties(92, 0, 10, 7)).hasMessageContaining("top-rules must be between 1 and 50");
        assertThatThrownBy(() -> new ReportsProperties(92, 51, 10, 7)).hasMessageContaining("top-rules must be between 1 and 50");
        assertThatThrownBy(() -> new ReportsProperties(92, 10, 0, 7)).hasMessageContaining("top-marks must be between 1 and 50");
        assertThatThrownBy(() -> new ReportsProperties(92, 10, 51, 7)).hasMessageContaining("top-marks must be between 1 and 50");
        assertThatThrownBy(() -> new ReportsProperties(92, 10, 10, 0)).hasMessageContaining("default-days must be between 1 and max-days");
        assertThatThrownBy(() -> new ReportsProperties(10, 10, 10, 11)).hasMessageContaining("default-days must be between 1 and max-days (10)");
        assertThatThrownBy(() -> bind("max-days", "0")).hasRootCauseMessage("listing-guard.reports.max-days must be at least 1");
    }
}
