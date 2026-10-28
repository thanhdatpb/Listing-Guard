package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The web application refuses to start without valid API keys; the one-shot jobs do not need any. */
@Testcontainers
class ApiKeysStartupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private ConfigurableApplicationContext start(WebApplicationType type, String... extraArgs) {
        String[] args = new String[4 + extraArgs.length];
        args[0] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        args[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        args[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        args[3] = "--server.port=0";
        System.arraycopy(extraArgs, 0, args, 4, extraArgs.length);
        return new SpringApplicationBuilder(ListingGuardApplication.class).web(type).run(args);
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
    void doesNotStartWithoutAnyKey() {
        assertThatThrownBy(() -> start(WebApplicationType.SERVLET))
                .satisfies(e -> assertThat(allMessages(e)).contains("No API keys configured").contains("LISTING_GUARD_API_KEYS"));
    }

    @Test
    void doesNotStartWithAMalformedKeyAndNeverPrintsIt() {
        String secret = "tooshortsecret";

        assertThatThrownBy(() -> start(WebApplicationType.SERVLET, "--listing-guard.security.api-keys=preview:" + secret))
                .satisfies(e -> assertThat(allMessages(e)).contains("entry #1").contains("shorter than 32").doesNotContain(secret));
    }

    @Test
    void startsWithValidKeys() {
        try (ConfigurableApplicationContext context = start(WebApplicationType.SERVLET,
                "--listing-guard.security.api-keys=" + ApiTestKeys.CONFIGURED, "--listing-guard.embedding.provider=hashing")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void theOneShotJobsNeedNoKeys() {
        try (ConfigurableApplicationContext context = start(WebApplicationType.NONE, "--listing-guard.embedding.provider=hashing")) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
