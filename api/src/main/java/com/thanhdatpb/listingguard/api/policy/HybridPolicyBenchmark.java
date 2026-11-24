package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Experimental eval-only ranking. Production search continues to use cosine similarity. */
final class HybridPolicyBenchmark {
    private static final int CANDIDATES = 30;
    private static final int RRF_CONSTANT = 60;

    private final JdbcTemplate jdbc;

    HybridPolicyBenchmark(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RetrievedChunk> search(String marketplace, String query, int topK, PolicyRetriever vector) {
        List<RetrievedChunk> semantic = vector.search(marketplace, query, CANDIDATES);
        List<RetrievedChunk> lexical = jdbc.query("""
                SELECT c.id, c.marketplace_code, d.title, d.source_file, c.section_path, c.url, d.version, c.content,
                       ts_rank(search.tokens, terms.query) AS rank
                FROM policy_chunk c JOIN policy_document d ON d.id = c.document_id
                CROSS JOIN plainto_tsquery('simple', ?) AS terms(query)
                CROSS JOIN LATERAL (SELECT to_tsvector('simple', c.section_path || ' ' || c.content) AS tokens) search
                WHERE c.marketplace_code = ? AND search.tokens @@ terms.query
                ORDER BY rank DESC, c.id
                LIMIT ?
                """, (rs, row) -> new RetrievedChunk(rs.getLong("id"), rs.getDouble("rank"),
                        rs.getString("marketplace_code"), rs.getString("title"), rs.getString("source_file"),
                        rs.getString("section_path"), rs.getString("url"), rs.getString("version"), rs.getString("content")),
                query, marketplace, CANDIDATES);
        Map<Long, Candidate> combined = new HashMap<>();
        addRanks(semantic, combined);
        addRanks(lexical, combined);
        return combined.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingLong(candidate -> candidate.chunk().chunkId()))
                .limit(topK)
                .map(candidate -> {
                    RetrievedChunk chunk = candidate.chunk();
                    return new RetrievedChunk(chunk.chunkId(), candidate.score(), chunk.marketplace(), chunk.document(),
                            chunk.sourceFile(), chunk.sectionPath(), chunk.url(), chunk.version(), chunk.content());
                }).toList();
    }

    private static void addRanks(List<RetrievedChunk> results, Map<Long, Candidate> combined) {
        for (int index = 0; index < results.size(); index++) {
            RetrievedChunk chunk = results.get(index);
            Candidate contribution = new Candidate(chunk, 1.0 / (RRF_CONSTANT + index + 1));
            combined.merge(chunk.chunkId(), contribution,
                    (first, second) -> new Candidate(first.chunk(), first.score() + second.score()));
        }
    }

    private record Candidate(RetrievedChunk chunk, double score) { }
}
