package com.thanhdatpb.listingguard.api.trademark;

/**
 * Trademark checks cannot be served: no catalog is configured, or the configured one is refused. {@code /checks} is
 * not affected. The message says why and never contains listing text.
 */
public class TrademarkUnavailableException extends RuntimeException {

    public TrademarkUnavailableException(String message) {
        super(message);
    }
}
