package com.thanhdatpb.listingguard.api.capabilities;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /api/v1/capabilities} returns: what this deployment serves, the same for every client (ADR-017). It holds
 * no setting that is a secret, no catalog name, no path and nothing about any client or plan of a client.
 */
public record Capabilities(
        @Schema(description = "The analysis packs this deployment serves over HTTP, sorted by id. `batch`, `policy-analyzer` and "
                + "`sheet-connector` are not listed: they have no endpoint here.")
        List<PackCapability> packs,
        @Schema(description = "The trademark catalog this deployment serves. Only its state, never its name.")
        TrademarkCatalogCapability trademarkCatalog,
        @Schema(description = "Whether plans and the monthly quota are applied in this deployment.")
        PlansCapability plans,
        @Schema(description = "Whether `POST /api/v1/checks` returns verified fix suggestions and readiness.")
        FixesCapability fixes) {

    public record PackCapability(
            @Schema(description = "The pack id (ADR-010).", example = "listing-rules")
            String id,
            @Schema(description = "Whether the pack can answer now. False for `trademark-risk` while the catalog is not READY; "
                    + "the endpoint then answers `503 trademark-not-configured`.", example = "true")
            boolean available,
            @Schema(description = "The version of the active ruleset per marketplace (upper case, sorted): the `rulesetVersion` "
                    + "of a result.", example = "{\"AMAZON\": \"amazon-2026-12c\", \"ETSY\": \"etsy-2026-12\"}")
            Map<String, String> rulesetVersions) {
    }

    public record TrademarkCatalogCapability(
            @Schema(description = "`READY`: trademark checks are served. `DISABLED`: no catalog is configured. `REFUSED`: a catalog "
                    + "is configured but cannot be served. In the last two the risk of a listing is unknown, never low.",
                    allowableValues = {"READY", "DISABLED", "REFUSED"}, example = "READY")
            String state,
            @Schema(description = "True when the configured catalog is the DEMO catalog of invented marks: a trademark result is "
                    + "then not real.", example = "false")
            boolean demo) {
    }

    public record PlansCapability(
            @Schema(description = "True when plans are on: packs are limited by plan and new results count against a monthly "
                    + "quota (read `GET /api/v1/usage` for the calling client). False: nothing is limited.", example = "false")
            boolean enforced) {
    }

    public record FixesCapability(
            @Schema(description = "True when `POST /api/v1/checks` adds verified fix suggestions and `listing-rules` readiness.",
                    example = "false")
            boolean enabled) {
    }
}
