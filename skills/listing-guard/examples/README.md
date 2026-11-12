# Worked examples

The inputs and abbreviated results below are fictional illustrations. They do not assert that a real API response will contain
these findings. A live response, including its message, evidence, disclaimer and versions, takes precedence.

## Etsy listing with a repeated tag

**Input:** Etsy title `Handmade blue ceramic mug`; tags `blue mug`, `blue mug`, `gift`.

**Tool sequence:** `get_capabilities` → `check_listing` → `check_trademark`.

**Present:** “The listing check reports `duplicate_tags` for the repeated tag, at the API's returned severity and field. Here is
the finding and suggestion as returned. Trademark result: [state exact risk, completeness, disclaimer and versions].” Do not
claim that other rules passed unless the response establishes that.

## Amazon title over the configured limit

**Input:** Amazon title `Handmade Personalized Blue Ceramic Coffee Mug for Home Office and Everyday Use`.

**Tool sequence:** `get_capabilities` → `check_listing` → `check_trademark` (omit `product_type` if unknown).

**Present:** If returned, report `title_length`, its API severity (the current Amazon rule is `WARNING`), field, evidence,
`sourceStatus`, and ruleset version exactly. Include the complete listing result's disclaimer and completeness when present.

## Sports team name with a DEMO trademark catalog

**Input:** Etsy title `Northvale Falcons Baseball Jersey`; DEMO catalog configured.

**Tool sequence:** `get_capabilities` → `check_listing` → `check_trademark` with `product_type: apparel` if that key is accepted.

**Present:** “This deployment reports a DEMO trademark catalog. Trademark risk is unknown/not determined; the catalog is
illustrative.” Also preserve the API's returned finding fields and levels without translating them; explain that demo data does
not establish production risk. Preserve the disclaimer and versions. If state is not `READY`, report risk as `UNKNOWN`, never
`LOW`.

## Buyer text contains a character the profile cannot print

**Input:** An order for a shirt; one buyer-entered value contains a non-Latin character. The exact value is intentionally omitted.

**Tool sequence:** `get_capabilities` → `screen_personalization` with `marketplace`, `product_type` if known, and `values` sent
exactly as received (plus a declared `print_profile` if supplied).

**Present:** “Personalization screening reports `unsupported_characters` at [returned field/offset] with severity
`[returned severity]`; completeness is `[returned completeness]`. Trademark status and risk: `[returned values]`.” Do not repeat
the buyer's text or snippet; retain the finding's rule ID, level, offsets, disclaimer and version.
