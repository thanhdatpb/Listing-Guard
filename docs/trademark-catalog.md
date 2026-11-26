# Trademark catalog

## Catalog workflow

Trademark checks (ADR-009) match a listing against a catalog that the operator loads and names by version. The catalog is optional:
without it the application and `/checks` run as before and trademark checks are not configured. No trademark data is committed.
The `trademark-import` job itself reads local files only. **You are responsible for checking that the terms of your data source
allow this storage and use.** No accuracy has been measured on real data. `uspto-sync` can collect USPTO backfile and daily
files from operator-provided folders; its optional Open Data Portal download is off by default and unverified end to end. The
`uspto-convert` job (below) follows the public daily applications XML DTD and applies the A2c latest-transaction and filters
rules, but is **unverified against real data**. Without a USPTO key, the `hf-convert` job converts a public Parquet rebuild of the
USPTO bulk XML from Hugging Face ([Hugging Face source](#hugging-face-source-hf-convert), ADR-030). For any other source, convert
your data to `jsonl-1` yourself.

### USPTO sync and activation

The `uspto-sync` job is a one-shot administrative workflow (profile and Compose service `uspto-sync`), not a scheduler and not
part of API checks. Its offline `--input-dir` and `--backfile` modes are supported. The optional ODP download requires an API
key, is configured separately and defaults off; the endpoint details have not been verified against the live portal. See
[ADR-009, scheduled USPTO sync](adr/ADR-009-trademark-risk-scanner.md#scheduled-uspto-sync-job-uspto-sync-pr-a2d) before enabling it.

After conversion and import, an activation log records which immutable catalog version runtime should serve. The API uses an
explicit configured version or follows the operator activation setting; it never selects a version called `latest`. The DEMO
catalog cannot be activated as production data. Catalog history and rollback commands are documented in
[deployment](deployment.md#trademark-catalog-adr-009-pr-a2).

1. **Prepare the file.** UTF-8 JSON Lines in `catalogs/raw/` (git-ignored), one record per line:
   `{"mark": "Zorvex", "status": "REGISTERED", "nice_classes": [25], "source_id": "123", "common_word": false}`
   (`status` is `REGISTERED`, `PENDING`, `EXPIRED` or `CANCELLED`; `nice_classes` are 1 to 45; `common_word` is
   optional). For USPTO Trademark Daily XML applications files (DTD V2.0) that you downloaded yourself, unzip them into
   `catalogs/raw/uspto/` (keep the `apcYYMMDD.xml` names) and run
   `docker compose run --rm uspto-convert --input-dir=uspto --output=<name>.jsonl --strict` (or `--input=` once for each
   file): it writes this format into `catalogs/raw/`, a `.report.json` with the files and the counts (records read, replaced
   by a later transaction, removed because their latest status is not live, filtered by each reason, written), and prints the
   `sha256` for the manifest. For each serial number only the record with the latest `transaction-date` is kept, whatever the
   order of the files, and only the Nice classes, live status codes and marks with words of a filters file
   (`uspto-convert-filters-2026-11.yaml`; every value is `unverified`). It reads the XML as a stream, stops on a `version-no`
   other than 2.0, and without `--strict` leaves a file it cannot read out and ends `INCOMPLETE`. It is unverified against
   real data, and a daily file holds changes only ([ADR-009](adr/ADR-009-trademark-risk-scanner.md), PR A2c).
2. **Describe it in `catalogs/manifest.yaml`:** a `version` label, `file`, `format_version: jsonl-1`, `source_name`,
   `url`, `license`, `source_date`, the file's `sha256` (`sha256sum`) and the `filters` (`include_classes`,
   `include_status`). A missing license or date, or a hash that differs from the file, is refused. To join several
   files (for example a public register and a list of your own), write a `sources:` list instead of `file`, with a
   `source_key` for each; the filters apply to the whole catalog, the source ids are stored as `<source_key>:<id>`, and a
   hash that differs in one file refuses the whole catalog (steps in [docs/deployment.md](deployment.md)).
3. **Import:** `docker compose run --rm trademark-import --catalog=<version label>`. It reads each file as a stream, so
   a file of any size needs little memory, validates every line (one bad line rejects the batch and names the line),
   keeps the records that match the filters, merges records that normalize to the same mark, writes one new catalog
   version in one transaction (with one row per data file in `trademark_catalog_source`) and prints its name (the label
   plus a digest).
   Running it again with the same file, filters and normalization settings changes nothing; a different file, filter or
   normalization makes a new version and never edits an old one.
4. **Serve it:** set `TRADEMARK_CATALOG_VERSION` (`listing-guard.trademark.catalog-version`) to that name and restart
   the API. Leave it empty to keep trademark checks off. A catalog that does not exist, or that was imported with
   other normalization settings or another normalizer version, is refused at start-up (the API still starts and
   `/checks` still works): import it again and configure the new name. A ruleset that only changes thresholds or word
   lists still serves the catalog.

With a catalog served, `POST /api/v1/trademark-checks` (the same JSON as `/checks`) returns possible trademark
references with the field and the span of each, and `GET /api/v1/trademark-checks/{id}` reads a stored result. The
result is a reference warning, not a legal conclusion. Only a hash of the listing and the short evidence of each finding
are stored, for 30 days (`listing-guard.trademark.retention.period`); a scheduled cleanup in the API deletes expired
results. Without a catalog the endpoint answers `503` (`trademark-not-configured`) and nothing else changes. See the
[integration guide](integration-guide.md#trademark-checks) and [deployment](deployment.md).

## Hugging Face source (`hf-convert`)

[`illeatmyhat/uspto-trademarks`](https://huggingface.co/datasets/illeatmyhat/uspto-trademarks) is a third party's rebuild of the
USPTO bulk XML: the annual backfile plus the dailies, as Parquet tables joined by `serial_no`, with the latest state of each case
file. Its card says the data is a US government work (public domain). The `hf-convert` job turns it into one `jsonl-2` file that
`trademark-import` loads like any other ([ADR-030](adr/ADR-030-hugging-face-uspto-parquet-source.md), which also holds the column
names, the measurements and the open questions). The job needs no USPTO key, no database and no network.

1. **Check the terms.** Read the dataset card. The rebuild is a third party's and Listing Guard has not checked it; you are
   responsible for the use.
2. **Download** into `catalogs/raw/hf/` (git-ignored), keeping the repository's layout: the `case_file` and `intl_class` Parquet
   files, `profile/build_lineage.json` (the build and the date of the data) and `lookups/status_codes.csv` (the status codes are
   checked against it). Pin the commit you read. On 2026-11-16 it was `50e430b4c021614e1302bddb45a4afcdcb7823f2`, data through
   2026-10-31, about 775 MB:

   ```bash
   REV=50e430b4c021614e1302bddb45a4afcdcb7823f2
   B=https://huggingface.co/datasets/illeatmyhat/uspto-trademarks/resolve/$REV
   for f in profile/build_lineage.json lookups/status_codes.csv lookups/_sources.json \
            data/intl_class/data_0.parquet data/case_file/data_0.parquet; do
     mkdir -p "catalogs/raw/hf/$(dirname $f)" && curl -fL --retry 3 "$B/$f" -o "catalogs/raw/hf/$f"
   done
   ```

   With the Hugging Face CLI the equivalent is `hf download illeatmyhat/uspto-trademarks --repo-type dataset --revision $REV
   --include "data/case_file/*" "data/intl_class/*" "profile/*" "lookups/*" --local-dir catalogs/raw/hf`. A later build may have
   more or other file names: list them on the dataset page. At that commit `data/case_file/` also holds `data_1` and `data_2`, left
   by the previous build. The job handles them (the latest row of a serial number wins and gives the same output), but they cost
   350 MB and a third more time, so `data_0` is enough there.
3. **Convert:**

   ```bash
   docker compose --profile tools run --rm --build hf-convert --input=hf --output=hf-uspto-2026-10.jsonl
   # optional: --min-year=2015 (only marks registered, or filed when not registered, in 2015 or later), --strict, --overwrite
   ```

   It writes `catalogs/raw/hf-uspto-2026-10.jsonl`, keeping for each serial number its latest row, a live status code (the
   dataset's Live/Registered and Live/Pending codes), at least one of the Nice classes 14, 16, 18, 20, 21, 24, 25 and 28, and only
   marks with words of at least two letters or digits. It also writes `hf-uspto-2026-10.jsonl.report.json`: rows read, serial
   numbers, written, removed as not live by status code, filtered by each reason, the files with their SHA-256, the build and its
   date. Finally it prints the `sha256` and a manifest entry. The filters are `hf/hf-convert-filters-2026-11.yaml` (every value
   `unverified`); to change one, copy it with a new `version` into `catalogs/raw/` and pass
   `HF_FILTERS=file:/catalogs/raw/<copy>.yaml`. An `INCOMPLETE` run (a missing lineage or lookup, a file of the build missing, two
   different rows for one serial number) still writes the file but says why: do not deploy it before you read the problems. On the
   dev machine the default run wrote 1,224,122 marks (104 MB) in about a minute with DuckDB at 2 GB.

   The job is also a plain jar: `./mvnw -B -pl hf-convert -am package`, then
   `CATALOGS_DIR=catalogs java -jar hf-convert/target/listing-guard-hf-convert.jar --input=hf --output=<name>.jsonl`.
   `--input=hf://datasets/illeatmyhat/uspto-trademarks@<rev>` reads the repository directly, but only with `--allow-network`
   (`HF_CONVERT_ALLOW_NETWORK=true`). It then downloads DuckDB's `httpfs` extension, and the report cannot hash the files it read.
4. **Describe it in `catalogs/manifest.yaml`:** paste the printed entry. It uses the `sources:` form with `source_key: uspto`, so
   the stored source ids are `uspto:<serial>`, which the TSDR link and `tsdr-status` read. `source_date` is the build's
   `data_current_through`.
5. **Import and serve** as in steps 3 and 4 of the workflow above:
   `docker compose run --rm trademark-import --catalog=hf-uspto-2026-10`, then set `TRADEMARK_CATALOG_VERSION` to the printed name
   (or activate it, see [deployment](deployment.md#trademark-catalog-adr-009-pr-a2)). On the dev machine the import of the default
   output took about 5 minutes, loaded 1,040,779 entries (records that normalize to the same mark are merged) and used 292 MB of
   database.
6. **Next build.** The dataset is rebuilt monthly. Download the new build into a fresh folder (for example `catalogs/raw/hf-2026-11/`),
   convert it with `--input=hf-2026-11` to a new file name, add a new manifest entry and import it as a new version. Never edit an
   imported file.


## Tín hiệu distinctiveness (jsonl-2)

Từ [ADR-054](adr/ADR-054-owner-based-distinctiveness.md), `hf-convert` mặc định dùng ngưỡng `distinctiveness-2026-12`
và đọc bảng `owner` nếu có. Trường tùy chọn `owner_count_same_mark` là số tên chủ chuẩn hóa theo lineage hiện hành.
Thiếu owner thì ORDINARY khi đã có từ điển và drawing hợp lệ. Truyền `--distinctiveness=distinctiveness-2026-11`
để tái lập JSONL cũ. Xem [runbook](catalog-activation-runbook.md) để đo trước khi kích hoạt.

`hf-convert` tạo `jsonl-2`. Mỗi dòng thêm `word_count`, `same_mark_count`, `same_mark_class_count`,
`mark_kind`, `dictionary_word`, `distinctiveness_tier` và `distinctiveness_reason` theo
[ADR-046](adr/ADR-046-catalog-distinctiveness-signals.md). Các trường này tùy chọn khi import.
Import dùng `format_version: jsonl-2` trong manifest. File và manifest `jsonl-1` cũ tiếp tục dùng được.
Khi các dòng trùng nhãn có bộ tín hiệu xung đột, entry để trống tín hiệu.

Trên máy đã có Parquet, dùng lệnh sau. Chỉ thêm `--wordlist=english.txt` nếu đã chọn danh sách và lưu nó
dưới `catalogs/raw/`. Mỗi dòng chứa một từ UTF-8, dòng bắt đầu bằng `#` là chú thích. Thiếu wordlist,
`dictionary_word` là null và tier là `ORDINARY`.

```bash
docker compose --profile tools run --rm --build hf-convert \
  --input=hf --output=hf-uspto-distinctiveness-v1.jsonl
```

Đọc report, xác minh `COMPLETE` và sao chép file vào thư mục `catalogs/raw/` của máy import. Thêm mục
manifest từ đầu ra với label mới `hf-uspto-distinctiveness-v1` và đúng checksum. Khi dùng từ điển,
ghi tên, URL, revision, giấy phép và SHA-256 tại nguồn dữ liệu nội bộ hoặc chú thích manifest.
Chạy import sau khi API đã áp dụng V48, rồi activate phiên bản đầy đủ do import in ra.

```bash
docker compose --profile prod --profile tools run --rm trademark-import \
  --catalog=hf-uspto-distinctiveness-v1
docker compose --profile prod --profile admin run --rm admin catalog activate <version>
```

Report có phân bố theo nhãn chuẩn hóa duy nhất, tối đa 50 nhãn WEAK và 50 nhãn STRONG, số serial, dung lượng,
thời gian và checksum. Giữ báo cáo thật ngoài Git. Ruleset scanner giữ nguyên logic của từng phiên bản. Ruleset 2026-12 đọc tier của catalog đang phục vụ.


## Keeping the status of marks current: `tsdr-status`

A catalog version is immutable, so a mark that was live when its bulk file was written can be abandoned, cancelled or expired
later without the catalog knowing. The one-shot job `tsdr-status` (profile and Compose service of that name, off unless you run
it, never part of the API; [ADR-009, "TSDR status check"](adr/ADR-009-trademark-risk-scanner.md#tsdr-status-check-job-tsdr-status))
asks the USPTO TSDR for the status of the marks of the **served** catalog that recent trademark findings still name, the most
named first, stores each answer (serial number, status code and date, when it was checked, the result of the call) in the
append-only table `tsdr_status`, and writes a report to `catalogs/raw/tsdr/out/<UTC timestamp>/`: `report.json` and
`dead-marks.csv`. **It only proposes.** A mark that is dead but still named is listed as `REMOVE_AT_NEXT_SYNC`; nothing is
removed from a catalog, no version is activated and no finding changes. Leaving a mark out is your decision, carried out
through the next catalog sync.

```bash
# which marks would be asked about (asks nothing, writes nothing)
docker compose --profile tsdr-status run --rm tsdr-status --dry-run
# no key yet: read TSDR documents you saved yourself (catalogs/raw/<folder>/sn<8 digits>.xml or .json)
docker compose --profile tsdr-status run --rm tsdr-status --input-dir=tsdr-saved
# ask the TSDR (TSDR_DOWNLOAD_ENABLED=true and LISTING_GUARD_TSDR_API_KEY in .env), a few marks first
docker compose --profile tsdr-status run --rm tsdr-status --max-marks=5
```

- **Off by default and unverified.** The calls need `TSDR_DOWNLOAD_ENABLED=true` and a key (`LISTING_GUARD_TSDR_API_KEY`, a
  secret, never on a command line). The base URL, the XML status path and the rate limit (60 requests per key per minute) come from
  a USPTO page; **the key header, the names of the fields that hold the status and the answer for an unknown serial number do
  not**, and no real answer has been read. Each is a setting you correct in `catalogs/raw/tsdr.override.yml`. The ADR lists the
  USPTO pages to open and the steps to take once you have a key; saving one real document and running the offline mode on it
  settles the field names before any key is used.
- **Polite.** At least 2 seconds between requests, backoff for `429` and `5xx`, `Retry-After` honored, a run that stops after a
  refused key, a limit that lasts or five failures in a row, no redirect followed, no more than `TSDR_MAX_MARKS` (100) marks a run,
  and a mark that was answered is not asked about again within `TSDR_RECHECK_INTERVAL` (30 days; one day after a failure).
- **Weekly.** `deploy/oracle/tsdr-status.sh` (host lock, one log per run) and a commented weekly line in
  `deploy/oracle/crontab.example`; see [deploy-oracle.md](deploy-oracle.md#8a-check-the-status-of-catalog-marks-tsdr-status).
- **Optional, off by default: the status on a finding.** `TSDR_FINDING_STATUS_ENABLED=true` adds the last TSDR status and the date
  it was checked to each trademark finding (`tsdrStatuses`, see the [integration guide](integration-guide.md#tsdrstatuses-the-last-uspto-status-of-the-source-records-optional-off-by-default)).
  Off, the answer is the same as before. On, it is read when the answer is built, never stored, and changes no level, cache
  identity or `analysisVersion`.


## Bật hiệu chỉnh trademark theo tier

Sau khi nhập catalog `jsonl-2` đã kiểm tra theo ADR-046, có thể bật ruleset mới cho máy chủ hoặc job bằng
`LISTING_GUARD_TRADEMARK_RULESET=classpath:rulesets/trademark-risk-2026-12.yaml`. Mặc định giữ `2026-11b`.
Để rollback, đặt biến về `classpath:rulesets/trademark-risk-2026-11b.yaml` và khởi động lại cùng catalog.
Ruleset đổi fingerprint, vì vậy cache không tái sử dụng kết quả giữa hai phiên bản.

Caps chỉ áp cho exact một token ở title, tags và description khi không có tín hiệu thương hiệu đã khai
báo. Xem bằng chứng trong `riskAdjustments`, gồm `from`, `to`, `note` và `distinctiveness` nếu có.
Catalog cũ hoặc match thiếu tier trả kết quả `INCOMPLETE` theo ADR-043. Một match được hạ vẫn xuất hiện
trong kết quả và có thể xem trong phần thu gọn của Console.
