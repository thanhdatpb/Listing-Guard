package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;

/**
 * What a catalog's marks were normalized with. The stored normalized form is only comparable to normalized input
 * while all four equal the active ruleset and normalizer; the catalog is refused otherwise (ADR-009, like the
 * embedding model guard of ADR-012).
 */
public record NormalizationIdentity(String packId, String rulesetVersion, String rulesetContentHash,
                                    String normalizationVersion) {

    public static NormalizationIdentity of(TrademarkRuleset ruleset) {
        return new NormalizationIdentity(ruleset.packId(), ruleset.version(), ruleset.contentHash(),
                TextNormalizer.VERSION);
    }
}
