package com.thanhdatpb.listingguard.api.trademark.tsdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@code tsdr-status} profile as an operator runs it (ADR-009, "TSDR status check"): the command line, what it refuses,
 * and that the same application started without the profile has no such job. The calls stay off and no key is set.
 */
@Testcontainers
class TsdrStatusJobProfileTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path catalogs;

    private ConfigurableApplicationContext start(String profile, String... arguments) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--listing-guard.trademark.catalogs-dir=" + catalogs));
        all.addAll(List.of(arguments));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ListingGuardApplication.class)
                .web(WebApplicationType.NONE);
        if (profile != null) {
            builder.profiles(profile);
        }
        return builder.run(all.toArray(String[]::new));
    }

    private void run(String... arguments) {
        start("tsdr-status", arguments).close();
    }

    @Test
    void theJobRefusesWhatItCannotDoAndSaysWhy() {
        // the calls are off by default: a live run has nothing to ask with (Spring Boot 4 hands the runner's exception through)
        assertThatThrownBy(() -> run()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("download.enabled");
        assertThatThrownBy(() -> run("--listing-guard.trademark.tsdr.download.enabled=true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LISTING_GUARD_TSDR_API_KEY");
        assertThatThrownBy(() -> run("stray-argument")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("options only");
        assertThatThrownBy(() -> run("--max-marks=lots", "--dry-run")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--max-marks");
    }

    @Test
    void withoutAServedCatalogEvenADryRunStopsBecauseTheMarksComeFromIt() {
        assertThatThrownBy(() -> run("--dry-run")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a served trademark catalog");
    }

    @Test
    void theJobIsNotPartOfTheApiProcess() {
        try (ConfigurableApplicationContext api = start(null)) {
            assertThat(api.getBeansOfType(TsdrStatusRunner.class)).isEmpty();
            // the overlay is there, and off: the answer of the API is unchanged
            assertThat(api.getBean(TsdrStatusOverlay.class).enabled()).isFalse();
            assertThat(api.getBean(TsdrProperties.class).download().enabled()).isFalse();
        }
    }
}
