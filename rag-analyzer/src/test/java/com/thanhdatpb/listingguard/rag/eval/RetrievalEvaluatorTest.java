package com.thanhdatpb.listingguard.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.rag.eval.EvalReportWriter.CorpusRow;
import com.thanhdatpb.listingguard.rag.eval.EvalReportWriter.RunInfo;
import com.thanhdatpb.listingguard.rag.eval.RetrievalEvaluator.Evaluation;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalEvaluatorTest {

    private static final String EVAL = """
            marketplace: ETSY
            queries:
              - id: q-ip
                en: "english ip"
                vi: "vietnamese ip"
                expected:
                  - file: ip.html
                    section: "1. Role"
              - id: q-fees
                en: "english fees"
                vi: "vietnamese fees"
                expected:
                  - file: seller.html
                    section: "e. Selling Fees"
            """;

    private static RetrievedChunk chunk(String file, String title, String path) {
        return new RetrievedChunk(1, 0.8, "ETSY", title, file, path, "https://example.com/", "v", "text");
    }

    /** Scripted retriever: the answer depends on the question text only. */
    private static PolicyRetriever scripted(Map<String, List<RetrievedChunk>> answers, List<String> asked) {
        return (marketplace, query, topK) -> {
            asked.add(marketplace + "|" + query + "|" + topK);
            return answers.getOrDefault(query, List.of());
        };
    }

    private static Map<String, List<RetrievedChunk>> answers() {
        RetrievedChunk ip = chunk("ip.html", "IP Policy", "IP Policy > 1. Role");
        RetrievedChunk fees = chunk("seller.html", "Seller Policy", "Seller Policy > e. Selling Fees");
        RetrievedChunk noise = chunk("seller.html", "Seller Policy", "Seller Policy > a. Basics");
        return Map.of(
                "english ip", List.of(ip, noise),                 // rank 1
                "vietnamese ip", List.of(noise, noise, ip),       // rank 3
                "english fees", List.of(noise, noise, noise, noise, noise, noise, fees), // rank 7: beyond recall@5
                "vietnamese fees", List.of(noise));               // miss
    }

    @Test
    void ranksEachQueryInEachLanguageAndRetrievesTenResults() {
        List<String> asked = new ArrayList<>();

        Evaluation evaluation = new RetrievalEvaluator().run(EvalSet.parse(EVAL), scripted(answers(), asked));

        assertThat(asked).hasSize(4).allSatisfy(call -> assertThat(call).startsWith("ETSY|").endsWith("|10"));
        assertThat(evaluation.results()).extracting(r -> r.language() + ":" + r.queryId() + ":" + r.firstHitRank())
                .containsExactly(
                        "en:q-ip:OptionalInt[1]", "en:q-fees:OptionalInt[7]",
                        "vi:q-ip:OptionalInt[3]", "vi:q-fees:OptionalInt.empty");
    }

    @Test
    void reportsRecallAndMrrPerLanguageGroup() {
        Evaluation evaluation = new RetrievalEvaluator().run(EvalSet.parse(EVAL), scripted(answers(), new ArrayList<>()));

        RetrievalEvaluator.GroupMetrics en = evaluation.groups().get(0);
        RetrievalEvaluator.GroupMetrics vi = evaluation.groups().get(1);
        assertThat(en.language()).isEqualTo("en");
        assertThat(en.recallAtK()).isEqualTo(0.5);                          // only q-ip is in the top 5
        assertThat(en.mrr()).isEqualTo((1.0 + 1.0 / 7) / 2);
        assertThat(vi.language()).isEqualTo("vi");
        assertThat(vi.recallAtK()).isEqualTo(0.5);
        assertThat(vi.mrr()).isEqualTo((1.0 / 3 + 0) / 2);
    }

    @Test
    void theMarkdownReportContainsTheMeasuredNumbersAndTheMisses() {
        Evaluation evaluation = new RetrievalEvaluator().run(EvalSet.parse(EVAL), scripted(answers(), new ArrayList<>()));

        String markdown = EvalReportWriter.render(evaluation, new RunInfo(Instant.parse("2026-11-01T10:00:00Z"),
                "ollama:nomic-embed-text:768", List.of(new CorpusRow("ip.html", "IP Policy", "2023-06-15", "a".repeat(64), 8))));

        assertThat(markdown)
                .contains("2026-11-01T10:00:00Z")
                .contains("ollama:nomic-embed-text:768")
                .contains("| en | 2 | 1 | 0.500 | 0.571 |")
                .contains("| vi | 2 | 1 | 0.500 | 0.167 |")
                .contains("ip.html (IP Policy) | 2023-06-15 | 8 | `aaaaaaaaaaaa`")
                .contains("| q-fees | vi | none |")
                .contains("### q-fees (en)")
                .contains("### q-fees (vi)")
                .contains("seller.html > e. Selling Fees")
                .doesNotContain("### q-ip (en)");
    }
}
