package com.thanhdatpb.listingguard.api.image;

import com.thanhdatpb.listingguard.image.ImageMarketplace;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the image-checks pack (ADR-008). Everything here is an operational limit of this deployment, never a marketplace
 * rule: the marketplace thresholds live in the image rulesets.
 *
 * @param enabled        whether {@code POST /api/v1/image-checks} works. Off by default: a self-hosted deployment is unchanged and
 *                       the endpoint answers {@code 503 image-checks-not-configured}. The bounded decode service and the private
 *                       upload staging of ADR-008 "Image input security" are not part of this change, so do not turn it on
 *                       for an untrusted caller before they exist
 * @param rulesets       the image ruleset of each marketplace, by marketplace code (ADR-010: one {@code ruleset_version} row per
 *                       pack, marketplace and version)
 * @param defaultProfile the profile used when a request names none
 * @param maxImages      most images in one request (more is a 400 {@code too-many-images})
 * @param maxFileSize    largest single image; a larger one is a 413. Also the multipart per-file limit
 * @param maxRequestSize largest whole request; a larger one is a 413. Also the multipart request limit and the body limit of
 *                       this endpoint while it is enabled
 */
@ConfigurationProperties(prefix = "listing-guard.image")
public record ImageProperties(
        @DefaultValue("false") boolean enabled,
        Map<String, String> rulesets,
        @DefaultValue("default") String defaultProfile,
        @DefaultValue("20") int maxImages,
        @DefaultValue("10MB") DataSize maxFileSize,
        @DefaultValue("60MB") DataSize maxRequestSize) {

    public ImageProperties {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (rulesets != null) {
            rulesets.forEach((marketplace, location) -> {
                String code = marketplace.trim().toUpperCase(Locale.ROOT);
                try {
                    ImageMarketplace.valueOf(code);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("listing-guard.image.rulesets names marketplace '" + marketplace
                            + "', which the image checker does not know");
                }
                normalized.put(code, location);
            });
        }
        rulesets = Map.copyOf(normalized);
        if (defaultProfile == null || defaultProfile.isBlank()) {
            throw new IllegalArgumentException("listing-guard.image.default-profile must not be blank");
        }
        if (maxImages < 1) {
            throw new IllegalArgumentException("listing-guard.image.max-images must be at least 1");
        }
        if (maxFileSize.toBytes() < 1) {
            throw new IllegalArgumentException("listing-guard.image.max-file-size must be positive");
        }
        if (maxRequestSize.toBytes() < maxFileSize.toBytes()) {
            throw new IllegalArgumentException("listing-guard.image.max-request-size must not be smaller than max-file-size");
        }
    }
}
