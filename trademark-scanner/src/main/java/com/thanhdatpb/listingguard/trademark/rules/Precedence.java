package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * When candidates overlap in the same field, the one with the highest risk wins that span and the others are not
 * reported; the winner lists the other rule ids that matched. Equal risk goes to the rule declared first in the
 * ruleset, then to the higher score, so the result is deterministic (ADR-009). A watchlist match (ADR-031) is not declared by
 * the ruleset and comes before every declared rule, so it wins a span at equal risk.
 */
public final class Precedence {

    private Precedence() {
    }

    /** The winners, ordered by field (as in {@code fieldOrder}), span start, then rule order. */
    public static List<Candidate> resolve(TrademarkRuleset ruleset, List<Candidate> candidates,
                                          List<String> fieldOrder) {
        Comparator<Candidate> strength = Comparator
                .comparing((Candidate c) -> c.risk().ordinal()).reversed()
                .thenComparingInt(c -> index(ruleset, c.ruleId()))
                .thenComparing((Candidate c) -> c.score() == null ? 1.0 : c.score(), Comparator.reverseOrder())
                .thenComparingInt(Candidate::start)
                .thenComparing(Candidate::end, Comparator.reverseOrder())
                .thenComparing(c -> c.mark() == null ? "" : c.mark());

        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(strength);

        List<Candidate> winners = new ArrayList<>();
        List<TreeSet<Integer>> losers = new ArrayList<>();
        for (Candidate candidate : sorted) {
            int winner = -1;
            for (int i = 0; i < winners.size(); i++) {
                if (winners.get(i).overlaps(candidate)) {
                    winner = i;
                    break;
                }
            }
            if (winner < 0) {
                winners.add(candidate);
                losers.add(new TreeSet<>());
            } else if (!winners.get(winner).ruleId().equals(candidate.ruleId())) {
                losers.get(winner).add(index(ruleset, candidate.ruleId()));
            }
        }

        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < winners.size(); i++) {
            List<String> others = losers.get(i).stream().map(index -> ruleId(ruleset, index)).toList();
            result.add(winners.get(i).withOtherRuleIds(others));
        }
        result.sort(Comparator.comparingInt((Candidate c) -> fieldOrder.indexOf(c.field()))
                .thenComparingInt(Candidate::start)
                .thenComparingInt(c -> index(ruleset, c.ruleId()))
                .thenComparing(Candidate::end));
        return result;
    }

    /** The rule's place in the order: -1 for the watchlist rule, which no ruleset declares. */
    static int index(TrademarkRuleset ruleset, String ruleId) {
        return RuleIds.WATCHLIST_MATCH.equals(ruleId) ? -1 : ruleset.ruleIndex(ruleId);
    }

    private static String ruleId(TrademarkRuleset ruleset, int index) {
        return index < 0 ? RuleIds.WATCHLIST_MATCH : ruleset.ruleOrder().get(index);
    }
}
