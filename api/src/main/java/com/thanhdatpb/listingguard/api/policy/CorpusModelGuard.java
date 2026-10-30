package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.DisabledEmbeddingProvider;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to search while any stored document was embedded with another model than
 * {@link EmbeddingProvider#modelId()} (ADR-012, item 4). Between changing the model setting and finishing the
 * next ingest, a query would be embedded by the new model and compared with the old vectors. Checking all
 * documents, not only those of the searched marketplace, keeps the rule simple: one corpus, one model.
 */
@Component
public class CorpusModelGuard {

    private static final int EXAMPLE_LIMIT = 5;

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddings;

    public CorpusModelGuard(JdbcTemplate jdbc, EmbeddingProvider embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    /**
     * @throws com.thanhdatpb.listingguard.rag.embedding.EmbeddingNotConfiguredException if no provider is selected
     * @throws CorpusModelMismatchException if a stored document was embedded with a different model
     */
    public void verify() {
        DisabledEmbeddingProvider.requireConfigured(embeddings);
        String current = embeddings.modelId();
        List<String> examples = new ArrayList<>();
        Set<String> storedModels = new LinkedHashSet<>();
        int[] mismatched = {0};
        jdbc.query("SELECT marketplace_code, source_file, pipeline_version FROM policy_document ORDER BY id", rs -> {
            String pipelineVersion = rs.getString("pipeline_version");
            if (!PolicyIngestService.embeddedWith(pipelineVersion, current)) {
                mismatched[0]++;
                storedModels.add(modelOf(pipelineVersion));
                if (examples.size() < EXAMPLE_LIMIT) {
                    examples.add(rs.getString("marketplace_code") + "/" + rs.getString("source_file"));
                }
            }
        });
        if (mismatched[0] > 0) {
            throw new CorpusModelMismatchException("The policy corpus was embedded with a different model than the one "
                    + "configured (configured: " + current + "; stored: " + String.join(", ", storedModels) + "; "
                    + mismatched[0] + " document(s), for example " + String.join(", ", examples) + "). Run the ingest job "
                    + "again to re-embed the corpus, or configure the model it was ingested with.");
        }
    }

    private static String modelOf(String pipelineVersion) {
        String[] parts = pipelineVersion.split("\\|", 3);
        return parts.length == 3 ? parts[2] : pipelineVersion;
    }
}
