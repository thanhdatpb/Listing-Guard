package com.thanhdatpb.listingguard.api.console;

public enum ConsoleAuthMode {
    MAGIC_LINK,
    CLOUDFLARE_ACCESS,
    BOTH;

    public boolean usesMagicLink() {
        return this == MAGIC_LINK || this == BOTH;
    }

    public boolean usesCloudflareAccess() {
        return this == CLOUDFLARE_ACCESS || this == BOTH;
    }
}
