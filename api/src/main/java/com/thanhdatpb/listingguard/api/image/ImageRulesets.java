package com.thanhdatpb.listingguard.api.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.image.ImageAnalysisFingerprint;
import com.thanhdatpb.listingguard.image.ImageChecker;
import com.thanhdatpb.listingguard.image.ImageCheckException;
import com.thanhdatpb.listingguard.image.ImageMarketplace;
import com.thanhdatpb.listingguard.image.ImageRuleSetLoader;
import com.thanhdatpb.listingguard.image.ImageRuleset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the image rulesets and registers each in {@code ruleset_version} under the pack {@code image-compliance} (ADR-010,
 * ADR-008). Like {@code PersonalizationRulesets}: startup fails if a ruleset is invalid or if its version is already
 * registered with another content hash. It is a separate registry because the text-check path ({@code ActiveRulesets}, used by
 * {@code /checks}) selects the pack {@code listing-rules} only and must not see this one.
 */
@Component
public class ImageRulesets implements SmartInitializingSingleton {

    static final String SOURCE = "marketplace image guidance, unverified (ADR-008)";

    /** What one marketplace's ruleset gives the endpoint, read once at start-up (the ruleset's document is copied on every read). */
    public record Loaded(ImageRuleset ruleset, String analysisVersion, List<String> profiles, String disclaimer,
                         Map<String, String> sourceStatusByRule) {
    }

    private final Map<String, Loaded> byMarketplace;
    private final RulesetVersionRepository versions;
    private final TransactionTemplate transactions;

    private volatile Map<String, Long> versionIdByMarketplace = Map.of();

    public ImageRulesets(ImageProperties properties, ResourceLoader resources, RulesetVersionRepository versions,
                         TransactionTemplate transactions) {
        this.versions = versions;
        this.transactions = transactions;
        Map<String, Loaded> loaded = new TreeMap<>();
        properties.rulesets().forEach((marketplace, location) -> loaded.put(marketplace, load(resources, marketplace, location)));
        this.byMarketplace = Map.copyOf(loaded);
    }

    private static Loaded load(ResourceLoader resources, String marketplace, String location) {
        ImageRuleset ruleset;
        try {
            ruleset = ImageRuleSetLoader.parse(resources.getResource(location).getContentAsString(StandardCharsets.UTF_8),
                    ImageMarketplace.valueOf(marketplace));
        } catch (IOException e) {
            throw new ImageCheckException("Cannot read the image ruleset " + location, e);
        } catch (ImageCheckException e) {
            throw new ImageCheckException("Image ruleset " + location + ": " + e.getMessage(), e);
        }
        JsonNode document = ruleset.document();
        String disclaimer = document.path("disclaimer").asText("");
        if (disclaimer.isBlank()) {
            throw new ImageCheckException("Image ruleset " + location + " has no disclaimer");
        }
        List<String> profiles = new ArrayList<>();
        document.path("profiles").fieldNames().forEachRemaining(profiles::add);
        if (profiles.isEmpty()) {
            throw new ImageCheckException("Image ruleset " + location + " declares no profile");
        }
        Map<String, String> status = new TreeMap<>();
        document.path("rules").fields().forEachRemaining(
                rule -> status.put(rule.getKey(), rule.getValue().path("source").path("status").asText()));
        return new Loaded(ruleset, ImageAnalysisFingerprint.of(ruleset).value(), List.copyOf(profiles), disclaimer,
                Map.copyOf(status));
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Long> registered = new TreeMap<>();
        byMarketplace.forEach((marketplace, loaded) -> registered.put(marketplace, register(marketplace, loaded.ruleset())));
        versionIdByMarketplace = Map.copyOf(registered);
    }

    /** The marketplaces that have an image ruleset. */
    public List<String> marketplaces() {
        return byMarketplace.keySet().stream().sorted().toList();
    }

    /** @throws IllegalArgumentException if the marketplace has no image ruleset */
    public Loaded loaded(String marketplace) {
        Loaded loaded = byMarketplace.get(normalize(marketplace));
        if (loaded == null) {
            throw new IllegalArgumentException("Image checks are not available for marketplace " + marketplace
                    + "; supported: " + marketplaces() + ".");
        }
        return loaded;
    }

    /** The checker of one profile of the marketplace's ruleset. The profile must be one of {@link Loaded#profiles()}. */
    public ImageChecker checker(String marketplace, String profile) {
        return new ImageChecker(loaded(marketplace).ruleset(), profile);
    }

    /** @throws IllegalStateException if the ruleset is not registered for the marketplace */
    public long rulesetVersionId(String marketplace) {
        Long id = versionIdByMarketplace.get(normalize(marketplace));
        if (id == null) {
            throw new IllegalStateException("The image ruleset is not registered for marketplace " + marketplace);
        }
        return id;
    }

    /**
     * Records the ruleset for the marketplace, or confirms it is already recorded with the same content hash.
     *
     * @throws IllegalStateException if the version exists with a different hash
     */
    public long register(String marketplace, ImageRuleset toRegister) {
        try {
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        } catch (DataIntegrityViolationException e) {
            // another instance inserted the same version between our read and write
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        }
    }

    private long registerOrConfirm(String marketplace, ImageRuleset config) {
        Optional<RulesetVersion> existing = versions.findByPackIdAndMarketplaceCodeAndVersion(config.packId(), marketplace,
                config.version());
        if (existing.isPresent()) {
            RulesetVersion row = existing.get();
            if (!row.getContentHash().equals(config.contentHash())) {
                throw new IllegalStateException("Image ruleset " + config.version() + " (" + marketplace
                        + ") is already registered with content hash " + row.getContentHash()
                        + " but its content now hashes to " + config.contentHash()
                        + ". Do not edit a published ruleset; bump its version instead.");
            }
            return row.getId();
        }
        return versions.saveAndFlush(new RulesetVersion(config.packId(), marketplace, config.version(), config.contentHash(),
                config.checkedAt(), SOURCE)).getId();
    }

    private static String normalize(String marketplace) {
        return marketplace.trim().toUpperCase(Locale.ROOT);
    }
}
