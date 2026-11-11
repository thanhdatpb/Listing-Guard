# Unreleased changes

Changes on `main` that no release has shipped yet. This file lists the Amazon description length rule and the batch review gate
(below), and the API keys page of the optional web console with its shared layout (ADR-014 part B4a); when the next version is cut,
move its content into `docs/releases/v<version>.md` with the other changes of that version and delete this file. The Amazon ruleset
is now `amazon-2026-12c`, so **the `analysisVersion` of Amazon checks changes** (an Amazon listing checked before is checked again);
Etsy, hashes and the API's request and response shapes are unchanged (`docs/api/openapi.json` differs only in an example's version
string), and there is no migration. The console stays **off by default** (`listing-guard.console.enabled=false`).

## What is new

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of
characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be
read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not
defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is
kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new
limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See
[the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order,
delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`,
`trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above
`--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota,
`UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal
conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the
flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### API keys page in the console (ADR-014 part B4a)

A signed-in console user can now manage the API keys of their own account at `/console/api-keys`, for example for an application
or a preview app that calls the API.

- **List.** The active keys of the account's client: key id, label, created and last used (UTC; "never" when the key has not been
  used; the API writes the time at most about once a minute per key). Keys made with the admin job are listed too. A revoked
  key is not.
- **Create.** An optional label (trimmed, up to 100 characters, no control characters). The secret is shown **once**, in the
  page that creates it, with a warning and a Copy button; it is not stored (only its SHA-256 is), not in a URL, a redirect, the
  session or a log, and the page is sent `no-store`. The page has no inline script: the button is `static/console/keys.js`.
- **Revoke.** A link opens a confirmation page that describes the key; only a CSRF-protected `POST` revokes it, and it takes
  effect at once. A key of another account is a 404, never a 403.
- **Limit.** At most `listing-guard.console.max-active-keys` active keys per account (`LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS`,
  default 5, at least 1 or the API does not start). Keys made with the admin job count. The count and the insert run in one
  transaction with a row lock on the client, so concurrent requests cannot pass the limit. A client that an operator gave more
  keys than the limit keeps them; the console only creates no more until enough are revoked.
- **Same keys, same audit.** A key made here is the same kind of key as `admin key create` makes, and the audit log gets the same
  lines (`key.create`, `key.revoke`; client, key id and time, never the secret).
- **No plan check.** Paid features are deferred: key creation is not tied to a plan or a pack, and there is no new migration. A
  plan comparison page and billing are not part of this change; the dashboard keeps showing the plan and usage.

### Shared console layout and the DEMO banner

- **Bar.** The dashboard, the Check listing page and the API keys page share one bar: Dashboard, Check listing, API keys and Sign
  out, with the current page marked.
- **DEMO banner.** While `listing-guard.trademark.catalog-version` starts with `demo-` (the fictional DEMO catalog of
  `catalogs/demo/`), every console page, the sign-in pages too, shows "Demo catalog: fictional marks only. Real trademark
  checking is not active.", and the trademark panel of the Check listing page repeats it. The banner follows the configured
  version only. The API, the batch reports and stored results are not marked.

No migration, no new API endpoint and no change to `docs/api/openapi.json`. The pages use `/console/console.css` and
`/console/keys.js`, served behind the console sign-in; there is no build step and no Node. See
[ADR-014](../adr/ADR-014-listing-workspace.md) and [deployment](../deployment.md#api-keys-page-adr-014-b4a).

## Breaking changes

None.

## Upgrade notes

Nothing to do. If you enable the console, `LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS` is optional (default 5). A console account's
client starts on the FREE plan, so with plans enabled its keys can use only the packs of that plan; change it with
`admin plan set`.
