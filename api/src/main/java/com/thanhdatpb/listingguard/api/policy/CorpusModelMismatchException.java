package com.thanhdatpb.listingguard.api.policy;

/**
 * The stored policy corpus was embedded with another model than the configured one (ADR-012). Searching would
 * compare vectors from two models and return wrong passages without any sign of it, so retrieval refuses.
 */
public class CorpusModelMismatchException extends RuntimeException {

    public CorpusModelMismatchException(String message) {
        super(message);
    }
}
