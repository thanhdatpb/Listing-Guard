# Đo và kích hoạt catalog dựa trên chủ sở hữu

Dùng Oracle A1 hoặc máy Linux có Docker, bên ngoài Codex Cloud. Catalog thật **chưa đo**. Chỉ thử bằng
listing tổng hợp trong repo. Các ngưỡng và ước lượng theo tên chủ trong [ADR-054](adr/ADR-054-owner-based-distinctiveness.md)
chưa được xác minh về nghiệp vụ.

## 1. Chọn đúng phiên bản và kiểm tra dung lượng

Chạy trong checkout triển khai có Compose và env của VM. Dùng đúng tag của image đang triển khai, có ADR-054.
Nếu tag hiện tại chưa chứa ADR-054, đo trên máy test với tag mới sau khi phát hành. Service `hf-convert`
build từ checkout; service import và backtest lấy image theo `LISTING_GUARD_VERSION`.

```bash
# Ví dụ khi image đã phát hành và VM đang chạy v0.11.0.
LG_DEPLOY_VERSION=0.11.0
# Nếu VM chạy bản mới hơn có ADR-054, đổi biến thành tag thực tế (không có v).
git fetch origin "refs/tags/v${LG_DEPLOY_VERSION}:refs/tags/v${LG_DEPLOY_VERSION}"
git checkout --detach "v${LG_DEPLOY_VERSION}"
test -f docs/adr/ADR-054-owner-based-distinctiveness.md
export LISTING_GUARD_VERSION="$LG_DEPLOY_VERSION"
# Pilot dùng LG_ENV_FILE=.env.pilot; đặt LG_COMPOSE_PROJECT nếu VM dùng tên project riêng.
lg_compose=(docker compose --env-file "${LG_ENV_FILE:-.env}")
if [ -n "${LG_COMPOSE_PROJECT:-}" ]; then lg_compose+=(-p "$LG_COMPOSE_PROJECT"); fi
mkdir -p catalogs/raw/hf eval/backtest/out
df -h catalogs/raw eval/backtest/out
docker info --format '{{.DockerRootDir}}'
```

Ở revision bên dưới, metadata repo ghi khoảng **1,52 GB** cho ba bảng (case file 730 MB, intl class 45 MB, owner 744 MB).
Cần chỗ cho cache tải, lớp image, file JSONL, database, backup và spill DuckDB. Dành khoảng trống dự phòng và
kiểm tra cả phân vùng Docker bằng `df -h`. Dung lượng làm việc, đỉnh RAM và thời gian với owner **chưa đo**.
Trên A1, chạy một job nặng mỗi lần. Nếu không đủ RAM hoặc đĩa, chuyển đổi trên Linux khác và chép JSONL cùng manifest lên VM.

## 2. Tải nguồn cố định và tạo wordlist

Cài Hugging Face CLI (`hf`) trên máy chuyển đổi. Các thư mục raw chỉ giữ cục bộ.

```bash
export HF_REV=50e430b4c021614e1302bddb45a4afcdcb7823f2
hf download illeatmyhat/uspto-trademarks --repo-type dataset --revision "$HF_REV" \
  --include 'data/case_file/*.parquet' 'data/intl_class/*.parquet' 'data/owner/*.parquet' 'profile/*' 'lookups/*' \
  --local-dir catalogs/raw/hf
find catalogs/raw/hf/data/{case_file,intl_class,owner} -name '*.parquet' -print
du -sh catalogs/raw/hf
```

FrequencyWords ghi **CC BY-SA 3.0 cho nội dung**, MIT cho code tại commit đã ghim. Giữ attribution và ghi rõ biến đổi.
Đọc tối đa 30.000 dòng đầu theo thứ tự tần suất, giữ cột từ. Bỏ mục chứa ký tự ngoài chữ/số ASCII và apostrophe
để wordlist tuân thủ đầu vào một token của `hf-convert`.

