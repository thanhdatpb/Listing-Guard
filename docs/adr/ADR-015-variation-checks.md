# ADR-015: Variation checks

- Status: Proposed
- Date: 2026-11-06
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (pack identity, cache identity, result storage),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (plans and quota; this record adds one pack and amends the seed),
  [ADR-006](ADR-006-category-rulesets-and-amazon-severity.md) (severity policy, product type as category),
  [ADR-009](ADR-009-trademark-risk-scanner.md) (the shape this pack follows: own endpoint, DTO, tables, retention)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

A listing with variations (a parent and its children) is a common source of errors for print-on-demand sellers who
sell one design as a shirt in many sizes and colors. The mistakes are structural, so they cannot be seen in the text
that `/checks` reads:

- **Amazon error 8024**: the variation theme is not valid for the category (apparel uses `Color-Size`; adding an
  attribute outside the theme, as in `Color-Size-Material`, is the mistake the source names), the children of one
  parent do not all fill the attributes of the theme, a child does not point at the SKU of its parent.
- **Amazon apparel size attributes**: `size_system`, `size_class`, `size`, `size_to` (needed for the `*_range` classes,
  and larger than `size`), `body_type`, `height_type`.
- **Different products under one parent** is a violation and can hide the whole parent.
- **Etsy**: at most two variation types per listing; with more than ten variations only ten can have a photo; some
  properties (Holiday, Occasion) cannot be variations.

### Sources, and what could be read

No official page is readable (the help pages need a login), so no value in this pack is `verified`
([ADR-001](ADR-001-rule-config.md): never mark a value `verified` without a URL, never guess a limit). The maintainer
named four secondary pages. Their state when this record was written (2026-11-06):

| Page | Read | Used for |
|---|---|---|
| godatafeed.com, "Amazon 8024 variation error" | Yes | Apparel uses `Color-Size`; `Color-Size-Material` is a mistake when the category allows only `Color-Size`; the parent has `parent_child` = parent, a child has `parent_child` = child and a `parent_sku` equal to the parent's SKU, children fill the attributes of the theme. The page gives no list of themes per category. |
| support.storeautomator.com, "New Apparel Size Requirements for Amazon" | **No: HTTP 403** | `size_system` `as1`..`as8`; `size_class` `numeric`, `numeric_range`, `alpha`, `alpha_range`, `age`, `age_range`; `size_to` required with `*_range` and larger than `size`; `body_type` `big`, `slim`, `regular`, `plus`; `height_type` required for some product types. Taken from the maintainer's brief, not re-read. |
| riverbendconsulting.com, "Product variations on Amazon" | **No: HTTP 403** | Putting different products under one parent is a violation. Taken from the brief, not re-read. |
| alura.io, "The seller's guide to Etsy listing variations" | Yes | "Each listing permits up to two types of variations"; "If there are more than 10 variations, only 10 can have linked photos"; "Some attributes like Holiday or Occasion don't support variations" (only these two examples, not a full list). |

A 403 is not worked around (no proxy, mirror or other trick); the two pages are listed for the maintainer to confirm
(Open questions 3). Anything the pack infers beyond these statements is `unverified` and says so.

## Decision

### A new pack, `variation-checks`

A separate analysis pack in the sense of ADR-010, built the way ADR-009 built the trademark pack: a Java-only module,
its own endpoint, DTO, result tables, cache identity, retention and plan pack. **`/checks` and `/trademark-checks` do
not change**: request, response, content hash, `analysisVersion` and cache are as they are, and neither endpoint
shows or depends on this one.

- Module `variation-checks/` (Maven artifact `variation-checks`, root package
  `com.thanhdatpb.listingguard.variation`): no Spring, no database, no HTTP. It depends on `core` for `Severity` only.
- `pack_id` is `variation-checks`.
- `POST /api/v1/variation-checks` and `GET /api/v1/variation-checks/{id}`, in `api/.../variation/`.
- Tables `variation_check` and `variation_check_finding` (migration V22). Plan pack `variation-checks` (migration V23).
- It checks and suggests. It never edits a listing, never publishes, proposes **no fix and no replacement value**, and
  does no SEO (a finding says what is inconsistent, not what to write).

