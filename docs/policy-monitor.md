# Policy change monitor

`policy-diff` is a one-shot, deterministic job. It compares policy files that a maintainer saved locally with the previous
document and section hash snapshot. It does not fetch pages, call an LLM or embedding model, expose an API endpoint, or store
policy text in the database. See [ADR-026](adr/ADR-026-policy-change-monitor.md).

## Monthly review

1. Check the marketplace's terms and save the current policy page under the matching ignored folder, for example
   `policies/raw/etsy/seller-policy.html`. Keep the original saved file locally; raw policy text is copyrighted and is not
   committed. The job accepts the HTML/PDF extensions supported by the existing policy parser.
2. Update the matching `policies/manifest.yaml` row: `fetched_at` is the date you saved the file and `version` is the page's
   own “Last updated” date in ISO format (`YYYY-MM-DD`). Leave the canonical public `url` unchanged unless it has moved.
3. Preview the comparison. On an Oracle host with the production `.env` and compose files:

   ```bash
   ./deploy/oracle/policy-diff.sh --dry-run
   ```

   The first preview shows every document as new. Read `policy-monitor/out/<UTC timestamp>/report.html` and
   `report.json`. Confirm document and section changes, stale-save and version-date alerts, and the rules to review with each
   rule's current source status.
4. Run the job without `--dry-run` to update the DB snapshot after reviewing the preview:

   ```bash
   ./deploy/oracle/policy-diff.sh
   ```

   A non-dry run writes the current hashes as the next baseline. Repeating it with identical files and manifest produces no
   change entries. The report folder is git-ignored because a changed section can include an excerpt of up to 200 characters.
5. Review affected rules manually. If a rule needs a change, create a new immutable ruleset version and update its source
   details/status through the normal ruleset review. This monitor never edits a ruleset or hash.

`fetched_at` older than `POLICY_MONITOR_STALE_DAYS` (default 90) is an alert. A parseable page date that differs from the
manifest `version` is also an alert. A missing or malformed local file stops the run without updating the snapshot. A dry run
does not write the snapshot, but it does write its report. `POLICY_MONITOR_KEEP_REPORTS` controls report retention (default 12):
after each successful run, the job keeps the newest timestamped report directories and removes older ones.

For a manual Compose invocation:

```bash
docker compose -f docker-compose.prod.yml --profile prod --profile policy-diff run --rm policy-diff --dry-run
docker compose -f docker-compose.prod.yml --profile prod --profile policy-diff run --rm policy-diff
```

The container mounts `policies/` read-only and `policy-monitor/` for reports. It uses the database configured in `.env`; the
additive V33 migration creates the snapshot tables when the API or job starts.

## Báo cáo tác động trong Web Console

Sau khi xem preview, maintainer có thể công bố báo cáo metadata lên Console bằng cùng job. Tệp phê duyệt
là JSON cục bộ do maintainer cung cấp. Mỗi khóa là URL HTTPS chính tắc. Ghim cả hash cũ và mới từ preview
và ghi ngày hiệu lực đã xác nhận từ nguồn. Dùng `null` cho hash của phiên bản vắng mặt khi thêm hoặc xóa
tài liệu. Ngày page được cập nhật và ngày thu thập không thay thế ngày hiệu lực.

```json
{
  "https://www.etsy.com/legal/sellers/": {
    "oldHash": "<SHA-256 từ preview>",
    "newHash": "<SHA-256 từ preview>",
    "effectiveDate": "2026-11-20"
  }
}
```

Đặt tệp phê duyệt ngoài Git, ví dụ trong thư mục đã bỏ qua `policies/raw/`, rồi chạy lệnh sau sau khi
đã xác nhận hash và ngày hiệu lực.

```bash
./deploy/oracle/policy-diff.sh --dry-run --impact-approval=/policies/raw/impact-approval.json
```

Lần chạy này đối chiếu hash với snapshot cũ và tệp nguồn được parse tại chỗ. Job giữ baseline nguyên vẹn
để các lần preview sau có thể tái lập. Khi cần đổi baseline, maintainer thực hiện lệnh cập nhật hiện hành
riêng sau khi kiểm tra báo cáo. V47 chỉ lưu ngày, URL, version, tên mục, hash và rule mapping. Đoạn trích
trong báo cáo preview cục bộ nằm ngoài dữ liệu Console.

- `PROPOSED` yêu cầu URL được duyệt, hash của hai phía khớp, version ISO hợp lệ, ngày hiệu lực và nguồn
  không có cảnh báo. Thiếu thông tin hoặc sai hash cho `NEEDS_REVIEW`. Console chỉ tạo link nguồn cho
  báo cáo đã kiểm tra được provenance.
- ID mục là đường dẫn heading đầy đủ theo parser hiện tại. Mục giữ nguyên hash được bỏ qua. Một mục xóa
  và một mục thêm có hash giống nhau, ghép được duy nhất, được ghi là di chuyển. Đổi URL tài liệu xuất
  hiện dưới dạng thêm/xóa tài liệu. Khi các hash trùng lặp, job giữ các mục thêm/xóa để reviewer xem.
- Ánh xạ rule dựa trên URL và section trong source hiện có. Category chỉ xuất hiện khi nguồn của chính
  override category khớp. Đây là danh sách cần xem lại; mục văn bản khác nhau chưa xác định chính sách
  có thay đổi về nội dung quy định hay cách áp dụng.
- Mở **Thay đổi chính sách** từ trang **Rà định kỳ**. Người dùng có thể lọc theo marketplace, trạng thái
  và ngày hiệu lực. Manager `ADMIN` ghi nhận `TEXT_ONLY`, `RULE_CHANGE` hoặc `DEFERRED` cho tenant.
  Một báo cáo `NEEDS_REVIEW` chỉ có thể được tạm hoãn. Quyết định đầu tiên được giữ theo tenant và report;
  chạy lại cùng thao tác không sinh bản ghi thứ hai. Báo cáo công bố chỉ chứa metadata nguồn công khai,
  còn quyết định và reviewer thuộc riêng tenant. Thao tác này không đổi ruleset.
- Người bán mở form re-audit hiện có để tự tải file listing hiện tại, hoặc mở so sánh và cung cấp hai
  bản nháp. Kết quả re-audit chỉ so sánh khi có dữ liệu phù hợp trong cùng scope. Công cụ không có dữ
  liệu để tự lập danh sách SKU bị ảnh hưởng. CSV xuất từ báo cáo đã lưu và vô hiệu hóa ô dạng công thức.

Chưa có endpoint API v1 mới. Rollback bằng dùng phiên bản ứng dụng trước và dừng dùng
`--impact-approval`; giữ V47 và metadata đã ghi. Job cũ và quy trình cập nhật immutable ruleset tiếp tục
theo ADR-026.
