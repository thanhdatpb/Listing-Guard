package com.thanhdatpb.listingguard.api.personalization;

import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.personalization.PersonalizationScreener;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetException;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetLoader;
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
 * Loads the personalization ruleset and registers it in {@code ruleset_version} for each configured marketplace under the
 * pack {@code personalization-screening} (ADR-010, ADR-016). Like {@code VariationRulesets}: startup fails if the ruleset is
 * invalid or if its version is already registered with another content hash. It is a separate registry because the
 * text-check path ({@code ActiveRulesets}, used by {@code /checks}) selects the pack {@code listing-rules} only and must not
 * see this one.
 */
@Component
public class PersonalizationRulesets implements SmartInitializingSingleton {

    static final String SOURCE = "internal print policy and heuristics, unverified (ADR-016)";

    private final PersonalizationProperties properties;
    private final PersonalizationRuleset ruleset;
    private final PersonalizationScreener screener;
    private final RulesetVersionRepository versions;
    private final TransactionTemplate transactions;

    private volatile Map<String, Long> versionIdByMarketplace = Map.of();

    public PersonalizationRulesets(PersonalizationProperties properties, ResourceLoader resources,
                                   RulesetVersionRepository versions, TransactionTemplate transactions) {
        this.properties = properties;
        this.versions = versions;
        this.transactions = transactions;
        try {
            this.ruleset = PersonalizationRulesetLoader.parse(
                    resources.getResource(properties.ruleset()).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PersonalizationRulesetException("Cannot read the personalization ruleset " + properties.ruleset(), e);
        } catch (PersonalizationRulesetException e) {
            throw new PersonalizationRulesetException(
                    "Personalization ruleset " + properties.ruleset() + ": " + e.getMessage(), e);
        }
        this.screener = new PersonalizationScreener(ruleset);
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
    public PersonalizationRuleset ruleset() {
        return ruleset;
    }

    /** The screener built from the loaded ruleset. */
    public PersonalizationScreener screener() {
        return screener;
    }

    /** @throws IllegalStateException if the ruleset is not registered for the marketplace */
    public long rulesetVersionId(String marketplace) {
        Long id = versionIdByMarketplace.get(normalize(marketplace));
        if (id == null) {
            throw new IllegalStateException("The personalization ruleset is not registered for marketplace " + marketplace);
        }
        return id;
    }

    /**
     * Records the ruleset for the marketplace, or confirms it is already recorded with the same content hash.
     *
     * @throws IllegalStateException if the version exists with a different hash
     */
    public long register(String marketplace, PersonalizationRuleset toRegister) {
        try {
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        } catch (DataIntegrityViolationException e) {
            // another instance inserted the same version between our read and write
            return transactions.execute(status -> registerOrConfirm(normalize(marketplace), toRegister));
        }
    }

    private long registerOrConfirm(String marketplace, PersonalizationRuleset config) {
        Optional<RulesetVersion> existing = versions.findByPackIdAndMarketplaceCodeAndVersion(config.packId(),
                marketplace, config.version());
        if (existing.isPresent()) {
            RulesetVersion row = existing.get();
            if (!row.getContentHash().equals(config.contentHash())) {
                throw new IllegalStateException("Personalization ruleset " + config.version() + " (" + marketplace
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
