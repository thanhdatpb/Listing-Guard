package com.thanhdatpb.listingguard.rag.analysis;

import java.util.List;

/**
 * @param valid    items that match the schema, in the model's order
 * @param proposed how many items the model returned, including the invalid ones and those past the limit
 */
public record ParseResult(List<ProposedFinding> valid, int proposed) {

    public ParseResult {
        valid = List.copyOf(valid);
        if (proposed < valid.size()) {
            throw new IllegalArgumentException("proposed is smaller than the number of valid items");
        }
    }

    public int schemaInvalid() {
        return proposed - valid.size();
    }
}
