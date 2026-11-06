package com.thanhdatpb.listingguard.api.variation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.variation.VariationInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The normalizer maps and cleans only: trim, drop blanks, nothing corrected, nothing reordered (docs/input-schema.md). */
class VariationNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VariationNormalizer normalizer = new VariationNormalizer();

    private VariationInput amazon(String json) throws Exception {
        return normalizer.normalize("AMAZON", MAPPER.treeToValue(MAPPER.readTree(json), VariationCheckRequest.class));
    }

    private VariationInput etsy(String json) throws Exception {
        return normalizer.normalize("ETSY", MAPPER.treeToValue(MAPPER.readTree(json), VariationCheckRequest.class));
    }

    @Test
    void anAmazonFamilyIsReadAsWritten() throws Exception {
        VariationInput input = amazon("""
                {"marketplace": "AMAZON",
                 "parent": {"sku": "P", "product_type": "Shirt", "variation_theme": "Color-Size", "title": "Tee"},
                 "children": [{"sku": "C1", "parent_sku": "P", "title": "Tee Red M",
                               "attributes": {"color": "Red", "size": "M"}}]}""");

        assertThat(input.marketplace()).isEqualTo("AMAZON");
        assertThat(input.parent()).isEqualTo(new VariationInput.Parent("P", "Shirt", "Color-Size", "Tee"));
        assertThat(input.variationProperties()).isEmpty();
        assertThat(input.children()).hasSize(1);
        VariationInput.Child child = input.children().get(0);
        assertThat(child.sku()).isEqualTo("C1");
        assertThat(child.parentSku()).isEqualTo("P");
        assertThat(child.title()).isEqualTo("Tee Red M");
        assertThat(child.attributes()).containsExactly(Map.entry("color", "Red"), Map.entry("size", "M"));
    }

    @Test
    void stringsAreTrimmedBlanksAreAbsentAndNothingElseIsChanged() throws Exception {
        VariationInput input = amazon("""
                {"marketplace": "AMAZON",
                 "parent": {"sku": "  P-1  ", "product_type": "   ", "variation_theme": " color-SIZE ", "title": null},
                 "children": [{"sku": " c1 ", "parent_sku": "p-1", "title": "  ",
                               "attributes": {" Color ": "  Red  ", "size": "   ", "": "x", "Shape": 7, "flag": true,
                                              "nested": {"a": 1}, "list": ["a"]}}]}""");

        assertThat(input.parent()).isEqualTo(new VariationInput.Parent("P-1", null, "color-SIZE", null));
        VariationInput.Child child = input.children().get(0);
        assertThat(child.sku()).isEqualTo("c1");
        assertThat(child.parentSku()).isEqualTo("p-1");
        assertThat(child.title()).isNull();
        assertThat(child.attributes()).as("trimmed names, case kept, a blank value, a blank name and an object or array dropped")
                .containsExactly(Map.entry("Color", "Red"), Map.entry("Shape", "7"), Map.entry("flag", "true"));
    }

    @Test
    void aParentWithNoFieldAndAChildWithNoFieldAreDroppedSoIndexesCountTheCleanedList() throws Exception {
        VariationInput input = amazon("""
                {"marketplace": "AMAZON", "parent": {"sku": " ", "title": ""},
                 "children": [{"sku": "A"}, {}, "text", 3, null, {"sku": " ", "attributes": {"x": " "}}, {"sku": "B"}]}""");

        assertThat(input.parent()).isNull();
        assertThat(input.children()).extracting(VariationInput.Child::sku).containsExactly("A", "B");
    }

    @Test
    void anythingThatIsNotTheExpectedShapeIsAbsent() throws Exception {
        VariationInput notShapes = amazon("{\"marketplace\": \"AMAZON\", \"parent\": \"P\", \"children\": {\"sku\": \"A\"}}");
        VariationInput missing = amazon("{\"marketplace\": \"AMAZON\"}");

        assertThat(notShapes.parent()).isNull();
        assertThat(notShapes.children()).isEmpty();
        assertThat(missing.parent()).isNull();
        assertThat(missing.children()).isEmpty();
        assertThat(missing.variationProperties()).isEmpty();
    }

    @Test
    void twoAttributeNamesThatAreEqualOnceTrimmedKeepTheFirst() throws Exception {
        VariationInput input = amazon("""
                {"marketplace": "AMAZON", "children": [{"attributes": {"color": "Red", " color ": "Blue"}}]}""");

        assertThat(input.children().get(0).attributes()).containsExactly(Map.entry("color", "Red"));
    }

    @Test
    void amazonReadsTheParentAndTheParentSkuAndIgnoresVariationProperties() throws Exception {
        VariationInput input = amazon("""
                {"marketplace": "AMAZON", "variation_properties": ["Size"],
                 "parent": {"sku": "P"}, "children": [{"sku": "A", "parent_sku": "P"}]}""");

        assertThat(input.variationProperties()).isEmpty();
        assertThat(input.parent()).isNotNull();
        assertThat(input.children().get(0).parentSku()).isEqualTo("P");
    }

    @Test
    void etsyReadsTheVariationPropertiesAndIgnoresTheParentAndTheParentSku() throws Exception {
        VariationInput input = etsy("""
                {"marketplace": "ETSY", "variation_properties": [" Primary color ", "", "Size", 5, ["x"], "Size"],
                 "parent": {"sku": "P"}, "children": [{"sku": "A", "parent_sku": "P", "attributes": {"Size": "M"}}]}""");

        assertThat(input.parent()).isNull();
        assertThat(input.variationProperties()).as("trimmed, blanks and non-text dropped, a repeat kept")
                .containsExactly("Primary color", "Size", "5", "Size");
        assertThat(input.children().get(0).parentSku()).isNull();
        assertThat(input.children().get(0).attributes()).containsExactly(Map.entry("Size", "M"));
    }

    @Test
    void theOrderOfTheChildrenAndAttributesIsTheOrderSent() throws Exception {
        JsonNode body = MAPPER.readTree("""
                {"marketplace": "AMAZON", "children": [{"sku": "Z", "attributes": {"b": "1", "a": "2"}}, {"sku": "A"}]}""");
        VariationInput input = normalizer.normalize("AMAZON", MAPPER.treeToValue(body, VariationCheckRequest.class));

        assertThat(input.children()).extracting(VariationInput.Child::sku).containsExactly("Z", "A");
        assertThat(List.copyOf(input.children().get(0).attributes().keySet())).containsExactly("b", "a");
    }
}
