package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import java.util.ArrayList;
import java.util.List;

/**
 * Color phrases of a field: the longest runs of consecutive tokens that are each a configured color word, a
 * configured pattern word or part of a catalog match. Whether the phrase has a catalog match decides which of the
 * two color rules applies to it.
 */
final class ColorPhrases {

    /**
     * @param from       first token of the phrase
     * @param to         last token, inclusive
     * @param hasColor   at least one token is a color word
     * @param coveredCount tokens that belong to a catalog match
     */
    record Run(NormalizedField field, int from, int to, boolean hasColor, int coveredCount) {
        int size() {
            return to - from + 1;
        }
    }

    private ColorPhrases() {
    }

    static List<Run> runs(ScanContext context, NormalizedField field, List<Candidate> soFar) {
        int size = field.tokens().size();
        boolean[] covered = new boolean[size];
        for (Candidate candidate : soFar) {
            if (RuleIds.CATALOG.contains(candidate.ruleId()) && candidate.field().equals(field.name())) {
                for (int i = candidate.tokenFrom(); i <= candidate.tokenTo(); i++) {
                    covered[i] = true;
                }
            }
        }
        List<Run> runs = new ArrayList<>();
        int i = 0;
        while (i < size) {
            if (!belongs(context, field, covered, i)) {
                i++;
                continue;
            }
            int from = i;
            boolean color = false;
            int coveredCount = 0;
            while (i < size && belongs(context, field, covered, i)) {
                color |= context.ruleset().lexicon().colorWords().contains(field.tokens().get(i).text());
                if (covered[i]) {
                    coveredCount++;
                }
                i++;
            }
            runs.add(new Run(field, from, i - 1, color, coveredCount));
        }
        return runs;
    }

    private static boolean belongs(ScanContext context, NormalizedField field, boolean[] covered, int index) {
        String token = field.tokens().get(index).text();
        return covered[index] || context.ruleset().lexicon().colorWords().contains(token)
                || context.ruleset().lexicon().patternWords().contains(token);
    }
}
