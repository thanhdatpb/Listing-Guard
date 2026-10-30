package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import java.util.ArrayList;
import java.util.List;

/**
 * A color phrase that contains a catalog match, such as "Zorvex Blue" (ADR-009). It is not exempt for being a color
 * name: the match keeps the match type, threshold and risk it has outside a color phrase. This rule only marks the
 * evidence ({@code insideColorPhrase}); the ruleset's note is added to the message. It never changes a risk.
 */
final class ColorNameContainsMarkRule implements TrademarkRule {

    @Override
    public String id() {
        return RuleIds.COLOR_NAME_CONTAINS_MARK;
    }

    @Override
    public List<Candidate> apply(ScanContext context, List<Candidate> soFar) {
        List<Candidate> result = new ArrayList<>(soFar);
        for (NormalizedField field : context.fields()) {
            for (ColorPhrases.Run run : ColorPhrases.runs(context, field, soFar)) {
                if (!run.hasColor() || run.coveredCount() == 0) {
                    continue;
                }
                for (int i = 0; i < result.size(); i++) {
                    Candidate candidate = result.get(i);
                    if (RuleIds.CATALOG.contains(candidate.ruleId()) && candidate.field().equals(field.name())
                            && candidate.tokenFrom() >= run.from() && candidate.tokenTo() <= run.to()) {
                        result.set(i, candidate.withInsideColorPhrase(true));
                    }
                }
            }
        }
        return result;
    }
}
