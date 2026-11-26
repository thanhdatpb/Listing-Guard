# Backtest

Measures Listing Guard against listings whose fate you already know: would it have warned about the listings that were claimed
or taken down, and how often does it warn about listings nobody claimed? It also gives the numbers per rule. The design is
[ADR-032](adr/ADR-032-value-backtest-and-labeled-evaluation.md). Like the rest of Listing Guard it only checks: it never edits,
fixes or publishes a listing, and its result is no legal conclusion.

```text
claims.csv + mapping.yaml --> backtest --> eval/backtest/out/<UTC timestamp>/report.md    for a person (Vietnamese)
                                      '--> eval/backtest/out/<UTC timestamp>/report.json  the same numbers, for a program
```

The job runs the same rules and the same trademark scanner as `POST /api/v1/checks` and `POST /api/v1/trademark-checks`, with the
ruleset and the catalog this deployment serves, but **it stores nothing, charges nothing and needs no client**: the file never
reaches the database. It reads local files and the database (for the catalog) and makes no network call.

The labelled sample set that CI scores on every build is a different thing: see [eval/README.md](../eval/README.md#listing).

## 1. Prepare the file

One row per listing, a header row, UTF-8 (set `csv.encoding` in the mapping for another encoding). Mix both kinds of rows: listings
that were claimed or taken down, **and** listings of the same shops and period that were not. Without unclaimed rows the report can
say what was caught but not how often Listing Guard warns for nothing.

| Column (example name) | Required | What to put in it |
|---|---|---|
| `row_id` | yes | Your own row number or an internal id, filled for every row. **Not** the Etsy listing id, ASIN, shop name or buyer name: the report repeats this value. |
| `title`, `description`, `tags`, `price`, `quantity`, `who_made`, `when_made`, `category` (Etsy) or `title`, `brand`, `bullet_points`, `description`, `search_terms`, `product_type` (Amazon) | as many as you have | The listing **as it was when it was claimed**, not as it is today. A field you leave out is reported missing by `required_fields`, so the per-rule numbers of that rule are then meaningless: map every field you have. |
| `claimed` | yes | `yes` or `no` (also `true`/`false`, `1`/`0`). Blank means "not known": the row is checked and not measured. |
| `issue` | recommended | What the claim was about: `trademark`, `listing-policy`, `prohibited`, `copyright`, `counterfeit`, `image` or `other`. Blank for a claimed row means unspecified. Anything else ("nhãn lạ") refuses the row's labels; the report lists it with its line. Left out for unclaimed rows. |
| `mark` | for trademark claims | The mark the complaint named, as written in the complaint (for example `Zibblefrax`). It is how the report explains a miss (not in the catalog, a different spelling, not in the text). |

**What to remove before the file leaves the claim system**: the complainant's name, e-mail and address; the claim or case number;
the buyer's name and order details; the seller's personal data; URLs of the listing or the shop; anything not in the table above.
Keep the listing's text: that is what is checked. The report never repeats the text (only row ids, rule ids, field names, catalog
marks, the labelled mark and, for a spelling variant, the near word cut to 40 characters), but the input itself is client data:
keep it in `eval/backtest/input/` (git-ignored) only for the run and delete it afterwards.

From a Google Sheet: *File > Download > Comma-separated values* on a sheet that has exactly the header above (add a `claimed` column
next to the listing columns). A cell with commas or line breaks is quoted by Sheets and read correctly.

