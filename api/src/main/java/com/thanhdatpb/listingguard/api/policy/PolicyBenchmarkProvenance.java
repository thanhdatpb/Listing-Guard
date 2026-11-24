package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.eval.EvalReportWriter;
import com.thanhdatpb.listingguard.rag.eval.EvalSet;
import com.thanhdatpb.listingguard.rag.eval.RetrievalEvaluator;
import com.thanhdatpb.listingguard.rag.eval.RetrievalEvaluator.Evaluation;
import com.thanhdatpb.listingguard.rag.eval.RetrievalEvaluator.QueryResult;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

final class PolicyBenchmarkProvenance {
    private static final int STALE_AFTER_DAYS = 90;
    private final Map<String, Document> documents = new HashMap<>();
    private final List<Document> corpus;
    private final LocalDate asOf;

    PolicyBenchmarkProvenance(JdbcTemplate jdbc, EvalSet set, LocalDate asOf) {
        this.asOf = asOf;
        corpus = jdbc.query("""
                SELECT d.source_file, d.title, d.version, d.source_hash, d.url, d.fetched_at, count(c.id) AS chunks
                FROM policy_document d LEFT JOIN policy_chunk c ON c.document_id = d.id
                WHERE d.marketplace_code = ?
                GROUP BY d.id ORDER BY d.source_file
                """, (rs, row) -> new Document(new EvalReportWriter.CorpusRow(rs.getString("source_file"),
                        rs.getString("title"), rs.getString("version"), rs.getString("source_hash"), rs.getInt("chunks")),
                        rs.getString("url"), rs.getObject("fetched_at", LocalDate.class)), set.marketplace());
        for (Document document : corpus) {
            var row = document.row();
            if (row.title().isBlank() || row.version().isBlank() || !row.sourceHash().matches("[0-9a-f]{64}")
                    || document.fetchedAt().isAfter(asOf) || !validUrl(document.url())) {
                throw new IllegalStateException("Invalid policy provenance for " + row.file());
            }
            documents.put(row.file(), document);
        }
        for (EvalSet.Query query : set.queries()) {
            for (EvalSet.Expected expected : query.expected()) {
                Document document = documents.get(expected.file());
                if (document == null || document.row().chunks() == 0) {
                    throw new IllegalStateException("Missing labelled policy source: " + expected.file());
                }
            }
        }
    }

    void appendCorpus(StringBuilder report) {
        report.append("## Source provenance\n\n")
                .append("Age uses the captured date. A capture older than 90 days is marked stale for this benchmark. ")
                .append("Effective dates and precise retrieval timestamps are unavailable.\n\n")
                .append("| File | Title | URL | Version | SHA-256 | Captured date | Stale |\n|---|---|---|---|---|---|---|\n");
        for (Document document : corpus) {
            var row = document.row();
            report.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    EvalReportWriter.cell(row.file()), EvalReportWriter.cell(row.title()), EvalReportWriter.cell(document.url()),
                    EvalReportWriter.cell(row.version()), row.sourceHash(), document.fetchedAt(), stale(document)));
        }
    }

    void appendMetrics(StringBuilder report, Evaluation evaluation) {
        report.append("| Language | No hits@10 | No relevant hits@10 | Expected source/version coverage@5 | Stale returned hits@5 |\n")
                .append("|---|---|---|---|---|\n");
        for (var group : evaluation.groups()) {
            List<QueryResult> results = evaluation.results().stream().filter(row -> row.language().equals(group.language())).toList();
            Set<String> expected = new HashSet<>();
            Set<String> covered = new HashSet<>();
            long returned = 0;
            long stale = 0;
            for (QueryResult result : results) {
                result.query().expected().forEach(label -> expected.add(label.file()));
                for (RetrievedChunk hit : result.top()) {
                    Document document = documents.get(hit.sourceFile());
                    if (document == null || !hit.marketplace().equals(evaluation.marketplace())
                            || !hit.version().equals(document.row().version()) || !hit.document().equals(document.row().title())
                            || !hit.url().split("#", 2)[0].equals(document.url().split("#", 2)[0])) {
                        throw new IllegalStateException("Retrieved chunk does not match the corpus provenance: " + hit.chunkId());
                    }
                }
                for (RetrievedChunk hit : result.top().stream().limit(RetrievalEvaluator.RECALL_K).toList()) {
                    returned++;
                    if (stale(documents.get(hit.sourceFile()))) {
                        stale++;
                    }
                    if (result.query().isRelevant(hit)) {
                        covered.add(hit.sourceFile());
                    }
                }
            }
            report.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n", group.language(),
                    rate(results.stream().filter(result -> result.top().isEmpty()).count(), results.size()),
                    rate(results.stream().filter(result -> result.firstHitRank().isEmpty()).count(), results.size()),
                    rate(covered.size(), expected.size()), rate(stale, returned)));
        }
    }

    private boolean stale(Document document) {
        return document.fetchedAt().isBefore(asOf.minusDays(STALE_AFTER_DAYS));
    }

    private static boolean validUrl(String value) {
        try {
            URI url = URI.create(value);
            return "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String rate(long count, long total) {
        return total == 0 ? "n/a (0/0)" : String.format(Locale.ROOT, "%.3f (%d/%d)", (double) count / total, count, total);
    }

    private record Document(EvalReportWriter.CorpusRow row, String url, LocalDate fetchedAt) { }
}
