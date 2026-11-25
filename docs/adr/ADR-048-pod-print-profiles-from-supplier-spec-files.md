# ADR-048: POD print profiles from supplier spec files

- Status: Proposed
- Date: 2026-11-25
- Depends on: [ADR-016](ADR-016-personalization-screening.md) (the personalization screening pack and its placeholder print
  profiles, Open question 1), [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (analysis version and cache identity),
  [ADR-002](ADR-002-ruleset-versioning.md) (immutable published versions), [ADR-022](ADR-022-api-contract-and-compatibility.md)
  (the REST contract this record does not change), [ADR-047](ADR-047-seller-pilot-profile-and-console-navigation.md) (the Console
  the POD page lives in)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

The POD readiness page (`/console/pod-readiness`, `docs/implementation/pod-readiness.md`) answers three questions side by side:
marketplace rules, trademark risk and production readiness. The production answer is `INCOMPLETE` for both shipped profiles,
`t-shirt-v1` and `personalized-ornament-v1`, because their print area is `UNVERIFIED` and empty: nobody has put a supplier's
numbers into the repository. The same gap is ADR-016's Open question 1: the personalization screening runs on placeholder limits
with invented numbers.

The numbers exist. The company keeps the blank specifications (print area, size, character limit) in an internal blank
management system that can export CSV or a Sheet. What is missing is a safe way to turn that export into a released profile
without hand-editing JSON, and a rule for when a number counts as verified.

## Decision

### One spec file, many versions of a profile

A supplier spec file is a CSV, UTF-8, comma-separated, with a header row. The columns (names are case-insensitive):

| Column | Meaning |
| --- | --- |
| `product_type` | The profile this row belongs to. Lowercased and reduced to letters, digits and hyphens, so `T-Shirt` and `t shirt` are `t-shirt`; it is the profile `id`. |
| `variant` or `size` (one of them) | The size or variant the row describes (`S`, `XL`). **Blank means the whole product**, and also the fallback for a size the file does not list. |
| `print_area_width_mm`, `print_area_height_mm` | The largest artwork, in millimetres, with a decimal point and at most 3 decimals. |
| `max_chars` | Optional: the longest personalization text the blank takes, in code points. The column can be absent or blank. |
| `source_ref` | The blank or printer code the numbers come from. |
| `verified_by`, `verified_at` | Who confirmed the row and when (`yyyy-MM-dd`). |

An unknown or repeated column, a missing required column, both `variant` and `size`, a cell that is not a number, a dimension that
is negative or zero, a `verified_at` that is not an ISO date, a `max_chars` outside 1 to 9999, a row with more cells than the
header and a size repeated for one product type each refuse the **whole file**, with the row number. Nothing is written then.

### When a print area is VERIFIED

A row is `VERIFIED` **only with a `source_ref` and two positive dimensions**. A row without them is imported as `UNVERIFIED` with
what it has: the file is a spec, and a half-filled row is information, not an error. A number that is present and not positive is
an error, not a missing value. `verified_by`, `verified_at` and `max_chars` are recorded as given and **do not decide the status**
(Open question 1). `VERIFIED` means "a person attached a source to positive numbers", not "the numbers are right".

### Released resource and version

The importer writes `pod/<id>-v<version>.json`, the same resource kind and the same `PodProfiles` loader as the two shipped
files, and prints the SHA-256 the Console shows. A profile has either the shipped `printArea` (one area, whole product) or
`printAreas[]` (one entry per variant, each with `variant`, `sourceStatus`, `sourceReference`, `maxWidthMm`, `maxHeightMm`,
`maxChars`, `verifiedBy`, `verifiedAt`). The two shipped files are not edited and their checksums stay pinned.

- **A new version, never a replacement.** The version is one above the highest released version of the id, found in the shipped
  resources and in the output directory; the first import of an id is `v1`. A file is written with `CREATE_NEW`, so an existing
  version is never overwritten. `--version=<n>` must be higher than every existing version; naming an existing one is refused.
- **An import that changes nothing writes nothing.** If the data equals the latest version the command says so and stops.
- **`marketplaces`, `variationRules` and `personalizationFields`** are not in the spec file. A product type with a released
  profile inherits them from its latest version; a new one takes `--marketplaces`, `--variation-rules` and
  `--personalization-fields`, defaulting to both marketplaces, `sku` and none. Variants in the file need the `size` rule.
- The id pattern now allows digits after the first letter (`mug-11oz`).
- `.gitattributes` marks `api/src/main/resources/pod/*.json` as `eol=lf`, as it does for the API contract files, because the
  SHA-256 is of the text: a Windows checkout with `core.autocrlf=true` otherwise hashes CRLF bytes and fails the pin in
  `PodProfilesTest`. The blobs are already LF, so no released file changes.
- The resource reaches the Console **with the next build**, like any released profile: it is committed in a pull request, and the
  checksum is pinned in `PodProfilesTest`. The Console never reads a profile from a mutable directory.

### The command

`pod-profile import <file.csv> [--out=<dir>] [--version=<n>] [--dry-run] [--variation-rules=...] [--personalization-fields=...]
[--marketplaces=...]` in the admin job (`docs/agent/operations.md`), next to `client`, `key`, `plan` and `catalog`. `--out` defaults
to `api/src/main/resources/pod`. `--dry-run` reports the files and writes none. The command reads the CSV and the released
profiles and writes only new files; it uses no database and no network. It is operator work, not an HTTP endpoint (ADR-007).

### Readiness reads the print area of each size

For each SKU row the page looks up the print area by the row's size (case and surrounding spaces ignored), then by the blank
`variant` entry; a profile without a `size` rule uses the whole-product entry.

- A size with **no print area**, or a profile with no usable area, is `INCOMPLETE` ("enter a size or variant that this profile has a
  print area for").
- Any area in play that is **`UNVERIFIED`** keeps the production result `INCOMPLETE`, as the shipped profiles do.
- When every area in play is verified, the artwork is compared with each of them: one that does not fit is a `REVIEW_REQUIRED`
  finding naming the size and its limit; otherwise the artwork finding is clear.
- For a profile with a personalization field, the source is confirmed only when **every area in play is `VERIFIED` and states
  `max_chars`**; otherwise the existing "confirm the allowed characters and length limits" item stays and the result is
  `INCOMPLETE`. The confirmation checkbox for the text field is still required.

The other checks of the production dimension (SKU, size and colour rows, the variation pack) are unchanged.

### One profile for readiness and personalization screening

The personalization screener takes an optional **`SupplierProfile`** (id, version, resource SHA-256, `max_chars`):

- Its limit replaces the ruleset print profile's length limit, for every label (the ruleset's placeholder label limits are dropped).
  **The characters the workshop can print stay the ruleset's** (`latin_basic` or `latin_extended`): the spec file has no character
  set, so the character classes are still Open question 1 of ADR-016. The result's print profile code names both, for example
  `latin_basic+sample-ornament-v1`.
- The **analysis fingerprint** gains two lines, `supplier_profile=<id>-v<version>:<sha256>` and `supplier_max_chars=<n>`, **only when
  a profile is given**. Without one the descriptor, and so the analysis version of every result stored before this record, is
  byte-for-byte what it was. A new version of a profile, another file with the same version name or another limit is a new analysis
  version; `rules_version` is unchanged.
- The Console POD page runs the screener on its fixed sample with the supplier profile when the limits are verified (otherwise
  without one, as before) and shows the sample's analysis version.

### Console

The profile picker groups the released profiles by product type (the id), newest version first, and each option says whether the
profile is verified. After a check, the page shows the profile's SHA-256 and a table of its print areas: size or variant, area in
millimetres, character limit, status, source reference, verifier and verification date. A product type without a translated name
shows its id. A profile that is not fully verified still reports its production as `INCOMPLETE`.

## Not done

- **`POST /api/v1/personalization-screenings` does not read an imported profile.** Its `print_profile` still names a ruleset
  profile. Letting a client name `t-shirt-v2` changes the request contract, the OpenAPI document and the compatibility baseline
  (ADR-022) and needs a plan for stored results; the screener and the fingerprint are ready for it.
- A character set or font table in the spec file; transliteration; a per-label limit (`name` against `number`).
- Reading the blank management system directly, a scheduled import, or an import from the Console.
- Per-client profiles, a profile editor and a profile that is not a released resource.
- Deleting or deprecating a version. Old versions stay selectable.
- A Vietnamese or English name for a new product type: the id is shown until a message key `pod.profile.<id>` is added.

## Consequences and tradeoffs

- The numbers enter the repository through a reviewed pull request, with a checksum, so a result can always be traced to the exact
  file. The price is a build and a release for every spec change; a hot reload would make the checksum meaningless.
- `VERIFIED` is a structural rule. It cannot tell a wrong number from a right one, and it does not require a name or a date. The
  verifier and the date are displayed so a reviewer can see who stood behind a row.
- A size missing from the spec file makes the production result incomplete instead of guessing from a neighbour.
- The sample screening is still a fixed sample ("Mẫu 2026"), so its findings are informational and never change the status.

## Acceptance criteria (tests)

Synthetic data only: invented codes (`BLK-TS-01`), invented people (`Operator A`) and invented dimensions.

1. **Import** (`PodProfileImporterTest`): a row is `VERIFIED` only with a source and two positive dimensions; the stored SHA-256 is
   the one the loader computes and the file has LF endings; a new version keeps the old file byte for byte; the same data again
   writes nothing; a repeated or lower `--version` is refused and nothing is overwritten (shipped versions included); a product
   type with a shipped profile inherits its fields; variants without a `size` rule are refused; a missing column, an unknown
   column, `variant` with `size`, a negative, zero or malformed dimension, a bad date, a bad `max_chars` and a repeated variant each
   refuse the file with the row; several product types become several files and one bad row writes none; a dry run writes none;
   a byte order mark, blank lines and quoted values are read.
2. **Model** (`PodProfileModelTest`): the invariants of `PrintArea` and `Profile`, and the lookup by size.
3. **Command** (`AdminCommandsPodProfileTest`): the route, the printed SHA-256, the second run, the dry run and the refusals,
   without a database.
4. **Screening** (`SupplierProfileTest`): the supplier limit replaces the ruleset limit and the label limits, the characters stay
   the ruleset's, the fingerprint is unchanged without a profile and changes with the id, the version, the hash and the limit.
5. **Console** (`ConsolePodVerifiedProfileIntegrationTest`, `ConsolePodIntegrationTest`): the picker groups by product type; with
   verified synthetic profiles the production result moves from `INCOMPLETE` to `NO_FINDINGS` when the artwork fits and to
   `REVIEW_REQUIRED` when it does not, naming the size; an unverified or unknown size stays `INCOMPLETE`; a verified character
   limit sets the sample screening and names the profile in its analysis version; the existing tests for the two shipped profiles
   pass unchanged.

## Open questions

1. **Should `verified_by` and `verified_at` be required for `VERIFIED`?** This record follows the request: a source and positive
   dimensions. Requiring a name and a date would make the status say more, at the cost of refusing exports that lack them.
2. **The real spec file.** No real numbers are in this repository; `docs/samples/pod-profile-spec-sample.csv` is invented.
3. **Character sets.** Which characters each blank or printer can print is still ADR-016's Open question 1.
4. **Exposing imported profiles on the REST endpoint** (see Not done).
5. **Product-type names.** Who writes the Vietnamese and English name of a new product type.
