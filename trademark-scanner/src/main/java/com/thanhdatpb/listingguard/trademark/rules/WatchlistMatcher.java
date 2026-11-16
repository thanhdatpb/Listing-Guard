package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.normalize.Token;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistEntry;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistMatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The {@code watchlist_match} rule (ADR-031). The mark and every alias of each entry are normalized with the ruleset's
 * normalizer, as catalog marks are; a form matches where the same sequence of normalized tokens appears in one scanned field. A
 * form is as long as it is (no n-gram limit), and there is no fuzzy match. When two entries share a form (the API refuses that,
 * so only a normalization change can cause it), the entry with the lower id wins.
 */
public final class WatchlistMatcher {

    private record Form(WatchlistEntry entry, String alias) {
    }

    private WatchlistMatcher() {
    }

    /** The candidates of the watchlist in the context's fields, at the settings' minimum level. Empty for an empty watchlist. */
    public static List<Candidate> candidates(ScanContext context, Watchlist watchlist, TextNormalizer normalizer) {
        if (watchlist.isEmpty()) {
            return List.of();
        }
        Map<String, Form> forms = new HashMap<>();
        TreeSet<Integer> lengths = new TreeSet<>();
        for (WatchlistEntry entry : watchlist.entries()) {
            add(forms, lengths, normalizer, entry.mark(), new Form(entry, null));
            for (String alias : entry.aliases()) {
                add(forms, lengths, normalizer, alias, new Form(entry, alias));
            }
        }
        RiskLevel minimum = watchlist.settings().minimumRisk();
        List<Candidate> candidates = new ArrayList<>();
        for (NormalizedField field : context.fields()) {
            List<Token> tokens = field.tokens();
            for (int length : lengths) {
                for (int from = 0; from + length <= tokens.size(); from++) {
                    int to = from + length - 1;
                    String gram = field.join(from, to);
                    Form form = forms.get(gram);
                    if (form == null) {
                        continue;
                    }
                    int start = tokens.get(from).start();
                    int end = tokens.get(to).end();
                    WatchlistEntry entry = form.entry();
                    Map<String, String> placeholders = Map.of(
                            "token", context.snippet(field, start, end),
                            "field", field.name(),
                            "mark", entry.mark(),
                            "alias", form.alias() == null ? "" : form.alias(),
                            "note", entry.note() == null ? "" : entry.note());
                    candidates.add(new Candidate(RuleIds.WATCHLIST_MATCH, minimum, field.name(), from, to, start, end, gram,
                            entry.mark(), length == 1 ? MatchType.EXACT : MatchType.PHRASE, 1.0, placeholders, false,
                            List.of(), entry.sourceIds(), List.of(), WatchlistMatch.of(entry, form.alias())));
                }
            }
        }
        return candidates;
    }

    /**
     * Raises each watchlist candidate to the highest level of any other candidate that overlaps it: the watchlist level is a
     * minimum, and the watchlist finding must not lose its span (and its note) to a higher catalog match.
     */
    public static List<Candidate> raise(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (!RuleIds.WATCHLIST_MATCH.equals(candidate.ruleId())) {
                result.add(candidate);
                continue;
            }
            RiskLevel level = candidate.risk();
            for (Candidate other : candidates) {
                if (!RuleIds.WATCHLIST_MATCH.equals(other.ruleId()) && other.overlaps(candidate)) {
                    level = RiskLevel.higher(level, other.risk());
                }
            }
            result.add(level == candidate.risk() ? candidate : candidate.withRisk(level));
        }
        return result;
    }

    private static void add(Map<String, Form> forms, TreeSet<Integer> lengths, TextNormalizer normalizer, String text, Form form) {
        String normalized = normalizer.normalizeMark(text);
        if (normalized.isEmpty()) {
            return;
        }
        if (forms.putIfAbsent(normalized, form) == null) {
            lengths.add(normalized.split(" ", -1).length);
        }
    }
}
