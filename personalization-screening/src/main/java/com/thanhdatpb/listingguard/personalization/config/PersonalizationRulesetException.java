package com.thanhdatpb.listingguard.personalization.config;

/** A personalization ruleset is malformed or incomplete. Raised at load time so the application fails fast. */
public class PersonalizationRulesetException extends RuntimeException {

    public PersonalizationRulesetException(String message) {
        super(message);
    }

    public PersonalizationRulesetException(String message, Throwable cause) {
        super(message, cause);
    }
}
