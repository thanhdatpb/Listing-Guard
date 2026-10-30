package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A phrase made only of configured color and pattern words, such as "Royal Blue White Pinstripe", with no catalog
 * match in it (exact, phrase, or fuzzy above the final threshold): an informational LOW finding that says no
 * trademark reference was detected in that phrase (ADR-009). It needs at least one color word and
 * {@code min_tokens} tokens. A phrase with one catalog token is not reported by this rule. The finding never raises
 * the overall risk, and its wording never calls the text safe, allowed or valid.
 */
final class PureColorDescriptionRule implements TrademarkRule {

    @Override
    public String id() {
        return RuleIds.PURE_COLOR_DESCRIPTION;
    }

    @Override
    public List<Candidate> apply(ScanContext context, List<Candidate> soFar) {
        TrademarkRuleset.PureColor settings = context.ruleset().pureColor();
        List<Candidate> result = new ArrayList<>(soFar);
        for (NormalizedField field : context.fields()) {
            for (ColorPhrases.Run run : ColorPhrases.runs(context, field, soFar)) {
                if (run.coveredCount() > 0 || !run.hasColor() || run.size() < settings.minTokens()) {
                    continue;
                }
                int start = field.tokens().get(run.from()).start();
                int end = field.tokens().get(run.to()).end();
                result.add(new Candidate(id(), settings.risk(), field.name(), run.from(), run.to(), start, end,
                        field.join(run.from(), run.to()), null, MatchType.CONTEXT, null,
                        Map.of("phrase", context.snippet(field, start, end), "field", field.name(),
                                "tokens", String.valueOf(run.size())),
                        false, List.of()));
            }
        }
        return result;
    }
}
