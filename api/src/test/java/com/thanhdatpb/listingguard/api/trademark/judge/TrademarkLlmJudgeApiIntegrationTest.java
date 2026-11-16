package com.thanhdatpb.listingguard.api.trademark.judge;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The judge inside {@code POST /api/v1/trademark-checks} (ADR-036) on a stub provider: a MEDIUM catalog finding is lowered and kept,
 * the result is stored and read back the same, a second identical request is a cache hit (no second model call), and a failing
 * provider leaves the rules' finding and an INCOMPLETE result that is never cached.
 */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "listing-guard.llm.enabled=true",
        "listing-guard.llm.judge.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, TrademarkLlmJudgeApiIntegrationTest.StubLlm.class})
class TrademarkLlmJudgeApiIntegrationTest {

    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile String answer;
    static volatile RuntimeException failure;

    @TestConfiguration(proxyBeanMethods = false)
    static class StubLlm {

        @Bean
        LlmClient llmClient() {
            LlmProvider provider = new LlmProvider() {
                @Override
                public String modelId() {
                    return "stub:judge-1";
                }

                @Override
                public LlmResponse complete(LlmRequest request) {
                    CALLS.incrementAndGet();
                    if (failure != null) {
                        throw failure;
                    }
                    return new LlmResponse(answer, 40, 12, "stop");
                }
            };
            Map<String, Long> used = new HashMap<>();
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
                    return used;
                }
            };
            return new LlmClient(List.of(new LlmClient.Endpoint("stub", provider, 1000, 1000, true, false)), store,
                    new LlmCache(100, Duration.ofHours(1), Clock.systemUTC()), Clock.systemUTC());
        }
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TrademarkRulesets rulesets;

    @BeforeEach
    void reset() {
        CALLS.set(0);
        failure = null;
        answer = "{\"verdict\":\"likely_descriptive\",\"reason\":\"Quillmark ở đây chỉ là tên màu.\"}";
    }

    /** Quillmark has Nice class 35 only, so an apparel check lowers its exact match from the rule level (ADR-009). */
    private ObjectNode request(String description) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", "Quillmark " + unique());
        listing.put("description", description);
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.put("product_type", "apparel");
        body.set("listing", listing);
        return body;
    }

    private JsonNode send(JsonNode body) throws Exception {
        String response = mvc.perform(post("/api/v1/trademark-checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private JsonNode read(String id) throws Exception {
        String response = mvc.perform(get("/api/v1/trademark-checks/" + id).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private static JsonNode exact(JsonNode result) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals("catalog_exact")) {
                return finding;
            }
        }
        throw new AssertionError("no catalog_exact finding in " + result);
    }

    private boolean mediumAfterTheProductType() {
        RiskLevel base = rulesets.ruleset().catalogExact().risk();
        RiskLevel lowered = RiskLevel.values()[Math.max(0, base.ordinal() - rulesets.ruleset().productClasses().downgradeLevels())];
        return lowered == RiskLevel.MEDIUM;
    }

    @Test
    void aDescriptiveVerdictLowersTheFindingKeepsItAndIsStoredAndCached() throws Exception {
        assumeThat(mediumAfterTheProductType()).as("the ruleset gives a MEDIUM finding for Quillmark in apparel").isTrue();
        RiskLevel base = rulesets.ruleset().catalogExact().risk();
        ObjectNode body = request("Plain description " + unique());

        JsonNode result = send(body);

        JsonNode finding = exact(result);
        assertThat(finding.get("risk").asText()).isEqualTo("LOW");
        JsonNode adjustments = finding.get("riskAdjustments");
        JsonNode llm = adjustments.get(adjustments.size() - 1);
        assertThat(llm.get("reason").asText()).isEqualTo("llm_descriptive");
        assertThat(llm.get("from").asText()).isEqualTo(base.name());
        assertThat(llm.get("to").asText()).isEqualTo("LOW");
        assertThat(llm.get("note").asText()).startsWith("LLM: có thể là từ mô tả.").contains("tên màu");
        assertThat(result.get("riskLevel").asText()).isEqualTo("LOW");
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(CALLS.get()).isEqualTo(1);

        assertThat(read(result.get("checkId").asText()).get("findings")).isEqualTo(result.get("findings"));

        JsonNode again = send(body);
        assertThat(again.get("cached").asBoolean()).isTrue();
        assertThat(again.get("checkId")).isEqualTo(result.get("checkId"));
        assertThat(CALLS.get()).as("a cached check makes no model call").isEqualTo(1);
    }

    @Test
    void aWrongJsonAnswerChangesNothingAndStaysComplete() throws Exception {
        assumeThat(mediumAfterTheProductType()).isTrue();
        answer = "```json {\"verdict\":\"likely_descriptive\"} ```";

        JsonNode result = send(request("Plain description " + unique()));

        assertThat(exact(result).get("risk").asText()).isEqualTo("MEDIUM");
        assertThat(exact(result).get("riskAdjustments")).noneMatch(a -> a.get("reason").asText().equals("llm_descriptive"));
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void aFailingProviderLeavesTheRulesAndAnIncompleteResultThatIsNeverCached() throws Exception {
        assumeThat(mediumAfterTheProductType()).isTrue();
        failure = new LlmException(LlmException.Kind.TIMEOUT, "timeout");
        ObjectNode body = request("Plain description " + unique());

        JsonNode first = send(body);

        assertThat(exact(first).get("risk").asText()).isEqualTo("MEDIUM");
        assertThat(first.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(first.get("skippedRuleIds")).hasSize(1);
        assertThat(first.get("skippedRuleIds").get(0).asText()).isEqualTo("llm_context_judge");
        assertThat(first.get("riskLevel").asText()).isEqualTo("MEDIUM");

        failure = null;
        JsonNode second = send(body);
        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(exact(second).get("risk").asText()).isEqualTo("LOW");
    }
}
