package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.embedding.DisabledEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingNotConfiguredException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-012: the application as it is configured by default (provider {@code none}, no key, no outside service)
 * starts, checks listings, and refuses only what needs embeddings.
 */
@SpringBootTest(properties = "test.default-embedding-provider=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class NoEmbeddingProviderIntegrationTest {

    @Autowired
    EmbeddingProvider embeddings;

    @Autowired
    PolicyIngestService ingest;

    @Autowired
    MockMvc mvc;

    @TempDir
    Path dir;

    @Test
    void theApplicationStartsWithTheDefaultConfigurationAndNoKey() {
        assertThat(embeddings).isInstanceOf(DisabledEmbeddingProvider.class);
        assertThat(embeddings.modelId()).isEqualTo("none");
    }

    @Test
    void checksWorkWithoutAnyEmbeddingProvider() throws Exception {
        mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"No embeddings needed\",\"tags\":[\"mug\"],\"quantity\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkId").exists());
    }

    @Test
    void theSearchEndpointAnswersWithAStableProblemCode() throws Exception {
        mvc.perform(get("/policies/search").header(HEADER, KEY_1).param("marketplace", "ETSY").param("q", "counterfeit"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("embedding-not-configured"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("LISTING_GUARD_EMBEDDING_PROVIDER")))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void ingestFailsFirstWithAMessageThatSaysHowToEnableAProvider() {
        // an empty directory has no manifest: reaching the manifest would be a different error than the one asserted
        assertThatThrownBy(() -> ingest.ingest(dir))
                .isInstanceOf(EmbeddingNotConfiguredException.class)
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=api")
                .hasMessageContaining("ollama");
    }

    @Test
    void embedNamesBothWaysToEnableAProvider() {
        assertThatThrownBy(() -> embeddings.embed("anything", EmbeddingProvider.Kind.QUERY))
                .isInstanceOf(EmbeddingNotConfiguredException.class)
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=api")
                .hasMessageContaining("LISTING_GUARD_EMBEDDING_PROVIDER=ollama");
    }
}
