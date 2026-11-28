package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.hygiene.ProductNouns;
import com.thanhdatpb.listingguard.ruleengine.hygiene.QuantityFacts;
import com.thanhdatpb.listingguard.ruleengine.hygiene.RecipientFacts;
import com.thanhdatpb.listingguard.ruleengine.hygiene.SizeFacts;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The pack {@code clone-leftover} (ADR-039 §4): the word lists and the one rule, {@code clone_leftover_from_source}, that compare a clone with its
 * original. It is a versioned, immutable file like a ruleset ({@code workspace/clone-leftover-2026-12.yaml}, read by {@link RulesetLoader}, with
 * its own content hash), and it is not part of any marketplace ruleset: the rule engine never runs it and {@code /api/v1/checks} never reads it.
 * The file is read once at startup; an invalid file stops the application, like an invalid ruleset.
 */
@Component
@AuditEnabled
public class CloneLeftoverPack {

    public static final String RESOURCE = "workspace/clone-leftover-2026-12.yaml";
    public static final String PACK = "clone-leftover";
    public static final String RULE_ID = "clone_leftover_from_source";

    private final RulesetConfig config;
    private final SizeFacts size;
    private final QuantityFacts quantity;
    private final RecipientFacts recipient;
    private final ProductNouns nouns;
    private final List<String> fields;

    public CloneLeftoverPack() {
        this(RulesetLoader.loadClasspath(RESOURCE));
    }

    CloneLeftoverPack(RulesetConfig config) {
        if (!PACK.equals(config.packId())) {
            throw new RulesetException(RESOURCE + ": pack_id must be " + PACK + ", got " + config.packId());
        }
        this.config = config;
        RuleSettings rule = config.rule(RULE_ID);
        this.fields = rule.stringListParam("fields");
        this.size = SizeFacts.from(config.rule("size"));
        this.quantity = QuantityFacts.from(config.rule("quantity"));
        this.recipient = RecipientFacts.from(config.rule("recipient"));
        this.nouns = ProductNouns.from(config.rule("product_noun"));
    }

    public String version() {
        return config.version();
    }

    /** What an audit records of the pack: its version and the hash of its text, so a changed file audits the clones again. */
    public String identity() {
        return config.version() + ":" + config.contentHash();
    }

    public com.thanhdatpb.listingguard.core.finding.Severity severity() {
        return config.rule(RULE_ID).severity();
    }

    /** The base names of the clone's texts that are read ({@code title}, {@code bullet_points}, ...). */
    public List<String> fields() {
        return fields;
    }

    SizeFacts size() {
        return size;
    }

    QuantityFacts quantity() {
        return quantity;
    }

    RecipientFacts recipient() {
        return recipient;
    }

    ProductNouns nouns() {
        return nouns;
    }
}