### Input

A separate request, documented in `docs/input-schema.md` ("Variation checks"). It is not wrapped in `listing`: a family
is not a listing and the `/checks` input stays untouched. Same rules for every field as the rest of the schema:
unknown fields are ignored, strings are trimmed, a blank string is absent, a number or boolean is read as its text, an
object or array where text is expected is absent, a value that is not an array where an array is expected is an empty
list.

Amazon:

```json
{
  "marketplace": "AMAZON",
  "parent": {"sku": "TEE-P", "product_type": "SHIRT", "variation_theme": "Color-Size", "title": "Retro Sunset Tee"},
  "children": [
    {"sku": "TEE-RED-M", "parent_sku": "TEE-P", "title": "Retro Sunset Tee - Red - M",
     "attributes": {"color": "Red", "size": "M", "size_system": "as1", "size_class": "alpha", "body_type": "regular"}}
  ]
}
```

Etsy (a listing is its own parent, so there is no `parent`):

```json
{
  "marketplace": "ETSY",
  "variation_properties": ["Primary color", "Size"],
  "children": [
    {"sku": "TEE-RED-M", "attributes": {"Primary color": "Red", "Size": "M"}}
  ]
}
```

- `children[]` entries have `sku`, `parent_sku` (Amazon only, ignored for Etsy), `title` and `attributes`, a map of
  attribute name to value. The same shape serves both marketplaces; an Etsy child's attributes are keyed by the names
  in `variation_properties`.
- **The normalizer only trims and drops blanks.** It does not correct, translate, deduplicate or reorder anything: a
  key is trimmed, an attribute whose value is blank is dropped, an entry of `children[]` that is not an object or has
  no non-blank field is dropped, and a `parent` with no non-blank field is absent. `children[i]` in a field path
  counts the cleaned list (as `tags[i]` does in `/checks`).
- Matching is a rule's business, not the normalizer's: attribute names, `product_type`, themes, attribute values
  (for duplicates) and the closed value lists are compared ignoring case after NFC and trim; SKUs are compared exactly
  (Open questions 9). If two attribute names of one child differ only by case, the one that sorts first by code
  point is read, so the result does not depend on the order of the JSON.
- The attribute names of a theme are the lower-case words of the theme joined by `_` (`Color-Size` is `color` and
  `size`; `SizeName-ColorName` is `size_name` and `color_name`). They are the standard input's names, not Amazon's, and
  are listed per theme in the ruleset.

### Canonical input and hash

SHA-256 over `listing-guard/variation-input/v1`, then every field as a line `name:byteLength:text`, after Unicode NFC
and `strip`, in this order: the parent (a line `parent:1` or `parent:0`, then `sku`, `product_type`,
`variation_theme`, `title`), `variation_properties` (each item, then `variation_properties:count`), then the children
(for each: `sku`, `parent_sku`, `title`, its attributes sorted by name by code point as `attribute:keyBytes:key:valueBytes:value`,
then `attributes:count`), then `children:count`. Counts and length prefixes make the serialization unambiguous. Case is
kept, so `Red` and `red` are two inputs. The marketplace is not in the hash: it is a separate part of the cache
identity.

### Ruleset

`rulesets/variation-checks-2026-11.yaml`, in the module, one file for both marketplaces, registered in `ruleset_version`
for `ETSY` and `AMAZON` under the pack `variation-checks`, exactly as `trademark-risk-2026-11` is. Immutable
([ADR-002](ADR-002-ruleset-versioning.md)): the content hash is pinned by a test and startup fails if the version is
registered with another hash.

- **Everything a rule needs is a key**: lists of themes and product types, attribute names, closed value lists, the
  numbers (2 types, 10 photos), the words ignored when titles are compared, the severity of each finding kind, and the
  Vietnamese message and suggestion of each kind, with `{placeholders}` that the loader checks. No limit, list or
  message is in Java ([ADR-001](ADR-001-rule-config.md)).
