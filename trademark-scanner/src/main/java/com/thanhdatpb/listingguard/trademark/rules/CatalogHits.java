package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.match.Similarity;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.Token;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog lookups of a scan. Input is cut into bounded grams, every run of one to {@code max_ngram_tokens}
 * tokens inside one field; only grams are sent to the catalog, never a whole field. Exact and phrase matches come
 * from one lookup of all grams. Fuzzy candidates are retrieved for the grams that did not match exactly and are
 * long enough, then scored here with {@link Similarity} against the final threshold.
 */
final class CatalogHits {

    /** A run of consecutive tokens of one field. */
    record Gram(NormalizedField field, int from, int to, String text, int start, int end) {
    }

    record Hit(Gram gram, CatalogMark mark, MatchType type, double score) {
    }

    private CatalogHits() {
    }

    static List<Gram> grams(ScanContext context) {
        int max = context.ruleset().matching().maxNgramTokens();
        List<Gram> grams = new ArrayList<>();
        for (NormalizedField field : context.fields()) {
            List<Token> tokens = field.tokens();
            for (int from = 0; from < tokens.size(); from++) {
                for (int to = from; to < tokens.size() && to - from < max; to++) {
                    grams.add(new Gram(field, from, to, field.join(from, to), tokens.get(from).start(),
                            tokens.get(to).end()));
                }
            }
        }
        return grams;
    }

    static List<Hit> exact(ScanContext context) {
        List<Gram> grams = grams(context);
        Set<String> forms = new LinkedHashSet<>();
        grams.forEach(gram -> forms.add(gram.text()));
        Map<String, CatalogMark> found = context.catalog().findExact(forms);

        List<Hit> hits = new ArrayList<>();
        for (Gram gram : grams) {
            CatalogMark mark = found.get(gram.text());
            if (mark != null) {
                MatchType type = mark.tokenCount() == 1 ? MatchType.EXACT : MatchType.PHRASE;
                hits.add(new Hit(gram, mark, type, 1.0));
            }
        }
        return hits;
    }

    static List<Hit> fuzzy(ScanContext context) {
        TrademarkRuleset.Matching matching = context.ruleset().matching();
        Set<String> exactForms = new HashSet<>();
        context.exactHits().forEach(hit -> exactForms.add(hit.gram().text()));

        List<Gram> eligible = new ArrayList<>();
        Set<String> forms = new LinkedHashSet<>();
        for (Gram gram : grams(context)) {
            int chars = gram.text().codePointCount(0, gram.text().length());
            if (chars >= matching.fuzzyMinChars() && chars <= matching.fuzzyMaxChars()
                    && !exactForms.contains(gram.text())) {
                eligible.add(gram);
                forms.add(gram.text());
            }
        }
        Map<String, List<CatalogMark>> candidates =
                context.catalog().fuzzyCandidates(forms, matching.candidateThreshold());

        List<Hit> hits = new ArrayList<>();
        for (Gram gram : eligible) {
            Hit best = null;
            for (CatalogMark mark : candidates.getOrDefault(gram.text(), List.of())) {
                double score = Similarity.score(gram.text(), mark.normalized());
                if (score < matching.finalThreshold()) {
                    continue;
                }
                Hit hit = new Hit(gram, mark, MatchType.FUZZY, score);
                if (best == null || Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparing(h -> h.mark().normalized()).compare(hit, best) < 0) {
                    best = hit;
                }
            }
            if (best != null) {
                hits.add(best);
            }
        }
        return hits;
    }
}
