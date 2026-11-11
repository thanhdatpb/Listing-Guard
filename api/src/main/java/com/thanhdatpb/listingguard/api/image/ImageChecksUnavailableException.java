package com.thanhdatpb.listingguard.api.image;

/** Image checks are switched off in this deployment ({@code listing-guard.image.enabled=false}, the default). */
public class ImageChecksUnavailableException extends RuntimeException {

    public ImageChecksUnavailableException() {
        super("Image checks are not enabled in this deployment (listing-guard.image.enabled).");
    }
}
