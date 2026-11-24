package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.rag.analysis.PolicyAnalyzer;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.HashingEmbeddingProvider;
import java.net.http.HttpTimeoutException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "listing-guard.analyzer.enabled=true", "listing-guard.analyzer.retention-days=2",
        "listing-guard.analyzer.daily-attempt-limit=10", "listing-guard.plans.enabled=true",
        "listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PlanTestClockConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class AnalyzerPolicyRetrievalIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
    private static final String PRIVATE = "synthetic-private-marker";
    private static final String TOPIC = "production partner disclosure";
    private static final Listing LISTING = new Listing(TOPIC, PRIVATE, List.of("design"), null, null, List.of(),
            List.of("https://example.test/private"), null, null, null);
    private static final HashingEmbeddingProvider FIXTURE_EMBEDDINGS = new HashingEmbeddingProvider(768);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PgVectorPolicyRetriever pgvector;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @Autowired private PolicyProperties policyProperties;
    @Autowired private PolicyIngestService ingest;
    @MockitoSpyBean private EmbeddingProvider embeddings;
    @MockitoBean(name = "policyLlmProvider") private LlmProvider policyProvider;
    @MockitoBean private JavaMailSender mail;
    @Autowired private MutableClock appClock;
    private static final JsonMapper JSON = new JsonMapper();
    private static final String API = "/api/v1/policy-checks";
    private AnalyzerPolicyRetrieval retrieval;

    @BeforeEach
    void resetCorpus() {
        appClock.set(CLOCK.instant());
        jdbc.update("DELETE FROM policy_document");
        jdbc.update("DELETE FROM policy_check");
        jdbc.update("DELETE FROM llm_usage WHERE provider_id = 'policy-analyzer'");
        jdbc.update("DELETE FROM client_usage");
        jdbc.update("UPDATE client SET plan_code = 'PRO' WHERE name = 'client-a'");
        when(policyProvider.modelId()).thenReturn("fake:policy-v1");
        retrieval = new AnalyzerPolicyRetrieval(jdbc, pgvector, embeddings, CLOCK, transactions, policyProperties);
    }

    @Test
    void returnsOnlyApprovedEvidenceFromOneBatchWithoutDuplicatesOrOverBudget(CapturedOutput output) {
        long oversized = seedCorpus(true);
        var result = retrieval.retrieve("ETSY", "vi", LISTING);

        assertThat(result.status()).isEqualTo(AnalyzerPolicyRetrieval.Status.READY);
        assertThat(result.descriptor().profileHash()).isEqualTo(retrieval.profile().hash());
        assertThat(result.descriptor().corpusFingerprint()).matches("[0-9a-f]{64}");
        assertThat(result.evidence()).isNotEmpty().hasSizeLessThanOrEqualTo(12);
        assertThat(result.evidence().stream().map(item -> item.chunk().chunkId())).doesNotHaveDuplicates().doesNotContain(oversized);
        assertThat(result.evidence()).allSatisfy(item -> {
            assertThat(item.chunk().marketplace()).isEqualTo("ETSY");
            assertThat(item.chunk().url()).startsWith("https://www.etsy.com/").doesNotContain("unapproved");
            assertThat(item.sourceLocale()).isEqualTo("en");
            assertThat(item.sourceHash()).matches("[0-9a-f]{64}");
        });
        assertThat(result.evidence().stream().mapToInt(item -> item.chunk().content().length()).sum()).isLessThanOrEqualTo(12_000);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queries = ArgumentCaptor.forClass(List.class);
        verify(embeddings, times(1)).embed(queries.capture(), eq(EmbeddingProvider.Kind.QUERY));
        assertThat(queries.getValue()).hasSize(6).contains(PRIVATE);
        assertThat(output.getAll()).doesNotContain(PRIVATE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_chunk WHERE content LIKE ?", Integer.class, "%" + PRIVATE + "%")).isZero();
        var groups = pgvector.searchAll("ETSY", List.of(TOPIC, "listing design"), 3);
        assertThat(groups).hasSize(2).allSatisfy(group -> assertThat(group).hasSize(3));
    }

    @Test
    void refusesUnsupportedMarketplacesLocalesAndMissingOrEmptySourcesBeforeEmbedding() {
        assertThat(retrieval.retrieve("AMAZON", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.UNSUPPORTED_MARKETPLACE);
        assertThat(retrieval.retrieve("ETSY", "fr", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.UNSUPPORTED_LOCALE);
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.MISSING_CORPUS);
        seedCorpus(false);
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.EMPTY_CORPUS);
        verify(embeddings, never()).embed(anyList(), eq(EmbeddingProvider.Kind.QUERY));
    }

    @Test
    void refusesStaleUnapprovedAndInvalidSourceMetadata() {
        seedCorpus(true);
        jdbc.update("UPDATE policy_document SET fetched_at = '2026-07-01'");
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.STALE_CORPUS);
        jdbc.update("UPDATE policy_document SET fetched_at = '2026-11-21', version = '2026-11-01'");
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.POLICY_VERSION_MISMATCH);
        jdbc.update("UPDATE policy_document SET source_hash='invalid'");
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.INVALID_PROVENANCE);
    }

    @Test
    void rejectsAStaleChunkingPipelineEvenWhenTheEmbeddingModelMatches() {
        seedCorpus(true);
        jdbc.update("UPDATE policy_document SET pipeline_version = 'chunker-0|100/50|hashing:768' WHERE marketplace_code = 'ETSY'");
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.CORPUS_PIPELINE_MISMATCH);
        verify(embeddings, never()).embed(anyList(), eq(EmbeddingProvider.Kind.QUERY));
    }

    @Test
    void classifiesEmbeddingErrorsAndLeavesDeterministicChecksUsable(CapturedOutput output) throws Exception {
        seedCorpus(true);
        doThrow(new EmbeddingException(PRIVATE, new HttpTimeoutException(PRIVATE)))
                .doThrow(new EmbeddingException(PRIVATE)).when(embeddings).embed(anyList(), eq(EmbeddingProvider.Kind.QUERY));
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.RETRIEVAL_TIMEOUT);
        assertThat(retrieval.retrieve("ETSY", "en", LISTING).status()).isEqualTo(AnalyzerPolicyRetrieval.Status.EMBEDDING_FAILED);
        mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(body("Synthetic non-sensitive mug"))).andExpect(status().isOk());
        verify(embeddings, times(2)).embed(anyList(), eq(EmbeddingProvider.Kind.QUERY));
        assertThat(output.getAll()).doesNotContain(PRIVATE);
    }

    @Test
    void policyAnalysisUsesTheActualRetrievedSnapshotAndNeverLogsModelOrListingText(CapturedOutput output) {
        seedCorpus(true);
        LlmProvider fake = new LlmProvider() {
            @Override
            public String modelId() {
                return "fake:policy-v1";
            }

            @Override
            public LlmResponse complete(LlmRequest request) {
                return completion(request, false);
            }
        };
        var service = new PolicyAnalysisService(retrieval, fake, CLOCK);
        var result = service.analyze("ETSY", "vi", LISTING);
        assertThat(result.status()).isEqualTo(PolicyAnalyzer.Status.REVIEW_REQUIRED);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.message()).startsWith("Cần đối chiếu");
            assertThat(finding.citation().quote()).isEqualTo(TOPIC);
            assertThat(finding.citation().evidence().sourceHash()).isEqualTo("a".repeat(64));
        });
        assertThat(output.getAll()).doesNotContain(PRIVATE, "synthetic-private-model-message");
        assertThat(result.toString()).doesNotContain(PRIVATE, "synthetic-private-model-message");
    }

    @Test
    void apiEnforcesTenantQuotaAndReturnsOnlyVerifiedEvidenceWithCacheAndExpiry(CapturedOutput output) throws Exception {
        seedCorpus(true);
        when(policyProvider.complete(any())).thenAnswer(call -> completion(call.getArgument(0), false));
        String body = body("Synthetic " + PRIVATE);
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marketplace\":\"ETSY\",\"listing\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post(API).header(HEADER, KEY_2).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        var first = mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.analysis.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.analysis.findings[0].citation.quote").value(TOPIC)).andReturn();
        String id = JSON.readTree(first.getResponse().getContentAsByteArray()).path("checkId").asText();
        mvc.perform(get(API + "/" + id).header(HEADER, KEY_2)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/" + id).header(HEADER, KEY_1)).andExpect(status().isOk());
        mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkId").value(id)).andExpect(jsonPath("$.cached").value(true));
        verify(policyProvider, times(1)).complete(any());
        verify(embeddings, times(1)).embed(anyList(), eq(EmbeddingProvider.Kind.QUERY));
        jdbc.update("UPDATE plan SET monthly_quota = 1 WHERE code='PRO'");
        try {
            mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body("Different listing")))
                    .andExpect(status().isTooManyRequests());
        } finally {
            jdbc.update("UPDATE plan SET monthly_quota = 6000 WHERE code='PRO'");
        }
        assertThat(jdbc.queryForObject("SELECT result::text FROM policy_check", String.class))
                .doesNotContain(PRIVATE, "synthetic-private-model-message");
        assertThat(output.getAll()).doesNotContain(PRIVATE, "synthetic-private-model-message");
        appClock.set(CLOCK.instant().plusSeconds(3 * 86_400));
        mvc.perform(get(API + "/" + id).header(HEADER, KEY_1)).andExpect(status().isNotFound());
    }

    @Test
    void apiDoesNotCacheMissingCorpusTimeoutOrBadCitations() throws Exception {
        String body = body("Synthetic policy check");
        mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.analysis.reason").value("NO_POLICY_CONTEXT"));
        verify(policyProvider, never()).complete(any());
        seedCorpus(true);
        when(policyProvider.complete(any())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, PRIVATE))
                .thenAnswer(call -> completion(call.getArgument(0), true));
        mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.reason").value("LLM_TIMEOUT"));
        mvc.perform(post(API).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.analysis.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.analysis.findings").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_check WHERE cache_version IS NULL", Integer.class)).isEqualTo(3);
    }

    @Test
    void consoleUsesSessionAndCsrfAndDisplaysOptInInBothLanguagesWithoutCallingProviderOnRecheck() throws Exception {
        seedCorpus(true);
        when(policyProvider.complete(any())).thenAnswer(call -> completion(call.getArgument(0), false));
        UUID client = jdbc.queryForObject("SELECT id FROM client WHERE name='client-a'", UUID.class);
        ConsoleUser user = new ConsoleUser("policy-workbench@example.test", client);
        jdbc.update("INSERT INTO account (email, client_id) VALUES (?, ?) ON CONFLICT (email) DO NOTHING", user.email(), client);
        String endpoint = "/console/check/edit/policy-checks";
        mvc.perform(post(endpoint).with(signedIn(user)).contentType(MediaType.APPLICATION_JSON).content(body("Mug")))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("Mug")))
                .andExpect(status().is3xxRedirection());
        for (String language : List.of("en", "vi")) {
            String html = mvc.perform(post("/console/check/edit").with(signedIn(user)).with(csrf())
                    .param("marketplace", "ETSY").cookie(new jakarta.servlet.http.Cookie("lg_lang", language))
                    .param("before", "{\"title\":\"Synthetic original title\"}")
                    .param("after", "{\"title\":\"x\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).contains("data-policy-explain", "role=\"status\"",
                    language.equals("vi") ? "Giải thích cảnh báo bằng AI" : "Explain this warning with AI");
        }
        verify(policyProvider, never()).complete(any());
        mvc.perform(post(endpoint).with(signedIn(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("Mug")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.status").value("REVIEW_REQUIRED"));
    }

    private static LlmResponse completion(LlmRequest request, boolean badCitation) {
        try {
            var evidence = JSON.readTree(request.user()).path("evidence").path(0);
            var result = JSON.createObjectNode();
            result.putArray("findings").addObject().put("ruleId", "policy.production_partner")
                    .put("severity", "WARNING").put("field", "description").putNull("value")
                    .put("message", "synthetic-private-model-message").put("suggestion", "Model suggestion")
                    .putObject("citation").put("chunkId", badCitation ? -1 : evidence.path("chunkId").asLong())
                    .put("sourceHash", evidence.path("sourceHash").asText()).put("quote", TOPIC)
                    .put("start", 0).put("end", TOPIC.length());
            return new LlmResponse(result.toString(), 30, 15, "stop");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("Invalid request test fixture", e);
        }
    }

    private long seedCorpus(boolean chunks) {
        int ordinal = 0;
        for (var entry : retrieval.profile().settings().sources().entrySet()) {
            var source = entry.getValue();
            long id = document("ETSY", entry.getKey(), source.url(), source.version());
            if (chunks) {
                chunk(id, "ETSY", source.url(), TOPIC + " " + "reference ".repeat(320), ordinal++);
            }
        }
        if (!chunks) {
            return 0;
        }
        String unapproved = "https://www.etsy.com/unapproved/";
        chunk(document("ETSY", "unapproved.html", unapproved, "2026-11-01"), "ETSY", unapproved, TOPIC, 0);
        chunk(document("AMAZON", "unapproved.html", unapproved, "2026-11-01"), "AMAZON", unapproved, TOPIC, 0);
        String file = "seller-policy.html";
        String approved = retrieval.profile().settings().sources().get(file).url();
        Long id = jdbc.queryForObject("SELECT id FROM policy_document WHERE source_file = ?", Long.class, file);
        return chunk(id, "ETSY", approved, "x".repeat(50_000), 10);
    }

    private long document(String marketplace, String filename, String url, String version) {
        return jdbc.queryForObject("""
                INSERT INTO policy_document (marketplace_code, source_file, title, url, version, fetched_at, source_hash, pipeline_version)
                VALUES (?, ?, 'Fixture policy', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, marketplace, filename, url, version, Date.valueOf(LocalDate.of(2026, 11, 21)), "a".repeat(64), ingest.pipelineVersion());
    }

    private long chunk(long id, String marketplace, String url, String content, int ordinal) {
        String vector = PolicyRepository.vectorLiteral(FIXTURE_EMBEDDINGS.embed(TOPIC, EmbeddingProvider.Kind.DOCUMENT));
        return jdbc.queryForObject("""
                INSERT INTO policy_chunk (document_id, marketplace_code, ordinal, section_path, url, content, content_hash, embedding)
                VALUES (?, ?, ?, 'Fixture policy > Topic', ?, ?, ?, ?::vector) RETURNING id
                """, Long.class, id, marketplace, ordinal, url + "#topic", content, "b".repeat(64), vector);
    }
}
