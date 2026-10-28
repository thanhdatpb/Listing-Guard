package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.security.ClientAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The web application starts without keys (and warns); development-only key and Swagger settings are fenced. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ApiKeysStartupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static PostgreSQLContainer<?> newDatabase() {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    private ConfigurableApplicationContext start(PostgreSQLContainer<?> database, WebApplicationType type, String... extraArgs) {
        String[] args = new String[4 + extraArgs.length];
        args[0] = "--spring.datasource.url=" + database.getJdbcUrl();
        args[1] = "--spring.datasource.username=" + database.getUsername();
        args[2] = "--spring.datasource.password=" + database.getPassword();
        args[3] = "--server.port=0";
        System.arraycopy(extraArgs, 0, args, 4, extraArgs.length);
        return new SpringApplicationBuilder(ListingGuardApplication.class).web(type).run(args);
    }

    private ConfigurableApplicationContext start(WebApplicationType type, String... extraArgs) {
        return start(POSTGRES, type, extraArgs);
    }

    /** Every message in the exception chain, to prove a secret appears nowhere in it. */
    private static String allMessages(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void startsWithoutAnyKeyAndWarnsThatEveryRequestWillGet401(CapturedOutput output) {
        try (PostgreSQLContainer<?> empty = newDatabase()) {
            empty.start();
            try (ConfigurableApplicationContext context = start(empty, WebApplicationType.SERVLET, "--listing-guard.embedding.provider=hashing")) {
                assertThat(context.isRunning()).isTrue();
            }
        }

        assertThat(output.getAll()).contains("WARN").contains("No active API key exists").contains("admin job");
    }

    // ---- development-only settings --------------------------------------------------------------------------

    private static final String DEV_KEY = "insecure-dev-key-change-me-0123456789abcdef";
    private static final String DEV_KEY_ARG = "--listing-guard.security.dev-api-key=" + DEV_KEY;

    @Test
    void aDevKeyWithoutTheDevProfileDoesNotStartAndIsNeverPrinted() {
        assertThatThrownBy(() -> start(WebApplicationType.SERVLET, DEV_KEY_ARG, "--listing-guard.embedding.provider=hashing"))
                .satisfies(e -> assertThat(allMessages(e)).contains("LISTING_GUARD_DEV_API_KEY").contains("'dev' profile").doesNotContain(DEV_KEY));
    }

    @Test
    void aDevKeyWithTheDevProfileStartsAndTheKeyBelongsToClientDev() {
        try (ConfigurableApplicationContext context = start(WebApplicationType.SERVLET, DEV_KEY_ARG,
                "--spring.profiles.active=dev", "--listing-guard.embedding.provider=hashing", "--listing-guard.security.auth-cache-ttl=0s")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getBean(ClientAuthenticator.class).authenticate(DEV_KEY))
                    .hasValueSatisfying(client -> assertThat(client.name()).isEqualTo("dev"));
        }
    }

    @Test
    void aDevKeyWithTheProdProfileDoesNotStartEvenIfDevIsActiveToo() {
        assertThatThrownBy(() -> start(WebApplicationType.SERVLET, DEV_KEY_ARG, "--spring.profiles.active=dev,prod",
                "--listing-guard.embedding.provider=hashing"))
                .satisfies(e -> assertThat(allMessages(e)).contains("never with 'prod'").doesNotContain(DEV_KEY));
    }

    @Test
    void aMalformedDevKeyDoesNotStartAndIsNeverPrinted() {
        String tooShort = "tooshortsecret";

        assertThatThrownBy(() -> start(WebApplicationType.SERVLET, "--listing-guard.security.dev-api-key=" + tooShort,
                "--spring.profiles.active=dev", "--listing-guard.embedding.provider=hashing"))
                .satisfies(e -> assertThat(allMessages(e)).contains("not valid").doesNotContain(tooShort));
    }

    @Test
    void theSwaggerFlagWithTheProdProfileDoesNotStart() {
        assertThatThrownBy(() -> start(WebApplicationType.SERVLET, "--spring.profiles.active=prod",
                "--listing-guard.api.swagger-ui-enabled=true", "--listing-guard.embedding.provider=hashing"))
                .satisfies(e -> assertThat(allMessages(e)).contains("LISTING_GUARD_SWAGGER_UI").contains("'prod'"));
    }

    @Test
    void theSwaggerFlagStartsWithAWarningOutsideProd(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(WebApplicationType.SERVLET, "--listing-guard.api.swagger-ui-enabled=true",
                "--listing-guard.embedding.provider=hashing")) {
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(output.getAll()).contains("WARN").contains("Swagger UI is enabled");
    }

    @Test
    void withoutTheSwaggerFlagThereIsNoSuchWarning(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start(WebApplicationType.SERVLET, "--spring.profiles.active=prod",
                "--listing-guard.embedding.provider=hashing")) {
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(output.getAll()).doesNotContain("Swagger UI is enabled");
    }

    @Test
    void theOneShotJobsNeedNoKeys() {
        try (ConfigurableApplicationContext context = start(WebApplicationType.NONE, "--listing-guard.embedding.provider=hashing")) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
