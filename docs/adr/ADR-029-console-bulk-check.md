# ADR-029: Console bulk check

- Status: Proposed
- Date: 2026-11-13

## Context

Sellers write titles, bullets, tags and descriptions in a Google Sheet, one listing per row. The console editor
([ADR-014](ADR-014-listing-workspace.md), part B3) checks one typed listing at a time; `batch-check`
([`docs/batch-check.md`](../batch-check.md)) checks a file, but it is an operator job: it needs shell access, a YAML mapping and a
client name. A seller needs the middle: copy many rows out of a Sheet (or take a CSV/TSV export), see which rows have a problem, and
paste the answer back next to the rows it belongs to.

The pieces exist. `CheckService`, `TrademarkCheckService` and `VariationCheckService` store results with the cache, plan, quota and
retention rules of the API (ADR-010, ADR-013, ADR-015). The input adapters
([ADR-025](ADR-025-listing-input-adapters.md)) read and detect marketplace exports. The editor shows a result with its readiness,
trademark panel and disclaimer. What is missing is the page that turns a table into those calls and a table of answers.

## Decision

Add `GET /console/bulk` and two `POST`s (`/console/bulk/prepare`, `/console/bulk/run`) to the console, in the session and CSRF chain
of the other console pages (a visitor who is not signed in goes to `/login`; with the console off there is no route). The page is in
English and Vietnamese like the rest of the console ([ADR-028](ADR-028-console-localization.md)): no sentence is written in the
template, the script or a class, every one is a key of `messages.properties` and `messages_vi.properties`, in the language of the
page. The menu entry is "Bulk check" or "Kiểm tra hàng loạt". It checks and suggests only: nothing is published, no fix is applied, and
nothing is written to a marketplace or to the seller's Sheet. The seller pastes the answer back by hand.

### Two tabs, one table

- **"Dán từ Sheet"**: a text area for what Google Sheets (or Excel) puts on the clipboard: tab-separated, a cell with a line break
  or a tab in double quotes, `""` for a quote in a quoted cell. The first row may be a header (automatic, or the seller says yes or no).
- **"Tải file"**: a CSV or TSV (`.csv`, `.tsv`, `.txt`, UTF-8, a byte order mark is dropped; the delimiter is found from the first row:
  tab, comma or semicolon). The header is matched against the built-in input adapters of ADR-025 (`BuiltinAdapterCatalog`, the
  `ConfiguredCsvAdapter` templates for Etsy listings, Amazon flat files and the experimental Shopify mapping). If one of them
  supports the header the file is read with that adapter's own columns, delimiter and bounds and the seller maps nothing; if the
  marketplace of that adapter is not the one chosen the page says so and checks nothing. Any other table is treated like a paste.

A pasted or uploaded table with no adapter goes through one **column mapping step**. Both tabs end in the same run and the same
result table.

### Column mapping

The page shows the columns of the table with up to three sample values each and, for every column, a drop-down of what the column is:
the fields of the standard input of the chosen marketplace (`docs/input-schema.md`: Etsy title, description, tags, materials, price,
quantity, category, `who_made`, `when_made`; Amazon title, brand, bullet points, description, search terms, product type, price,
quantity, item highlights), a SKU (names the row in the answer, is not a listing field), a parent SKU and variation attributes (below),
or "skip". Several columns given to a list field are its entries left to right (`Bullet 1` to `Bullet 5`, `Tag 1`...); one column given
to a list field is cut at a separator the seller picks (new line, comma, semicolon, `|`). The page guesses each column from its header:
the header is lower-cased and stripped of accents and punctuation and compared with a short list of English and Vietnamese names
(`title`, `tiêu đề`, `tags`, `bullet 1`...`bullet 5`, `mô tả`, `brand`, `thương hiệu`, `sku`, `giá`, `số lượng`...). A column that matches
nothing is "skip": the page never invents a meaning. The seller changes any drop-down before running; a field given to two columns, or no
column mapped, is refused with a message and the form comes back as it was.

The guess is a default, never a decision: **the mapping that was used is remembered per account and marketplace and applied first the
next time** (by column header text, so a Sheet with reordered columns still maps; a column the stored mapping does not know falls back to
the guess). Only column labels, targets and separators are stored (V36, `console_bulk_mapping`, at most 100 columns of at most 120
characters each, deleted with the account); never a cell value. The seller can untick "remember" for a run.