```bash
set -o pipefail
FW_COMMIT=072bbed282316a23651aa7068c7173aa7898cf80
FW_URL="https://raw.githubusercontent.com/hermitdave/FrequencyWords/$FW_COMMIT/content/2018/en/en_50k.txt"
WORDLIST=catalogs/raw/wordlist-en-frequencywords-30k.txt
{
  printf '# FrequencyWords (hermitdave), en_50k.txt\n# Source: %s\n# Commit: %s\n' "$FW_URL" "$FW_COMMIT"
  printf '# Content license: CC BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/)\n'
  printf '# First 30000 records; retain the word column; skip non-ASCII words except apostrophe; distinctiveness-2026-12\n'
  curl -fsSL --retry 3 "$FW_URL" |
    LC_ALL=C awk 'NR <= 30000 && $1 ~ /^[[:alnum:]\047]+$/ { print $1 } END { if (NR < 30000) exit 1 }'
} > "$WORDLIST.part" && mv "$WORDLIST.part" "$WORDLIST"
sha256sum "$WORDLIST"
```

## 3. Chuyển đổi, xem báo cáo và nhập bản mới

Bảo đảm uid 10001 đọc nguồn, wordlist, và có thể ghi `catalogs/raw` và `eval/backtest/out` theo [runbook Oracle](deploy-oracle.md).
Job tắt mạng và mặc định dùng 2 GB DuckDB, hai luồng.

```bash
"${lg_compose[@]}" --profile tools run --rm --build hf-convert \
  --input=hf --output=hf-uspto-owner-2026-12.jsonl --strict \
  --wordlist=wordlist-en-frequencywords-30k.txt --distinctiveness=distinctiveness-2026-12
REPORT=catalogs/raw/hf-uspto-owner-2026-12.jsonl.report.json
jq -e '.completeness == "COMPLETE" and .distinctiveness.owners.state == "PRESENT"
       and .distinctiveness.owners.marksWithCount > 0 and .distinctiveness.dictionarySha256 != null' "$REPORT"
jq '{source, elapsedMillis, outputBytes, outputSha256, recordsWritten, distinctiveness}' "$REPORT"
sha256sum catalogs/raw/hf-uspto-owner-2026-12.jsonl
```

Nếu schema khác, dừng và gửi tên cột thật từ lỗi `Owner schema mismatch` cho maintainer. Nếu bảng owner vắng,
converter có thể xuất ORDINARY và báo `MISSING`; chưa đủ điều kiện kích hoạt pilot. Với `PRESENT`, đọc số mark
có/thiếu owner và các nhóm tier trước khi phê duyệt. So checksum đầu ra với report.

Thêm entry do converter in ra vào `catalogs/manifest.yaml`, dùng label riêng `hf-uspto-owner-2026-12`, giữ
`format_version: jsonl-2`, `source_key: uspto`, lớp, trạng thái và SHA-256 do converter in. Dùng URL dataset với revision ghim
trong `sources[].url`. Ghi URL, license và SHA-256 wordlist trong **chú thích YAML**, vì schema manifest hiện không nhận trường wordlist.

```bash
"${lg_compose[@]}" --profile prod --profile tools run --rm trademark-import --catalog=hf-uspto-owner-2026-12
"${lg_compose[@]}" --profile prod --profile admin run --rm admin catalog list
read -r -p 'Version đầy đủ vừa import (gồm digest): ' LG_OWNER_VERSION
```

## 4. Đo với cùng catalog, kiểm tra cổng rồi mới chuyển pilot

Hai job cùng ghim **bản vừa nhập**. Lần đầu chạy scanner 2026-11b, lần sau 2026-12. Không dùng `--llm-judge`.
Toàn bộ kết quả viết vào `eval/backtest/out/` đã git-ignore.

