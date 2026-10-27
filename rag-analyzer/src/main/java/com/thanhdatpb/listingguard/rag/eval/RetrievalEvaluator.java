package com.thanhdatpb.listingguard.rag.eval;

import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Runs every question of an {@link EvalSet}, in every language, through a retriever and measures it.
 * Recall is taken at {@link #RECALL_K}; MRR over the top {@link #RETRIEVE_K}.
 */
public final class RetrievalEvaluator {

    public static final int RECALL_K = 5;
    public static final int RETRIEVE_K = 10;
    private static final int TOP_SHOWN = 3;

    /** @param firstHitRank rank (1 = best) of the first relevant result within the top RETRIEVE_K, if any */
    public record QueryResult(String queryId, String language, String question, EvalSet.Query query,
                              OptionalInt firstHitRank, List<RetrievedChunk> top) {
    }

    public record GroupMetrics(String language, int queries, int hitsAtK, double recallAtK, double mrr) {
    }

    public record Evaluation(String marketplace, List<QueryResult> results, List<GroupMetrics> groups) {
    }

    public Evaluation run(EvalSet set, PolicyRetriever retriever) {
        List<QueryResult> results = new ArrayList<>();
        List<GroupMetrics> groups = new ArrayList<>();
        for (String language : EvalSet.LANGUAGES) {
            List<OptionalInt> ranks = new ArrayList<>();
            for (EvalSet.Query query : set.queries()) {
                String question = query.questions().get(language);
                List<RetrievedChunk> found = retriever.search(set.marketplace(), question, RETRIEVE_K);
                OptionalInt rank = firstRelevantRank(query, found);
                ranks.add(rank);
                results.add(new QueryResult(query.id(), language, question, query, rank,
                        List.copyOf(found.subList(0, Math.min(TOP_SHOWN, found.size())))));
            }
            groups.add(new GroupMetrics(language, ranks.size(),
                    (int) ranks.stream().filter(r -> r.isPresent() && r.getAsInt() <= RECALL_K).count(),
                    RetrievalMetrics.recallAtK(ranks, RECALL_K), RetrievalMetrics.meanReciprocalRank(ranks)));
        }
        return new Evaluation(set.marketplace(), results, groups);
    }

    private static OptionalInt firstRelevantRank(EvalSet.Query query, List<RetrievedChunk> found) {
        for (int i = 0; i < found.size(); i++) {
            if (query.isRelevant(found.get(i))) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.empty();
    }
}
