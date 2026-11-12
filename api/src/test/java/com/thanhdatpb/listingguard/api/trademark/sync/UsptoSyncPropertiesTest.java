package com.thanhdatpb.listingguard.api.trademark.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The settings of the sync (ADR-009, PR A2d) as Spring binds them: safe defaults (download off, HTTPS, the key hidden), and
 * the refusals that keep a wrong value from reaching a run. The file name patterns are settings and unverified.
 */
class UsptoSyncPropertiesTest {

    private static UsptoSyncProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("listing-guard.trademark.uspto.sync", UsptoSyncProperties.class);
    }

    @Test
    void theDefaultsAreOffAndSafe() {
        UsptoSyncProperties properties = bind(Map.of());

        assertThat(properties.download().enabled()).isFalse();
        assertThat(properties.download().hasKey()).isFalse();
        assertThat(properties.download().baseUrl()).startsWith("https://");
        assertThat(properties.download().allowedHosts()).containsExactly("api.uspto.gov");
        assertThat(properties.keepVersions()).isEqualTo(3);
        assertThat(properties.requireBackfile()).isTrue();
        assertThat(properties.catalog().license()).as("the operator must state the terms").isEmpty();
    }

    @Test
    void theKeyIsNeverPrinted() {
        UsptoSyncProperties properties = bind(Map.of("listing-guard.trademark.uspto.sync.download.api-key",
                "secret-odp-key-value"));

        assertThat(properties.download().hasKey()).isTrue();
        assertThat(properties.toString()).doesNotContain("secret-odp-key-value").contains("(set)");
    }

    @Test
    void wrongValuesStopTheStartUp() {
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.keep-versions", "1")))
                .hasRootCauseMessage("listing-guard.trademark.uspto.sync.keep-versions must be at least 2, got 1: the "
                        + "version served before the newest one is kept until every API has refreshed.");
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.download.base-url", "http://example.com")))
                .rootCause().hasMessageContaining("must be https");
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.download.base-url",
                "https://other.example.com"))).rootCause().hasMessageContaining("allowed-hosts");
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.catalog.label-prefix", "demo-uspto")))
                .rootCause().hasMessageContaining("DEMO");
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.daily-name-pattern", "apc[0-9]{6}\\.zip")))
                .rootCause().hasMessageContaining("named groups");
        assertThatThrownBy(() -> bind(Map.of("listing-guard.trademark.uspto.sync.min-loaded-ratio", "1.5")))
                .rootCause().hasMessageContaining("from 0 to 1");
    }

    @Test
    void plainHttpIsAllowedToThisMachineOnly() {
        UsptoSyncProperties properties = bind(Map.of("listing-guard.trademark.uspto.sync.download.base-url",
                "http://127.0.0.1:8099", "listing-guard.trademark.uspto.sync.download.allowed-hosts", "127.0.0.1"));

        assertThat(properties.download().baseUrl()).isEqualTo("http://127.0.0.1:8099");
    }

    @Test
    void theDefaultPatternsReadTheBelievedNames() {
        UsptoSyncProperties properties = bind(Map.of());
        UsptoFileNames names = new UsptoFileNames(properties.dailyNamePattern(), properties.backfileNamePattern());

        assertThat(names.daily("apc261110.zip")).contains(new UsptoFileNames.Parsed(LocalDate.of(2026, 11, 10), null));
        assertThat(names.daily("APC261110.XML")).isPresent();
        assertThat(names.daily("apc261340.zip")).as("not a real date").isEmpty();
        assertThat(names.daily("../apc261110.zip")).as("never a path").isEmpty();
        assertThat(names.backfile("apc18840407-20251231-07.zip"))
                .contains(new UsptoFileNames.Parsed(LocalDate.of(2025, 12, 31), 7));
        assertThat(names.backfile("apc261110.zip")).isEmpty();
        assertThat(UsptoFileNames.plain("apc 261110.zip")).isFalse();
    }
}
