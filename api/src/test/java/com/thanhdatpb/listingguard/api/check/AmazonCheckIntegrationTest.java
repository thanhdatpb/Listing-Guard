package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Amazon through the real API: normalizer, ruleset, storage, caching. Limits are read from the response, not assumed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AmazonCheckIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private static String requestOf(String listingJson) {
        return "{\"marketplace\":\"AMAZON\",\"listing\":" + listingJson + "}";
    }

    private JsonNode postOk(String listingJson) throws Exception {
        String response = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(requestOf(listingJson)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    /** A listing that satisfies every Amazon rule; the title makes it unique so tests never share a cached result. */
    private static String cleanListing(String id) {
        return """
                {
                  "title": "Hardside luggage %s",
                  "brand": "Roadster",
                  "bullet_points": ["Lightweight shell", "Four spinner wheels"],
                  "description": "A durable suitcase.",
                  "search_terms": "carry on suitcase",
                  "product_type": "LUGGAGE",
                  "price": {"amount": 49.99, "currency_code": "USD"},
                  "quantity": 5,
                  "images": ["https://example.com/1.jpg"]
                }
                """.formatted(id);
    }

    private static List<String> ruleIds(JsonNode result) {
        List<String> ids = new ArrayList<>();
        result.get("findings").forEach(finding -> ids.add(finding.get("ruleId").asText()));
        return ids;
    }

    private static JsonNode finding(JsonNode result, String ruleId) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding " + ruleId + " in " + result.get("findings"));
    }

    @Test
    void aCleanListingOnlyGetsTheInfoThatItsProductTypeHasNoOwnRules() throws Exception {
        JsonNode result = postOk(cleanListing(UUID.randomUUID().toString()));

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("marketplace").asText()).isEqualTo("AMAZON");
        assertThat(result.get("rulesetVersion").asText()).isEqualTo("amazon-2026-11");
        assertThat(result.get("analysisVersion").asText()).isEqualTo("amazon-2026-11");
        assertThat(result.get("cached").asBoolean()).isFalse();
        assertThat(result.get("summary").get("errors").asInt()).isZero();
        assertThat(result.get("summary").get("warnings").asInt()).isZero();
        assertThat(result.get("summary").get("infos").asInt()).isEqualTo(1);
        assertThat(ruleIds(result)).containsExactly("category_not_covered");
        JsonNode info = finding(result, "category_not_covered");
        assertThat(info.get("severity").asText()).isEqualTo("INFO");
        assertThat(info.get("source").asText()).isEqualTo("RULE");
        assertThat(info.get("field").asText()).isEqualTo("product_type");
        assertThat(info.get("value").asText()).isEqualTo("LUGGAGE");
    }

    @Test
    void problemsAreReportedWithFieldValueSeverityAndVietnameseMessages() throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode result = postOk("""
                {
                  "title": "Duffel bag bag bag! %s",
                  "brand": "Roadster",
                  "bullet_points": ["One", "Two", "Visit www.example.com", "Four", "Five", "Six"],
                  "description": "Write to jane@example.com",
                  "search_terms": "%s",
                  "product_type": "LUGGAGE"
                }
                """.formatted(id, "t".repeat(400)));

        assertThat(ruleIds(result)).containsExactly("title_repeated_words", "title_forbidden_chars", "bullet_count",
                "search_terms_length", "prohibited_content", "prohibited_content", "category_not_covered");

        JsonNode repeated = finding(result, "title_repeated_words");
        assertThat(repeated.get("severity").asText()).isEqualTo("WARNING");
        assertThat(repeated.get("field").asText()).isEqualTo("title");
        assertThat(repeated.get("value").asText()).isEqualTo("bag");
        assertThat(repeated.get("message").asText()).isEqualTo("Từ 'bag' xuất hiện 3 lần trong tiêu đề, tối đa 2 lần.");

        JsonNode forbidden = finding(result, "title_forbidden_chars");
        assertThat(forbidden.get("value").asText()).isEqualTo("!");

        JsonNode count = finding(result, "bullet_count");
        assertThat(count.get("field").asText()).isEqualTo("bullet_points");
        assertThat(count.get("value").asText()).isEqualTo("6");

        JsonNode terms = finding(result, "search_terms_length");
        assertThat(terms.get("field").asText()).isEqualTo("search_terms");
        assertThat(terms.get("message").asText()).contains("dài 400 byte (UTF-8)").contains("tối đa 249 byte");

        List<String> prohibitedFields = new ArrayList<>();
        result.get("findings").forEach(f -> {
            if (f.get("ruleId").asText().equals("prohibited_content")) {
                prohibitedFields.add(f.get("field").asText() + "=" + f.get("value").asText());
            }
        });
        assertThat(prohibitedFields).containsExactly("description=jane@example.com", "bullet_points[2]=www.example.com");

        assertThat(result.get("summary").get("errors").asInt()).isZero();
        assertThat(result.get("summary").get("warnings").asInt()).isEqualTo(6);
        assertThat(result.get("summary").get("infos").asInt()).isEqualTo(1);
    }

    @Test
    void aMissingProductTypeIsAnErrorAndGetsNoCoverageInfo() throws Exception {
        JsonNode result = postOk("{\"title\": \"Hardside luggage " + UUID.randomUUID() + "\"}");

        assertThat(ruleIds(result)).containsExactly("required_fields");
        JsonNode error = finding(result, "required_fields");
        assertThat(error.get("severity").asText()).isEqualTo("ERROR");
        assertThat(error.get("field").asText()).isEqualTo("product_type");
        assertThat(error.get("value").isNull()).isTrue();
        assertThat(result.get("summary").get("errors").asInt()).isEqualTo(1);
    }

    @Test
    void theSwitchedOffBulletLengthRuleDoesNotRun() throws Exception {
        JsonNode result = postOk("""
                {"title": "Hardside luggage %s", "bullet_points": ["%s"], "product_type": "LUGGAGE"}
                """.formatted(UUID.randomUUID(), "x".repeat(2000)));

        assertThat(ruleIds(result)).doesNotContain("bullet_length");
    }

    @Test
    void theSameListingTwiceIsAnsweredFromTheStoredCheck() throws Exception {
        String listing = cleanListing(UUID.randomUUID().toString());

        JsonNode first = postOk(listing);
        JsonNode second = postOk(listing);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId").asText()).isEqualTo(first.get("checkId").asText());
        assertThat(second.get("contentHash").asText()).isEqualTo(first.get("contentHash").asText());
        assertThat(second.get("analysisVersion").asText()).isEqualTo("amazon-2026-11");
    }

    @Test
    void searchTermsAsAnArrayAndAsAStringAreTheSameContent() throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode asString = postOk(cleanListing(id));
        JsonNode asArray = postOk(cleanListing(id).replace("\"carry on suitcase\"", "[\"carry\", \"on\", \"suitcase\"]"));

        assertThat(asArray.get("cached").asBoolean()).isTrue();
        assertThat(asArray.get("checkId").asText()).isEqualTo(asString.get("checkId").asText());
    }

    @Test
    void theStoredAmazonCheckCanBeReadBackById() throws Exception {
        JsonNode created = postOk("{\"title\": \"Duffel bag bag bag " + UUID.randomUUID() + "\", \"product_type\": \"LUGGAGE\"}");

        mvc.perform(get(CHECKS + "/" + created.get("checkId").asText()).header(HEADER, KEY_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplace").value("AMAZON"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.rulesetVersion").value("amazon-2026-11"))
                .andExpect(jsonPath("$.findings[0].ruleId").value("title_repeated_words"))
                .andExpect(jsonPath("$.findings[0].value").value("bag"));
    }

    @Test
    void theAmazonRulesetIsRegisteredOnceUnderAmazon() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ruleset_version WHERE marketplace_code = 'AMAZON' AND version = 'amazon-2026-11'", Integer.class);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void anAmazonAndAnEtsyCheckOfSimilarContentAreSeparateChecks() throws Exception {
        String title = "Shared title " + UUID.randomUUID();
        JsonNode amazon = postOk("{\"title\": \"" + title + "\", \"product_type\": \"LUGGAGE\"}");
        String etsy = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"" + title + "\"}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode etsyResult = mapper.readTree(etsy);
        assertThat(etsyResult.get("checkId").asText()).isNotEqualTo(amazon.get("checkId").asText());
        assertThat(etsyResult.get("rulesetVersion").asText()).isEqualTo("etsy-2026-11");
        assertThat(etsyResult.get("cached").asBoolean()).isFalse();
    }

    @Test
    void aListingThatIsNotAnObjectIs400() throws Exception {
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(requestOf("[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.detail").value("Amazon listing must be a JSON object"));
    }
}
