package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.listing.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Looks up the {@link Normalizer} for a marketplace code (case-insensitive). */
@Component
public class NormalizerRegistry {

    private final Map<String, Normalizer> byMarketplace = new HashMap<>();

    public NormalizerRegistry(List<Normalizer> normalizers) {
        for (Normalizer normalizer : normalizers) {
            byMarketplace.put(normalizer.marketplace().toUpperCase(Locale.ROOT), normalizer);
        }
    }

    public Normalizer get(String marketplace) {
        Normalizer normalizer = marketplace == null ? null : byMarketplace.get(marketplace.trim().toUpperCase(Locale.ROOT));
        if (normalizer == null) {
            throw new UnsupportedMarketplaceException(marketplace);
        }
        return normalizer;
    }
}
