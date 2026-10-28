package com.thanhdatpb.listingguard.api.web;

import java.io.IOException;

/** Thrown while reading a request body that grows past the configured limit. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("Request body is larger than " + limit + " bytes");
    }
}
