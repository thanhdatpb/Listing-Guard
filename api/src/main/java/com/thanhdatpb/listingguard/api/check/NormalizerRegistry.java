package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.listing.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Looks up the {@link Normalizer} for a marketplace code (case-insensitive). A planned marketplace without a
 * normalizer is reported differently from a code nobody has heard of.
 */
@Component
public class NormalizerRegistry {

    private final Map<String, Normalizer> byMarketplace = new HashMap<>();
    private final List<String> planned;

    public NormalizerRegistry(List<Normalizer> normalizers, MarketplaceProperties properties) {
        for (Normalizer normalizer : normalizers) {
            byMarketplace.put(normalizer.marketplace().toUpperCase(Locale.ROOT), normalizer);
        }
        this.planned = properties.planned().stream().map(code -> code.trim().toUpperCase(Locale.ROOT)).toList();
    }

    public List<String> supported() {
        return byMarketplace.keySet().stream().sorted().toList();
    }

    /**
     * @throws MarketplaceNotYetSupportedException for a planned marketplace
     * @throws UnknownMarketplaceException         for any other unsupported code
     */
    public Normalizer get(String marketplace) {
        String code = marketplace == null ? "" : marketplace.trim().toUpperCase(Locale.ROOT);
        Normalizer normalizer = byMarketplace.get(code);
        if (normalizer != null) {
            return normalizer;
        }
        if (planned.contains(code)) {
            throw new MarketplaceNotYetSupportedException(code, supported());
        }
        throw new UnknownMarketplaceException(marketplace, supported());
    }
}
