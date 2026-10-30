package com.thanhdatpb.listingguard.rag.analysis;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides, in code and never by the model, which proposed findings are backed by the policy text (ADR-011
 * item 6). A finding is kept only if its chunk was retrieved for this request, its quote is within the length
 * limits, has no ellipsis and occurs verbatim in that chunk, and its value occurs in the named listing field. Everything
 * is compared in the {@link TextNormalizer} form, case-sensitive. A failing finding is removed and counted
 * under the first reason that applies, in this order: unknown chunk, quote length, quote not found, value not
 * found. Kept findings never carry {@code ERROR}.
 */
public final class CitationVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(CitationVerifier.class);

    private final int minQuoteLength;
    private final int maxQuoteLength;

    /**
     * Both limits come from the analyzer profile and count code points of the normalized quote.
     *
     * @param minQuoteLength fewest characters a quote may have, positive
     * @param maxQuoteLength most characters a quote may have, at least {@code minQuoteLength}
     */
    public CitationVerifier(int minQuoteLength, int maxQuoteLength) {
        if (minQuoteLength < 1) {
            throw new IllegalArgumentException("minQuoteLength must be positive");
        }
        if (maxQuoteLength < minQuoteLength) {
            throw new IllegalArgumentException("maxQuoteLength must not be smaller than minQuoteLength");
        }
        this.minQuoteLength = minQuoteLength;
        this.maxQuoteLength = maxQuoteLength;
    }

    /**
     * @param retrieved the chunks retrieved for this request, and no others
     * @param listing   listing text by field name; a field with several values (tags) lists them separately,
     *                  so a value must occur inside one of them
     */
    public VerificationResult verify(
            ParseResult parsed, Collection<RetrievedChunk> retrieved, Map<String, List<String>> listing) {
        Map<Long, RetrievedChunk> chunks = new HashMap<>();
        Map<Long, String> chunkText = new HashMap<>();
        for (RetrievedChunk chunk : retrieved) {
            if (chunks.putIfAbsent(chunk.chunkId(), chunk) == null) {
                chunkText.put(chunk.chunkId(), TextNormalizer.normalize(chunk.content()));
            }
        }
        Map<String, List<String>> listingText = new HashMap<>();
        listing.forEach((field, values) ->
                listingText.put(field, values.stream().map(TextNormalizer::normalize).toList()));

        Map<DropReason, Integer> dropped = new EnumMap<>(DropReason.class);
        dropped.put(DropReason.SCHEMA_INVALID, parsed.schemaInvalid());
        int clamped = 0;
        List<PolicyFinding> kept = new ArrayList<>();
        for (ProposedFinding p : parsed.valid()) {
            DropReason reason = check(p, chunkText, listingText);
            if (reason != null) {
                dropped.merge(reason, 1, Integer::sum);
                continue;
            }
            RetrievedChunk chunk = chunks.get(p.chunkId());
            Severity severity = p.severity();
            if (severity == Severity.ERROR) {
                severity = Severity.WARNING;
                clamped++;
            }
            kept.add(new PolicyFinding(
                    p.ruleId(), severity, p.field(), p.value(), p.message(), p.suggestion(),
                    new PolicyFinding.Citation(
                            chunk.sourceFile(), chunk.sectionPath(), chunk.url(), chunk.version(), p.quote())));
        }
        VerificationStats stats = new VerificationStats(parsed.proposed(), kept.size(), dropped, clamped);
        LOG.info("citation verification: proposed={} kept={} dropped={} severityClamped={}",
                stats.proposed(), stats.kept(), stats.dropped(), stats.severityClamped());
        return new VerificationResult(kept, stats);
    }

    private DropReason check(
            ProposedFinding p, Map<Long, String> chunkText, Map<String, List<String>> listingText) {
        String content = chunkText.get(p.chunkId());
        if (content == null) {
            return DropReason.UNKNOWN_CHUNK;
        }
        String quote = TextNormalizer.normalize(p.quote());
        int length = quote.codePointCount(0, quote.length());
        if (length < minQuoteLength || length > maxQuoteLength || quote.contains("...")) {
            return DropReason.QUOTE_LENGTH;
        }
        if (!content.contains(quote)) {
            return DropReason.QUOTE_NOT_FOUND;
        }
        if (p.value() != null) {
            String value = TextNormalizer.normalize(p.value());
            boolean found = !value.isEmpty() && listingText.getOrDefault(p.field(), List.of()).stream()
                    .anyMatch(text -> text.contains(value));
            if (!found) {
                return DropReason.VALUE_NOT_FOUND;
            }
        }
        return null;
    }
}
