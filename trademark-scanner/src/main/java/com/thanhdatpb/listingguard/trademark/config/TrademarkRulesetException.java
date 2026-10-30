package com.thanhdatpb.listingguard.trademark.config;

/** A trademark ruleset is malformed or incomplete. Raised at load time so the application fails fast. */
public class TrademarkRulesetException extends RuntimeException {

    public TrademarkRulesetException(String message) {
        super(message);
    }

    public TrademarkRulesetException(String message, Throwable cause) {
        super(message, cause);
    }
}
