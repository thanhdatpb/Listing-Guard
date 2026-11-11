package com.thanhdatpb.listingguard.image;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

/** Immutable, hash-pinned ruleset parsed from a versioned YAML resource. */
public record ImageRuleset(String packId, String version, LocalDate checkedAt, ImageMarketplace marketplace,
                           String contentHash, JsonNode document) {
    public ImageRuleset { document = document.deepCopy(); }
    @Override public JsonNode document() { return document.deepCopy(); }
}
