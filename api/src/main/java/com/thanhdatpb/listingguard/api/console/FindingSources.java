package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.Source;
import java.net.URI;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Where a listing rule's threshold comes from and how well it is backed (ADR-041, the source line of the finding card), read from the
 * ruleset that is active now. The source is the address the ruleset names for the rule, shown as its host name ("developers.etsy.com")
 * because the rule's note is a remark about how the number was found, not a name; the verification word ("verified", "secondary
 * source", "unverified") is the console's, in the language of the page (ADR-028).
 *
 * <p>It only reads. A rule the active ruleset does not declare, or a marketplace it does not serve, has no source line: the page
 * says nothing rather than guess.
 */
@Component("findingSources")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class FindingSources {

    private final ActiveRulesets rulesets;
    private final ConsoleMessages messages;

    public FindingSources(ActiveRulesets rulesets, ConsoleMessages messages) {
        this.rulesets = rulesets;
        this.messages = messages;
    }

    /** The source line of a rule of the active {@code listing-rules} ruleset, or null. */
    public String listingRule(String marketplace, String ruleId) {
        if (marketplace == null || ruleId == null) {
            return null;
        }
        Optional<Source> source = rulesets.find(RulesetConfig.DEFAULT_PACK_ID, marketplace)
                .map(active -> active.config().rules().get(ruleId)).map(RuleSettings::source);
        if (source.isEmpty()) {
            return null;
        }
        String what = source.get().urls().isEmpty() ? null : host(source.get().urls().get(0));
        String status = switch (source.get().status()) {
            case VERIFIED -> messages.get("finding.status.verified");
            case SECONDARY -> messages.get("finding.status.secondary");
            case UNVERIFIED -> messages.get("finding.status.unverified");
        };
        return what == null ? messages.get("finding.source", status) : messages.get("finding.source.status", what, status);
    }

    /** The host name of an address, or the address itself when it has none. */
    private static String host(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? url.strip() : host;
        } catch (IllegalArgumentException e) {
            return url.strip();
        }
    }
}
