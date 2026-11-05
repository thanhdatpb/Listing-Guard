package com.thanhdatpb.listingguard.api.trademark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the {@code uspto-convert} job (ADR-009, PR A2b). Nothing here changes the API or the checks.
 *
 * @param allowOtherVersion read a file whose {@code version-no} is not 2.0, the one version of the Trademark
 *                          Applications Daily DTD the converter was written for. Off by default: the job stops and
 *                          names the version it found.
 * @param statusMap         the versioned YAML that maps USPTO status codes to catalog statuses
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.uspto")
public record UsptoConvertProperties(
        @DefaultValue("false") boolean allowOtherVersion,
        @DefaultValue("classpath:uspto/uspto-status-map-2026-11.yaml") String statusMap) {
}
