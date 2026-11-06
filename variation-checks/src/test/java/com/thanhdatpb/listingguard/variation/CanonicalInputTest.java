package com.thanhdatpb.listingguard.variation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalInputTest {

    private static VariationInput amazon(VariationInput.Parent parent, VariationInput.Child... children) {
        return new VariationInput("AMAZON", parent, List.of(), List.of(children));
    }

    private static VariationInput.Child child(String sku, Map<String, String> attributes) {
        return new VariationInput.Child(sku, "P", "Tee", attributes);
    }

    @Test
    void theHashIsTheOneOfTheDocumentedSerialization() {
        // Computed outside the code with sha256sum over the text that CanonicalInput's javadoc describes, so a change
        // of the format cannot pass by changing code and test together.
        VariationInput input = amazon(null, new VariationInput.Child("A1", null, null, Map.of("color", "Red")));
        assertThat(CanonicalInput.of(input).hash())
                .isEqualTo("468b3208109c553e1c49c5975e1ad807c54fbf8ba13931ad5dc59f52fb48a5cf");
    }

    @Test
    void theOrderOfTheAttributesDoesNotChangeTheHash() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("color", "Red");
        one.put("size", "M");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("size", "M");
        other.put("color", "Red");
        assertThat(CanonicalInput.of(amazon(null, child("A", one))).hash())
                .isEqualTo(CanonicalInput.of(amazon(null, child("A", other))).hash());
    }

    @Test
    void unicodeFormAndSurroundingWhitespaceDoNotChangeTheHash() {
        VariationInput composed = amazon(null, child("A", Map.of("color", "Café")));
        VariationInput decomposedAndPadded = amazon(null, child(" A ", Map.of("color", " Café ")));
        assertThat(CanonicalInput.of(composed).hash()).isEqualTo(CanonicalInput.of(decomposedAndPadded).hash());
    }

    @Test
    void aDifferentCaseIsADifferentInput() {
        assertThat(CanonicalInput.of(amazon(null, child("A", Map.of("color", "Red")))).hash())
                .isNotEqualTo(CanonicalInput.of(amazon(null, child("A", Map.of("color", "red")))).hash());
    }

    @Test
    void theOrderOfTheChildrenAndTheirContentMatter() {
        VariationInput.Child a = child("A", Map.of("color", "Red"));
        VariationInput.Child b = child("B", Map.of("color", "Blue"));
        assertThat(CanonicalInput.of(amazon(null, a, b)).hash()).isNotEqualTo(CanonicalInput.of(amazon(null, b, a)).hash());
        assertThat(CanonicalInput.of(amazon(null, a)).hash()).isNotEqualTo(CanonicalInput.of(amazon(null, a, b)).hash());
    }

    @Test
    void aMissingParentIsNotAnEmptyParent() {
        VariationInput.Child only = child("A", Map.of());
        assertThat(CanonicalInput.of(amazon(null, only)).hash())
                .isNotEqualTo(CanonicalInput.of(amazon(new VariationInput.Parent(null, null, null, null), only)).hash());
    }

    @Test
    void aValueCannotBePassedOffAsTheNextField() {
        // the length prefix keeps "ab" + "" apart from "a" + "b"
        VariationInput.Child first = new VariationInput.Child("ab", null, null, Map.of());
        VariationInput.Child second = new VariationInput.Child("a", "b", null, Map.of());
        assertThat(CanonicalInput.of(amazon(null, first)).hash()).isNotEqualTo(CanonicalInput.of(amazon(null, second)).hash());
    }

    @Test
    void theVariationPropertiesTakePart() {
        VariationInput one = new VariationInput("ETSY", null, List.of("Size"), List.of());
        VariationInput other = new VariationInput("ETSY", null, List.of("Size", "Color"), List.of());
        assertThat(CanonicalInput.of(one).hash()).isNotEqualTo(CanonicalInput.of(other).hash());
    }
}
