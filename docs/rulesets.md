# Rulesets

## Etsy and Amazon rules

`rule-engine/src/main/resources/rulesets/etsy-2026-12.yaml` holds every threshold, word list and
severity. Each rule records where its values come from:

| Rule | Severity | Source status |
|---|---|---|
| `title_length` (max 140) | ERROR | verified |
| `title_characters` | ERROR | verified: Etsy Open API `createDraftListing` |
| `title_repeated_special_chars` (`%` `:` `&`, once each) | WARNING | secondary: the same API text also lists `+`, not checked here |
| `tag_count` (max 13), `tag_length` (max 20) | ERROR | secondary: help.etsy.com blocks automated access |
| `tag_characters` | ERROR | verified: Etsy Open API `createDraftListing` |
| `required_fields` | ERROR | verified: Etsy Open API `createDraftListing` |
| `price_quantity_positive` | ERROR | verified: Etsy Open API `createDraftListing` |
| `duplicate_tags` | WARNING | unverified |
| `title_word_count` (over 15 words) | INFO | secondary: Etsy Seller Handbook of August 2025, read in an article; advice, not an Etsy limit |
| `title_repeated_words` (a word more than once, articles, prepositions, conjunctions exempt) | WARNING | secondary: same article ("Try not to repeat words", no number: once is our reading); the exempt list is ours |
| `title_promotional_terms` (`free shipping`, `sale`, `% off`, `discount`, ...) | WARNING | secondary: same article (price, shipping and sales are badged by Etsy); the term list is ours |
| `personalization_question_count` (max 5 questions, max 1 upload) | ERROR | verified: Etsy developer guide "Personalization migration" |
| `personalization_question_text` (1 to 45 characters) | ERROR | verified: same guide |
| `personalization_dropdown_options` (1 to 30 options, each 1 to 20 characters, no label twice) | ERROR | verified: same guide; whether "Red" and "red" are the same label is not stated, so only exact repeats are reported |
| `personalization_upload_files` (max 10 files, a labeled upload at least 2) | ERROR | verified: same guide |
| `personalization_instructions_length` (max 120 characters) | WARNING | secondary: the guide says 120, an Etsy account in discussion 1551 of the open-api repository says 256 still applies until the general launch |
| `personalization_free_text_sensitive` (a free text question with `name`, `number`, `team` or `player`) | INFO, never higher | unverified: our own reminder that a dropdown keeps buyers from typing a team or player that is a trademark; no trademark verdict |
| `banned_words` | WARNING, **switched off** | unverified: sample list only, never backed by an Etsy policy |

`etsy-2026-12` adds the optional `personalization` input block (see [input schema](input-schema.md)): a listing that does not send it is
hashed exactly as before. `tag_count_underused` is not in it (it needs a fix action that adds a value, ADR-014).

`amazon-2026-12c.yaml` is the Amazon ruleset (`amazon-2026-11.yaml`, `amazon-2026-12.yaml` and `amazon-2026-12b.yaml` are kept unchanged as history, like
`etsy-2026-10.yaml`; a test pins their hashes). It follows the Amazon announcement in the Seller Forums of June 10, 2026
(effective July 27, 2026): a 75-character title and the new Item Highlights field. Amazon uses ERROR only for verified values, so most of its rules
are WARNING (the sources are an Amazon announcement and forum replies; Seller Central help needs a login). A
rule can be overridden per product type (`categories`) and switched off (`enabled: false`); no product type is
overridden yet. See [ADR-006](adr/ADR-006-category-rulesets-and-amazon-severity.md).

A change to a ruleset inside the same month gets a letter after the month (`amazon-2026-12b`, then `c`), so the next
version of a month that is already taken keeps the `YYYY-MM` part. `amazon-2026-12b` adds the optional `customization` input block for Amazon
Custom products (see [input schema](input-schema.md)) and the five `customization_*` rules below; a listing that does not send the block is
hashed exactly as before, and every other rule is copied unchanged from `amazon-2026-12`. `amazon-2026-12c` adds one rule,
`description_length`, whose number is `unverified` (see the table): it is a WARNING, never an ERROR, and has no fix suggestion.

