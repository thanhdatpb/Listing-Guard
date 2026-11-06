package com.thanhdatpb.listingguard.api.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The normalizer maps and cleans and nothing else; above all it does not trim the buyer's text (ADR-016). */
class PersonalizationNormalizerTest {

    private final PersonalizationNormalizer normalizer = new PersonalizationNormalizer();

    private PersonalizationInput normalize(String json) throws Exception {
        return normalizer.normalize(PersonalizationApiSupport.MAPPER.readValue(json, PersonalizationScreeningRequest.class));
    }

    private static List<String> texts(PersonalizationInput input) {
        return input.values().stream().map(PersonalizationInput.Value::text).toList();
    }

    @Test
    void theTextOfAValueKeepsItsSpacesAndOnlyGetsUnicodeNfc() throws Exception {
        PersonalizationInput input = normalize("""
                {"marketplace":"ETSY","values":[{"text":"  AB  "},{"text":"A  B"},{"text":"a\\u0323"}]}""");

        assertThat(texts(input)).containsExactly("  AB  ", "A  B", "ạ");
    }

    @Test
    void anEntryWithoutTextOrWithEmptyTextOrNotAnObjectIsDroppedAndTheIndexCountsTheCleanedList() throws Exception {
        PersonalizationInput input = normalize("""
                {"marketplace":"ETSY","values":[{"label":"a"},{"label":"b","text":""},"loose",null,
                 {"label":"c","text":"X"},{"label":"d","text":{"nested":1}},{"label":"e","text":["x"]},{"label":"f","text":"Y"}]}""");

        assertThat(input.values()).extracting(PersonalizationInput.Value::label).containsExactly("c", "f");
        assertThat(texts(input)).containsExactly("X", "Y");
    }

    @Test
    void aTextOfOnlySpacesIsKeptSoTheRuleCanReportIt() throws Exception {
        assertThat(texts(normalize("{\"marketplace\":\"ETSY\",\"values\":[{\"text\":\"   \"}]}"))).containsExactly("   ");
    }

    @Test
    void aNumberOrBooleanTextIsReadAsItsText() throws Exception {
        assertThat(texts(normalize("{\"marketplace\":\"ETSY\",\"values\":[{\"text\":10},{\"text\":true}]}")))
                .containsExactly("10", "true");
    }

    @Test
    void labelsAndCodesAreTrimmedAndBlankIsAbsent() throws Exception {
        PersonalizationInput input = normalize("""
                {"marketplace":"ETSY","print_profile":"  latin_basic ","product_type":"  jersey ",
                 "values":[{"label":"  name ","text":"A"},{"label":"   ","text":"B"}]}""");

        assertThat(input.printProfile()).isEqualTo("latin_basic");
        assertThat(input.productType()).isEqualTo("jersey");
        assertThat(input.values()).extracting(PersonalizationInput.Value::label).containsExactly("name", null);
    }

    @Test
    void anObjectWhereTextIsExpectedIsAbsent() throws Exception {
        PersonalizationInput input = normalize("""
                {"marketplace":"ETSY","print_profile":{"a":1},"product_type":["x"],"values":[{"label":{"a":1},"text":"A"}]}""");

        assertThat(input.printProfile()).isNull();
        assertThat(input.productType()).isNull();
        assertThat(input.values().get(0).label()).isNull();
    }

    @Test
    void valuesThatAreNotAnArrayAreAnEmptyList() throws Exception {
        assertThat(normalize("{\"marketplace\":\"ETSY\"}").values()).isEmpty();
        assertThat(normalize("{\"marketplace\":\"ETSY\",\"values\":{\"text\":\"A\"}}").values()).isEmpty();
        assertThat(normalize("{\"marketplace\":\"ETSY\",\"values\":\"A\"}").values()).isEmpty();
    }

    @Test
    void nothingIsDedupedTruncatedOrReordered() throws Exception {
        String longText = "Z".repeat(500);
        PersonalizationInput input = normalize("{\"marketplace\":\"ETSY\",\"values\":[{\"text\":\"B\"},{\"text\":\"A\"},"
                + "{\"text\":\"B\"},{\"text\":\"" + longText + "\"}]}");

        assertThat(texts(input)).containsExactly("B", "A", "B", longText);
    }
}
