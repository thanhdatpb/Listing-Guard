package com.thanhdatpb.listingguard.trademark.catalog;

import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A catalog held in memory, for tests and local use. Marks are normalized with the ruleset's normalizer. */
public final class InMemoryTrademarkCatalog implements TrademarkCatalog {

    private final String version;
    private final Map<String, CatalogMark> byNormalized = new TreeMap<>();

    public InMemoryTrademarkCatalog(String version, TextNormalizer normalizer, Collection<String> displayMarks) {
        this.version = version;
        for (String display : displayMarks) {
            String normalized = normalizer.normalizeMark(display);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Mark has no letters or digits: '" + display + "'");
            }
            if (byNormalized.putIfAbsent(normalized, new CatalogMark(normalized, display)) != null) {
                throw new IllegalArgumentException("Mark listed twice after normalization: '" + display + "'");
            }
        }
    }

    /** A catalog of marks that are already normalized, with their metadata. */
    public InMemoryTrademarkCatalog(String version, Collection<CatalogMark> marks) {
        this.version = version;
        for (CatalogMark mark : marks) {
            if (mark.normalized().isEmpty() || byNormalized.putIfAbsent(mark.normalized(), mark) != null) {
                throw new IllegalArgumentException("Mark is empty or listed twice: '" + mark.display() + "'");
            }
        }
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
        Map<String, CatalogMark> found = new LinkedHashMap<>();
        for (String form : normalizedForms) {
            CatalogMark mark = byNormalized.get(form);
            if (mark != null) {
                found.put(form, mark);
            }
        }
        return found;
    }

    @Override
    public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms,
                                                           double candidateThreshold) {
        Map<String, List<CatalogMark>> result = new LinkedHashMap<>();
        for (String form : normalizedForms) {
            List<CatalogMark> candidates = new ArrayList<>();
            for (CatalogMark mark : byNormalized.values()) {
                if (Trigrams.similarity(form, mark.normalized()) >= candidateThreshold) {
                    candidates.add(mark);
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort(Comparator.comparing(CatalogMark::normalized));
                result.put(form, candidates);
            }
        }
        return result;
    }
}
