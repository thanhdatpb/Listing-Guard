package com.thanhdatpb.listingguard.api.policy;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.core.listing.Listing;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
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
    private AnalyzerPolicyRetrieval retrieval;

    @BeforeEach
    void resetCorpus() {
        jdbc.update("DELETE FROM policy_document");
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