Then copy `eval/backtest/mappings/claims-etsy.example.yaml` and change each column name to your header's (the format is the
batch-check mapping, [batch-check.md](batch-check.md#2-write-the-mapping)). The label columns are not in the mapping.

## 2. Run it

```bash
docker compose run --rm backtest --input=/backtest/input/claims.csv --mapping=/backtest/mappings/claims-etsy.yaml \
  --marketplace=ETSY --claimed-column=claimed --issue-column=issue --mark-column=mark
```

`./eval/backtest` is mounted at `/backtest`. Trademark numbers need the catalog the API serves (`TRADEMARK_CATALOG_VERSION`, or
`TRADEMARK_FOLLOW_ACTIVATION=true`); without one the rule numbers are still produced and every trademark claim that was missed says
`TRADEMARK_NOT_SERVED`. On the Oracle host use `docker-compose.prod.yml` with `--profile prod --profile tools` (see the comment of the
`backtest` service).

| Option | Default | Meaning |
|---|---|---|
| `--catch-severity` | `WARNING` | A rule finding at this severity or above counts as "caught". `ERROR` is stricter. |
| `--catch-trademark-risk` | `MEDIUM` | A trademark level at this level or above counts as "caught". |
| `--variant-threshold` | `0.6` | How close a word of the listing must be to the labelled mark for a miss to be called a spelling variant. It only explains a miss and never changes a finding. |
| `--out` | `eval/backtest/out/<UTC timestamp>` | Where the two files go. |
| `--max-rows` | all | Stop after this many rows (the report says so). |

A row with the wrong number of cells or an empty id is listed as `UNREADABLE`, a label not on the list as `LABEL_INVALID`, and the
run goes on. A missing label column stops the job before any row; a damaged file (an unclosed quote, bytes that are not UTF-8) ends
it with a report marked **CHƯA XONG** and a non-zero exit.

## 3. Read the report

- **Recall** = caught / (caught + missed) among claimed rows. Rows that are *undetermined* (a rule failed, the scan failed or the
  trademark level is `UNKNOWN`) are counted apart and left out: they are neither a catch nor a miss.
- **By issue type**: a claim about copyright, counterfeits or images is something no part of Listing Guard checks; its misses are
  `NOT_COVERED`, so the recall of `trademark` and `listing-policy` is the honest measure of what the product claims to do.
- **Trademark claims caught on their own mark**: caught because of the mark the complaint named, not by chance on another one.
- **Why missed**: one reason per miss. `MARK_NOT_IN_CATALOG` means the catalog needs that mark; `SPELLING_VARIANT` shows the near
  word and its similarity; `MARK_NOT_IN_TEXT` usually means the claim rested on an image or a logo; `NOT_MATCHED` needs a person.
- **Unclaimed listings flagged**: an estimate of false alarms, and an upper bound: a listing nobody claimed may still break a rule.
- **Per rule**: for each rule, how many flagged listings were claimed and how many were not, and the share that were claimed (a
  precision estimate). It means something only when the file is a fair sample: a file of claimed rows only gives every rule 100%.
- Small numbers are small: "3/4" is not "75% accuracy". Every rate is printed with its counts; quote them together.

## 4. One page for the manager

Copy the numbers from `report.md` into this template (Vietnamese, the language of the report). Keep the counts next to every rate and
keep the last paragraph.

```markdown
# Listing Guard: kiểm tra lại trên các vụ claim đã xảy ra

**Dữ liệu**: <N> listing của <số shop> shop, từ <tháng> đến <tháng>; trong đó <C> listing từng bị claim/takedown và <U> listing
không bị claim. Marketplace: <ETSY/AMAZON>. Ruleset <phiên bản>, catalog nhãn hiệu <phiên bản> (chạy ngày <ngày>).

## Kết quả chính

| | Kết quả |
|---|---|
| Listing bị claim được cảnh báo trước (recall) | <bắt được>/<bắt được + bị sót> (<%>) |
| — riêng claim nhãn hiệu | <x>/<y> (<%>); đúng mark bị claim: <a>/<b> |
| — riêng claim vi phạm chính sách listing | <x>/<y> (<%>) |
| Listing không bị claim nhưng bị cảnh báo (ước lượng báo nhầm, cận trên) | <f>/<f + sạch> (<%>) |
| Không xác định (không tính vào tỉ lệ) | <số dòng> |

## Vì sao bị sót (<tổng số vụ sót> vụ)

| Lý do | Số vụ | Việc cần làm |
|---|---:|---|
| Mark không có trong catalog | <n> | Bổ sung nguồn catalog |
| Viết khác chính tả | <n> | Xem lại ngưỡng so khớp gần đúng |
| Loại vấn đề không kiểm tra (bản quyền, ảnh, hàng giả) | <n> | Ngoài phạm vi hiện tại |
| ... | | |

## Rule đáng chú ý

- <rule>: cảnh báo <x> listing bị claim và <y> listing không bị claim (tỉ lệ đúng ước lượng <%>).

## Giới hạn

Số liệu chỉ đúng cho file này; mẫu nhỏ thì sai số lớn. Listing không bị claim vẫn có thể vi phạm, nên tỉ lệ báo nhầm là cận trên.
Kết quả là cảnh báo tham khảo, không phải kết luận pháp lý. Listing Guard không sửa hay đăng listing.
```


## Kỳ vọng trademark theo dòng

Hai cột tùy chọn `expected_min_risk` và `expected_max_risk` nhận LOW, MEDIUM, HIGH hoặc để trống. Kết quả
`INCOMPLETE`, kết quả thiếu catalog và mức chưa xác định không đạt cổng. Báo cáo kèm tier, số chủ và số serial của
finding nặng nhất nếu có dữ liệu. Cổng độc lập với nhãn claim và precision/recall. Xem lệnh chạy bộ mẫu tổng hợp và
cách so finding trước/sau trong [runbook](catalog-activation-runbook.md).
