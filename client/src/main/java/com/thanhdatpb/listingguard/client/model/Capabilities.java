package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/**
 * The answer of {@code GET /api/v1/capabilities} (schema {@code Capabilities}): what the deployment serves, the same for every
 * client. A trademark catalog that is not {@code READY} means trademark risk is unknown, not low.
 *
 * @param packs            the analysis packs served over HTTP, sorted by id
 * @param trademarkCatalog the state of the trademark catalog
 * @param plans            whether plans and the monthly quota are applied
 * @param fixes            whether checks return fix suggestions
 */
public record Capabilities(
        List<PackCapability> packs,
        TrademarkCatalogCapability trademarkCatalog,
        PlansCapability plans,
        FixesCapability fixes) {

    public Capabilities {
        packs = Copies.list(packs);
    }
}
