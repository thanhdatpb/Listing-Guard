package com.thanhdatpb.listingguard.api.check;

public class UnsupportedMarketplaceException extends RuntimeException {

    public UnsupportedMarketplaceException(String marketplace) {
        super("Unsupported marketplace: " + marketplace);
    }
}
