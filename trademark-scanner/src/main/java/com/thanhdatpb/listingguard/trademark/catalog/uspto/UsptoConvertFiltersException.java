package com.thanhdatpb.listingguard.trademark.catalog.uspto;

/** The USPTO conversion filters are invalid. Thrown at load time; the message never contains a mark. */
public class UsptoConvertFiltersException extends RuntimeException {

    public UsptoConvertFiltersException(String message) {
        super(message);
    }

    public UsptoConvertFiltersException(String message, Throwable cause) {
        super(message, cause);
    }
}
