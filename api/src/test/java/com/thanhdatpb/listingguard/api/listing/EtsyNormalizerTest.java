package com.thanhdatpb.listingguard.api.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EtsyNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EtsyNormalizer normalizer = new EtsyNormalizer();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void reportsEtsyMarketplaceCode() {
        assertThat(normalizer.marketplace()).isEqualTo("ETSY");
    }

    @Test
    void mapsAllFields() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {
                  "title": "Handmade Mug",
                  "description": "A mug.",
                  "tags": ["mug", "ceramic"],
                  "price": {"amount": 1999, "divisor": 100, "currency_code": "usd"},
                  "taxonomy_path": ["Home & Living", "Kitchen", "Mugs"],
                  "materials": ["clay", "glaze"],
                  "images": [{"url_fullxfull": "https://img/1.jpg"}, "https://img/2.jpg"],
                  "quantity": 5,
                  "who_made": "i_did",
                  "when_made": "made_to_order"
                }
                """));

        assertThat(listing.title()).isEqualTo("Handmade Mug");
        assertThat(listing.description()).isEqualTo("A mug.");
        assertThat(listing.tags()).containsExactly("mug", "ceramic");
        assertThat(listing.price().amount()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(listing.price().currency()).isEqualTo("USD");
        assertThat(listing.category()).isEqualTo("Home & Living > Kitchen > Mugs");
        assertThat(listing.materials()).containsExactly("clay", "glaze");
        assertThat(listing.images()).containsExactly("https://img/1.jpg", "https://img/2.jpg");
        assertThat(listing.quantity()).isEqualTo(5);
        assertThat(listing.whoMade()).isEqualTo("i_did");
        assertThat(listing.whenMade()).isEqualTo("made_to_order");
    }

    @Test
    void trimsValuesAndDropsBlankEntries() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"title": "  Mug  ", "description": "   ", "tags": [" a ", "", "  "], "materials": ["", " wood "]}
                """));

        assertThat(listing.title()).isEqualTo("Mug");
        assertThat(listing.description()).isNull();
        assertThat(listing.tags()).containsExactly("a");
        assertThat(listing.materials()).containsExactly("wood");
    }

    @Test
    void keepsDuplicateTagsSoTheRuleEngineCanReportThem() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"tags": ["Mug", "mug", "MUG", "cup"]}
                """));

        assertThat(listing.tags()).containsExactly("Mug", "mug", "MUG", "cup");
    }

    @Test
    void doesNotTruncateOrRejectOverlongValues() throws Exception {
        String longTitle = "x".repeat(500);
        Listing listing = normalizer.normalize(json("{\"title\": \"" + longTitle + "\"}"));

        assertThat(listing.title()).hasSize(500);
    }

    @Test
    void missingFieldsBecomeNullOrEmptyLists() throws Exception {
        Listing listing = normalizer.normalize(json("{}"));

        assertThat(listing.title()).isNull();
        assertThat(listing.description()).isNull();
        assertThat(listing.price()).isNull();
        assertThat(listing.category()).isNull();
        assertThat(listing.quantity()).isNull();
        assertThat(listing.tags()).isEmpty();
        assertThat(listing.materials()).isEmpty();
        assertThat(listing.images()).isEmpty();
    }

    @Test
    void acceptsPlainNumericPriceAndCategoryString() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"price": "12.50", "category": "Mugs"}
                """));

        assertThat(listing.price().amount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(listing.price().currency()).isNull();
        assertThat(listing.category()).isEqualTo("Mugs");
    }

    @Test
    void unparseablePriceBecomesNull() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"price": "free"}
                """));

        assertThat(listing.price()).isNull();
    }

    @Test
    void rejectsNonObjectPayload() throws Exception {
        assertThatThrownBy(() -> normalizer.normalize(json("[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aListingWithoutPersonalizationHasNoQuestions() throws Exception {
        assertThat(normalizer.normalize(json("{}")).personalizationQuestions()).isEmpty();
        assertThat(normalizer.normalize(json("{\"personalization\": {}}")).personalizationQuestions()).isEmpty();
        assertThat(normalizer.normalize(json("{\"personalization\": {\"questions\": []}}")).personalizationQuestions()).isEmpty();
        assertThat(normalizer.normalize(json("{\"personalization\": \"yes\"}")).personalizationQuestions()).isEmpty();
        assertThat(normalizer.normalize(json("{\"personalization\": {\"questions\": \"Name\"}}")).personalizationQuestions())
                .isEmpty();
    }

    @Test
    void mapsEveryFieldOfEveryQuestion() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"personalization": {"questions": [
                  {"type": "TEXT", "question_text": "Name", "instructions": "Letters only", "required": true, "max_chars": 20},
                  {"type": "DROPDOWN", "question_text": "Font", "required": false, "options": ["Serif", "Script"]},
                  {"type": "UPLOAD", "question_text": "Photo", "options": ["Front", "Back"], "max_files": 2}
                ]}}
                """));

        assertThat(listing.personalizationQuestions()).containsExactly(
                new PersonalizationQuestion("TEXT", "Name", "Letters only", true, 20, java.util.List.of(), null),
                new PersonalizationQuestion("DROPDOWN", "Font", null, false, null, java.util.List.of("Serif", "Script"), null),
                new PersonalizationQuestion("UPLOAD", "Photo", null, null, null, java.util.List.of("Front", "Back"), 2));
    }

    @Test
    void trimsAndDropsBlanksButNeverCorrectsAQuestion() throws Exception {
        String longText = "x".repeat(300);
        Listing listing = normalizer.normalize(json("""
                {"personalization": {"questions": [
                  {"type": " dropdown ", "question_text": "  Colour  ", "instructions": "   ",
                   "options": [" Red ", "", "  ", "Red", "RED"]},
                  {"type": "text", "question_text": "%s"}
                ]}}
                """.formatted(longText)));

        PersonalizationQuestion dropdown = listing.personalizationQuestions().get(0);
        assertThat(dropdown.type()).isEqualTo("DROPDOWN");
        assertThat(dropdown.questionText()).isEqualTo("Colour");
        assertThat(dropdown.instructions()).isNull();
        assertThat(dropdown.options()).containsExactly("Red", "Red", "RED");
        assertThat(listing.personalizationQuestions().get(1).type()).isEqualTo("TEXT");
        assertThat(listing.personalizationQuestions().get(1).questionText()).hasSize(300);
    }

    @Test
    void aQuestionWithNoTextIsKeptSoARuleCanReportIt() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"personalization": {"questions": [{"type": "TEXT", "question_text": "  "}, {}, "Name", 3, null, []]}}
                """));

        assertThat(listing.personalizationQuestions()).hasSize(2);
        assertThat(listing.personalizationQuestions().get(0).questionText()).isNull();
        assertThat(listing.personalizationQuestions().get(1)).isEqualTo(
                new PersonalizationQuestion(null, null, null, null, null, java.util.List.of(), null));
    }

    @Test
    void anUnknownTypeIsKeptAsWritten() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"personalization": {"questions": [{"type": "radio", "question_text": "Size"}]}}
                """));

        assertThat(listing.personalizationQuestions().get(0).type()).isEqualTo("RADIO");
    }

    @Test
    void numbersAndFlagsAreReadOnlyWhenTheyAreJsonNumbersAndBooleans() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"personalization": {"questions": [
                  {"type": "TEXT", "question_text": "A", "required": "true", "max_chars": "20", "max_files": 2.9},
                  {"type": "TEXT", "question_text": "B", "required": null, "max_chars": null}
                ]}}
                """));

        PersonalizationQuestion first = listing.personalizationQuestions().get(0);
        assertThat(first.required()).isNull();
        assertThat(first.maxChars()).isNull();
        assertThat(first.maxFiles()).isEqualTo(2);
        assertThat(listing.personalizationQuestions().get(1).required()).isNull();
        assertThat(listing.personalizationQuestions().get(1).maxChars()).isNull();
    }
}
