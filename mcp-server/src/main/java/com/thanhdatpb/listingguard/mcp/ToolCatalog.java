package com.thanhdatpb.listingguard.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * The seven tools (ADR-017). The list is closed: there is no tool that edits a listing, applies a fix, publishes or writes
 * anywhere, and none takes a URL or a path. The marketplace is a plain string, not an enum: the API is the only place
 * that knows which marketplaces exist and answers {@code 400 unknown-marketplace} for the others.
 */
final class ToolCatalog {

    static final String CHECK_LISTING = "check_listing";
    static final String CHECK_TRADEMARK = "check_trademark";
    static final String CHECK_VARIATIONS = "check_variations";
    static final String SCREEN_PERSONALIZATION = "screen_personalization";
    static final String GET_LIMITS = "get_limits";
    static final String GET_USAGE = "get_usage";
    static final String GET_CAPABILITIES = "get_capabilities";

    private static final String MARKETPLACE = """
            "marketplace": {"type": "string",
              "description": "Marketplace code, case-insensitive, for example ETSY or AMAZON. The API rejects an unknown one."}""";

    private static final String LISTING_SCHEMA = """
            {"type": "object",
             "properties": {
               %s,
               "listing": {"type": "object",
                 "description": "The listing in the marketplace's standard input format (docs/input-schema.md): Etsy Open API v3 style snake_case fields, or the Amazon simple format (title, brand, bullet_points, description, search_terms, product_type, price, quantity, images). Send what the seller wrote; do not correct or shorten it."}
             },
             "required": ["marketplace", "listing"],
             "additionalProperties": false}""".formatted(MARKETPLACE);

    private static final String VARIATIONS_SCHEMA = """
            {"type": "object",
             "properties": {
               %s,
               "parent": {"type": "object",
                 "description": "Amazon only: the parent, with optional sku, product_type, variation_theme and title. Ignored for Etsy."},
               "variation_properties": {"type": "array", "items": {"type": "string"},
                 "description": "Etsy only: the names of the variation types, for example [\\"Primary color\\", \\"Size\\"]. Ignored for Amazon."},
               "children": {"type": "array", "items": {"type": "object"},
                 "description": "The children: objects with sku, parent_sku (Amazon only), title and attributes (a map from an attribute name to its value)."}
             },
             "required": ["marketplace"],
             "additionalProperties": false}""".formatted(MARKETPLACE);

    private static final String PERSONALIZATION_SCHEMA = """
            {"type": "object",
             "properties": {
               %s,
               "values": {"type": "array",
                 "items": {"type": "object",
                   "properties": {"label": {"type": "string"}, "text": {"type": "string"}},
                   "required": ["text"]},
                 "description": "What the buyer typed, one object per field of the order: label (optional, for example name) and text. Send the text exactly as typed: it is not trimmed, because stray spaces are reported."},
               "print_profile": {"type": "string",
                 "description": "Optional code of a print profile declared in the ruleset. Leave it out for the default; an undeclared code is refused by the API."},
               "product_type": {"type": "string",
                 "description": "Optional free text naming the product, for example jersey."}
             },
             "required": ["marketplace", "values"],
             "additionalProperties": false}""".formatted(MARKETPLACE);

    private static final String LIMITS_SCHEMA = """
            {"type": "object",
             "properties": {
               %s,
               "product_type": {"type": "string",
                 "description": "Optional product type (Amazon) for the limits that differ by category."}
             },
             "required": ["marketplace"],
             "additionalProperties": false}""".formatted(MARKETPLACE);

    private static final String NO_ARGUMENTS_SCHEMA = """
            {"type": "object", "properties": {}, "additionalProperties": false}""";

    private final List<ToolSpec> tools;

    ToolCatalog(ObjectMapper mapper) {
        this.tools = List.of(
                new ToolSpec(CHECK_LISTING, "Check a listing",
                        "Check a marketplace listing (Etsy or Amazon) before it is published, with Listing Guard's "
                                + "deterministic rules. Returns the API's findings and suggestions with rulesetVersion, "
                                + "analysisVersion and cached, unchanged. It only checks: it never edits or publishes "
                                + "anything. A new result may use one unit of the client's monthly quota; repeating an "
                                + "identical request is a free cache hit. Findings are advice for a person to decide on; "
                                + "an empty list is not an approval by the marketplace.",
                        Endpoint.CHECKS, false, schema(mapper, LISTING_SCHEMA)),
                new ToolSpec(CHECK_TRADEMARK, "Check a listing for trademark references",
                        "Compare the text of a listing with the trademark catalog this Listing Guard deployment serves "
                                + "(same input as check_listing). The result is a reference warning, not a legal "
                                + "conclusion: show its disclaimer. riskLevel UNKNOWN or completeness INCOMPLETE is not "
                                + "low risk. When the deployment has no catalog the answer is status NOT_CONFIGURED with "
                                + "riskLevel UNKNOWN, not an error.",
                        Endpoint.TRADEMARK_CHECKS, false, schema(mapper, LISTING_SCHEMA)),
                new ToolSpec(CHECK_VARIATIONS, "Check the variations of a listing",
                        "Check the structure of a listing with variations: for Amazon a parent and its children, for "
                                + "Etsy the variation types and the children. A reference warning built on secondary "
                                + "sources and heuristics, not a statement of the marketplace: show its disclaimer. It "
                                + "proposes no fix and no replacement value.",
                        Endpoint.VARIATION_CHECKS, false, schema(mapper, VARIATIONS_SCHEMA)),
                new ToolSpec(SCREEN_PERSONALIZATION, "Screen personalization text",
                        "Screen the text a buyer typed for a custom product (a name, a number) before the order goes "
                                + "into production: character rules of a print profile in findings, and the trademark "
                                + "scan in trademark. Show both; neither replaces the other. The text is personal data: "
                                + "do not copy it into other tools. A reference warning, not a decision.",
                        Endpoint.PERSONALIZATION_SCREENINGS, false, schema(mapper, PERSONALIZATION_SCHEMA)),
                new ToolSpec(GET_LIMITS, "Read the limits of a ruleset",
                        "Read the numeric limits of the active listing-rules ruleset of a marketplace (title length, "
                                + "tag count and so on) as the rules declare them, optionally for one product type. "
                                + "Read only, not counted against the quota. Use it to answer what a limit is instead "
                                + "of guessing.",
                        Endpoint.RULESET_LIMITS, true, schema(mapper, LIMITS_SCHEMA)),
                new ToolSpec(GET_USAGE, "Read plan and usage",
                        "Read the calling client's plan, packs, monthly quota, usage and reset time. Read only, not "
                                + "counted against the quota. Use it before a large run.",
                        Endpoint.USAGE, true, schema(mapper, NO_ARGUMENTS_SCHEMA)),
                new ToolSpec(GET_CAPABILITIES, "Read what the deployment serves",
                        "Read what this Listing Guard deployment serves: its packs with the ruleset version per "
                                + "marketplace, the state of the trademark catalog (READY, DISABLED or REFUSED, and "
                                + "whether it is the DEMO catalog), whether plans and fixes are on. It is the same for "
                                + "every client and says nothing about this client's plan: use get_usage for that. A "
                                + "catalog that is not READY means trademark risk is unknown, not low. Read only, not "
                                + "counted against the quota. Use it before a run to know which checks can answer.",
                        Endpoint.CAPABILITIES, true, schema(mapper, NO_ARGUMENTS_SCHEMA)));
    }

    List<ToolSpec> tools() {
        return tools;
    }

    private static Map<String, Object> schema(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A tool schema is not valid JSON", e);
        }
    }
}
