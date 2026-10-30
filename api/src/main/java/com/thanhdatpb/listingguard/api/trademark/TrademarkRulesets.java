package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetException;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
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
 * Loads the trademark ruleset and registers it in {@code ruleset_version} for each configured marketplace under the
 * pack {@code trademark-risk} (ADR-010). Like {@code ActiveRulesets}: startup fails if the ruleset is invalid or if
 * its version is already registered with another content hash. It is a separate registry because the text-check
 * path ({@code ActiveRulesets}, used by {@code /checks}) selects the pack {@code listing-rules} only and must not
 * see this one.
 */
@Component
public class TrademarkRulesets implements SmartInitializingSingleton {

    static final String SOURCE = "internal heuristics, unverified (ADR-009)";

    private final TrademarkProperties properties;
    private final TrademarkRuleset ruleset;
    private final RulesetVersionRepository versions;
    private final TransactionTemplate transactions;

    private volatile Map<String, Long> versionIdByMarketplace = Map.of();

    public TrademarkRulesets(TrademarkProperties properties, ResourceLoader resources,
                             RulesetVersionRepository versions, TransactionTemplate transactions) {
        this.properties = properties;
        this.versions = versions;
        this.transactions = transactions;
        try {
            this.ruleset = TrademarkRulesetLoader.parse(
                    resources.getResource(properties.ruleset()).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrademarkRulesetException("Cannot read the trademark ruleset " + properties.ruleset(), e);
        } catch (TrademarkRulesetException e) {
            throw new TrademarkRulesetException("Trademark ruleset " + properties.ruleset() + ": " + e.getMessage(), e);
        }
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
    public TrademarkRuleset ruleset() {
        return ruleset;
    }

    /** @throws IllegalStateException if the ruleset is not registered for the marketplace */
    public long rulesetVersionId(String marketplace) {
        Long id = versionIdByMarketplace.get(normalize(marketplace));
        if (id == null) {
            throw new IllegalStateException("The trademark ruleset is not registered for marketplace " + marketplace);
        }
        return id;
    }

    /**
     * Records the ruleset for the marketplace, or confirms it is already recorded with the same content hash.
     *
     * @throws IllegalStateException if the version exists with a different hash
     */
    public long register(String marketplace, TrademarkRuleset toRegister) {
        try {
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        } catch (DataIntegrityViolationException e) {
            // another instance inserted the same version between our read and write
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        }
    }

    private long registerOrConfirm(String marketplace, TrademarkRuleset config) {
        Optional<RulesetVersion> existing = versions.findByPackIdAndMarketplaceCodeAndVersion(config.packId(),
                marketplace, config.version());
        if (existing.isPresent()) {
            RulesetVersion row = existing.get();
            if (!row.getContentHash().equals(config.contentHash())) {
                throw new IllegalStateException("Trademark ruleset " + config.version() + " (" + marketplace
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
