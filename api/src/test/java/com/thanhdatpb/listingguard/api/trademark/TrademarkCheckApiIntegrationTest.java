package com.thanhdatpb.listingguard.api.trademark;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1_SECOND;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
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

/**
 * {@code POST /api/v1/trademark-checks} and {@code GET /api/v1/trademark-checks/{id}} on a context where the synthetic
 * catalog is READY (ADR-009, ADR-010): disclaimer and evidence, spans, client isolation, the cache and retention at the
 * HTTP level, and that no listing text is stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class TrademarkCheckApiIntegrationTest {

    private static final String TRADEMARK = "/api/v1/trademark-checks";
    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TrademarkCatalogRuntime runtime;

    // ---- request helpers

    /** Letters only, so the text can never look like a catalog mark or a leet spelling of one. */
    static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.toString();
    }

    private ObjectNode etsy(String title, String description, String... tags) {
        ObjectNode listing = mapper.createObjectNode();
        if (title != null) {
            listing.put("title", title);
        }
        listing.put("description", description == null ? "Plain description " + unique() : description);
        ArrayNode tagNodes = listing.putArray("tags");
        for (String tag : tags) {
            tagNodes.add(tag);
        }
        return envelope("ETSY", listing);
    }

    private ObjectNode envelope(String marketplace, ObjectNode listing) {
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", marketplace);
        body.set("listing", listing);
        return body;
    }

    private JsonNode trademark(String key, JsonNode body) throws Exception {
        String response = mvc.perform(post(TRADEMARK).header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private JsonNode read(String key, String id) throws Exception {
        String response = mvc.perform(get(TRADEMARK + "/" + id).header(HEADER, key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    /** The field value as a client can reproduce it: the cleaned list entry or value, then NFC, then trimmed. */
    static String canonicalField(JsonNode listing, String field) {
        String value;
        if (field.contains("[")) {
            String name = field.substring(0, field.indexOf('['));
            int index = Integer.parseInt(field.substring(field.indexOf('[') + 1, field.indexOf(']')));
            value = cleaned(listing.get(name)).get(index);
        } else if (field.equals("search_terms") && listing.get(field) != null && listing.get(field).isArray()) {
            value = String.join(" ", cleaned(listing.get(field)));
        } else {
            JsonNode node = listing.get(field);
            value = node == null ? "" : node.asText().trim();
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    private static List<String> cleaned(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static JsonNode findingOf(JsonNode result, String ruleId) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding of rule " + ruleId + " in " + result.get("findings"));
    }

    private static JsonNode findingOfField(JsonNode result, String field) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("field").asText().equals(field)) {
                return finding;
            }
        }
        throw new AssertionError("no finding for " + field);
    }

    private void expire(String id) {
        jdbc.update("UPDATE trademark_check SET created_at = now() - interval '31 days', "
                + "expires_at = now() - interval '1 day' WHERE id = ?::uuid", id);
    }

    private int rows(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    /** The problem body with the parts that legitimately differ per request (ids, instance) taken out. */
    private static String shape(String problem, String id) {
        return problem.replace(id, "<id>").replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }

    // ---- disclaimer, evidence, spans

    @Test
    void aResultCarriesTheDisclaimerTheVersionsAndEvidenceThatPointIntoTheText() throws Exception {
        ObjectNode request = etsy("Zorvex Style Northvale Baseball Jersey", null, "jersey", "royal blue white pinstripe");
        JsonNode listing = request.get("listing");

        JsonNode result = trademark(KEY_1, request);

        assertThat(result.get("cached").asBoolean()).isFalse();
        assertThat(result.get("marketplace").asText()).isEqualTo("ETSY");
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(result.get("failedRuleIds")).isEmpty();
        assertThat(result.get("disclaimer").asText()).isEqualTo(rulesets.ruleset().disclaimer()).isNotBlank();
        assertThat(result.get("rulesetVersion").asText()).isEqualTo(rulesets.ruleset().version());
        assertThat(result.get("catalogVersion").asText()).isEqualTo(TrademarkReadyTestConfig.catalogVersion());
        assertThat(result.get("analysisVersion").asText()).isEqualTo(runtime.fingerprint("ETSY").value()).matches("[0-9a-f]{64}");
        assertThat(Duration.between(Instant.parse(result.get("createdAt").asText()), Instant.parse(result.get("expiresAt").asText())))
                .isEqualTo(Duration.ofDays(30));

        List<String> ruleIds = new ArrayList<>();
        result.get("findings").forEach(f -> ruleIds.add(f.get("ruleId").asText()));
        assertThat(ruleIds).containsExactlyInAnyOrder("catalog_exact", "geo_sport_combination", "pure_color_description");
        for (JsonNode finding : result.get("findings")) {
            String text = canonicalField(listing, finding.get("field").asText());
            assertThat(text.substring(finding.get("start").asInt(), finding.get("end").asInt()))
                    .as("the span of %s points at its snippet", finding.get("ruleId").asText())
                    .isEqualTo(finding.get("snippet").asText());
            assertThat(finding.get("message").asText()).isNotBlank();
            assertThat(finding.get("suggestion").asText()).isNotBlank();
        }
        JsonNode exact = findingOf(result, "catalog_exact");
        assertThat(exact.get("field").asText()).isEqualTo("title");
        assertThat(exact.get("risk").asText()).isEqualTo("HIGH");
        assertThat(exact.get("start").asInt()).isEqualTo(0);
        assertThat(exact.get("end").asInt()).isEqualTo(6);
        assertThat(exact.get("mark").asText()).isEqualTo("ZORVEX");
        assertThat(exact.get("matchType").asText()).isEqualTo("EXACT");
        assertThat(exact.get("score").asDouble()).isEqualTo(1.0);
        assertThat(exact.get("insideColorPhrase").asBoolean()).isFalse();
        List<String> sourceIds = new ArrayList<>();
        exact.get("sourceIds").forEach(s -> sourceIds.add(s.asText()));
        assertThat(sourceIds).containsExactly("S-001", "S-002");
        JsonNode geo = findingOf(result, "geo_sport_combination");
        assertThat(geo.get("matchType").asText()).isEqualTo("CONTEXT");
        assertThat(geo.get("mark").isNull()).isTrue();
        assertThat(geo.get("score").isNull()).isTrue();
        assertThat(geo.get("sourceIds")).isEmpty();
        JsonNode color = findingOf(result, "pure_color_description");
        assertThat(color.get("field").asText()).isEqualTo("tags[1]");
        assertThat(color.get("risk").asText()).isEqualTo("LOW");
    }

    @Test
    void aSpanIsAnOffsetInUtf16UnitsOfTheTextAfterNfcAndTrim() throws Exception {
        // decomposed e + combining acute accent, an emoji (a surrogate pair) and surrounding blanks: the offsets differ
        // between the raw string, its NFC form and a code point count
        String raw = "  Café 😀 Zorvex fan  ";
        ObjectNode request = etsy(raw, null);

        JsonNode result = trademark(KEY_1, request);

        JsonNode exact = findingOf(result, "catalog_exact");
        String canonical = canonicalField(request.get("listing"), "title");
        assertThat(canonical).isEqualTo("Café 😀 Zorvex fan");
        assertThat(exact.get("start").asInt()).isEqualTo(8);
        assertThat(exact.get("end").asInt()).isEqualTo(14);
        assertThat(canonical.substring(8, 14)).isEqualTo("Zorvex");
        assertThat(exact.get("snippet").asText()).isEqualTo("Zorvex");
    }

    @Test
    void aTagIsNamedByItsPositionInTheCleanedList() throws Exception {
        ObjectNode request = etsy("Plain mug", null, "", "  ", "Zorvex");

        JsonNode result = trademark(KEY_1, request);

        // blank tags are dropped by the normalizer shared with /checks, so the tag is tags[0], as in the /checks findings
        JsonNode exact = findingOf(result, "catalog_exact");
        assertThat(exact.get("field").asText()).isEqualTo("tags[0]");
        assertThat(canonicalField(request.get("listing"), "tags[0]")).isEqualTo("Zorvex");
    }

    @Test
    void marksInAmazonSearchTermsBulletPointsAndBrandAreReportedWithTheirFieldAndSpan() throws Exception {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", "Plain ceramic mug " + unique());
        listing.put("brand", "Zorvex");
        listing.putArray("bullet_points").add("Soft handle").add("  Compatible with Quillmark devices ");
        listing.putArray("search_terms").add("mug").add("zorvex").add("cup");
        ObjectNode request = envelope("AMAZON", listing);

        JsonNode result = trademark(KEY_1, request);

        assertThat(result.get("marketplace").asText()).isEqualTo("AMAZON");
        List<String> fields = new ArrayList<>();
        for (JsonNode finding : result.get("findings")) {
            String field = finding.get("field").asText();
            fields.add(field);
            String text = canonicalField(listing, field);
            assertThat(text.substring(finding.get("start").asInt(), finding.get("end").asInt()))
                    .isEqualTo(finding.get("snippet").asText());
        }
        assertThat(fields).containsExactlyInAnyOrder("brand", "bullet_points[1]", "search_terms");
        JsonNode bullet = findingOfField(result, "bullet_points[1]");
        assertThat(bullet.get("snippet").asText()).isEqualTo("Quillmark");
        assertThat(bullet.get("start").asInt()).isEqualTo(16);
        assertThat(bullet.get("mark").asText()).isEqualTo("Quillmark");
        JsonNode searchTerms = findingOfField(result, "search_terms");
        // the array is joined by single spaces, as for the /checks rules: "mug zorvex cup"
        assertThat(searchTerms.get("start").asInt()).isEqualTo(4);
        assertThat(searchTerms.get("end").asInt()).isEqualTo(10);
    }

    @Test
    void aSnippetIsBoundedByTheRuleset() throws Exception {
        int limit = rulesets.ruleset().matching().maxSnippetChars();
        String longColorPhrase = "red blue ".repeat(40).strip();
        ObjectNode request = etsy("Plain mug", longColorPhrase);

        JsonNode result = trademark(KEY_1, request);

        JsonNode color = findingOf(result, "pure_color_description");
        assertThat(color.get("snippet").asText()).hasSizeLessThanOrEqualTo(limit);
        assertThat(color.get("end").asInt() - color.get("start").asInt()).isGreaterThan(limit);
    }

    // ---- GET, cache, client isolation

    @Test
    void getReturnsTheStoredResultAsPostedButNotCached() throws Exception {
        JsonNode posted = trademark(KEY_1, etsy("Zorvex gift " + unique(), null));

        JsonNode read = read(KEY_1, posted.get("checkId").asText());

        assertThat(read).isEqualTo(posted);
        assertThat(read.get("cached").asBoolean()).isFalse();
    }

    @Test
    void anIdenticalRequestIsAnsweredFromTheStoredResultWithTheSameCheckId() throws Exception {
        ObjectNode request = etsy("Zorvex mug " + unique(), null, "mug");

        JsonNode first = trademark(KEY_1, request);
        JsonNode second = trademark(KEY_1_SECOND, request);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).as("another key of the same client shares the cache").isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
        assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
        assertThat(second.get("findings")).isEqualTo(first.get("findings"));
        assertThat(rows("trademark_check", "id = ?::uuid", first.get("checkId").asText())).isEqualTo(1);
    }

    @Test
    void submittedTextThatOnlyNormalizesToTheSameMatchFormIsAnotherCheck() throws Exception {
        String suffix = unique();

        JsonNode upper = trademark(KEY_1, etsy("ZORVEX mug " + suffix, "same description"));
        JsonNode lower = trademark(KEY_1, etsy("zorvex mug " + suffix, "same description"));

        assertThat(lower.get("cached").asBoolean()).isFalse();
        assertThat(lower.get("checkId")).isNotEqualTo(upper.get("checkId"));
    }

    @Test
    void theSameTextForAnotherMarketplaceIsAnotherCheck() throws Exception {
        String title = "Zorvex mug " + unique();
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);

        JsonNode etsy = trademark(KEY_1, envelope("ETSY", listing.deepCopy()));
        JsonNode amazon = trademark(KEY_1, envelope("AMAZON", listing.deepCopy()));

        assertThat(amazon.get("cached").asBoolean()).isFalse();
        assertThat(amazon.get("checkId")).isNotEqualTo(etsy.get("checkId"));
        assertThat(amazon.get("analysisVersion")).as("the marketplace is part of the fingerprint")
                .isNotEqualTo(etsy.get("analysisVersion"));
    }

    @Test
    void aCheckOfAnotherClientAnswersLikeAnIdThatDoesNotExist() throws Exception {
        String id = trademark(KEY_1, etsy("Zorvex own " + unique(), null)).get("checkId").asText();

        mvc.perform(get(TRADEMARK + "/" + id).header(HEADER, KEY_1_SECOND)).andExpect(status().isOk());
        String foreign = mvc.perform(get(TRADEMARK + "/" + id).header(HEADER, KEY_2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trademark-check-not-found"))
                .andReturn().getResponse().getContentAsString();
        String unknownId = UUID.randomUUID().toString();
        String unknown = mvc.perform(get(TRADEMARK + "/" + unknownId).header(HEADER, KEY_2))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(shape(foreign, id)).isEqualTo(shape(unknown, unknownId));
    }

    @Test
    void theSameInputFromAnotherClientIsNotACacheHit() throws Exception {
        ObjectNode request = etsy("Zorvex shared " + unique(), "shared description");

        JsonNode ofA = trademark(KEY_1, request);
        JsonNode ofB = trademark(KEY_2, request);
        JsonNode againA = trademark(KEY_1, request);
        JsonNode againB = trademark(KEY_2, request);

        assertThat(ofB.get("cached").asBoolean()).as("A's result is not reused for B").isFalse();
        assertThat(ofB.get("checkId")).isNotEqualTo(ofA.get("checkId"));
        assertThat(againA.get("checkId")).isEqualTo(ofA.get("checkId"));
        assertThat(againB.get("checkId")).isEqualTo(ofB.get("checkId"));
        assertThat(againA.get("cached").asBoolean()).isTrue();
        assertThat(againB.get("cached").asBoolean()).isTrue();
    }

    // ---- retention

    @Test
    void aResultIsNotReadableAfterItsRetentionEvenIfCleanupHasNotRun() throws Exception {
        String id = trademark(KEY_1, etsy("Zorvex kept " + unique(), null)).get("checkId").asText();
        read(KEY_1, id);

        expire(id);

        String expired = mvc.perform(get(TRADEMARK + "/" + id).header(HEADER, KEY_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trademark-check-not-found"))
                .andReturn().getResponse().getContentAsString();
        String unknownId = UUID.randomUUID().toString();
        String unknown = mvc.perform(get(TRADEMARK + "/" + unknownId).header(HEADER, KEY_1))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(shape(expired, id)).as("an expired id looks like one that never existed").isEqualTo(shape(unknown, unknownId));
        assertThat(rows("trademark_check", "id = ?::uuid", id)).as("the job has not run: the row is still there").isEqualTo(1);
    }

    @Test
    void aRepeatedRequestAfterRetentionIsAMissThatReplacesTheExpiredRowAndItsFindings() throws Exception {
        ObjectNode request = etsy("Zorvex replaced " + unique(), null);
        JsonNode first = trademark(KEY_1, request);
        String oldId = first.get("checkId").asText();
        assertThat(rows("trademark_check_finding", "check_id = ?::uuid", oldId)).isPositive();
        expire(oldId);

        JsonNode second = trademark(KEY_1, request);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId").asText()).isNotEqualTo(oldId);
        assertThat(second.get("findings")).isEqualTo(first.get("findings"));
        assertThat(rows("trademark_check", "id = ?::uuid", oldId)).as("the expired row was removed").isZero();
        assertThat(rows("trademark_check_finding", "check_id = ?::uuid", oldId)).as("with its findings").isZero();
        mvc.perform(get(TRADEMARK + "/" + oldId).header(HEADER, KEY_1)).andExpect(status().isNotFound());
        read(KEY_1, second.get("checkId").asText());
        assertThat(trademark(KEY_1, request).get("checkId")).isEqualTo(second.get("checkId"));
    }

    // ---- no full text is stored

    @Test
    void theSubmittedTextIsNotStoredOnlyItsHashAndTheBoundedEvidence() throws Exception {
        String marker = unique();
        String title = "Zorvex personalised ceramic travel mug " + marker;
        String description = "A very long and private description that must not be stored anywhere " + marker;
        String tag = "tag text " + marker;
        ObjectNode request = etsy(title, description, tag);

        JsonNode result = trademark(KEY_1, request);
        String id = result.get("checkId").asText();

        List<String> columns = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_name IN ('trademark_check', 'trademark_check_finding')""", String.class);
        assertThat(columns).as("no column can hold a title, tags or a description")
                .noneMatch(c -> c.contains("title") || c.contains("tags") || c.endsWith(".description") || c.contains("listing")
                        || c.contains("brand") || c.contains("search_terms"));
        String stored = jdbc.queryForObject("SELECT row_to_json(c)::text FROM trademark_check c WHERE id = ?::uuid",
                String.class, id)
                + jdbc.queryForList("SELECT row_to_json(f)::text FROM trademark_check_finding f WHERE check_id = ?::uuid",
                String.class, id);
        assertThat(stored).doesNotContain(marker).doesNotContain(title).doesNotContain(description);
        assertThat(stored).as("only the matched snippet of client text is kept").contains("Zorvex");
        assertThat(jdbc.queryForObject("SELECT input_hash FROM trademark_check WHERE id = ?::uuid", String.class, id))
                .matches("[0-9a-f]{64}");
    }

    // ---- the contract of /checks next to it

    @Test
    void theSameJsonGoesToBothEndpointsAndChecksIsUnchanged() throws Exception {
        ObjectNode request = etsy("Zorvex mug " + unique(), null, "mug");
        String body = mapper.writeValueAsString(request);

        JsonNode checks1 = mapper.readTree(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode trademark = trademark(KEY_1, request);
        JsonNode checks2 = mapper.readTree(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(checks1.get("cached").asBoolean()).isFalse();
        assertThat(checks2.get("cached").asBoolean()).isTrue();
        assertThat(checks2.get("checkId")).isEqualTo(checks1.get("checkId"));
        assertThat(checks2.get("analysisVersion").asText()).isEqualTo("etsy-2026-11");
        assertThat(checks2.get("rulesetVersion").asText()).isEqualTo("etsy-2026-11");
        assertThat(fieldNames(checks2)).containsExactly("checkId", "status", "marketplace", "rulesetVersion", "analysisVersion",
                "contentHash", "cached", "createdAt", "summary", "findings");
        assertThat(trademark.get("checkId")).isNotEqualTo(checks1.get("checkId"));
        List<String> checkRuleIds = new ArrayList<>();
        checks1.get("findings").forEach(f -> checkRuleIds.add(f.get("ruleId").asText()));
        assertThat(checkRuleIds).doesNotContain("catalog_exact");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    // ---- the examples of the OpenAPI document

    @Test
    void theOpenApiExamplesAreWhatTheEndpointReallyAnswers() throws Exception {
        JsonNode request = mapper.readTree(ApiExamples.TRADEMARK_REQUEST);
        JsonNode example = mapper.readTree(ApiExamples.TRADEMARK_RESULT);
        JsonNode cachedExample = mapper.readTree(ApiExamples.TRADEMARK_RESULT_CACHED);

        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(ApiExamples.TRADEMARK_REQUEST)).andExpect(status().isOk());
        JsonNode actual = trademark(KEY_1, request);
        JsonNode again = trademark(KEY_1, request);

        assertThat(actual.get("findings")).isEqualTo(example.get("findings"));
        for (String field : List.of("marketplace", "rulesetVersion", "cached", "completeness", "riskLevel", "failedRuleIds",
                "disclaimer")) {
            assertThat(actual.get(field)).as(field).isEqualTo(example.get(field));
        }
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(example));
        assertThat(again.get("cached").asBoolean()).isTrue();
        assertThat(again.get("findings")).isEqualTo(cachedExample.get("findings"));
        assertThat(fieldNames(again)).isEqualTo(fieldNames(cachedExample));
        ObjectNode withoutCached = (ObjectNode) cachedExample.deepCopy();
        ObjectNode exampleWithoutCached = (ObjectNode) example.deepCopy();
        withoutCached.remove("cached");
        exampleWithoutCached.remove("cached");
        assertThat(withoutCached).as("the cached example is the same result").isEqualTo(exampleWithoutCached);
    }

    @Test
    void theNotFoundExampleHasTheShapeOfTheRealProblem() throws Exception {
        JsonNode example = mapper.readTree(ApiExamples.PROBLEM_TRADEMARK_NOT_FOUND);
        JsonNode actual = mapper.readTree(mvc.perform(get(TRADEMARK + "/" + UUID.randomUUID()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(fieldNames(actual)).containsExactlyInAnyOrderElementsOf(fieldNames(example));
        assertThat(actual.get("code")).isEqualTo(example.get("code"));
        assertThat(actual.get("type")).isEqualTo(example.get("type"));
        assertThat(actual.get("status")).isEqualTo(example.get("status"));
        assertThat(actual.get("title")).isEqualTo(example.get("title"));
    }

    // ---- bad requests

    @Test
    void invalidRequestsAreProblemsWithStableCodes() throws Exception {
        mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":[1]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"NOPE\",\"listing\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown-marketplace"));
        mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"WALMART\",\"listing\":{}}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("marketplace-not-yet-supported"));
        mvc.perform(get(TRADEMARK + "/not-a-uuid").header(HEADER, KEY_1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post(TRADEMARK).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
}
