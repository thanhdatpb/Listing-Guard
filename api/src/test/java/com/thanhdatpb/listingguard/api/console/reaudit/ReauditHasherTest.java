package com.thanhdatpb.listingguard.api.console.reaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The hash a re-audit keeps of each listing row (ADR-035): stable for the same listing, different for an edit, no text in it. */
class ReauditHasherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void theOrderOfTheKeysAndOfNothingElseDoesNotChangeTheHash() throws Exception {
        JsonNode a = json("{\"title\":\"Mug\",\"tags\":[\"a\",\"b\"],\"price\":19.99}");
        JsonNode b = json("{\"price\":19.99,\"tags\":[\"a\",\"b\"],\"title\":\"Mug\"}");
        JsonNode otherOrderOfTags = json("{\"price\":19.99,\"tags\":[\"b\",\"a\"],\"title\":\"Mug\"}");

        assertThat(ReauditHasher.hash(a)).isEqualTo(ReauditHasher.hash(b)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ReauditHasher.hash(otherOrderOfTags)).as("the order of a list is the listing's own").isNotEqualTo(ReauditHasher.hash(a));
    }

    @Test
    void blanksAroundATextAnEmptyCellAndANullAreNotPartOfTheListing() throws Exception {
        JsonNode plain = json("{\"title\":\"Mug\",\"tags\":[\"a\",\"b\"]}");
        JsonNode padded = json("{\"title\":\"  Mug \",\"description\":\"\",\"brand\":null,\"tags\":[\"a\",\"\",\"b\"]}");

        assertThat(ReauditHasher.hash(padded)).isEqualTo(ReauditHasher.hash(plain));
    }

    @Test
    void anEditOfOneCharacterChangesTheHash() throws Exception {
        assertThat(ReauditHasher.hash(json("{\"title\":\"Plain ceramic mug\"}")))
                .isNotEqualTo(ReauditHasher.hash(json("{\"title\":\"Plain ceramic mugs\"}")));
        assertThat(ReauditHasher.hash(json("{\"price\":19.99}"))).isNotEqualTo(ReauditHasher.hash(json("{\"price\":19.98}")));
    }

    @Test
    void theSameLetterWrittenAsOneCodePointOrAsTwoHasOneHash() throws Exception {
        String composed = "{\"title\":\"café\"}";
        String decomposed = "{\"title\":\"café\"}";

        assertThat(ReauditHasher.hash(json(decomposed))).isEqualTo(ReauditHasher.hash(json(composed)));
    }

    @Test
    void theCanonicalFormIsCompactSortedAndEscaped() throws Exception {
        assertThat(ReauditHasher.canonical(json("{\"b\":\"x\\\"y\\nz \",\"a\":[1,true]}"))).isEqualTo("{\"a\":[1,true],\"b\":\"x\\\"y\\nz\"}");
    }
}
