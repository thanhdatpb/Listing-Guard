package com.thanhdatpb.listingguard.api.trademark;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.trademark.TrademarkCheckApiIntegrationTest.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.SourceLink;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Product type, risk adjustments and source links of {@code POST /api/v1/trademark-checks} (ADR-009, "Product classes, source
 * links and generic words") on a context where the synthetic catalog is READY. The product types, classes and levels are
 * read from the active ruleset; the marks are the synthetic ones of the test catalog (Zorvex has Nice classes 25 and 35,
 * Quillmark has 35).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class TrademarkProductClassesApiIntegrationTest {

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

    @Autowired
    TrademarkCheckStore store;

    @Autowired
    TransactionTemplate transactions;

    private TrademarkRuleset.ProductClasses classes() {
        return rulesets.ruleset().productClasses();
    }

    private ObjectNode request(String title, String productType) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        listing.put("description", "Plain description " + unique());
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.set("listing", listing);
        if (productType != null) {
            body.put("product_type", productType);
        }
        return body;
    }

    private JsonNode send(JsonNode body) throws Exception {
        String response = mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private JsonNode read(String id) throws Exception {
        String response = mvc.perform(get(TRADEMARK + "/" + id).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private static JsonNode finding(JsonNode result, String ruleId) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding " + ruleId + " in " + result);
    }

    private long storedChecks() {
        return jdbc.queryForObject("SELECT count(*) FROM trademark_check", Long.class);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    // ---- the product type

    @Test
    void aMarkOutsideTheProductTypesClassesIsReportedLowerWithTheOriginalLevel_andGetShowsTheSame() throws Exception {
        TrademarkRuleset ruleset = rulesets.ruleset();
        RiskLevel base = ruleset.catalogExact().risk();
        RiskLevel lowered = RiskLevel.values()[Math.max(0, base.ordinal() - classes().downgradeLevels())];

        JsonNode result = send(request("Quillmark " + unique(), "apparel"));

        assertThat(result.get("productType").asText()).isEqualTo("apparel");
        JsonNode exact = finding(result, "catalog_exact");
        assertThat(exact.get("risk").asText()).isEqualTo(lowered.name());
        assertThat(result.get("riskLevel").asText()).isEqualTo(lowered.name());
        assertThat(exact.get("riskAdjustments")).hasSize(1);
        JsonNode adjustment = exact.get("riskAdjustments").get(0);
        assertThat(adjustment.get("reason").asText()).isEqualTo(AdjustmentReason.OUTSIDE_PRODUCT_CLASSES.code());
        assertThat(adjustment.get("from").asText()).isEqualTo(base.name());
        assertThat(adjustment.get("to").asText()).isEqualTo(lowered.name());
        assertThat(adjustment.get("note").asText()).contains("Quillmark", "35", "apparel");
        assertThat(exact.get("message").asText()).endsWith(adjustment.get("note").asText());
        assertThat(exact.get("sourceLinks")).isEmpty();

        JsonNode stored = read(result.get("checkId").asText());
        assertThat(stored.get("productType").asText()).isEqualTo("apparel");
        assertThat(stored.get("findings")).isEqualTo(result.get("findings"));
        assertThat(stored.get("riskLevel")).isEqualTo(result.get("riskLevel"));
    }

    @Test
    void aMarkInOneOfTheProductTypesClassesKeepsItsLevel() throws Exception {
        JsonNode result = send(request("Zorvex " + unique(), "apparel"));

        JsonNode exact = finding(result, "catalog_exact");
        assertThat(exact.get("risk").asText()).isEqualTo(rulesets.ruleset().catalogExact().risk().name());
        assertThat(exact.get("riskAdjustments")).isEmpty();
    }

    @Test
    void theProductTypeIsPartOfTheCacheIdentityAndABlankOneIsNone() throws Exception {
        // one listing (the description is random per request() call), only the product type and the marketplace's case vary
        ObjectNode base = request("Zorvex " + unique(), null);
        ObjectNode withMug = base.deepCopy().put("product_type", "mug");
        ObjectNode withMugLowerCase = withMug.deepCopy().put("marketplace", "etsy");
        ObjectNode withBlank = base.deepCopy().put("product_type", "   ");

        JsonNode none = send(base);
        JsonNode mug = send(withMug);
        JsonNode mugAgain = send(withMugLowerCase);
        JsonNode blank = send(withBlank);

        assertThat(none.get("productType").isNull()).isTrue();
        assertThat(mug.get("productType").asText()).isEqualTo("mug");
        assertThat(mug.get("checkId")).isNotEqualTo(none.get("checkId"));
        assertThat(finding(mug, "catalog_exact").get("risk"))
                .isNotEqualTo(finding(none, "catalog_exact").get("risk"));
        assertThat(mug.get("cached").asBoolean()).isFalse();
        // the marketplace is case-insensitive and a blank product type is none: both are the stored result
        assertThat(mugAgain.get("cached").asBoolean()).isTrue();
        assertThat(mugAgain.get("checkId")).isEqualTo(mug.get("checkId"));
        assertThat(blank.get("cached").asBoolean()).isTrue();
        assertThat(blank.get("checkId")).isEqualTo(none.get("checkId"));
    }

    @Test
    void aRepeatedRequestWithTheSameProductTypeIsTheCachedResult() throws Exception {
        ObjectNode body = request("Zorvex " + unique(), "mug");

        JsonNode first = send(body);
        JsonNode second = send(body);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
        assertThat(second.get("findings")).isEqualTo(first.get("findings"));
        assertThat(second.get("productType").asText()).isEqualTo("mug");
    }

    @Test
    void aBlankProductTypeAnswersWithTheStoredResultOfTheRequestWithNone() throws Exception {
        ObjectNode withNone = request("Zorvex " + unique(), null);
        JsonNode first = send(withNone);
        withNone.put("product_type", "  ");

        JsonNode second = send(withNone);

        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
    }

    @Test
    void anUndeclaredProductTypeIsRefusedWithTheDeclaredOnesAndStoresNothing() throws Exception {
        long before = storedChecks();
        String secretTitle = "Zorvex " + unique();

        // the keys are exact: a capital letter or a hyphen instead of an underscore is another, unknown, key
        for (String type : List.of("spaceship", "Apparel", "phone-case")) {
            mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request(secretTitle, type))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("unknown-product-type"))
                    .andExpect(jsonPath("$.requestId").exists())
                    .andExpect(jsonPath("$.known.length()").value(classes().knownTypes().size()))
                    .andExpect(jsonPath("$.known[0]").value(classes().knownTypes().get(0)))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .doesNotContain(secretTitle));
        }

        assertThat(storedChecks()).isEqualTo(before);
    }

    @Test
    void aProductTypeOverTheLengthLimitIsAnInvalidRequest() throws Exception {
        mvc.perform(post(TRADEMARK).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("Zorvex", "x".repeat(65)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void theChecksEndpointIgnoresAProductTypeItIsSent() throws Exception {
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("Plain title " + unique(), "spaceship"))))
                .andExpect(status().isOk());
    }

    @Test
    void theResultHasTheNewFieldsInOrder() throws Exception {
        JsonNode result = send(request("Zorvex " + unique(), "mug"));

        assertThat(fieldNames(result)).containsExactly("checkId", "marketplace", "productType", "rulesetVersion",
                "catalogVersion", "analysisVersion", "cached", "completeness", "riskLevel", "failedRuleIds", "disclaimer",
                "createdAt", "expiresAt", "findings");
        assertThat(fieldNames(result.get("findings").get(0))).containsExactly("ruleId", "risk", "field", "start", "end",
                "snippet", "mark", "matchType", "score", "insideColorPhrase", "otherRuleIds", "sourceIds", "sourceLinks",
                "riskAdjustments", "message", "suggestion");
    }

    // ---- the OpenAPI examples

    @Test
    void theProductTypeExamplesAreWhatTheEndpointReallyAnswers() throws Exception {
        JsonNode example = mapper.readTree(ApiExamples.TRADEMARK_RESULT_PRODUCT_TYPE);

        JsonNode actual = send(mapper.readTree(ApiExamples.TRADEMARK_REQUEST_PRODUCT_TYPE));

        assertThat(actual.get("findings")).isEqualTo(example.get("findings"));
        for (String field : List.of("marketplace", "productType", "rulesetVersion", "cached", "completeness", "riskLevel",
                "failedRuleIds", "disclaimer")) {
            assertThat(actual.get(field)).as(field).isEqualTo(example.get(field));
        }
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(example));

        JsonNode problemExample = mapper.readTree(ApiExamples.PROBLEM_UNKNOWN_PRODUCT_TYPE);
        ObjectNode bad = (ObjectNode) mapper.readTree(ApiExamples.TRADEMARK_REQUEST_PRODUCT_TYPE);
        bad.put("product_type", "spaceship");
        JsonNode problem = mapper.readTree(mvc.perform(post(TRADEMARK).header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(problem)).containsExactlyInAnyOrderElementsOf(fieldNames(problemExample));
        assertThat(problem.get("known")).isEqualTo(problemExample.get("known"));
        assertThat(problem.get("detail")).isEqualTo(problemExample.get("detail"));
        assertThat(problem.get("code")).isEqualTo(problemExample.get("code"));
    }

    // ---- storage of the new columns

    private UUID clientA() {
        return jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, ApiTestKeys.CLIENT_1);
    }

    private UUID storeResult(String productType, List<SourceLink> links, List<RiskAdjustment> adjustments) {
        TrademarkRuleset ruleset = rulesets.ruleset();
        TrademarkCatalogRuntime.Serving serving = runtime.serving("ETSY");
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        TrademarkCheckStore.Identity identity = new TrademarkCheckStore.Identity(clientA(), "ETSY", ruleset.packId(), hash,
                rulesets.rulesetVersionId("ETSY"), hash);
        Evidence evidence = new Evidence("title", 0, 6, "Zorvex", "zorvex", "Zorvex", MatchType.EXACT, 1.0,
                serving.scanner().ruleset().version(), "catalog_exact", false, List.of(), List.of("uspto:12345678"),
                adjustments, links);
        TrademarkResult result = new TrademarkResult(hash, ruleset.version(), "catalog-test", RiskLevel.MEDIUM,
                Completeness.COMPLETE,
                List.of(new TrademarkFinding("catalog_exact", RiskLevel.MEDIUM, "message", "suggestion", evidence)),
                List.of(), ruleset.disclaimer());
        return transactions.execute(status -> store.insert(identity, serving.catalogVersionId(), result, productType));
    }

    @Test
    void sourceLinksAndAdjustmentsAreStoredWithTheFindingAndReadBackAsTheyWere() {
        List<SourceLink> links = List.of(new SourceLink("uspto:12345678", "USPTO TSDR",
                "https://tsdr.uspto.gov/#caseNumber=12345678&caseSearchType=US_APPLICATION&caseType=DEFAULT&searchType=statusSearch"));
        List<RiskAdjustment> adjustments = List.of(
                new RiskAdjustment(AdjustmentReason.OUTSIDE_PRODUCT_CLASSES, RiskLevel.HIGH, RiskLevel.MEDIUM, "ghi chú \"một\""),
                new RiskAdjustment(AdjustmentReason.COMMON_WORD, RiskLevel.HIGH, RiskLevel.LOW, "ghi chú hai"));

        UUID id = storeResult("mug", links, adjustments);

        TrademarkCheckResult read = store.find(clientA(), id).orElseThrow();
        assertThat(read.productType()).isEqualTo("mug");
        TrademarkCheckResult.FindingView finding = read.findings().get(0);
        assertThat(finding.sourceLinks()).containsExactly(new TrademarkCheckResult.SourceLinkView("uspto:12345678",
                "USPTO TSDR", links.get(0).url()));
        assertThat(finding.riskAdjustments()).containsExactly(
                new TrademarkCheckResult.RiskAdjustmentView("outside_product_classes", RiskLevel.HIGH, RiskLevel.MEDIUM,
                        "ghi chú \"một\""),
                new TrademarkCheckResult.RiskAdjustmentView("common_word", RiskLevel.HIGH, RiskLevel.LOW, "ghi chú hai"));
        assertThat(finding.sourceIds()).containsExactly("uspto:12345678");
    }

    @Test
    void aResultStoredBeforeTheColumnsExistedReadsAsNoProductTypeNoLinkAndNoAdjustment() {
        UUID id = storeResult("mug", List.of(new SourceLink("uspto:12345678", "USPTO TSDR", "https://tsdr.uspto.gov/x")),
                List.of(new RiskAdjustment(AdjustmentReason.COMMON_WORD, RiskLevel.HIGH, RiskLevel.LOW, "note")));
        // what V28 leaves in a row that existed before it
        jdbc.update("UPDATE trademark_check SET product_type = NULL WHERE id = ?", id);
        jdbc.update("UPDATE trademark_check_finding SET risk_adjustments = DEFAULT, source_links = DEFAULT WHERE check_id = ?", id);

        TrademarkCheckResult read = store.find(clientA(), id).orElseThrow();

        assertThat(read.productType()).isNull();
        assertThat(read.findings().get(0).sourceLinks()).isEmpty();
        assertThat(read.findings().get(0).riskAdjustments()).isEmpty();
    }

    @Test
    void theDatabaseRefusesAProductTypeThatIsNotAKeyAndJsonThatIsNotAList() {
        UUID id = storeResult(null, List.of(), List.of());

        assertThatThrownBy(() -> jdbc.update("UPDATE trademark_check SET product_type = 'Bad Type' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trademark_check_finding SET source_links = '{}'::jsonb WHERE check_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trademark_check_finding SET risk_adjustments = '\"x\"'::jsonb WHERE check_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