### What is read

`price` must be a plain decimal number and `quantity` a whole number, exactly as in the editor and `batch-check` (no currency sign, no
thousands separator, no guessing of a decimal comma): a row that fails is "Không đọc được" with the reason, is not checked and costs
nothing. A blank cell is an absent field (the rules report it as missing). A row whose cells are all blank is "Dòng trống" and is
skipped. Values are passed on as written; the normalizer trims, like for the API. The listing JSON is built by the console's own
mapper and always passes through `StandardListingAdapter.toStandardInput`, so only fields of the standard input reach a check.

### What runs

Per row the console calls the services the API and `batch-check` call, with the client of the signed-in account:
`CheckService.checkDetailed` (the rules with verified fixes and readiness, like the editor), `TrademarkCheckService.check` and, when
rows share a parent SKU, `VariationCheckService.check` once per family. Nothing is checked in the console that the API would not check:
the cache identity, retention, plan packs, quota and `analysisVersion` are theirs. A check that cannot run says why for the row and
the others go on: no trademark catalog (`DISABLED` or `REFUSED`), a plan without the `trademark-risk` or `variation-checks` pack, and a
used-up quota each stop only the part they concern (the same split as the editor and the batch). A catalog that is not served leaves
the rules running and the page says "chưa kiểm tra nhãn hiệu" for every row, in the table and in the text pasted back, so a "Đạt" never
reads as a trademark all-clear.

**Variation.** When a parent SKU column is mapped and some rows have a value in it, the rows with the same parent SKU are the children of
one family. Amazon: the parent is the row whose SKU is that value (its title, product type and the optional "variation theme" column
feed the parent object), or just the SKU when no such row is in the table. Etsy: the listing is its own parent, and the names of the
variation types are the names of the attribute columns. Variation attributes are the columns the seller marks "Thuộc tính biến thể";
the attribute name is the column's header. Each finding of a family goes to the row it names (`children[i]` is the i-th child, counted the
way the normalizer counts the cleaned list), and a finding with no child to the parent row, or to the first child when the parent is not
in the table. Files read by an adapter carry the family key but no variation attributes (the adapters of ADR-025 do not map them), so
**no variation check runs for them** and the page says that, instead of reporting "attribute missing" for data it never saw.

### Limits

`listing-guard.console.bulk.max-rows` (default 200 data rows) and `max-bytes` (default 1 MiB of text or file). A larger input is refused
as a whole with a message that gives both numbers; nothing is checked, nothing is charged and nothing is cut (a cut table would shift
the rows against the Sheet). The request body limit of the API (`listing-guard.api.max-body-bytes`, 256 KB) does not fit a paste of
this size, so `BodyLimitFilter` takes a map of path allowances instead of one path (the image checks, ADR-008, and these two):
`/console/bulk/prepare` and `/console/bulk/run`, `POST` with `multipart/form-data` only, up to `2 * max-bytes + 64 KiB`, and only while
the console is on. Both pages post multipart on purpose: a text in a URL-encoded field grows several-fold, and Tomcat stops reading a
URL-encoded body at `server.tomcat.max-http-form-post-size` (2 MB). The limits above are the real ones and are checked in the
controller (`max-bytes` must stay below `spring.servlet.multipart.max-file-size`, which the container applies to every part). Every
other path keeps its limit.

### Plans and quota

Where plans are on (ADR-013) the run is charged per row, through the services: a stored result is free, a new `COMPLETE` result is one
unit (rules, trademark, and one per family for variation). Before reading rows the console requires the `listing-rules` pack and a unit
left, as `batch-check` does, and stops with the message and the reset date rather than writing a table of failures. The mapping step
shows the most the run can cost. A row that needs a new result after the quota ran out is "Chưa kiểm tra" with `quota-exceeded`; rows whose
results are stored are still answered. The `batch` pack is **not** required (open question 1).

### Result

