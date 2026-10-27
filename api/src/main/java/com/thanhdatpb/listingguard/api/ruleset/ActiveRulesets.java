package com.thanhdatpb.listingguard.api.ruleset;

import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the configured rulesets at startup, validates them against the registered rules and records
 * each one in {@code ruleset_version}. Startup fails if a ruleset is invalid, or if its version is
 * already registered with different content (the version must be bumped for any change).
 */
@Component
public class ActiveRulesets implements SmartInitializingSingleton {

    /** A loaded ruleset together with its {@code ruleset_version} row id. */
    public record Active(RulesetConfig config, long rulesetVersionId) {
    }

    private final RulesetProperties properties;
    private final ResourceLoader resources;
    private final RuleEngine engine;
    private final RulesetVersionRepository versions;
    private final TransactionTemplate transactions;

    private volatile Map<String, Active> byMarketplace = Map.of();

    public ActiveRulesets(RulesetProperties properties, ResourceLoader resources, RuleEngine engine,
                          RulesetVersionRepository versions, TransactionTemplate transactions) {
        this.properties = properties;
        this.resources = resources;
        this.engine = engine;
        this.versions = versions;
        this.transactions = transactions;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Active> loaded = new HashMap<>();
        for (String location : properties.locations()) {
            RulesetConfig config = load(location);
            engine.validate(config);
            if (loaded.containsKey(config.marketplace())) {
                throw new IllegalStateException("More than one ruleset configured for marketplace " + config.marketplace());
            }
            loaded.put(config.marketplace(), register(config));
        }
        byMarketplace = Map.copyOf(loaded);
    }

    /** @throws IllegalStateException if no ruleset is active for the marketplace */
    public Active get(String marketplace) {
        Active active = byMarketplace.get(marketplace);
        if (active == null) {
            throw new IllegalStateException("No active ruleset for marketplace " + marketplace);
        }
        return active;
    }

    /**
     * Records the ruleset, or confirms it is already recorded with the same content hash.
     *
     * @throws IllegalStateException if the version exists with a different hash
     */
    public Active register(RulesetConfig config) {
        try {
            return transactions.execute(status -> registerOrConfirm(config));
        } catch (DataIntegrityViolationException e) {
            // Another instance inserted the same version between our read and write; re-read it.
            return transactions.execute(status -> registerOrConfirm(config));
        }
    }

    private Active registerOrConfirm(RulesetConfig config) {
        Optional<RulesetVersion> existing = versions.findByMarketplaceCodeAndVersion(config.marketplace(), config.version());
        if (existing.isPresent()) {
            RulesetVersion row = existing.get();
            if (!row.getContentHash().equals(config.contentHash())) {
                throw new IllegalStateException("Ruleset " + config.version() + " (" + config.marketplace()
                        + ") is already registered with content hash " + row.getContentHash()
                        + " but its content now hashes to " + config.contentHash()
                        + ". Do not edit a published ruleset; bump its version instead.");
            }
            return new Active(config, row.getId());
        }
        RulesetVersion saved = versions.saveAndFlush(new RulesetVersion(config.marketplace(), config.version(),
                config.contentHash(), config.checkedAt(), String.join("\n", config.sourceUrls())));
        return new Active(config, saved.getId());
    }

    private RulesetConfig load(String location) {
        try {
            return RulesetLoader.parse(resources.getResource(location).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RulesetException("Cannot read ruleset " + location, e);
        } catch (RulesetException e) {
            throw new RulesetException("Ruleset " + location + ": " + e.getMessage(), e);
        }
    }
}
