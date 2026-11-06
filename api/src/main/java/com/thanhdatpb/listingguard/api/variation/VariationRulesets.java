package com.thanhdatpb.listingguard.api.variation;

import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.variation.VariationChecker;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetException;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Loads the variation ruleset and registers it in {@code ruleset_version} for each configured marketplace under the pack
 * {@code variation-checks} (ADR-010, ADR-015). Like {@code TrademarkRulesets}: startup fails if the ruleset is invalid or if
 * its version is already registered with another content hash. It is a separate registry because the text-check path
 * ({@code ActiveRulesets}, used by {@code /checks}) selects the pack {@code listing-rules} only and must not see this one.
 */
@Component
public class VariationRulesets implements SmartInitializingSingleton {

    static final String SOURCE = "secondary sources and internal heuristics, unverified (ADR-015)";

    private final VariationProperties properties;
    private final VariationRuleset ruleset;
    private final VariationChecker checker;
    private final RulesetVersionRepository versions;
    private final TransactionTemplate transactions;

    private volatile Map<String, Long> versionIdByMarketplace = Map.of();

    public VariationRulesets(VariationProperties properties, ResourceLoader resources, RulesetVersionRepository versions,
                             TransactionTemplate transactions) {
        this.properties = properties;
        this.versions = versions;
        this.transactions = transactions;
        try {
            this.ruleset = VariationRulesetLoader.parse(
                    resources.getResource(properties.ruleset()).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new VariationRulesetException("Cannot read the variation ruleset " + properties.ruleset(), e);
        } catch (VariationRulesetException e) {
            throw new VariationRulesetException("Variation ruleset " + properties.ruleset() + ": " + e.getMessage(), e);
        }
        this.checker = new VariationChecker(ruleset);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Long> registered = new TreeMap<>();
        for (String marketplace : properties.marketplaces()) {
            registered.put(normalize(marketplace), register(marketplace, ruleset));
        }
        versionIdByMarketplace = Map.copyOf(registered);
    }

    /** The loaded ruleset; the same for every marketplace. */
    public VariationRuleset ruleset() {
        return ruleset;
    }

    /** The checker built from the loaded ruleset. */
    public VariationChecker checker() {
        return checker;
    }

    /** @throws IllegalStateException if the ruleset is not registered for the marketplace */
    public long rulesetVersionId(String marketplace) {
        Long id = versionIdByMarketplace.get(normalize(marketplace));
        if (id == null) {
            throw new IllegalStateException("The variation ruleset is not registered for marketplace " + marketplace);
        }
        return id;
    }

    /**
     * Records the ruleset for the marketplace, or confirms it is already recorded with the same content hash.
     *
     * @throws IllegalStateException if the version exists with a different hash
     */
    public long register(String marketplace, VariationRuleset toRegister) {
        try {
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        } catch (DataIntegrityViolationException e) {
            // another instance inserted the same version between our read and write
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        }
    }

    private long registerOrConfirm(String marketplace, VariationRuleset config) {
        Optional<RulesetVersion> existing = versions.findByPackIdAndMarketplaceCodeAndVersion(config.packId(),
                marketplace, config.version());
        if (existing.isPresent()) {
            RulesetVersion row = existing.get();
            if (!row.getContentHash().equals(config.contentHash())) {
                throw new IllegalStateException("Variation ruleset " + config.version() + " (" + marketplace
                        + ") is already registered with content hash " + row.getContentHash()
                        + " but its content now hashes to " + config.contentHash()
                        + ". Do not edit a published ruleset; bump its version instead.");
            }
            return row.getId();
        }
        return versions.saveAndFlush(new RulesetVersion(config.packId(), marketplace, config.version(),
                config.contentHash(), config.checkedAt(), SOURCE)).getId();
    }

    private static String normalize(String marketplace) {
        return marketplace.trim().toUpperCase(Locale.ROOT);
    }
}
