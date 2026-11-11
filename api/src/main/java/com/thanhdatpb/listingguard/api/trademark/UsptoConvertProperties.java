package com.thanhdatpb.listingguard.api.trademark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the {@code uspto-convert} job (ADR-009, PRs A2b and A2c). Nothing here changes the API or the checks.
 *
 * @param allowOtherVersion  read a file whose {@code version-no} is not 2.0, the one version of the Trademark
 *                           Applications Daily DTD the converter was written for. Off by default: the file is refused and
 *                           the message names the version it found.
 * @param statusMap          the versioned YAML that maps USPTO status codes to catalog statuses
 * @param filters            the versioned YAML with the Nice classes, the live status codes, the text-only rule and the
 *                           patterns that read a date from a file name ({@code classpath:} or {@code file:})
 * @param strict             stop at the first problem (a file that cannot be read, a record without a serial number or a
 *                           transaction date) instead of leaving the file out or counting the record; the job's
 *                           {@code --strict} turns it on too
 * @param maxDistinctSerials the most distinct serial numbers held in memory (about 17 to 34 bytes each) while the latest
 *                           record of each is decided; more stops the run
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.uspto")
public record UsptoConvertProperties(
        @DefaultValue("false") boolean allowOtherVersion,
        @DefaultValue("classpath:uspto/uspto-status-map-2026-11.yaml") String statusMap,
        @DefaultValue("classpath:uspto/uspto-convert-filters-2026-11.yaml") String filters,
        @DefaultValue("false") boolean strict,
        @DefaultValue("12000000") int maxDistinctSerials) {
}
