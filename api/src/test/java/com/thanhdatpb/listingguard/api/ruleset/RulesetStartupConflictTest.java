package com.thanhdatpb.listingguard.api.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the whole application (not just the registrar) against a database that already holds a
 * ruleset version, to prove a published version cannot silently change (ADR-002).
 */
@Testcontainers
class RulesetStartupConflictTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path tempDir;

    /** Command-line arguments win over application.yml, so they can replace the datasource and rulesets. */
    private ConfigurableApplicationContext start(String... extraArgs) {
        String[] args = new String[3 + extraArgs.length];
        args[0] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        args[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        args[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        return new SpringApplicationBuilder(ListingGuardApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    private static String shippedRuleset() throws IOException {
        return new ClassPathResource("rulesets/etsy-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void startsAgainWhenTheRegisteredRulesetIsUnchanged() {
        try (ConfigurableApplicationContext first = start()) {
            assertThat(first.isRunning()).isTrue();
        }
        try (ConfigurableApplicationContext second = start()) {
            assertThat(second.isRunning()).isTrue();
        }
    }

    @Test
    void refusesToStartWhenTheVersionIsRegisteredWithDifferentContent() throws IOException {
        try (ConfigurableApplicationContext first = start()) {
            assertThat(first.isRunning()).isTrue();
        }

        String shipped = shippedRuleset();
        String edited = shipped.replace("max_length: 140", "max_length: 141");
        assertThat(edited).as("the edit must actually change the file").isNotEqualTo(shipped);
        Path conflicting = tempDir.resolve("etsy-2026-11.yaml");
        Files.writeString(conflicting, edited, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> start("--listing-guard.rulesets.locations[0]=" + conflicting.toUri()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("etsy-2026-11")
                .hasMessageContaining("already registered")
                .hasMessageContaining("bump its version");
    }
}
