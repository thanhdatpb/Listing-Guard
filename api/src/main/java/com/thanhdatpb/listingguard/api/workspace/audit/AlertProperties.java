package com.thanhdatpb.listingguard.api.workspace.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The e-mail alerts of the background audit (ADR-061). Off by default and meaningful only while the audit is on.
 *
 * @param enabled      sends one summary e-mail to each person after an audit that found a new risk
 * @param dailyCap     the most e-mails one person gets in a day (UTC)
 * @param excerptChars how many characters of the listing text are quoted on each side of a match
 * @param maxRows      the most rows of the table in one e-mail; the rest is counted
 */
@ConfigurationProperties(prefix = "listing-guard.workspace.alerts.email")
public record AlertProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int dailyCap,
        @DefaultValue("40") int excerptChars,
        @DefaultValue("40") int maxRows) {

    public AlertProperties {
        if (dailyCap < 1 || excerptChars < 0 || excerptChars > 200 || maxRows < 1 || maxRows > 500) {
            throw new IllegalArgumentException("listing-guard.workspace.alerts.email: daily-cap must be at least 1, excerpt-chars 0 to 200 "
                    + "and max-rows 1 to 500.");
        }
    }
}
