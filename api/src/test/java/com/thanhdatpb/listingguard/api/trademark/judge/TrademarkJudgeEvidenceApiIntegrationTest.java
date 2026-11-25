package com.thanhdatpb.listingguard.api.trademark.judge;

import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The judge with the evidence prompt inside {@code POST /api/v1/trademark-checks} (ADR-051), with a stub provider, the synthetic
 * catalog and the real tables: the evidence is shown to the model, kept with the lowered finding, stored and read back; this client's
 * verified decision is shown to the model and moves the analysis version, another client's is not and does not.
 */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "listing-guard.llm.enabled=true",
        "listing-guard.llm.judge.enabled=true", "listing-guard.llm.judge.prompt-version=trademark-judge-2026-12",
        "listing-guard.verified-cases.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, TrademarkJudgeEvidenceApiIntegrationTest.StubLlm.class})
class TrademarkJudgeEvidenceApiIntegrationTest {

    static final List<LlmRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final String REASON = "Quillmark ở đây chỉ là tên màu.";

    @TestConfiguration(proxyBeanMethods = false)
    static class StubLlm {

        @Bean
        LlmClient llmClient() {
            LlmProvider provider = new LlmProvider() {
                @Override
                public String modelId() {
                    return "stub:judge-evidence-1";
                }

                @Override
                public LlmResponse complete(LlmRequest request) {
                    REQUESTS.add(request);
                    return new LlmResponse("{\"verdict\":\"likely_descriptive\",\"reason\":\"" + REASON + "\"}", 40, 12, "stop");
                }
            };
            LlmUsageStore store = new LlmUsageStore() {
                @Override
                public boolean reserve(String providerId, LocalDate day, int limit) {
                    return true;
                }

                @Override
                public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
                }

                @Override
                public Map<String, Long> requestsOn(LocalDate day) {
                    return Map.of();
                }
            };
            return new LlmClient(List.of(new LlmClient.Endpoint("stub", provider, 1000, 1000, true, false)), store,
                    new LlmCache(100, Duration.ofHours(1), Clock.systemUTC()), Clock.systemUTC());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TrademarkRulesets rulesets;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        REQUESTS.clear();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Quillmark has Nice class 35 only, so an apparel check lowers its exact match from the rule level (ADR-009). */
    private ObjectNode request(String title) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        listing.put("description", "Plain description " + unique());
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.put("product_type", "apparel");
        body.set("listing", listing);
        return body;
    }

