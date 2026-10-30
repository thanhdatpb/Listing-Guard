package com.thanhdatpb.listingguard.api.trademark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the {@code trademark-import} job writes a catalog (ADR-009, PR A2b).
 *
 * @param batchSize how many records are sent to the database in one JDBC batch while a data file is read. The file is
 *                  read as a stream, so this (and not the size of the file) bounds the memory of the import. The whole
 *                  import is still one transaction.
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.import")
public record TrademarkImportProperties(@DefaultValue("5000") int batchSize) {

    public TrademarkImportProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("listing-guard.trademark.import.batch-size must be at least 1");
        }
    }
}
