package com.thanhdatpb.listingguard.trademark.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The versioned mark catalog the scanner reads (ADR-009). A catalog instance is one immutable version: it makes no
 * network call during a check, and everything it is asked is in normalized form (see {@code TextNormalizer}).
 * The PostgreSQL adapter (a later PR) answers {@link #fuzzyCandidates} with {@code pg_trgm} under a
 * transaction-local threshold; {@link InMemoryTrademarkCatalog} does the same in memory for tests.
 */
public interface TrademarkCatalog {

    /** Identifies this catalog version; recorded in evidence and in the analysis fingerprint by the caller. */
    String version();

    /**
     * The marks whose normalized form equals one of {@code normalizedForms} (a token or n-gram of tokens joined by
     * one space). The result is keyed by the form that matched; forms without a mark are absent.
     */
    Map<String, CatalogMark> findExact(Collection<String> normalizedForms);

    /**
     * Candidate marks for each bounded token or n-gram: marks whose trigram similarity to it is at least
     * {@code candidateThreshold}. This only narrows the search; the scanner scores the candidates and applies the
     * final threshold itself. Forms without candidates are absent. Candidates are in a deterministic order.
     */
    Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms, double candidateThreshold);
}
