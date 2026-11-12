package com.thanhdatpb.listingguard.client;

/**
 * Every failure of a Listing Guard call (ADR-023). Either the API answered with an error
 * ({@link ListingGuardApiException} and its subclasses), or there was no usable answer ({@link ListingGuardTransportException}).
 * Unchecked. A message never contains a request body, the API key or a Cloudflare Access secret.
 */
public abstract class ListingGuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected ListingGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
