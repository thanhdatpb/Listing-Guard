package com.thanhdatpb.listingguard.api.check;

import java.util.List;

/** The marketplace code is not one this service knows at all. */
public class UnknownMarketplaceException extends RuntimeException {

    public UnknownMarketplaceException(String marketplace, List<String> supported) {
        super("Unknown marketplace: " + marketplace + ". Supported: " + String.join(", ", supported) + ".");
    }
}
