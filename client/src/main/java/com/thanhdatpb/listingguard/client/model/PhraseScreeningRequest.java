package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A batch of seller-provided keywords or print text. A missing marketplace defaults to Etsy. */
public record PhraseScreeningRequest(List<Phrase> phrases, String marketplace,
                                     @JsonProperty("product_type") String productType) {
    public PhraseScreeningRequest {
        phrases = List.copyOf(phrases);
    }

    public record Phrase(String text, Long searchVolume) {}
}
