package com.thanhdatpb.listingguard.trademark.catalog.uspto;

/**
 * A USPTO file cannot be converted: it is not well-formed, it is not a Trademark Applications Daily file, or it
 * declares a version the converter does not read. The message names the problem and, for a version, the values found;
 * it never contains the text of a mark.
 */
public class UsptoConversionException extends RuntimeException {

    public UsptoConversionException(String message) {
        super(message);
    }

    public UsptoConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
