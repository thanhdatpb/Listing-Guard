package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The last status the USPTO TSDR gave for a source record of a catalog mark (schema {@code TsdrStatusView}). It is information
 * read when the answer was built, not part of the stored result, and it never changes the finding's level. The status can be old:
 * read {@code checkedAt}.
 *
 * @param sourceId          the source id the status is for, for example {@code uspto:12345678}
 * @param statusCode        the status code the USPTO gave
 * @param statusDescription the USPTO's own description of the status, or {@code null}
 * @param statusClass       alive, dead or not settled, decided from the code by the deployment's versioned (unverified) map
 * @param statusDate        the date of the status the USPTO gave, or {@code null}
 * @param checkedAt         when the deployment asked the TSDR (or read a file saved from it)
 */
public record TsdrStatus(
        String sourceId,
        String statusCode,
        String statusDescription,
        TsdrStatusClass statusClass,
        LocalDate statusDate,
        Instant checkedAt) {
}