- **The loader is strict** like the other two: an unknown or missing key, a duplicate key, an unknown rule or finding
  kind, an unknown placeholder, a rule declared twice, a theme used by a product type but not defined, a bad number
  fail at start-up. A key is read by the code that needs it and `finish()` fails on any key nobody read.
- **Every block has a `source`** (`status` and `urls`, with a `note` where the claim is ours). `secondary` needs at
  least one URL, `verified` needs one too, and **this version contains no `verified` value** (a test holds it).
  Severity follows [ADR-006](ADR-006-category-rulesets-and-amazon-severity.md) for the whole pack, Etsy included:
  **`ERROR` only when the rule's source is `verified`**, so nothing in this version is `ERROR`, and the loader refuses an
  `ERROR` for `children_differ_outside_theme` whatever its status. Raising a status or a severity is a new ruleset
  version.
- A rule has `marketplaces` (which of `AMAZON` and `ETSY` it runs for); the loader refuses a marketplace the rule's
  code cannot handle.
- The rules run in the declared order, which is also the order of the findings; inside a rule, findings follow the
  order of the children.

### Rules

A finding has a `ruleId`, a `kind` (the case inside the rule, stable like the `ruleId`), a `severity`, the rule's
`sourceStatus`, a `field` (`parent.sku`, `children[3].attributes.size`, `variation_properties[2]`), a `value` (the
offending text, cut to the ruleset's `evidence.max_value_chars`, `null` when nothing applies), `relatedFields` (the
other places the message refers to, for example the first child with the same combination), and a Vietnamese `message`
and `suggestion` that cite the real values and numbers. Positions in the text of a message are 1-based, the paths are
0-based.

**`parent_child_structure`** (Amazon, WARNING, secondary). Needs a parent that says its SKU and its theme, children that
point at that SKU and children that fill the attributes of the theme. Kinds: `parent_missing` (no parent or no parent
SKU), `parent_theme_missing`, `no_children` (INFO: nothing to check), `theme_attributes_unknown` (INFO: the theme is not
in the ruleset's theme table, so the attributes are not checked), `child_parent_sku_missing`,
`child_parent_sku_mismatch`, `child_attribute_missing` (one per missing attribute of the theme).

**`variation_theme_allowed`** (Amazon, WARNING, secondary). The theme must be one of those the ruleset lists for the
parent's `product_type`. Kinds: `theme_not_allowed` (WARNING; the message lists the allowed themes),
`product_type_not_covered` (INFO: the `product_type` is not in the ruleset, so "chưa có dữ liệu cho loại này" and
nothing is claimed), `product_type_missing` (INFO). A missing theme is `parent_theme_missing` only, so it is not
reported twice. Only themes with a source are listed (`Color-Size` is on the godatafeed page); nothing is guessed.
This is the same idea as the `category_not_covered` of ADR-006, with its own INFO because this pack's categories are
always a few product types, never "all but the defaults".

**`apparel_size_attributes`** (Amazon, WARNING, secondary), only when the parent's `product_type` is in the ruleset's
apparel list (otherwise the rule says nothing). For each child: the attributes of `required_attributes`
(`size_system`, `size_class`, `size`, `body_type`) must be present (`size_attribute_missing`); the attributes that have a
closed list (`size_system` `as1`..`as8`, `size_class`, `body_type`) must hold one of its values
(`size_attribute_invalid`); when `size_class` is one of the `*_range` classes, `size_to` must be present
(`size_to_missing`) and larger than `size` (`size_to_not_greater`, also when equal). The comparison is per class:
`numeric_range` as decimal numbers, `alpha_range` by the position in the ruleset's `alpha_scale` (an ordered list; labels
that share a rank are listed together), `age_range` is not compared. When the two values cannot be compared (not a
number, not in the scale, an age range) the finding is INFO `size_range_not_comparable`, never a warning: the alpha scale
is our own ordering of common labels (`unverified`) and the sources give no list of valid sizes. `height_type` is not
checked: neither a list of values nor the product types that require it is available.

