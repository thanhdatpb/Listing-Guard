package com.thanhdatpb.listingguard.api.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operations this deployment announces as deprecated (ADR-022, section 6). Every response of such an operation carries
 * {@code Deprecation}, and {@code Sunset} and {@code Link} when they are set. Empty by default: nothing is deprecated.
 *
 * @param endpoints the deprecated operations; {@link ApiDeprecations#of} checks them at start-up
 */
@ConfigurationProperties(prefix = "listing-guard.api.deprecation")
public record ApiDeprecationProperties(@DefaultValue List<Endpoint> endpoints) {

    /**
     * @param method       the HTTP method of the operation, for example {@code GET}
     * @param path         the path exactly as {@code docs/api/openapi.json} writes it, for example {@code /api/v1/checks/{id}}
     * @param deprecatedAt when the operation is (or will be) deprecated: a date ({@code 2027-01-01}, midnight UTC) or an
     *                     ISO-8601 instant
     * @param sunsetAt     optional: after when it may stop working, at least 180 days after {@code deprecatedAt}
     * @param link         optional: an absolute {@code https} address of the migration notes
     */
    public record Endpoint(String method, String path, String deprecatedAt, String sunsetAt, String link) {
    }
}