A table, in the order of the input: the row number (the line of the text where the row starts, the header being line 1), the SKU if the
table has one, a status, the number of findings and the main finding. The statuses are `Đạt`, `Cảnh báo`, `Lỗi` and the ones that say a
row was not judged: `Không đọc được`, `Chưa kiểm tra` (quota, plan, a check that failed) and `Dòng trống`. A row is `Lỗi` when a rule
finds an `ERROR`; `Cảnh báo` for a `WARNING` (rules or variation), a trademark risk of `MEDIUM` or `HIGH`, or any part that is
`INCOMPLETE` (never read as clean); otherwise `Đạt`. The main finding is the first of: rule `ERROR`, trademark `HIGH`, rule `WARNING`,
variation `WARNING`, trademark `MEDIUM`, information, trademark `LOW`. `Đạt` means "no listing rule found an error or warning", never that a
marketplace accepts the listing, and the page says so next to the table with the trademark disclaimer of the active ruleset.

The seller can filter by status (a script that only hides rows; without it every row shows) and open one row (`<details>`) to see what
the editor shows: the readiness score, the trademark panel with its disclaimer, the rule findings with message, suggestion and verified
fix text, the trademark and variation findings. Fix text is shown; there is no "apply" button, because there is no form to fill.

**Copy back.** "Sao chép cột kết quả" copies two columns, status and summary, one line per input row in the same order, tabs between the
two columns and no tab or line break inside a cell, so the block pastes into two cells of a Sheet. A table that had a header gets a header
line ("Trạng thái", "Tóm tắt") first, so the block is pasted beside the header row; a blank input row is a blank line (the order never
shifts). The same text is in a read-only text area, so the seller can select it by hand when the browser has no clipboard script.
**"Tải CSV"** is a `data:` link to a UTF-8 file with a byte order mark; it carries the same rows with the status, counts and main
finding. In both a cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading apostrophe, like `batch-check`'s
reports: finding text can quote a listing.

### What is stored

**Not the pasted text, not the file, not the table.** The text lives in the request and, between the mapping step and the run, in a
hidden field of the page that goes back to the seller's own browser (the server keeps nothing between the two requests: no session
attribute, no temporary file of its own; the multipart upload of the container is removed with the request). The run stores what the
services store, as for any check: only the canonical input hash and bounded evidence, with their 30-day retention (ADR-010, ADR-015),
and the one new table above. The result table is built for the response and is not kept: it is not listed anywhere afterwards. Nothing
is added to the health report: a bulk run is a number of ordinary checks, which the report already counts.

### Not changed

No API endpoint, no MCP tool, no change to `docs/api/openapi.json`, to a ruleset, to `analysisVersion` or to a content hash. The adapter
module gains an optional `id_column` in its YAML templates (the three built-ins set it) and the `ListingRecord` an `id`, so a file read by
an adapter can name its rows; every existing constructor and template keeps working. `batch-check` is untouched.

## Consequences

- A seller with a Sheet needs no shell, no YAML and no CSV round trip: paste, confirm the mapping once, run, copy two columns back.
- The mapping guess is a convenience that can be wrong; the page shows what it chose and the run uses only what the seller confirmed.
- Results cannot be reopened later from the console (nothing lists them); the API's `GET /api/v1/checks/{id}` still answers for the
  retained checks. Saved listings and history stay "not designed" in ADR-014.
- Every text is a message (ADR-028), so a status, a note, a sentence of the block pasted into the Sheet and the headers of the CSV follow
  the language of the page; the status words in this ADR (`Đạt`, `Cảnh báo`, `Lỗi`) are the Vietnamese ones. The English sentences and
  the Vietnamese ones are for the maintainer to review. A remembered mapping is keyed by the header text of the Sheet, which is the
  seller's own words in any language; the label of a column that has no header ("Column A", "Cột A") follows the language.
- A paste is limited to what fits one request (200 rows by default). A bigger file stays a job for `batch-check`.

## Open questions

1. Should the page require the `batch` pack, as the job does? Today a FREE client can use it within its quota; requiring the pack makes
   the page a paid capability, which ADR-014 deferred.
2. The default limits (200 rows, 1 MiB): the page runs the rows one after another in one request, so a larger default needs a background
   run and a way to come back to it, which means storing results. Not designed here.
3. Variation attributes for adapter files: which Amazon flat-file columns hold them is not verified, so none is mapped.
4. Should the mapping be shared by the members of a client rather than per account (the team workspace work may add accounts to a client)?
5. A decimal comma in a price ("19,99") is a row error, as in the batch. Should the Vietnamese UI accept it, and with what rule for
   "1.234,5"?
