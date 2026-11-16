package com.thanhdatpb.listingguard.api.feedback;

/**
 * No finding of the calling client at that address (ADR-034): an unknown result, another client's, one past its retention, or an
 * index outside the result. They all answer alike, {@code 404 finding-not-found} (ADR-007).
 */
public class FindingNotFoundException extends RuntimeException {

    /** The stable problem code of the answer (HTTP 404). */
    public static final String CODE = "finding-not-found";

    public FindingNotFoundException() {
        super("No finding of this client at that address. The result may be unknown, expired, or the index outside it.");
    }
}
