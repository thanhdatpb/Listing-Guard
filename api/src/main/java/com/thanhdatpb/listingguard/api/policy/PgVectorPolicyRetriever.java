package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cosine-similarity search over pgvector. The query filters on {@code marketplace_code} and ranks only the
 * rows that pass. With an HNSW index a filter is normally applied after the index scan, which can leave fewer
 * than topK rows; pgvector 0.8's iterative scan keeps scanning until topK rows pass the filter.
 */
@Component
public class PgVectorPolicyRetriever implements PolicyRetriever {

    /** Upper bound for topK: this is a retrieval step, not a listing of the corpus. */
    public static final int MAX_TOP_K = 50;

    private static final String SEARCH_SQL = """
            SELECT c.id, 1 - (c.embedding <=> ?::vector) AS score, c.marketplace_code, d.title, d.source_file,
                   c.section_path, c.url, d.version, c.content
            FROM policy_chunk c
            JOIN policy_document d ON d.id = c.document_id
            WHERE c.marketplace_code = ?
            """;
    private static final String SORT = " ORDER BY c.embedding <=> ?::vector LIMIT ?";
    private static final String BOUNDED = """
             AND c.document_id = ANY (?::bigint[]) AND octet_length(c.content) <= 48000
             AND octet_length(c.url) <= 8192 AND octet_length(c.section_path) <= 8192
            """;

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddings;
    private final TransactionTemplate transactions;
    private final CorpusModelGuard corpusGuard;

    private volatile Boolean iterativeScanSupported;

    public PgVectorPolicyRetriever(JdbcTemplate jdbc, EmbeddingProvider embeddings, TransactionTemplate transactions,
                                   CorpusModelGuard corpusGuard) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.transactions = transactions;
        this.corpusGuard = corpusGuard;
    }

    @Override
    public List<RetrievedChunk> search(String marketplace, String query, int topK) {
        if (marketplace == null || marketplace.isBlank()) {
            throw new IllegalArgumentException("marketplace must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("k must be between 1 and " + MAX_TOP_K + ", got " + topK);
        }
        String code = marketplace.trim().toUpperCase(Locale.ROOT);
        // before embedding: a query from the current model must never be ranked against vectors of another one
        corpusGuard.verify();
        String vector = PolicyRepository.vectorLiteral(embeddings.embed(query, EmbeddingProvider.Kind.QUERY));

        return transactions.execute(status -> {
            configureIterativeScan();
            return rank(code, vector, topK, null);
        });
    }

    @Override
    public List<List<RetrievedChunk>> searchAll(String marketplace, List<String> queries, int topK) {
        return searchAll(marketplace, queries, topK, null);
    }

    /** Analyzer-only source constraint, applied before ranking and before materializing result text. */
    List<List<RetrievedChunk>> searchAll(String marketplace, List<String> queries, int topK, List<Long> allowedDocuments) {
        if (marketplace == null || marketplace.isBlank() || topK < 1 || topK > MAX_TOP_K
                || queries.isEmpty() || queries.size() > 6
                || queries.stream().anyMatch(query -> query == null || query.length() > 6_000 || query.isBlank())
                || (allowedDocuments != null && (allowedDocuments.isEmpty() || allowedDocuments.size() > 16))) {
            throw new IllegalArgumentException("Invalid policy retrieval batch");
        }
        String code = marketplace.trim().toUpperCase(Locale.ROOT);
        corpusGuard.verify();
        List<float[]> vectors = embeddings.embed(queries, EmbeddingProvider.Kind.QUERY);
        if (vectors.size() != queries.size() || vectors.stream().anyMatch(v -> !validVector(v))) {
            throw new EmbeddingException("Invalid query embedding batch");
        }
        String ids = allowedDocuments == null ? null
                : allowedDocuments.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        return transactions.execute(status -> {
            configureIterativeScan();
            List<List<RetrievedChunk>> results = new ArrayList<>(vectors.size());
            for (float[] vector : vectors) {
                results.add(rank(code, PolicyRepository.vectorLiteral(vector), topK, ids));
            }
            return List.copyOf(results);
        });
    }

    private static boolean validVector(float[] vector) {
        if (vector == null || vector.length != 768) {
            return false;
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private List<RetrievedChunk> rank(String marketplace, String vector, int topK, String allowedDocuments) {
        String scope = allowedDocuments == null ? "" : BOUNDED;
        Object[] params = allowedDocuments == null ? new Object[]{vector, marketplace, vector, topK}
                : new Object[]{vector, marketplace, allowedDocuments, vector, topK};
        return jdbc.query(SEARCH_SQL + scope + SORT, (rs, row) -> new RetrievedChunk(
                rs.getLong("id"), rs.getDouble("score"), rs.getString("marketplace_code"), rs.getString("title"),
                rs.getString("source_file"), rs.getString("section_path"), rs.getString("url"),
                rs.getString("version"), rs.getString("content")), params);
    }

    private void configureIterativeScan() {
        if (iterativeScanSupported()) {
            jdbc.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        }
    }

    /** Iterative index scans exist from pgvector 0.8.0. */
    private boolean iterativeScanSupported() {
        Boolean supported = iterativeScanSupported;
        if (supported == null) {
            String version = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            supported = major > 0 || minor >= 8;
            iterativeScanSupported = supported;
        }
        return supported;
    }
}
