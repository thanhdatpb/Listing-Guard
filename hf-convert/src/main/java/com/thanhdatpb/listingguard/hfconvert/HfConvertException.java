package com.thanhdatpb.listingguard.hfconvert;

/** A problem that stops the {@code hf-convert} job. The message never holds a mark or a serial number. */
public class HfConvertException extends RuntimeException {

    public HfConvertException(String message) {
        super(message);
    }

    public HfConvertException(String message, Throwable cause) {
        super(message, cause);
    }
}
