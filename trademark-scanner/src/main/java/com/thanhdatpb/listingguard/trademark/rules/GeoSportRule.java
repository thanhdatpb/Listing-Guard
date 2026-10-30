package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A place name and a sport term in the same field, at most {@code max_token_distance} tokens apart, as in "Northvale
 * Baseball Jersey" (ADR-009). It needs no catalog match. Distance is counted in normalized tokens of one field: the
 * last token of the earlier term to the first token of the later one, so adjacent words are 1 apart. A place alone, a
 * sport term alone and words in different fields give nothing. A sport word that lies entirely inside a place name
 * matched in the same field is ignored, so "Port Hockey" is a place, not a place and a sport.
 */
final class GeoSportRule implements TrademarkRule {

    /** A term found at tokens {@code from..to}. */
    private record Occurrence(int from, int to, String text) {
    }

    @Override
    public String id() {
        return RuleIds.GEO_SPORT_COMBINATION;
    }

    @Override
    public List<Candidate> apply(ScanContext context, List<Candidate> soFar) {
        TrademarkRuleset.GeoSport settings = context.ruleset().geoSport();
        Set<String> places = joined(context.ruleset().lexicon().places());
        Set<String> sports = joined(context.ruleset().lexicon().sportTerms());
        int longest = Math.max(longest(context.ruleset().lexicon().places()),
                longest(context.ruleset().lexicon().sportTerms()));

        List<Candidate> result = new ArrayList<>(soFar);
        for (NormalizedField field : context.fields()) {
            List<Occurrence> placeHits = occurrences(field, places, longest);
            // a sport word that is part of a matched place name (such as "Port Hockey") is not a sport term here
            List<Occurrence> sportHits = occurrences(field, sports, longest).stream()
                    .filter(sport -> placeHits.stream().noneMatch(place -> place.from() <= sport.from()
                            && sport.to() <= place.to()))
                    .toList();
            for (Occurrence place : placeHits) {
                Occurrence nearest = null;
                int nearestDistance = Integer.MAX_VALUE;
                for (Occurrence sport : sportHits) {
                    int distance = distance(place, sport);
                    if (distance >= 1 && distance <= settings.maxTokenDistance() && distance < nearestDistance) {
                        nearest = sport;
                        nearestDistance = distance;
                    }
                }
                if (nearest != null) {
                    result.add(candidate(context, settings, field, place, nearest, nearestDistance));
                }
            }
        }
        return result;
    }

    private Candidate candidate(ScanContext context, TrademarkRuleset.GeoSport settings, NormalizedField field,
                                Occurrence place, Occurrence sport, int distance) {
        int from = Math.min(place.from(), sport.from());
        int to = Math.max(place.to(), sport.to());
        List<Token> tokens = field.tokens();
        int start = tokens.get(from).start();
        int end = tokens.get(to).end();
        return new Candidate(id(), settings.risk(), field.name(), from, to, start, end, field.join(from, to), null,
                MatchType.CONTEXT, null,
                Map.of("place", place.text(), "sport", sport.text(), "field", field.name(),
                        "distance", String.valueOf(distance), "max_distance",
                        String.valueOf(settings.maxTokenDistance())),
                false, List.of());
    }

    /** Tokens between the two terms plus one; 0 or less when they overlap. */
    private static int distance(Occurrence a, Occurrence b) {
        return a.to() < b.from() ? b.from() - a.to() : (b.to() < a.from() ? a.from() - b.to() : 0);
    }

    private static List<Occurrence> occurrences(NormalizedField field, Set<String> terms, int longest) {
        List<Occurrence> found = new ArrayList<>();
        int size = field.tokens().size();
        for (int from = 0; from < size; from++) {
            for (int to = from; to < size && to - from < longest; to++) {
                String text = field.join(from, to);
                if (terms.contains(text)) {
                    found.add(new Occurrence(from, to, text));
                }
            }
        }
        return found;
    }

    private static Set<String> joined(List<List<String>> entries) {
        Set<String> result = new HashSet<>();
        entries.forEach(tokens -> result.add(String.join(" ", tokens)));
        return result;
    }

    private static int longest(List<List<String>> entries) {
        return entries.stream().mapToInt(List::size).max().orElse(1);
    }
}
