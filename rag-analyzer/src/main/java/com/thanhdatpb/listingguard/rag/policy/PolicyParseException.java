package com.thanhdatpb.listingguard.rag.policy;

/** A policy file or manifest could not be read or understood. */
public class PolicyParseException extends RuntimeException {

    public PolicyParseException(String message) {
        super(message);
    }

    public PolicyParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
