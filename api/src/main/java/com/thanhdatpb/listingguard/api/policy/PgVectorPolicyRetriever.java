package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.util.List;
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
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddings;
    private final TransactionTemplate transactions;

    private volatile Boolean iterativeScanSupported;

    public PgVectorPolicyRetriever(JdbcTemplate jdbc, EmbeddingProvider embeddings, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.transactions = transactions;
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
        String vector = PolicyRepository.vectorLiteral(embeddings.embed(query, EmbeddingProvider.Kind.QUERY));

        return transactions.execute(status -> {
            if (iterativeScanSupported()) {
                jdbc.execute("SET LOCAL hnsw.iterative_scan = strict_order");
            }
            return jdbc.query(SEARCH_SQL, (rs, row) -> new RetrievedChunk(
                    rs.getLong("id"), rs.getDouble("score"), rs.getString("marketplace_code"), rs.getString("title"),
                    rs.getString("source_file"), rs.getString("section_path"), rs.getString("url"),
                    rs.getString("version"), rs.getString("content")), vector, code, vector, topK);
        });
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
