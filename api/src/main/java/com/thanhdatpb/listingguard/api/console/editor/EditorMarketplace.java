package com.thanhdatpb.listingguard.api.console.editor;

import java.util.Arrays;
import java.util.Optional;

/** The marketplaces the console editor offers; the codes are those of {@code POST /api/v1/checks}. */
public enum EditorMarketplace {
    ETSY("Etsy"),
    AMAZON("Amazon");

    private final String label;

    EditorMarketplace(String label) {
        this.label = label;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public boolean isAmazon() {
        return this == AMAZON;
    }

    public boolean isEtsy() {
        return this == ETSY;
    }

    /** Exact match on the code, ignoring case and surrounding blanks; anything else is empty, never a default. */
    public static Optional<EditorMarketplace> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.trim();
        return Arrays.stream(values()).filter(m -> m.name().equalsIgnoreCase(wanted)).findFirst();
    }
}
