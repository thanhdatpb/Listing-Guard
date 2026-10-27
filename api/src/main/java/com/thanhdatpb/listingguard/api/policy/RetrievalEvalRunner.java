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
import java.util.List;
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

    public RetrievalEvalRunner(PolicyRetriever retriever, EmbeddingProvider embeddings, JdbcTemplate jdbc,
                               @Value("${listing-guard.eval.dir:eval/retrieval}") String evalDir) {
        this.retriever = retriever;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.evalDir = evalDir;
    }

    @Override
    public void run(String... args) {
        Path dir = Path.of(evalDir);
        EvalSet set = EvalSet.load(dir.resolve("queries.yaml"));

        List<EvalReportWriter.CorpusRow> corpus = jdbc.query("""
                        SELECT d.source_file, d.title, d.version, d.source_hash, count(c.id) AS chunks
                        FROM policy_document d LEFT JOIN policy_chunk c ON c.document_id = d.id
                        WHERE d.marketplace_code = ?
                        GROUP BY d.id ORDER BY d.source_file
                        """,
                (rs, row) -> new EvalReportWriter.CorpusRow(rs.getString("source_file"), rs.getString("title"),
                        rs.getString("version"), rs.getString("source_hash"), rs.getInt("chunks")),
                set.marketplace());
        if (corpus.isEmpty()) {
            throw new IllegalStateException("No ingested documents for " + set.marketplace() + "; run the ingest job first");
        }

        RetrievalEvaluator.Evaluation evaluation = new RetrievalEvaluator().run(set, retriever);
        String markdown = EvalReportWriter.render(evaluation, new EvalReportWriter.RunInfo(Instant.now(), embeddings.modelId(), corpus));
        try {
            Files.writeString(dir.resolve("results.md"), markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write results.md", e);
        }
        evaluation.groups().forEach(group -> log.info("{}: recall@{}={} mrr={} ({} queries)", group.language(),
                RetrievalEvaluator.RECALL_K, group.recallAtK(), group.mrr(), group.queries()));
        log.info("Wrote {}", dir.resolve("results.md").toAbsolutePath());
    }
}
