package com.thanhdatpb.listingguard.api.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The one-shot jobs {@code ingest} and {@code eval-retrieval} need an embedding provider. With the default
 * ({@code none}) they must end with a non-zero exit, caused by a message that says how to enable one (ADR-012).
 */
@Testcontainers
class NoEmbeddingProviderJobsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** No provider argument: the default configuration, which is what the compose jobs run with. */
    private void runJob(String profile) {
        new SpringApplicationBuilder(ListingGuardApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword())
                .close();
    }

    @Test
    void ingestFailsAtOnceWhenNoProviderIsConfigured() {
        assertThatThrownBy(() -> runJob("ingest"))
                .isInstanceOf(EmbeddingNotConfiguredException.class)
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=api")
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=ollama");
    }

    @Test
    void evalRetrievalFailsAtOnceWhenNoProviderIsConfigured() {
        assertThatThrownBy(() -> runJob("eval-retrieval"))
                .isInstanceOf(EmbeddingNotConfiguredException.class)
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=api");
    }
}
