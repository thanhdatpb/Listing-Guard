package com.thanhdatpb.listingguard.ruleengine.config;

/** A ruleset is malformed or incomplete. Raised at load time so the application fails fast. */
public class RulesetException extends RuntimeException {

    public RulesetException(String message) {
        super(message);
    }

    public RulesetException(String message, Throwable cause) {
        super(message, cause);
    }
}
