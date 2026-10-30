package com.thanhdatpb.listingguard.trademark.catalog.load;

/**
 * A catalog manifest or data file is invalid. The whole batch is rejected; the message names the line when the
 * problem is in one. It never contains the text of a mark.
 */
public class CatalogFormatException extends RuntimeException {

    public CatalogFormatException(String message) {
        super(message);
    }

    public CatalogFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
