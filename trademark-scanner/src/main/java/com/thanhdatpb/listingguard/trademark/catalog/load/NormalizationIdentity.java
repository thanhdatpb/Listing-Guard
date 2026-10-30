package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;

/**
 * What a catalog's marks were normalized with. The stored normalized form is only comparable to normalized input
 * while all three equal the active ones; the catalog is refused otherwise (ADR-009, like the embedding model guard of
 * ADR-012). Only the normalization is part of it, not the whole ruleset: a new ruleset version that changes a
 * threshold, a word list or a message still serves the same catalog.
 *
 * @param packId               the pack, {@code trademark-risk}
 * @param normalizationVersion {@link TextNormalizer#VERSION}: the steps of the normalizer
 * @param normalizationHash    {@code NormalizationSettings.canonicalHash()}: the tables the steps read
 */
public record NormalizationIdentity(String packId, String normalizationVersion, String normalizationHash) {

    public static NormalizationIdentity of(TrademarkRuleset ruleset) {
        return new NormalizationIdentity(ruleset.packId(), TextNormalizer.VERSION,
                ruleset.normalization().canonicalHash());
    }
}
