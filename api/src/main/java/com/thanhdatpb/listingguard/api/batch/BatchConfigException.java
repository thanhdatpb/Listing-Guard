package com.thanhdatpb.listingguard.api.batch;

/**
 * The options, the mapping file or the input file of a batch cannot be used. Raised before any listing is checked and
 * the message says what to fix, so a mistake costs nothing but a re-run.
 */
public class BatchConfigException extends IllegalArgumentException {

    public BatchConfigException(String message) {
        super(message);
    }

    public BatchConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
