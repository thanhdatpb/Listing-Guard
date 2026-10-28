package com.thanhdatpb.listingguard.api.check;

import java.util.UUID;

public class CheckNotFoundException extends RuntimeException {

    public CheckNotFoundException(UUID id) {
        super("No check with id " + id);
    }
}
