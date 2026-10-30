package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The three catalog rules: {@code catalog_exact} (a one-token mark equals a token), {@code catalog_phrase} (a
 * multi-token mark equals consecutive tokens) and {@code catalog_fuzzy} (a bounded gram is similar to a mark, scored
 * in Java). The risk of each is a key of the ruleset.
 */
final class CatalogRule implements TrademarkRule {

    private final String id;
    private final MatchType type;

    CatalogRule(String id) {
        this.id = id;
        this.type = switch (id) {
            case RuleIds.CATALOG_EXACT -> MatchType.EXACT;
            case RuleIds.CATALOG_PHRASE -> MatchType.PHRASE;
            case RuleIds.CATALOG_FUZZY -> MatchType.FUZZY;
            default -> throw new IllegalArgumentException("Not a catalog rule: " + id);
        };
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<Candidate> apply(ScanContext context, List<Candidate> soFar) {
        TrademarkRuleset.CatalogRule settings = context.ruleset().catalogRule(id);
        List<CatalogHits.Hit> hits = type == MatchType.FUZZY ? context.fuzzyHits() : context.exactHits();
        List<Candidate> result = new ArrayList<>(soFar);
        for (CatalogHits.Hit hit : hits) {
            if (hit.type() != type) {
                continue;
            }
            CatalogHits.Gram gram = hit.gram();
            String snippet = context.snippet(gram.field(), gram.start(), gram.end());
            result.add(new Candidate(id, settings.risk(), gram.field().name(), gram.from(), gram.to(), gram.start(),
                    gram.end(), gram.text(), hit.mark().display(), type, hit.score(),
                    Map.of("mark", hit.mark().display(), "token", snippet, "field", gram.field().name(),
                            "score", String.valueOf(Math.round(hit.score() * 100))),
                    false, List.of()));
        }
        return result;
    }
}
