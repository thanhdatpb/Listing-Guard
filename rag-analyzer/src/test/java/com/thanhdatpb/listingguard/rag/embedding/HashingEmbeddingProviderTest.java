package com.thanhdatpb.listingguard.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider(768);

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    @Test
    void vectorsHaveTheConfiguredSizeAndUnitLength() {
        float[] vector = provider.embed("hello policy world", Kind.DOCUMENT);

        assertThat(vector).hasSize(768);
        assertThat(cosine(vector, vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(provider.dimensions()).isEqualTo(768);
        assertThat(provider.modelId()).isEqualTo("hashing:768");
    }

    @Test
    void sameTextGivesTheSameVector() {
        assertThat(provider.embed("Selling counterfeit items", Kind.DOCUMENT))
                .containsExactly(provider.embed("Selling counterfeit items", Kind.DOCUMENT));
    }

    @Test
    void kindDoesNotChangeTheVector() {
        assertThat(provider.embed("same words", Kind.QUERY)).containsExactly(provider.embed("same words", Kind.DOCUMENT));
    }

    @Test
    void textsSharingWordsAreCloserThanUnrelatedTexts() {
        float[] query = provider.embed("counterfeit branded items", Kind.QUERY);
        float[] related = provider.embed("selling counterfeit items is prohibited", Kind.DOCUMENT);
        float[] unrelated = provider.embed("photograph requirements for listing thumbnails", Kind.DOCUMENT);

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    @Test
    void caseAndPunctuationAreIgnored() {
        assertThat(provider.embed("Hello, WORLD!", Kind.DOCUMENT)).containsExactly(provider.embed("hello world", Kind.DOCUMENT));
    }

    @Test
    void emptyTextStillGivesANonZeroVector() {
        float[] vector = provider.embed("   ", Kind.QUERY);

        assertThat(cosine(vector, vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void embedsABatchInOrder() {
        List<float[]> vectors = provider.embed(List.of("alpha", "beta", "gamma"), Kind.DOCUMENT);

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(1)).containsExactly(provider.embed("beta", Kind.DOCUMENT));
    }
}
