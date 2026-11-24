package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import com.thanhdatpb.listingguard.rag.eval.EvalReportWriter;
import com.thanhdatpb.listingguard.rag.eval.EvalSet;
import com.thanhdatpb.listingguard.rag.eval.RetrievalEvaluator;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=eval-retrieval} measures retrieval on the ingested corpus
 * with {@code queries.yaml} and writes {@code results.md} next to it. Needs the real embedding backend and a
 * corpus that was ingested with it.
 */
@Component
@Profile("eval-retrieval")
public class RetrievalEvalRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalRunner.class);

    private final PolicyRetriever retriever;
    private final EmbeddingProvider embeddings;
    private final JdbcTemplate jdbc;
    private final String evalDir;
    private final CorpusModelGuard corpusGuard;

    public RetrievalEvalRunner(PolicyRetriever retriever, EmbeddingProvider embeddings, JdbcTemplate jdbc,
                               @Value("${listing-guard.eval.dir:eval/retrieval}") String evalDir,
                               CorpusModelGuard corpusGuard) {
        this.retriever = retriever;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.evalDir = evalDir;
        this.corpusGuard = corpusGuard;
    }

    @Override
    public void run(String... args) {
        // numbers measured against a corpus of another model would be wrong, so the job fails before any query
        corpusGuard.verify();
        Path dir = Path.of(evalDir);
        EvalSet set = EvalSet.load(dir.resolve("queries.yaml"));

        String markdown = benchmark(set, retriever, jdbc, Instant.now(), embeddings.modelId());
        try {
            Files.writeString(dir.resolve("results.md"), markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write results.md", e);
        }
        log.info("Wrote {}", dir.resolve("results.md").toAbsolutePath());
    }

    static String benchmark(EvalSet set, PolicyRetriever vector, JdbcTemplate jdbc, Instant asOf, String model) {
        PolicyBenchmarkProvenance provenance = new PolicyBenchmarkProvenance(jdbc, set, asOf.atOffset(ZoneOffset.UTC).toLocalDate());
        HybridPolicyBenchmark hybrid = new HybridPolicyBenchmark(jdbc);
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        var baseline = evaluator.run(set, vector);
        var combined = evaluator.run(set, (marketplace, query, topK) -> hybrid.search(marketplace, query, topK, vector));

        StringBuilder report = new StringBuilder("# Policy retrieval benchmark\n\n");
        report.append("- Evaluated at: ").append(asOf).append(" (UTC)\n")
                .append("- Marketplace: ").append(EvalReportWriter.cell(set.marketplace())).append('\n')
                .append("- Embedding model: `").append(EvalReportWriter.cell(model)).append("`\n\n");
        report.append("Ranking metrics measure retrieval against section labels. They do not verify policy correctness or applicability.\n\n");
        provenance.appendCorpus(report);
        report.append("\n## Vector baseline\n\n");
        provenance.appendMetrics(report, baseline);
        report.append('\n');
        EvalReportWriter.appendRankings(report, baseline);
        report.append("\n## Hybrid (PostgreSQL keyword + pgvector, reciprocal rank fusion)\n\n");
        provenance.appendMetrics(report, combined);
        report.append('\n');
        EvalReportWriter.appendRankings(report, combined);
        return report.toString();
    }
}
