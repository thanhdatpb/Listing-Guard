package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** The command line of the {@code health-report} job (ADR-018). */
class HealthReportOptionsTest {

    private static HealthReportOptions parse(String... arguments) {
        return HealthReportOptions.parse(new DefaultApplicationArguments(arguments));
    }

    @Test
    void aClientAloneTakesTheDefaultPeriod() {
        HealthReportOptions options = parse("--client=billing");

        assertThat(options.client()).isEqualTo("billing");
        assertThat(options.from()).isNull();
        assertThat(options.to()).isNull();
        assertThat(options.marketplace()).isNull();
    }

    @Test
    void everyOptionIsRead() {
        HealthReportOptions options = parse("--client=all", "--from=2026-11-02", "--to=2026-11-08", "--marketplace=etsy",
                "--spring.profiles.active=health-report");

        assertThat(options).isEqualTo(new HealthReportOptions("all", "2026-11-02", "2026-11-08", "etsy"));
    }

    @Test
    void whatIsMissingRepeatedUnknownOrHalfAPeriodIsRefusedWithTheUsage() {
        assertThatThrownBy(() -> parse()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing --client")
                .hasMessageContaining("--client=<client name|client id|all>");
        assertThatThrownBy(() -> parse("--client=a", "--client=b")).hasMessageContaining("Pass --client once");
        assertThatThrownBy(() -> parse("--client=")).hasMessageContaining("--client is empty");
        assertThatThrownBy(() -> parse("--client=a", "--bogus=1")).hasMessageContaining("Unknown option --bogus");
        assertThatThrownBy(() -> parse("--client=a", "--from=2026-11-02")).hasMessageContaining("--from and --to together");
        assertThatThrownBy(() -> parse("--client=a", "--to=2026-11-02")).hasMessageContaining("--from and --to together");
    }
}
