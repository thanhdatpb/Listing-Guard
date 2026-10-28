# ADR-006: Rulesets with per-category overrides, and a stricter severity policy for Amazon

- Status: Accepted
- Date: 2026-10-28

Amazon is the second marketplace. Unlike Etsy, many of its limits depend on the product type (Amazon's own
schemas are published per product type, for example `LUGGAGE`), and most of the numbers we can read today are
not in documents we can fetch: the Seller Central help pages need a seller login, and the figures we have come from
an Amazon announcement and from Amazon staff in the Seller Forums. This record covers how a ruleset expresses
per-category values, and how severity is chosen when the sources are weak.

## One file per marketplace version, defaults plus overrides

`amazon-2026-11.yaml` keeps the structure of ADR-001 and ADR-002: a rule's top-level keys are its defaults.
A rule may add `categories`, a mapping from category to the keys it overrides:

```yaml
title_length:
  severity: WARNING
  max_length: 200
  source: { status: secondary, urls: [...] }
  categories:
    LUGGAGE:
      max_length: 150
      source: { status: verified, urls: [...] }
```

The loader merges each override over the defaults, so a rule sees one flat set of settings for the listing's
category. An override can change `severity`, `enabled` and any key the rule already defines; it cannot introduce a
key (a typo would otherwise silently do nothing) and cannot nest. Every override must carry its own `source`,
because the reason to override is usually a different, better source. Everything is checked when the ruleset is
loaded or the application starts: an unknown key, a missing or sourceless override, a duplicate category (names
ignore case) and a malformed override value all stop the start-up. The rules validate the defaults and every
override. The version and the content hash cover the whole file, so changing one override needs a new version
(ADR-002).

`enabled: false` switches a rule off without deleting it. Its parameters are still validated and its source stays
on record. The engine does not run it. An override can switch a rule off for one category, or on for one category
while it stays off by default.

## The category is the product type

A listing's category is its `product_type`, the Amazon concept that the Listings Items API requires and that the
product type schemas are keyed by. It is matched ignoring case and blanks. Browse nodes were rejected: they are
numeric, per store, very numerous, and nothing official ties a limit to one.

A product type with no override anywhere in the ruleset gets the defaults and one `INFO` finding,
`category_not_covered`, saying so. It is a statement about the ruleset, not a problem in the listing, and it tells
the caller that category-specific limits are not being checked. A listing without a product type gets the
`required_fields` ERROR instead (the product type is the one field with an official source) and no INFO. No
product type is overridden in `amazon-2026-11`: the numbers are not available yet. The mechanism is tested with a
separate test ruleset in `src/test`, not with invented categories in the real one.

## Severity: stricter for Amazon than for Etsy

Etsy uses ERROR for hard limits that are verified, and also for two limits that are only secondary but consistent
across sources (`tag_count`, `tag_length`); that decision stays. Amazon uses ERROR only for `verified` values.
Secondary and unverified values are WARNING. The reasons:

- The Etsy secondary values are the same in several independent places. The Amazon ones come from one
  announcement, forum replies and quoted help text, and some sources disagree (bullet length is reported as 100,
  256, 500, or 1000 characters in total; search terms as 249, 250 or 500).
- A wrong ERROR blocks a seller from publishing something valid; a wrong WARNING costs a glance.
- It gives a clear path: when a value is confirmed (a saved help page, a product type schema), a new ruleset
  version raises the status and the severity together. A test fails if a non-verified Amazon rule is ERROR.

`bullet_length` is in the ruleset with `enabled: false` because its sources contradict each other. Today the
only Amazon ERROR is `required_fields` for `product_type`.

## The listing model and the cache

`Listing` gains `brand`, `bulletPoints`, `searchTerms` and `productType`, used by Amazon; the Etsy constructor is
kept. The content hash leaves these four out while they are empty, so the hash of every Etsy listing is exactly what
it was and cached Etsy results stay valid (a test pins an Etsy hash computed independently of the code). Cache
identity is unchanged (ADR-005): `(marketplace, contentHash, analysisVersion)`, where the analysis version of an
Amazon check is its ruleset version, `amazon-2026-11`.

## Consequences

- The string rules are English (repeated words and their exempt list), so the ruleset targets the US store. Other
  stores need their own ruleset or a language key; not decided here.
- Raising a severity or adding a category override is a new ruleset version and a new set of cached results.
- The exempt-word list for repeated title words is ours (Amazon publishes none) and is marked as such.
- Phone numbers and addresses are not checked by `prohibited_content`: a regex cannot tell them apart reliably.
