package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalInputTest {

    private static String hash(String profile, String productType, PersonalizationInput.Value... values) {
        return CanonicalInput.of(new PersonalizationInput(profile, productType, List.of(values))).hash();
    }

    private static PersonalizationInput.Value value(String label, String text) {
        return new PersonalizationInput.Value(label, text);
    }

    @Test
    void theHashIsStableAndCovers64HexCharacters() {
        String hash = hash(null, null, value("name", "MAX"));

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash(null, null, value("name", "MAX"))).isEqualTo(hash);
    }

    @Test
    void aSpaceInTheTextChangesTheHashBecauseTheTextIsNotStripped() {
        assertThat(hash(null, null, value(null, "MAX"))).isNotEqualTo(hash(null, null, value(null, "MAX ")));
        assertThat(hash(null, null, value(null, "A B"))).isNotEqualTo(hash(null, null, value(null, "A  B")));
    }

    @Test
    void theLabelAndTheCodesAreStrippedAndTheCaseIsKept() {
        assertThat(hash(" latin_basic ", " jersey ", value(" name ", "MAX")))
                .isEqualTo(hash("latin_basic", "jersey", value("name", "MAX")));
        assertThat(hash(null, null, value(null, "Max"))).isNotEqualTo(hash(null, null, value(null, "MAX")));
    }

    @Test
    void aDecomposedAndAComposedLetterAreOneInput() {
        assertThat(hash(null, null, value(null, "ạ"))).isEqualTo(hash(null, null, value(null, "ạ")));
    }

    @Test
    void thePositionOfTheFieldsAndTheLengthPrefixesKeepTwoInputsApart() {
        assertThat(hash(null, null, value("a", "bc"))).isNotEqualTo(hash(null, null, value("ab", "c")));
        assertThat(hash(null, null, value(null, "ab"), value(null, "c")))
                .isNotEqualTo(hash(null, null, value(null, "a"), value(null, "bc")));
        assertThat(hash("x", null)).isNotEqualTo(hash(null, "x"));
        assertThat(hash(null, null)).isNotEqualTo(hash(null, null, value(null, "")));
    }

    @Test
    void theOrderOfTheValuesMatters() {
        assertThat(hash(null, null, value(null, "A"), value(null, "B")))
                .isNotEqualTo(hash(null, null, value(null, "B"), value(null, "A")));
    }
}