```bash
for variant in 2026-11b 2026-12; do
  TRADEMARK_CATALOG_VERSION="$LG_OWNER_VERSION" TRADEMARK_FOLLOW_ACTIVATION=false \
    LISTING_GUARD_TRADEMARK_RULESET="classpath:rulesets/trademark-risk-$variant.yaml" \
    "${lg_compose[@]}" --profile prod --profile tools run --rm backtest \
    --input=/backtest/samples/catalog-probe.csv --mapping=/backtest/mappings/catalog-probe-amazon.yaml \
    --marketplace=AMAZON --claimed-column=claimed --mark-column=mark --out="/backtest/out/catalog-$variant"
done
python3 - <<'PY_GATE'
import json
from collections import Counter
from pathlib import Path
directory = Path("eval/backtest/out")
def load(version):
    report = json.loads((directory / ("catalog-" + version) / "report.json").read_text())
    assert report["run"]["trademarkState"] == "READY"
    assert report["run"]["trademarkRulesetVersion"] == "trademark-risk-" + version
    assert report["run"]["abortReason"] is None and not report["run"]["stoppedAtMaxRows"]
    return report
before, after = load("2026-11b"), load("2026-12")
assert before["run"]["inputSha256"] == after["run"]["inputSha256"]
assert before["run"]["trademarkCatalogVersion"] == after["run"]["trademarkCatalogVersion"]
old = {row["id"]: row for row in before["probes"]}
new = {row["id"]: row for row in after["probes"]}
assert old.keys() == new.keys() and len(new) == 47
fail = []
for name, row in new.items():
    if not row["passed"]:
        fail.append((name, "sai ngưỡng hoặc thiếu dữ liệu"))
    if name.startswith(("BRAND-", "FAMOUS-")) and (
        row["namedMarkRisk"] not in ("MEDIUM", "HIGH") or row["namedMarkTier"] not in ("ORDINARY", "STRONG")
    ):
        fail.append((name, "mark đích thiếu, WEAK hoặc dưới MEDIUM"))
    if Counter(old[name]["findingKeys"]) - Counter(row["findingKeys"]):
        fail.append((name, "mất finding từ lần chạy 2026-11b"))
print(f"Đã so sánh {len(new)} ca; {len(fail)} điều kiện không đạt.")
for item in fail:
    print(*item, sep=": ")
raise SystemExit(bool(fail))
PY_GATE
```

Kỳ vọng là 0 HIGH cho nhóm `DESC`, mỗi brand/mark nổi tiếng phải có finding trên đúng mark, tier ORDINARY hoặc STRONG
và mức từ MEDIUM trở lên. Bộ này là cổng hồi quy, không phải nhãn pháp lý, không phải phép đo precision/recall. Nếu bất kỳ cổng nào
đỏ, giữ catalog đang phục vụ và gửi báo cáo cho maintainer.

Khi đã duyệt, ghi activation. Với môi trường đang theo activation log, API sẽ nhận version mới. Với pilot ghim version như mẫu,
chỉ đổi `TRADEMARK_CATALOG_VERSION` trong env sau khi đã kiểm tra cổng. Giữ ruleset 2026-12.

```bash
"${lg_compose[@]}" --profile prod --profile admin run --rm admin catalog activate "$LG_OWNER_VERSION" --note='ADR-054 owner catalog'
# Nếu pilot ghim version, cập nhật TRADEMARK_CATALOG_VERSION trong env trước khi tạo lại API.
"${lg_compose[@]}" --profile prod up -d --no-deps --force-recreate api
```

Kiểm tra `/api/v1/capabilities` và một check với mẫu probe qua lệnh smoke test hiện hành. Rollback với
`admin catalog rollback` khi theo activation log. Với pilot ghim version, khôi phục version cũ trong env và tạo lại `api`.

## 5. Gửi lại cho maintainer

- Từ report converter lấy `completeness`, `source`, `elapsedMillis`, `outputBytes`, `outputSha256`, `recordsWritten`
  và phần `distinctiveness` gồm tier, mức bao phủ owner, phiên bản ngưỡng và SHA-256 wordlist.
- Gửi `report.md` của hai lần backtest với phần kỳ vọng theo dòng và kết quả cổng ở trên. Chỉ gửi kết quả của
  `catalog-probe.csv`, không gửi listing khách, cơ sở dữ liệu, log bí mật, file raw hay wordlist.


## Đo độ trễ phrase screening

Chưa đo trên catalog thật. Dùng image chứa ADR-055, bật `LISTING_GUARD_PHRASE_SCREENING_ENABLED=true`, kích hoạt catalog đã
được duyệt và kiểm tra `/api/v1/capabilities`. Dùng client kiểm thử để tránh tiêu quota của seller. Mặc định 10 lượt sẽ dùng
10.000 cụm của hạn mức ngày. Nhập API key qua prompt để khỏi lưu trong lịch sử lệnh.

```bash
export LG_BASE_URL=https://your-listing-guard-host
read -r -s -p 'Listing Guard test API key: ' LG_API_KEY
printf '\n'
export LG_API_KEY
LG_RUNS=10 ./scripts/measure-phrase-screening.sh
unset LG_API_KEY
```

Ghi p50/p95, số lượt, catalog version, ruleset, giới hạn fuzzy, phiên bản image và kiến trúc VM. Dừng nếu request lỗi.
Đo stress với 2.000 cụm riêng sau khi xác nhận mức độ trễ của 1.000 cụm.
