package com.thanhdatpb.listingguard.api.phrase;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("listing-guard.phrase-screening")
public record PhraseScreeningProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2000") int maxPhrases,
        @DefaultValue("200") int maxFuzzyPhrases,
        @DefaultValue("20000") int maxPhrasesPerDay) {

    public static final int MAX_PHRASES = 2000;
    public static final long MAX_BODY_BYTES = 5L * 1024 * 1024;

    public PhraseScreeningProperties {
        if (maxPhrases < 1 || maxPhrases > MAX_PHRASES || maxFuzzyPhrases < 0
                || maxFuzzyPhrases > MAX_PHRASES || maxPhrasesPerDay < 1) {
            throw new IllegalArgumentException("Invalid phrase screening limits");
        }
    }
}
