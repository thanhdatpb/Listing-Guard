# Listing input adapters

An adapter reads a seller's local export and maps each record to the standard input in [`input-schema.md`](input-schema.md).
It never edits a listing or sends data to Etsy, Amazon, Shopify or another marketplace. Keep store-specific settings and
real exports out of the repository.

## Use a built-in

The read-only `GET /api/v1/input-adapters` endpoint lists the installed adapter ids, destination marketplaces, accepted
formats, required columns, experimental status and family behavior. The templates live in
`listing-input-adapters/src/main/resources/adapters/`. The legacy YAML mapping files under `batch/mappings/` continue to
work with `batch-check`.

Shopify's template is experimental and targets AMAZON: `Vendor` maps to `brand`, `Type` to `product_type`, and `Tags` to
`search_terms`. These are approximations; review them before using the result. Shopify parent/variant rows are not
automatically equivalent to Amazon parent/child listings.

## Write a YAML adapter

Copy the smallest matching template. Set a stable `id`, one target `marketplace` (`ETSY` or `AMAZON`), a `format`,
delimiter and encoding for CSV, and `required_columns` that uniquely identify the format. `fields` maps source header
names to keys in the target section of `input-schema.md`; names are exact, including case and spaces. Use `experimental:
true` when a mapping is an approximation or has not been reviewed against a representative export. Never add a field that
is not in the standard input schema.

The reader streams rows and enforces caller-supplied byte and row limits. It reports the starting line of each record,
including records whose quoted cell spans multiple lines. A malformed encoding or CSV stops the read; a field conversion
problem is returned with its field name so the caller can report it without losing other rows. Blank values are absent,
and unknown source columns are ignored.

### Minimal CSV template

```yaml
id: example-csv
marketplace: ETSY
format: csv
delimiter: ","
encoding: UTF-8
required_columns: [Listing ID, Listing title]
fields:
  title: Listing title
```

Use the existing example files and [`batch-check.md`](batch-check.md) for legacy batch mapping syntax. A new adapter should
include synthetic tests for its header detection, BOM, delimiter, quoted line breaks, missing and unknown columns,
encoding errors, row and byte limits, and any variation-family grouping it declares.