| Rule | Severity | Source status |
|---|---|---|
| `required_fields` (`product_type`) | ERROR | verified: Listings Items API model |
| `title_length` (max 75) | WARNING | secondary: Seller Forums announcement of June 10, 2026, read in an article (the official page needs a Seller Central login). Media is exempt at Amazon; here it needs an override, none yet |
| `title_repeated_words` (max 2, articles, prepositions, conjunctions exempt) | WARNING | secondary; the exempt list is ours |
| `title_forbidden_chars` (`! $ ? _ { } ^ ¬ ¦`, unless in the brand) | WARNING | secondary |
| `bullet_count` (max 5) | WARNING | unverified |
| `bullet_length` | WARNING, **switched off** | unverified: sources disagree |
| `item_highlights_length` (max 125 characters) | WARNING | secondary: same announcement; no fix suggestion |
| `description_length` (max 2000 characters) | WARNING | **unverified**: no official page readable, no Amazon text names the number; two old Seller Forums posts by ordinary sellers say 2000 and the unit is not defined; no fix suggestion |
| `search_terms_length` (max 249 bytes) | WARNING | secondary: Amazon staff, Seller Forums |
| `prohibited_content` (web addresses, emails; title, description, bullets and `item_highlights`) | WARNING | secondary; scanning `item_highlights` is our extension of the same ban |
| `customization_text_input_count` (max 10 text inputs, all surfaces together) | WARNING | secondary: two articles on Amazon Custom (ecomcrew, sellersnap); the Seller Central page needs a login |
| `customization_dropdown_options` (max 100 options per print surface; no label twice in one dropdown) | WARNING | secondary: same articles; the repeated-label check is ours and reports only exact repeats |
| `customization_image_input_count` (max 10 image inputs per print surface) | WARNING | secondary: same articles |
| `customization_text_input_max_chars` (a text input without `max_chars`) | WARNING | unverified: our own check, the text can overflow the print area |
| `customization_free_text_sensitive` (a text input whose label has `name`, `number`, `team` or `player`) | INFO, never higher | unverified: our own reminder to use a dropdown or limit the kinds of characters; no trademark verdict; shares its matching with the Etsy rule |
| `category_not_covered` | INFO | not an Amazon rule: the product type has no overrides (only reported when the ruleset has some) |

`variation-checks-2026-11.yaml` (in `variation-checks/`) is the ruleset of the variation checks
([ADR-015](adr/ADR-015-variation-checks.md)). No official page could be read, so **no value is `verified`** and no
finding is an `ERROR`; two of the four secondary pages answered `403` and their statements come from the maintainer's
brief. The product types and themes it knows are few on purpose (one apparel product type); any other product type is an
`INFO` ("no data"), not an error.

| Rule | Severity | Source status |
|---|---|---|
| `parent_child_structure` (Amazon: a parent with a SKU and a theme, children that point at it and fill the theme's attributes) | WARNING | secondary: godatafeed, error 8024 |
| `variation_theme_allowed` (Amazon: the theme is one of those listed for the parent's product type) | WARNING; INFO when the product type is unknown | secondary; the product type and one theme are unverified |
| `apparel_size_attributes` (Amazon apparel: `size_system`, `size_class`, `size`, `size_to` of a range, `body_type`) | WARNING; INFO when a range cannot be compared | secondary: storeautomator (not readable, from the brief); the alpha order is ours |
| `duplicate_combination`, `duplicate_sku` | WARNING | unverified |
| `children_differ_outside_theme` (titles that look like different products) | WARNING, never ERROR | unverified: a heuristic |
| `etsy_variation_limits` (at most 2 variation types, `Holiday` and `Occasion` cannot vary, only 10 variations take a photo) | WARNING; INFO for the photo limit | secondary: alura.io |

`personalization-screening-2026-11.yaml` (in `personalization-screening/`) is the ruleset of the personalization screening
([ADR-016](adr/ADR-016-personalization-screening.md)): character classes, print profiles, the three character rules and
the Vietnamese wording of the trademark findings. It is the maintainer's internal print policy, not a marketplace or legal rule,
so **every source is `unverified`**, no finding is an `ERROR`, and **the two shipped profiles (`latin_basic`, the default, and
`latin_extended`) are placeholders with invented numbers**: the real workshop's profile replaces them in a new ruleset version.

| Rule | Severity | Source status |
|---|---|---|
| `unsupported_characters` (a character outside the profile's classes: emoji, symbols, letters with marks the profile does not allow; with a capitals-only profile, lowercase letters) | WARNING; INFO for lowercase on a capitals-only profile | unverified |
| `too_long` (more characters than the profile allows for the field, by label or the default) | WARNING | unverified |
| `whitespace_issue` (space at the start or end, several in a row; nothing is removed) | INFO | unverified |

To change a threshold or a severity, create a new ruleset file with a new `version`: the API refuses to
start if a registered version has different content (see [ADR-002](adr/ADR-002-ruleset-versioning.md)).
`etsy-2026-10.yaml`, `etsy-2026-11.yaml`, `amazon-2026-11.yaml` and `amazon-2026-12.yaml` are kept unchanged as history (a test pins their hashes) and are no longer loaded.
Which rulesets are loaded is set by `listing-guard.rulesets.locations` in `application.yml`.

The numeric limits of the loaded rulesets are served, read only, by `GET /api/v1/rulesets/{marketplace}/limits` (and
`?productType=` for an Amazon override). Each rule that has a number declares it with `Rule.limits`, with the unit its own check
counts (code points, items, UTF-8 bytes, occurrences), so the endpoint and the console editor's counters show what the check
enforces and a new rule appears in the answer without a change outside the rule. See the
[integration guide](integration-guide.md#limits-for-a-character-counter).
