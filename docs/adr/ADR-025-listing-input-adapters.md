# ADR-025: Listing input adapters

- Status: Proposed
- Date: 2026-11-12

## Context

Listing input files come from several independent systems. The current batch mapping describes a CSV column mapping in
the job package, while the console editor has its own form-to-schema mapper. New vendor formats should not require
changes to batch or console code. Input adapters are read-only: they convert a file record to the standard listing input
in [`docs/input-schema.md`](../input-schema.md); they never call a marketplace or write a listing.

## Decision

Add the Java-only `listing-input-adapters` Maven module. Its `ListingInputAdapter` contract names an adapter, destination
marketplace and supported formats; detects a delimited format from its header; and opens a streaming reader that returns
the normalized listing, source line and field-scoped errors. Readers enforce byte and record limits. Adapter templates
are YAML resources; a store-specific instance is configuration, not Java code. The API only exposes template metadata.

The legacy `BatchMapping` YAML remains accepted for compatibility. Existing Etsy and Amazon mappings remain under
`batch/mappings/`; their mapping and row conversion behavior is held by byte-for-byte batch report comparison tests.
New adapters are described in `docs/adapters.md`. Batch and the console share the adapter module's input contract.

The built-ins are `etsy-listings-export` (ETSY), `amazon-flat-file` (AMAZON), `shopify-product-csv` (AMAZON,
experimental), and `generic-json` (standard JSON listings; the target marketplace is chosen in adapter configuration).
Shopify's `Vendor` and `Type` columns can be approximated as Amazon `brand` and `product_type`, and Shopify tags as
Amazon search terms, but these fields are not semantically identical. The adapter remains experimental and results need
operator review. It does not claim that Shopify is an Amazon export.

Amazon parent/child rows are identified by their relationship and parent SKU columns and grouped under one family key
for variation checks. They remain separate listing inputs for the ordinary listing check.

Add `GET /api/v1/input-adapters`, a read-only, additive API response listing each adapter, target marketplace, supported
media types, required header columns, experimental status and family behavior. No input file or listing content is sent
to this endpoint. The OpenAPI contract and ADR-022 compatibility report are updated in the same change.

## Consequences

- Adding a CSV or JSON format is a YAML template plus tests when it fits the existing contract; no marketplace write
  functionality is introduced.
- The API v1 addition is additive and makes no change to existing request schemas, hashes, pack hashes or
  `analysisVersion` values.
- Shopify remains explicitly experimental until a maintainer validates every field mapping against the intended use.

## Open questions

- Should a later release add a dedicated Shopify target schema instead of the experimental Amazon approximation?
- Which Amazon feed variants and relationship column values should be promoted from unverified examples to reviewed
  adapter definitions?
