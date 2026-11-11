package com.thanhdatpb.listingguard.api.image;

import java.util.UUID;

/**
 * No retained image check with this id for the calling client. A check of another client, an id that never existed and an
 * expired check are reported exactly alike (ADR-007, ADR-008).
 */
public class ImageCheckNotFoundException extends RuntimeException {

    public ImageCheckNotFoundException(UUID id) {
        super("No image check with id " + id + " was found for this client.");
    }
}
