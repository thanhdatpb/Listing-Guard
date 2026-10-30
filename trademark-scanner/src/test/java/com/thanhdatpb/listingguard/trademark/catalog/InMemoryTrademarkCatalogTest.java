package com.thanhdatpb.listingguard.trademark.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTrademarkCatalogTest {

    private final TextNormalizer normalizer = new TextNormalizer(TestRulesets.test().normalization());
    private final InMemoryTrademarkCatalog catalog = new InMemoryTrademarkCatalog("v1", normalizer,
            List.of("Zorvex", "Blue Harbor Tools", "Quillmark"));

    @Test
    void marksAreStoredInMatchFormAndKeepTheirDisplayForm() {
        assertThat(catalog.version()).isEqualTo("v1");
        assertThat(catalog.findExact(List.of("blue harbor tools", "zorvex", "unknown")))
                .containsOnlyKeys("blue harbor tools", "zorvex");
        assertThat(catalog.findExact(List.of("blue harbor tools")).get("blue harbor tools").display())
                .isEqualTo("Blue Harbor Tools");
        assertThat(catalog.findExact(List.of("blue harbor tools")).get("blue harbor tools").tokenCount()).isEqualTo(3);
        assertThat(catalog.findExact(List.of("zorvex")).get("zorvex").tokenCount()).isEqualTo(1);
    }

    @Test
    void exactLookupNeedsTheWholeNormalizedForm() {
        assertThat(catalog.findExact(List.of("zorv", "zorvex shirt", "Zorvex"))).isEmpty();
    }

    @Test
    void fuzzyCandidatesAreMarksAtLeastAsSimilarAsTheThreshold() {
        assertThat(catalog.fuzzyCandidates(List.of("zorvx"), 0.30).get("zorvx"))
                .extracting(CatalogMark::normalized).containsExactly("zorvex");
        assertThat(catalog.fuzzyCandidates(List.of("zorvx"), 0.95)).isEmpty();
        assertThat(catalog.fuzzyCandidates(List.of("plain"), 0.30)).isEmpty();
    }

    @Test
    void candidatesComeInADeterministicOrder() {
        InMemoryTrademarkCatalog close = new InMemoryTrademarkCatalog("v", normalizer, List.of("Zorvik", "Zorvex", "Zorvax"));

        assertThat(close.fuzzyCandidates(List.of("zorvx"), 0.1).get("zorvx")).extracting(CatalogMark::normalized)
                .containsExactly("zorvax", "zorvex", "zorvik");
    }

    @Test
    void trigramSimilarityFollowsPgTrgm() {
        assertThat(Trigrams.of("cat")).containsExactlyInAnyOrder("  c", " ca", "cat", "at ");
        assertThat(Trigrams.similarity("word", "two words")).isEqualTo(4.0 / 11.0);
        assertThat(Trigrams.similarity("zorvex", "zorvex")).isEqualTo(1.0);
        assertThat(Trigrams.similarity("", "zorvex")).isEqualTo(0.0);
    }

    @Test
    void rejectsAMarkWithoutLettersAndADuplicateAfterNormalization() {
        assertThatThrownBy(() -> new InMemoryTrademarkCatalog("v", normalizer, List.of("!!!")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryTrademarkCatalog("v", normalizer, List.of("Zorvex", "ZORVEX!")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }
}
