package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Etsy personalization block and the title guidance of {@code etsy-2026-12} through the real API: normalizer, ruleset,
 * storage and caching. The numbers come from the active ruleset, not from this file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class EtsyPersonalizationCheckIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ActiveRulesets rulesets;

    private RulesetConfig config() {
        return rulesets.get("ETSY").config();
    }

    /** A listing that satisfies every Etsy rule; the description makes it unique, so no test shares a cached result. */
    private ObjectNode listing() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "title": "Handmade Ceramic Mug",
                  "description": "A ceramic mug %s.",
                  "tags": ["mug", "ceramic"],
                  "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                  "taxonomy_path": ["Home & Living", "Mugs"],
                  "materials": ["clay"],
                  "images": ["https://example.com/1.jpg"],
                  "quantity": 5,
                  "who_made": "i_did",
                  "when_made": "made_to_order"
                }
                """.formatted(UUID.randomUUID()));
    }

    private JsonNode check(ObjectNode listing) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("marketplace", "ETSY");
        body.set("listing", listing);
        String response = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private static List<JsonNode> findings(JsonNode result, String ruleId) {
        List<JsonNode> found = new ArrayList<>();
        result.get("findings").forEach(finding -> {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                found.add(finding);
            }
        });
        return found;
    }

    private static ObjectNode question(ObjectNode listing, String type, String text) {
        ObjectNode question = ((ArrayNode) listing.get("personalization").get("questions")).addObject();
        question.put("type", type).put("question_text", text);
        return question;
    }

    private static ObjectNode withPersonalization(ObjectNode listing) {
        listing.putObject("personalization").putArray("questions");
        return listing;
    }

    @Test
    void aGoodPersonalizationBlockHasNoFindings() throws Exception {
        ObjectNode listing = withPersonalization(listing());
        question(listing, "TEXT", "Gift message").put("instructions", "Up to 20 letters").put("max_chars", 20);
        question(listing, "DROPDOWN", "Font").putArray("options").add("Serif").add("Script");
        ObjectNode upload = question(listing, "UPLOAD", "Photo").put("max_files", 2);
        upload.putArray("options").add("Front").add("Back");

        JsonNode result = check(listing);

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("rulesetVersion").asText()).isEqualTo("etsy-2026-12");
        assertThat(result.get("analysisVersion").asText()).isEqualTo("etsy-2026-12");
        assertThat(result.get("findings")).isEmpty();
    }

    @Test
    void aBrokenPersonalizationBlockIsReportedAtEveryPathWithTheSeverityOfItsRule() throws Exception {
        int maxText = config().rule("personalization_question_text").intParam("max_length");
        int maxInstructions = config().rule("personalization_instructions_length").intParam("max_length");
        int maxOptions = config().rule("personalization_dropdown_options").intParam("max_count");
        int maxFiles = config().rule("personalization_upload_files").intParam("max_files");
        int maxQuestions = config().rule("personalization_question_count").intParam("max_count");

        ObjectNode listing = withPersonalization(listing());
        question(listing, "TEXT", "Player name").put("instructions", "i".repeat(maxInstructions + 1));
        ArrayNode options = question(listing, "DROPDOWN", "Font").putArray("options");
        for (int i = 0; i <= maxOptions; i++) {
            options.add("Option " + i);
        }
        options.add("Option 0");
        question(listing, "UPLOAD", "Photo A").put("max_files", maxFiles + 1);
        question(listing, "UPLOAD", "Photo B").put("max_files", 1);
        question(listing, "TEXT", "t".repeat(maxText + 1));
        while (listing.get("personalization").get("questions").size() <= maxQuestions) {
            question(listing, "TEXT", "Extra " + listing.get("personalization").get("questions").size());
        }

        JsonNode result = check(listing);

        assertThat(findings(result, "personalization_question_count")).extracting(f -> f.get("field").asText())
                .containsExactly("personalization.questions", "personalization.questions[3]");
        assertThat(findings(result, "personalization_question_count")).extracting(f -> f.get("severity").asText())
                .containsOnly("ERROR");
        JsonNode sensitive = findings(result, "personalization_free_text_sensitive").get(0);
        assertThat(sensitive.get("field").asText()).isEqualTo("personalization.questions[0].question_text");
        assertThat(sensitive.get("severity").asText()).isEqualTo("INFO");
        assertThat(sensitive.get("value").asText()).isEqualTo("Player name");
        JsonNode instructions = findings(result, "personalization_instructions_length").get(0);
        assertThat(instructions.get("field").asText()).isEqualTo("personalization.questions[0].instructions");
        assertThat(instructions.get("severity").asText()).isEqualTo("WARNING");
        assertThat(findings(result, "personalization_dropdown_options")).extracting(f -> f.get("field").asText())
                .containsExactly("personalization.questions[1].options",
                        "personalization.questions[1].options[" + (maxOptions + 1) + "]");
        JsonNode files = findings(result, "personalization_upload_files").get(0);
        assertThat(files.get("field").asText()).isEqualTo("personalization.questions[2].max_files");
        assertThat(files.get("value").asText()).isEqualTo(String.valueOf(maxFiles + 1));
        JsonNode text = findings(result, "personalization_question_text").get(0);
        assertThat(text.get("field").asText()).isEqualTo("personalization.questions[4].question_text");
        assertThat(text.get("severity").asText()).isEqualTo("ERROR");
        result.get("findings").forEach(finding -> {
            assertThat(finding.has("fix")).as("no fix unless the fixes flag is on").isFalse();
            assertThat(finding.get("message").asText()).isNotBlank();
        });
        assertThat(result.get("summary").get("infos").asInt()).isEqualTo(1);
        assertThat(result.get("summary").get("warnings").asInt()).isEqualTo(1);
        // the question count (2), the dropdown (2), the upload files (1) and the question text (1)
        assertThat(result.get("summary").get("errors").asInt()).isEqualTo(6);
    }

    @Test
    void thePersonalizationQuestionsArePartOfTheCacheIdentity() throws Exception {
        ObjectNode listing = withPersonalization(listing());
        question(listing, "TEXT", "Gift message");

        JsonNode first = check(listing);
        JsonNode again = check(listing);
        ((ObjectNode) listing.get("personalization").get("questions").get(0)).put("question_text", "Greeting");
        JsonNode changed = check(listing);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(again.get("cached").asBoolean()).isTrue();
        assertThat(again.get("checkId").asText()).isEqualTo(first.get("checkId").asText());
        assertThat(changed.get("cached").asBoolean()).isFalse();
        assertThat(changed.get("checkId").asText()).isNotEqualTo(first.get("checkId").asText());
    }

    @Test
    void anEmptyPersonalizationBlockIsTheSameListingAsNoBlock() throws Exception {
        ObjectNode plain = listing();
        ObjectNode empty = plain.deepCopy();
        empty.putObject("personalization").putArray("questions");

        JsonNode first = check(plain);
        JsonNode second = check(empty);

        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId").asText()).isEqualTo(first.get("checkId").asText());
    }

    @Test
    void theTitleGuidanceAndTheSwitchedOffBannedWordsThroughTheApi() throws Exception {
        String banned = config().rule("banned_words").stringListParam("words").get(0);
        int maxWords = config().rule("title_word_count").intParam("max_words");
        ObjectNode listing = listing();
        StringBuilder title = new StringBuilder("Mug Free Shipping");
        for (int i = 0; i <= maxWords; i++) {
            title.append(" word").append(i);
        }
        listing.put("title", title.toString());
        listing.put("description", "A " + banned + " " + UUID.randomUUID());
        ((ArrayNode) listing.get("tags")).add(banned);

        JsonNode result = check(listing);

        assertThat(findings(result, "title_word_count")).hasSize(1);
        assertThat(findings(result, "title_word_count").get(0).get("severity").asText()).isEqualTo("INFO");
        assertThat(findings(result, "title_promotional_terms")).extracting(f -> f.get("value").asText())
                .containsExactly("Free Shipping");
        assertThat(findings(result, "title_promotional_terms").get(0).get("severity").asText()).isEqualTo("WARNING");
        assertThat(findings(result, "banned_words")).isEmpty();
    }
}
