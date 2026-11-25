package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The released POD profiles: JSON resources named {@code <id>-v<version>.json} (ADR-048). A released resource is never edited,
 * and a new version is a new resource (the SHA-256 of each is pinned by a test). A profile is either a shipped one with one
 * {@code printArea} for the whole product (the two v1 files, unverified), or an imported one with {@code printAreas}, one per
 * variant or size of the supplier's spec.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
class PodProfiles {
    static final String LOCATION = "classpath:/pod/*.json";

    enum Verification { UNVERIFIED, VERIFIED }

    /**
     * What the supplier states for one variant (a size, say) or, with no variant, for the whole product. It is {@code VERIFIED}
     * only with a source reference and two positive dimensions; {@code maxChars}, {@code verifiedBy} and {@code verifiedAt}
     * are recorded as given and do not decide the status.
     */
    record PrintArea(String variant, Verification sourceStatus, String sourceReference, BigDecimal maxWidthMm,
                     BigDecimal maxHeightMm, Integer maxChars, String verifiedBy, String verifiedAt) {
        PrintArea {
            variant = blankToNull(variant);
            sourceReference = blankToNull(sourceReference);
            verifiedBy = blankToNull(verifiedBy);
            verifiedAt = blankToNull(verifiedAt);
            if (sourceStatus == null || (sourceStatus == Verification.VERIFIED
                    && (sourceReference == null || !positive(maxWidthMm) || !positive(maxHeightMm)))) {
                throw new IllegalArgumentException("Verified print areas require a source and positive dimensions.");
            }
            if (maxChars != null && maxChars < 1) {
                throw new IllegalArgumentException("A character limit must be positive.");
            }
            if (verifiedAt != null) {
                try {
                    LocalDate.parse(verifiedAt);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Verification dates use the form yyyy-MM-dd.", e);
                }
            }
        }

        /** The whole product, as the two shipped v1 profiles state it. */
        PrintArea(Verification sourceStatus, String sourceReference, BigDecimal maxWidthMm, BigDecimal maxHeightMm) {
            this(null, sourceStatus, sourceReference, maxWidthMm, maxHeightMm, null, null, null);
        }

        public String messageKey() {
            return "pod.verification." + sourceStatus.name();
        }

        boolean verified() {
            return sourceStatus == Verification.VERIFIED;
        }

        private static boolean positive(BigDecimal value) {
            return value != null && value.signum() > 0;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    record Profile(String id, int version, List<EditorMarketplace> marketplaces, List<String> variationRules,
                   PrintArea printArea, List<PrintArea> printAreas, List<String> personalizationFields) {
        static final String ID_PATTERN = "[a-z][a-z0-9]*(-[a-z0-9]+)*";

        Profile {
            if (id == null || !id.matches(ID_PATTERN) || version < 1) {
                throw new IllegalArgumentException("Invalid POD profile identity or provenance.");
            }
            printAreas = printAreas == null ? List.of() : List.copyOf(printAreas);
            if ((printArea == null) == printAreas.isEmpty()) {
                throw new IllegalArgumentException("A POD profile has either one print area or a list of them.");
            }
            Set<String> variants = new HashSet<>();
            for (PrintArea area : printAreas) {
                if (!variants.add(variantKey(area.variant()))) {
                    throw new IllegalArgumentException("A POD profile has one print area per variant.");
                }
            }
            marketplaces = List.copyOf(marketplaces);
            variationRules = List.copyOf(variationRules);
            personalizationFields = List.copyOf(personalizationFields);
            if (marketplaces.isEmpty() || !variationRules.contains("sku")
                    || !List.of("sku", "size", "color").containsAll(variationRules)
                    || !List.of("text").containsAll(personalizationFields)) {
                throw new IllegalArgumentException("Unsupported POD profile fields.");
            }
        }

        /** A profile with one print area for the whole product, like the shipped v1 files. */
        Profile(String id, int version, List<EditorMarketplace> marketplaces, List<String> variationRules,
                PrintArea printArea, List<String> personalizationFields) {
            this(id, version, marketplaces, variationRules, printArea, null, personalizationFields);
        }

        public String messageKey() {
            return "pod.profile." + id;
        }

        public String key() {
            return id + "-v" + version;
        }

        /** The print areas of the profile: the one of a shipped profile, or the imported list. */
        public List<PrintArea> areas() {
            return printArea != null ? List.of(printArea) : printAreas;
        }

        /** True when every print area is verified. */
        public boolean verified() {
            return areas().stream().allMatch(PrintArea::verified);
        }

        /**
         * The print area for a size or variant: the area named that way (case and surrounding spaces ignored), otherwise the
         * area with no variant, otherwise null.
         */
        PrintArea areaFor(String size) {
            String wanted = variantKey(size);
            PrintArea fallback = null;
            for (PrintArea area : areas()) {
                String key = variantKey(area.variant());
                if (key.equals(wanted)) {
                    return area;
                }
                if (key.isEmpty()) {
                    fallback = area;
                }
            }
            return fallback;
        }

        static String variantKey(String variant) {
            return variant == null ? "" : variant.strip().toLowerCase(Locale.ROOT);
        }
    }

    record Release(Profile profile, String sha256) { }

    /** The versions of one product type, the newest first. */
    record Group(String id, List<Profile> versions) {
        public String messageKey() {
            return "pod.profile." + id;
        }
    }

    private final Map<String, Release> profiles;

    @Autowired
    PodProfiles(ObjectMapper mapper, ResourcePatternResolver resources) throws IOException {
        this(mapper, resources, LOCATION);
    }

    PodProfiles(ObjectMapper mapper, ResourcePatternResolver resources, String locationPattern) throws IOException {
        Map<String, Release> releases = new LinkedHashMap<>();
        for (Resource resource : resources.getResources(locationPattern)) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            Profile profile = mapper.readValue(content, Profile.class);
            if (!(profile.key() + ".json").equals(resource.getFilename())
                    || releases.putIfAbsent(profile.key(), new Release(profile, ApiKeyHasher.sha256Hex(content))) != null) {
                throw new IllegalStateException("POD profiles must have unique versioned resource names.");
            }
        }
        profiles = Map.copyOf(releases);
    }

    Release find(String key) {
        return key == null ? null : profiles.get(key);
    }

    List<Profile> released() {
        return profiles.values().stream().map(Release::profile)
                .sorted(Comparator.comparing(Profile::key)).toList();
    }

    /** The released profiles by product type (the id, in order), the newest version of each first. */
    List<Group> groups() {
        Map<String, List<Profile>> byId = new LinkedHashMap<>();
        profiles.values().stream().map(Release::profile)
                .sorted(Comparator.comparing(Profile::id).thenComparing(Comparator.comparingInt(Profile::version).reversed()))
                .forEach(profile -> byId.computeIfAbsent(profile.id(), id -> new ArrayList<>()).add(profile));
        return byId.entrySet().stream().map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }
}
