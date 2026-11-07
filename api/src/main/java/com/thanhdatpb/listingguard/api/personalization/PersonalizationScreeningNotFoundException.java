package com.thanhdatpb.listingguard.api.personalization;

import java.util.UUID;

/**
 * No retained personalization screening with this id for the calling client. A screening of another client, an id that never
 * existed and an expired screening are reported exactly alike (ADR-007, ADR-016).
 */
public class PersonalizationScreeningNotFoundException extends RuntimeException {

    public PersonalizationScreeningNotFoundException(UUID id) {
        super("No personalization screening with id " + id + " was found for this client.");
    }
}