    private JsonNode send(TestClient client, JsonNode body) throws Exception {
        String response = mvc.perform(post("/api/v1/trademark-checks").header("X-API-Key", client.key())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private JsonNode read(TestClient client, String id) throws Exception {
        String response = mvc.perform(get("/api/v1/trademark-checks/" + id).header("X-API-Key", client.key()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private static int exactIndex(JsonNode result) {
        for (int i = 0; i < result.get("findings").size(); i++) {
            if (result.get("findings").get(i).get("ruleId").asText().equals("catalog_exact")) {
                return i;
            }
        }
        throw new AssertionError("no catalog_exact finding in " + result);
    }

    private static JsonNode lastAdjustment(JsonNode result) {
        JsonNode adjustments = result.get("findings").get(exactIndex(result)).get("riskAdjustments");
        return adjustments.get(adjustments.size() - 1);
    }

    private String lastUser() {
        return REQUESTS.getLast().user();
    }

    private boolean mediumAfterTheProductType() {
        RiskLevel base = rulesets.ruleset().catalogExact().risk();
        RiskLevel lowered = RiskLevel.values()[Math.max(0, base.ordinal() - rulesets.ruleset().productClasses().downgradeLevels())];
        return lowered == RiskLevel.MEDIUM;
    }

    /** A verified decision (ADR-044) of {@code client} for the catalog finding of a stored check, written as the review flow writes it. */
    private UUID verify(TestClient client, JsonNode check, String status) {
        UUID caseId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String reason = status.equals("RESOLVED") ? "FIX_CONFIRMED" : "NO_ACTION_REQUIRED";
        jdbc.update("""
                INSERT INTO review_case (client_id, id, pack_id, check_id, finding_position, marketplace_code, severity, status,
                    revision, reviewer_id, reason)
                VALUES (?, ?, 'trademark-risk', ?::uuid, ?, 'ETSY', 'MEDIUM', ?, 2, ?, ?)""", client.id(), caseId,
                check.get("checkId").asText(), exactIndex(check), status, actor, reason);
        jdbc.update("""
                INSERT INTO review_case_event (client_id, case_id, revision, request_id, actor_id, previous_status, status, reason,
                    occurred_at)
                VALUES (?, ?, 0, NULL, ?, NULL, 'OPEN', NULL, now()),
                       (?, ?, 1, ?, ?, 'OPEN', 'IN_REVIEW', NULL, now()),
                       (?, ?, 2, ?, ?, 'IN_REVIEW', ?, ?, now())""", client.id(), caseId, actor, client.id(), caseId,
                UUID.randomUUID(), actor, client.id(), caseId, UUID.randomUUID(), actor, status, reason);
        jdbc.update("""
                INSERT INTO verified_case (client_id, case_id, pack_id, marketplace_code, rule_id, severity, evidence_source,
                    analysis_version, ruleset_version, catalog_version, product_type, outcome, reason, decision_revision,
                    metadata_version, verified_by, verified_at, expires_at)
                VALUES (?, ?, 'trademark-risk', 'ETSY', 'catalog_exact', 'MEDIUM', 'CATALOG', ?, ?, ?, 'apparel', ?, ?, 2, 1, ?,
                    now(), now() + interval '90 days')""", client.id(), caseId, check.get("analysisVersion").asText(),
                check.get("rulesetVersion").asText(), check.get("catalogVersion").asText(), status, reason, actor);
        return caseId;
    }

    @Test
    void theEvidenceIsShownToTheModelKeptWithTheLoweredFindingStoredAndReadBack() throws Exception {
        assumeThat(mediumAfterTheProductType()).as("the ruleset gives a MEDIUM finding for Quillmark in apparel").isTrue();
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);

        JsonNode result = send(client, request("Quillmark " + unique()));

        assertThat(REQUESTS).hasSize(1);
        assertThat(lastUser()).startsWith("Evidence computed by the program").contains("- product_class_overlap: no\n")
                .contains("- verified_case: none\n").contains("- word_count: 1\n");
        assertThat(REQUESTS.getFirst().system()).contains("EVIDENCE");
        JsonNode llm = lastAdjustment(result);
        assertThat(llm.get("reason").asText()).isEqualTo("llm_descriptive");
        JsonNode basis = llm.get("judgeEvidence");
        assertThat(basis.get("promptVersion").asText()).isEqualTo("trademark-judge-2026-12");
        assertThat(basis.get("reason").asText()).isEqualTo(REASON);
        assertThat(basis.get("evidence").size()).isEqualTo(9);
        assertThat(basis.get("evidence").get("product_class_overlap").asText()).isEqualTo("no");
        assertThat(basis.get("evidence").get("verified_case").asText()).isEqualTo("none");
        assertThat(result.get("findings").get(exactIndex(result)).get("risk").asText()).isEqualTo("LOW");

        assertThat(read(client, result.get("checkId").asText()).get("findings")).isEqualTo(result.get("findings"));
        assertThat(jdbc.queryForObject("SELECT risk_adjustments::text FROM trademark_check_finding WHERE check_id = ?::uuid AND "
                + "rule_id = 'catalog_exact'", String.class, result.get("checkId").asText())).contains("judgeEvidence")
                .doesNotContain("Plain description");
    }

    @Test
    void theVerifiedDecisionOfTheSameClientIsShownAndMovesTheAnalysisVersionAndAnotherClientsIsNeitherShownNorCounted()
            throws Exception {
        assumeThat(mediumAfterTheProductType()).isTrue();
        TestClient owner = newClient(provisioning, clients, PlanCode.PRO);
        TestClient other = newClient(provisioning, clients, PlanCode.PRO);
        JsonNode first = send(owner, request("Quillmark " + unique()));
        assertThat(lastUser()).contains("- verified_case: none\n");

        UUID decided = verify(owner, first, "RESOLVED");
        ObjectNode again = request("Quillmark " + unique());
        JsonNode second = send(owner, again);
        assertThat(lastUser()).as("the owner's decision, with its age and nothing else")
                .contains("- verified_case: confirmed, 0 days ago\n").doesNotContain("FIX_CONFIRMED");
        assertThat(lastAdjustment(second).get("judgeEvidence").get("evidence").get("verified_case").asText())
                .isEqualTo("confirmed, 0 days ago");

        JsonNode foreign = send(other, again);
        assertThat(lastUser()).as("another client's finding sees no decision of the owner").contains("- verified_case: none\n");
        assertThat(foreign.get("analysisVersion").asText()).as("a client with no decision has the plain judge fingerprint")
                .isNotEqualTo(second.get("analysisVersion").asText());

        jdbc.update("UPDATE verified_case SET revoked_at = verified_at WHERE client_id = ? AND case_id = ?", owner.id(), decided);
        JsonNode third = send(owner, request("Quillmark " + unique()));
        assertThat(lastUser()).as("a revoked decision is not shown").contains("- verified_case: none\n");
        assertThat(third.get("analysisVersion").asText()).as("and the analysis version is again the plain one")
                .isEqualTo(foreign.get("analysisVersion").asText());

        UUID expired = verify(owner, second, "DISMISSED");
        jdbc.update("UPDATE verified_case SET verified_at = now() - interval '2 days', expires_at = now() - interval '1 day' "
                + "WHERE client_id = ? AND case_id = ?", owner.id(), expired);
        send(owner, request("Quillmark " + unique()));
        assertThat(lastUser()).as("an expired decision is not shown").contains("- verified_case: none\n");
    }
}
