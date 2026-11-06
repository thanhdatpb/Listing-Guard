package com.thanhdatpb.listingguard.api.variation;

import java.util.UUID;

/**
 * No retained variation check with this id for the calling client. A check of another client, an id that never existed
 * and an expired check are reported exactly alike (ADR-007, ADR-015).
 */
public class VariationCheckNotFoundException extends RuntimeException {

    public VariationCheckNotFoundException(UUID id) {
        super("No variation check with id " + id + " was found for this client.");
    }
}
