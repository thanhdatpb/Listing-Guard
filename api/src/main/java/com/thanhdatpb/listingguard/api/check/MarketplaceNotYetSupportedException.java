package com.thanhdatpb.listingguard.api.check;

import java.util.List;

/** The marketplace is known and planned, but there is no support for it yet. */
public class MarketplaceNotYetSupportedException extends RuntimeException {

    public MarketplaceNotYetSupportedException(String marketplace, List<String> supported) {
        super("Marketplace " + marketplace + " is planned but not supported yet. Supported: " + String.join(", ", supported) + ".");
    }
}
