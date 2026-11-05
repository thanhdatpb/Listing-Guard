package com.thanhdatpb.listingguard.trademark.catalog.uspto;

/** The USPTO status map is invalid. Thrown at load time; the message never contains a mark. */
public class UsptoStatusMapException extends RuntimeException {

    public UsptoStatusMapException(String message) {
        super(message);
    }

    public UsptoStatusMapException(String message, Throwable cause) {
        super(message, cause);
    }
}
