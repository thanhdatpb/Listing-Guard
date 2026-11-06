package com.thanhdatpb.listingguard.api.ruleset;

import java.util.List;

/** No text ruleset (pack {@code listing-rules}) is active for the marketplace, so there is nothing to read its limits from. */
public class RulesetNotFoundException extends RuntimeException {

    public RulesetNotFoundException(String marketplace, List<String> supported) {
        super("No ruleset is active for marketplace " + marketplace + ". Supported: " + String.join(", ", supported) + ".");
    }
}
