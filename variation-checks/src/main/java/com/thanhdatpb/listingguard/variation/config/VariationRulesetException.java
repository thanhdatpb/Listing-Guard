package com.thanhdatpb.listingguard.variation.config;

/** A variation ruleset is malformed or incomplete. Raised at load time so the application fails fast. */
public class VariationRulesetException extends RuntimeException {

    public VariationRulesetException(String message) {
        super(message);
    }

    public VariationRulesetException(String message, Throwable cause) {
        super(message, cause);
    }
}
