package com.thanhdatpb.listingguard.client.model;

/**
 * The state of the trademark catalog (schema {@code TrademarkCatalogCapability}). The catalog's name is never shown.
 *
 * @param state {@code READY}, {@code DISABLED} or {@code REFUSED}
 * @param demo  true for the DEMO catalog of invented marks, whose results are not real
 */
public record TrademarkCatalogCapability(CatalogState state, boolean demo) {
}
