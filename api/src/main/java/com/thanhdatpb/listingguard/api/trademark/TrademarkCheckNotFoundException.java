package com.thanhdatpb.listingguard.api.trademark;

import java.util.UUID;

/**
 * No retained trademark check with this id for the calling client. A check of another client, an id that never
 * existed and one past its retention period all look the same (ADR-007, ADR-009), so the answer reveals nothing about
 * which ids are in use or were.
 */
public class TrademarkCheckNotFoundException extends RuntimeException {

    public TrademarkCheckNotFoundException(UUID id) {
        super("No trademark check with id " + id);
    }
}
