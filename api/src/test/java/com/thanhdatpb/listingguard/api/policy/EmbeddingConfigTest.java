package com.thanhdatpb.listingguard.api.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.rag.embedding.DisabledEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.HashingEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The provider bean is the one place every process passes through (web application, ingest, eval-retrieval), so an
 * empty key must stop the start-up here (ADR-012). No database or web server is needed to prove it.
 */
class EmbeddingConfigTest {

    private static final String KEY = "test-secret-key-123";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(EmbeddingConfig.class);

    private static String allMessages(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void theDefaultProviderIsNoneAndStartsWithoutAKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EmbeddingProvider provider = context.getBean(EmbeddingProvider.class);
            assertThat(provider).isInstanceOf(DisabledEmbeddingProvider.class);
            assertThat(provider.modelId()).isEqualTo("none");
        });
    }

    @Test
    void theNoneProviderRefusesToEmbedAndSaysHowToEnableOne() {
        runner.withPropertyValues("listing-guard.embedding.provider=none").run(context -> {
            assertThat(context).hasNotFailed();
            EmbeddingProvider provider = context.getBean(EmbeddingProvider.class);
            assertThatThrownBy(() -> provider.embed("anything", EmbeddingProvider.Kind.DOCUMENT))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=api")
                    .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=ollama");
        });
    }

    @Test
    void theApiProviderStartsOnlyWithAKey() {
        runner.withPropertyValues("listing-guard.embedding.provider=api").run(context -> {
            assertThat(context).hasFailed();
            assertThat(allMessages(context.getStartupFailure())).contains("LISTING_GUARD_EMBEDDING_API_KEY");
        });
        runner.withPropertyValues("listing-guard.embedding.provider=api", "listing-guard.embedding.api.key=" + KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingProvider.class).modelId()).isEqualTo("api:google:gemini-embedding-2:768");
        });
    }

    @Test
    void anEmptyOrBlankKeyWithTheApiProviderStopsTheStartUp() {
        runner.withPropertyValues("listing-guard.embedding.provider=api", "listing-guard.embedding.api.key=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(allMessages(context.getStartupFailure())).contains("LISTING_GUARD_EMBEDDING_API_KEY");
        });
        runner.withPropertyValues("listing-guard.embedding.provider=api", "listing-guard.embedding.api.key=   ").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void theHashingProviderNeedsNoKeySoAdminAndTestsStart() {
        runner.withPropertyValues("listing-guard.embedding.provider=hashing").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingProvider.class)).isInstanceOf(HashingEmbeddingProvider.class);
        });
    }

    @Test
    void ollamaStaysSelectableAndNeedsNoKey() {
        runner.withPropertyValues("listing-guard.embedding.provider=ollama").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingProvider.class).modelId()).isEqualTo("ollama:nomic-embed-text:768");
        });
    }

    @Test
    void anUnknownProviderStopsTheStartUp() {
        runner.withPropertyValues("listing-guard.embedding.provider=nope").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void modelDimensionsAndBaseUrlAreConfigurable() {
        runner.withPropertyValues("listing-guard.embedding.provider=api", "listing-guard.embedding.api.key=" + KEY,
                "listing-guard.embedding.api.model=other-model",
                "listing-guard.embedding.api.dimensions=1536", "listing-guard.embedding.api.base-url=http://localhost:9/v1beta")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingProvider.class).modelId()).isEqualTo("api:google:other-model:1536");
                });
    }

    @Test
    void theKeyIsNotInTheStartUpFailureOfAnotherSetting() {
        runner.withPropertyValues("listing-guard.embedding.provider=api", "listing-guard.embedding.api.key=" + KEY,
                "listing-guard.embedding.api.batch-size=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(allMessages(context.getStartupFailure())).doesNotContain(KEY);
        });
    }

    @Test
    void thePropertiesNeverPrintTheKey() {
        runner.withPropertyValues("listing-guard.embedding.api.key=" + KEY).run(context -> {
            EmbeddingProperties properties = context.getBean(EmbeddingProperties.class);

            assertThat(properties.api().key()).isEqualTo(KEY);
            assertThat(properties.toString()).doesNotContain(KEY).contains("key=<set>");
            assertThat(properties.api().toString()).doesNotContain(KEY);
        });
        runner.withPropertyValues("listing-guard.embedding.provider=hashing").run(context ->
                assertThat(context.getBean(EmbeddingProperties.class).toString()).contains("key=<empty>"));
    }
}
