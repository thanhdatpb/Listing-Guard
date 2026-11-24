package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
class PodProfiles {
    enum Verification { UNVERIFIED, VERIFIED }

    record PrintArea(Verification sourceStatus, String sourceReference, BigDecimal maxWidthMm, BigDecimal maxHeightMm) {
        PrintArea {
            if (sourceStatus == null || (sourceStatus == Verification.VERIFIED
                    && (sourceReference == null || sourceReference.isBlank() || !positive(maxWidthMm) || !positive(maxHeightMm)))) {
                throw new IllegalArgumentException("Verified print areas require a source and positive dimensions.");
            }
        }

        public String messageKey() {
            return "pod.verification." + sourceStatus.name();
        }

        private static boolean positive(BigDecimal value) {
            return value != null && value.signum() > 0;
        }
    }

    record Profile(String id, int version, List<EditorMarketplace> marketplaces, List<String> variationRules,
                   PrintArea printArea, List<String> personalizationFields) {
        Profile {
            if (id == null || !id.matches("[a-z]+(-[a-z]+)*") || version < 1 || printArea == null) {
                throw new IllegalArgumentException("Invalid POD profile identity or provenance.");
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

        public String messageKey() {
            return "pod.profile." + id;
        }

        public String key() {
            return id + "-v" + version;
        }
    }

    record Release(Profile profile, String sha256) { }

    private final Map<String, Release> profiles;

    PodProfiles(ObjectMapper mapper, ResourcePatternResolver resources) throws IOException {
        Map<String, Release> releases = new LinkedHashMap<>();
        for (Resource resource : resources.getResources("classpath:/pod/*.json")) {
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
                .sorted(java.util.Comparator.comparing(Profile::key)).toList();
    }
}