**`duplicate_combination`** (both marketplaces, WARNING, unverified). Two children with the same value for every
attribute of the theme (Amazon: the attributes of the parent's theme; Etsy: the names in `variation_properties`), compared
ignoring case. One finding for each later child, naming the first. A child that lacks an attribute of the theme has no
combination and is skipped (the structure rule reports the gap); when the attributes of the theme are not known, the
rule says nothing.

**`duplicate_sku`** (both, WARNING, unverified). A child SKU that appears on an earlier child or equals the parent's SKU.
One finding for each later occurrence, naming the first. Children without a SKU are skipped.

**`children_differ_outside_theme`** (both, WARNING at most, unverified, never `ERROR`). Detects children that look like
different products put under one parent. For each child with a title: lower-case the title, split it into tokens (runs of
letters and digits), remove the tokens that are words of the values of the child's own theme attributes (when the
theme's attributes are not known, of all its attributes) and the words in the ruleset's `ignore_words` (size and color
words), keep the order. If the remainders are not all equal, the children are grouped by remainder; the largest group
(a tie goes to the group whose first child comes first) is the reference, and **each child outside it gets a finding**
whose `value` is its remainder, whose `relatedFields` names the first child of the reference group, and whose message
quotes both remainders. With fewer than two children that have a title nothing is compared (an Etsy child normally has
no title). It is a heuristic and the message says "có thể", not "vi phạm": the claim that mixing products is a violation
comes from a page that could not be re-read, and whether two titles describe two products is not something code can
know.

**`etsy_variation_limits`** (Etsy). `too_many_variation_types` (WARNING, secondary) when the number of distinct
`variation_properties` (ignoring case) exceeds `max_variation_types` (2); `variation_type_not_supported` (WARNING,
secondary), one per property in `unsupported_properties` (`Holiday`, `Occasion`, the two the page names; the list is not
complete and the message says so); `many_variations_photo_limit` (INFO, secondary) when the number of children, the
combinations, exceeds `photo_variation_limit` (10), to remind that only that many variations can have a photo. The
property is matched ignoring case.

### Result and completeness

The response has `completeness` (`COMPLETE`, or `INCOMPLETE` when a rule threw), `failedRuleIds`, a `summary`
(`errors`, `warnings`, `infos`), the findings, a `disclaimer` and the usual identity members. A rule that throws is
logged, reported in `failedRuleIds`, and the findings of the other rules are kept, as in
[ADR-010](ADR-010-pack-identity-and-analysis-version.md); the legacy `RuleEngine` log-and-skip is untouched. There is no
`riskLevel`: this pack has no risk scale, so the "UNKNOWN, never LOW" rule of ADR-009 has nothing to apply to; what
replaces it is that an `INCOMPLETE` result is never reported as clean, never cached and never charged. A rule that applies
to a marketplace but cannot run for lack of data (no theme, unknown theme) says so with an INFO where the table above
has one, and is otherwise silent: silence means "nothing found by the rules that could run", and the disclaimer says that
a result is a reference warning, not a statement of the marketplace.

### Data, privacy and retention

Same as the trademark pack ([ADR-009](ADR-009-trademark-risk-scanner.md) "Data, privacy and retention"), nothing new in
principle. The submitted family is **not stored**, only the canonical input hash, the result and each finding's bounded
evidence (`field`, `value` cut to `evidence.max_value_chars`, `relatedFields`, the message). Evidence is client-derived
text (a SKU, a theme, a title fragment), so it lives and dies with the result. Retention is
`listing-guard.variation.retention.period`, 30 days by default (an operational proposal, not a guarantee);
`expires_at` is decided on read by GET and the cache lookup, whatever the cleanup does; an expired id, an unknown id and
another client's id are the same `404` (`variation-check-not-found`).

Tables (V22), mirroring V17: `variation_check` (id, `client_id`, `marketplace_code`, `pack_id`, `input_hash`,
`ruleset_version_id`, `analysis_version`, `completeness`, `failed_rule_ids`, `disclaimer`, `created_at`, `expires_at`;
CHECKs on `completeness` and `expires_at > created_at`) and `variation_check_finding` (`check_id` with
`ON DELETE CASCADE`, `position`, `rule_id`, `kind`, `severity`, `source_status`, `field`, `value`, `related_fields`,
`message`, `suggestion`; CHECKs on `severity` and `source_status`; `value` has a ceiling in the table).

Cleanup is a scheduled task of its own (`VariationRetentionCleanup`, the same shape as the trademark one: bounded batches,
oldest expiry first, `FOR UPDATE SKIP LOCKED`, a web application only, `cleanup-enabled`), in each batch transaction
under `pg_try_advisory_xact_lock(1279746628, 1380271182)`. That key pair is the one ADR-010 reserves for "Listing Guard
retention cleanup" and this is a retention cleanup, so it is reused, not a new pair; a run that cannot take the lock is
skipped and tried again at the next interval, and the start delay differs from the trademark one so they rarely meet. The
code takes the key constants from `TrademarkRetentionCleanup`. See Open questions 7: `CLAUDE.md` words the reservation
more narrowly.

### Cache

Identity is `(client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)`, ADR-010's, which
contains the `(client, marketplace, hash, analysisVersion)` of the request and adds the pack and the ruleset row. A
partial unique index on `completeness = 'COMPLETE'` enforces it; every lookup also requires `expires_at > now()`; an
`INCOMPLETE` result is stored so GET can read it and is never returned for another request; an expired complete result is
replaced in one transaction (delete, insert), and the loser of a race on `uq_variation_check_cache` (recognized by
SQLSTATE `23505` and the index name only) answers from the winner's row. Nothing is shared between clients. All as in
ADR-009 "Endpoint (PR A3)".

`analysisVersion` is the pack fingerprint: SHA-256 over `listing-guard/variation-fingerprint/v1` and the lines
`pack_id`, `ruleset_version`, `ruleset_hash`, `input_version` (`variation-input-1`, the canonical input format),
`rules_version` (`variation-rules-1`, the comparison logic: how values, titles and SKUs are compared) and `marketplace`.
There is no external dataset. A change to the ruleset, to the way values are compared or to the canonical input format
changes it, so old results are not reused.

### Plans and quota ([ADR-013](ADR-013-client-plans-and-monthly-quota.md))

- A new pack id `variation-checks`: the Java enum `Pack` gets `VARIATION_CHECKS`; migration **V23** replaces the CHECK
  `ck_plan_pack_pack_id` (V19 is not edited) and gives the pack to `SELLER` and `PRO` (`ON CONFLICT DO NOTHING`, so an
  operator's own rows are respected). `FREE` does not get it. The test that holds the enum equal to the constraint reads
  the constraint as it is after V23.
- Order in `VariationCheckService`: `requirePack` (403 `plan-not-allowed`), cache, `requireQuota` (429), analysis,
  `charge` inside the storing transaction. **One unit is one new `COMPLETE` result**, per ADR-013's "per endpoint";
  cached results, GETs and `INCOMPLETE` results cost nothing. While plans are off nothing is queried or written.
- This amends ADR-013 in two places and nothing else: the pack list and seed gain one entry, and a "full check" of a
  listing that has variations is three units when the client calls all three endpoints (Open questions 5).

### Errors

`400 invalid-request` (body missing or not JSON, `marketplace` missing), `400 unknown-marketplace`, `422
marketplace-not-yet-supported` (through `NormalizerRegistry`, as for the other endpoints), `401`, `403 plan-not-allowed`,
`404 variation-check-not-found`, `413`, `415`, `429` (`rate-limited` or `quota-exceeded`), as the integration guide
describes. There is no `503`: the pack needs no catalog or model. The rate limit is the client's, shared.

### Not done

Fix suggestions or any proposed value; checking the variation family against the listing text of `/checks` or the
trademark pack; Amazon feed or SP-API attribute formats (a client maps its own format to the standard input); other
Amazon stores than the US one; `height_type`; the list of valid `size` values; a limit on the number of children beyond
the 256 KB body; prices, quantities, images or stock per child; Etsy inventory API formats; SKU format rules; themes or
product types without a source; per-client rules; a console page; the `batch-check` job (a CSV row is a listing, not a
family); the `GET /api/v1/rulesets/{marketplace}/limits` endpoint (it reads the `listing-rules` pack); and an
evaluation dataset (a later PR, like ADR-009 A4, if the maintainer wants numbers).

## Consequences and tradeoffs

- A client with variations calls a third endpoint and, where plans are on, spends a unit more. The checks are cheap and
  local; the cost is a new surface (endpoint, tables, cleanup, docs) to keep in step with the others.
- Most findings are `unverified` or `secondary` and WARNING at most. That is the honest state of the sources; it gives a
  path (confirm a page, raise the status in a new ruleset version) rather than a false certainty.
- `children_differ_outside_theme` will have false positives (a style number in a title) and false negatives (two products
  with similar titles). Being a WARNING with the differing text as evidence lets the seller judge.
- The ruleset is small on purpose: one apparel product type and two themes. A product type outside it is reported as
  "no data", not as an error, so the pack does not punish what it does not know.
- Keeping messages in the ruleset makes a wording change a new ruleset version and a new set of cached results, the
  price already accepted for the trademark pack.

## Acceptance criteria (tests)

Synthetic data only, no company data. Thresholds, lists, themes and attribute names are read from the ruleset, never
repeated in a test.

1. **Module**: a shirt with one parent and children `S`..`5XL` × two colors, themed as the ruleset allows and filled with
   valid apparel attributes, gives no structure finding (`parent_child_structure`, `variation_theme_allowed`,
   `apparel_size_attributes`, `duplicate_*`). A child missing the size; a child whose `parent_sku` differs, and one with
   none; a theme not in the list of a declared product type; a product type that is not declared (INFO, no error); a
   repeated combination; a repeated SKU, among children and with the parent; the two titles "Retro Sunset Tee - Red - M"
   and "Mountain Hike Tee - Red - L" (`children_differ_outside_theme` with the differing text as evidence, and the same
   family with only size and color different gives none); `alpha_range` without `size_to`, with `size_to` smaller than
   `size`, and equal; `numeric_range` compared as numbers; an uncomparable range (INFO); Etsy with three variation types,
   with `Holiday`, and with one combination more than the photo limit (INFO) and exactly the limit (nothing).
2. **Completeness**: a rule that throws gives `INCOMPLETE`, `failedRuleIds` names it, and the other findings stay.
3. **Ruleset**: the content hash is pinned; every key is read (a stray key fails); a `verified` status without a URL, a
   `secondary` one without a URL, an `ERROR` that is not `verified`, an `ERROR` for `children_differ_outside_theme`, an
   unknown placeholder, a theme used but not defined, and a missing finding kind all fail; the shipped file has no
   `verified` value; every message avoids the words "đã được xác nhận" and "an toàn" (no claim of approval).
4. **Hash and normalization**: the hash is the same for JSON keys in another order and for extra whitespace, differs for
   a different case, and `children[i]` counts the cleaned list; the normalizer trims and drops blanks and changes nothing
   else; the Etsy and Amazon examples of `docs/input-schema.md` are read out of the file and normalized by a test.
5. **API** (Testcontainers): a fresh request is `cached: false`, the same request again is `cached: true` with the same
   `checkId`; another client's `GET` is `404` exactly like an unknown id and an expired one; the stored row holds a
   hash and bounded evidence and **no submitted text** beyond it; two identical concurrent requests store one result;
   expiry decided on read and by the cache lookup, the cleanup deleting expired rows under the advisory lock; the
   `INCOMPLETE` result is stored for GET and never reused; a different ruleset or analysis version is a cache miss.
6. **Plans**: with plans on and a plan without the pack, `403 plan-not-allowed`; `SELLER` and `PRO` are allowed and `FREE`
   is not; a cached answer is free, also at the limit; a new complete result is one unit; `INCOMPLETE` is not charged; with
   plans off nothing is queried or written. The enum `Pack` equals the constraint after V23, and V23 applied to a V22
   database seeds the two plans without touching other rows.
7. **Contract**: `docs/api/openapi.json` shows both routes and the new problem codes, and a test fails on drift.
   `/checks` and `/trademark-checks` are unchanged: their OpenAPI parts, hashes and tests pass as they are.

## Planned pull request

One pull request (`feat/variation-checks-pack`), in small commits: this ADR; the module and its ruleset; the pack, the
tables and the cleanup in `api` (V22); the plan pack (V23); the docs (`docs/api/openapi.json`, `docs/input-schema.md`,
`docs/integration-guide.md`, `README.md`, `CLAUDE.md`). `docs/releases` is not touched. If another migration reaches
`main` first, V22 and V23 are renumbered before the merge.

## Open questions

Written as questions, not decided here; each says what this record assumed meanwhile. None of them contradicts an
earlier ADR, but 5 and 7 touch a decision of one and need an answer before this record is Accepted.

1. **Apparel product types.** The ruleset ships one, `SHIRT`, marked `unverified`: it is the product type name used for
   shirts by Amazon's product type definitions, but no readable page says so, and the pages above name no product type.
   Which others belong in the apparel list (and in the theme table)?
2. **Themes per product type.** `SHIRT` allows `Color-Size` (on the godatafeed page) and `SizeName-ColorName` (named in the
   maintainer's brief, absent from the page text that could be read). The attribute names of a theme (`color`, `size`,
   `size_name`, `color_name`) are the standard input's own. Which other themes, for which product types, and what are the
   attribute names Amazon uses for `SizeName-ColorName`?
3. **Two pages were not readable (HTTP 403)** and their statements are in the ruleset from the brief: the value lists of
   `size_system`, `size_class` and `body_type`, the `size_to` rule, and the mixed-products statement. Please confirm them
   against the pages, or send saved copies.
4. **Lists that are ours (`unverified`).** The `alpha_scale` order (`xxs` … `5xl`, with `small`/`medium`/`large` and
   `xxl`/`2xl`, `xxxl`/`3xl` as synonyms) and the size and color words ignored when titles are compared. Add or remove
   words as the real catalog needs.
5. **Cost.** One unit for a new complete result, like the other endpoints. A listing with variations then costs three
   units where a listing without costs two, which changes the ADR-013 price-list sentence "about 500 full listing checks
   for 1000 units" for such sellers. Keep one unit, or price this pack differently?
6. **Plan seed.** `variation-checks` goes to `SELLER` and `PRO`, not `FREE`. Confirm.
7. **Advisory lock and cleanup task.** ADR-010 reserves the key pair for "Listing Guard retention cleanup" and speaks of
   "one scheduled cleanup task"; `CLAUDE.md` and the class comment of `TrademarkRetentionCleanup` say it is reserved for
   the trademark cleanup and that no other query may take it. This record follows ADR-010 (same key pair, a second task
   with its own class, a skipped run is retried), and `CLAUDE.md` is worded to match. Alternatives: one generic cleanup
   for all packs' tables (a refactor of the trademark code), or a second key pair (ADR-010 would need an amendment).
8. **`height_type`** is not checked. Which values are valid, and for which product types is it required?
9. **SKU case.** SKUs are compared exactly, attribute values and themes ignoring case. If SKUs are case-insensitive for the
   marketplace, `duplicate_sku` and `child_parent_sku_mismatch` should compare ignoring case.
10. **Etsy "combinations".** The photo reminder counts the children sent, one per combination. A client that sends only the
    property names and not the children gets no reminder.
