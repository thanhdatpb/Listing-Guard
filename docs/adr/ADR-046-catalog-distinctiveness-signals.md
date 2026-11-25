# ADR-046 — Catalog distinctiveness signals

- Trạng thái: Proposed.
- Phụ thuộc [ADR-009](ADR-009-trademark-risk-scanner.md), [ADR-030](ADR-030-hugging-face-uspto-parquet-source.md), [ADR-031](ADR-031-client-trademark-watchlist.md) và [ADR-043](ADR-043-trademark-context-calibration.md).

## Bối cảnh

Trong lần thử lịch sử với catalog thật, 20/20 listing mẫu trả `HIGH`. Mẫu chưa có nhãn nên kết quả không xác định được tỷ lệ báo nhầm. PR này đo tín hiệu từ catalog và lưu để maintainer đánh giá. Scanner tiếp tục quyết định mức rủi ro bằng ruleset hiện có.

## Quyết định

- `hf-convert` tiếp tục dùng đúng các cột trong bảng **Column names used** của ADR-030. DuckDB chọn lineage mới nhất và áp các bộ lọc live, Nice class, ngày và nội dung hiện hành. Java chuẩn hóa bằng `TextNormalizer.normalizeMark`; DuckDB nhóm kết quả đó dưới giới hạn bộ nhớ và thư mục spill hiện hành.
- Mỗi dòng `jsonl-2` giữ serial gốc. Các dòng có cùng chuỗi chuẩn hóa dùng chung `word_count`, `same_mark_count` (serial live khác nhau còn lại sau lọc, trước khi import gộp bản ghi) và `same_mark_class_count` (hợp các Nice class nằm trong bộ lọc). Mảng `nice_classes` đầu ra giữ hành vi cũ. Số serial có thể gồm nhiều đăng ký của cùng một chủ.
- Dùng code `4` cho `STANDARD_CHARACTER` và `3` cho `WORDS_WITH_DESIGN`. ADR-030 xác minh cột và phạm vi lookup 1–6. Code 1 (typeset), 5 (stylized) và các code còn lại được giữ ở `OTHER`; nhóm gồm nhiều loại cũng là `OTHER`. Tra cứu định nghĩa drawing trong [USPTO TMEP §807](https://tmep.uspto.gov/RDMS/TMEP/current#/current/TMEP-800d1e1.html).
- Khi chọn `--wordlist=<file>`, từ điển chứa một token mỗi dòng UTF-8 và được chuẩn hóa bằng cùng normalizer. `dictionary_word` biểu thị membership của chuỗi chuẩn hóa hoàn chỉnh; danh sách chỉ chứa một token nên chuỗi nhiều token cho `false`. Thiếu danh sách cho `null`. Report giữ SHA-256 của file thực đọc.
- `jsonl-1` tiếp tục import. `jsonl-2` thêm bảy trường tùy chọn, gồm `distinctiveness_reason`. V48 thêm cột `trademark_entry.distinctiveness` nullable dạng JSONB. Khi gộp, giữ bộ tín hiệu duy nhất nếu các bộ hiện diện đồng nhất; nếu xung đột thì để null. `CatalogMark` mang tín hiệu để tra cứu. `common_word`, `generic_words`, `context_fields` và cách chấm của scanner giữ nguyên.

### Ngưỡng mặc định

Tất cả là **unverified**. Sửa ngưỡng bằng một phiên bản tài nguyên mới. Bản đầu là [distinctiveness-2026-11.json](../../hf-convert/src/main/resources/hf/distinctiveness-2026-11.json).

| Ưu tiên | Điều kiện | Tier và lý do |
| --- | --- | --- |
| 1 | Không có wordlist | `ORDINARY`, `DICTIONARY_UNAVAILABLE` |
| 2 | Drawing không xác định chắc chắn hoặc nhóm có loại hỗn hợp | `ORDINARY`, `DRAWING_UNKNOWN` |
| 3 | Một token và nằm trong từ điển, hoặc có ít nhất 3 serial live, hoặc ít nhất 3 Nice class trong bộ lọc. Điều kiện một token áp dụng cho cả ba nhánh. | `WEAK`, `COMMON_OR_REPEATED` |
| 4 | Standard character, ít nhất 2 token, đúng 1 serial live và đúng 1 Nice class trong bộ lọc | `STRONG`, `UNIQUE_MULTIWORD` |
| 5 | Các trường hợp còn lại | `ORDINARY`, `DEFAULT` |

Tier là tín hiệu để chọn mẫu kiểm tra. Tên tier không khẳng định nhãn mạnh hay yếu theo pháp luật. Báo cáo đếm theo chuỗi chuẩn hóa duy nhất và lưu tối đa 50 dòng mỗi nhóm WEAK/STRONG, chỉ có chuỗi và số serial. Sắp WEAK theo số đếm giảm dần, STRONG tăng dần, rồi theo chuỗi để kết quả có thể tái lập.

### Wordlist để maintainer chọn

| Nguồn | Giấy phép dữ liệu và thao tác chuẩn bị |
| --- | --- |
| [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | CC BY-SA 4.0 cho dữ liệu. Chọn ngưỡng tần suất và trích cột từ cho tiếng Anh. |
| [wordfreq](https://github.com/rspeer/wordfreq) | CC BY-SA 4.0 cho dữ liệu tần suất. Xuất danh sách tiếng Anh với ngưỡng cố định. |

Ghi tên, revision, URL, giấy phép, ngưỡng xuất và SHA-256 khi chọn nguồn. Giữ file trong `catalogs/raw/` và ghi attribution theo điều khoản dữ liệu.

### Để sau (P2b)

Owner/party để đếm chủ sở hữu, Supplemental Register và disclaimer cần dữ liệu ngoài các cột ADR-030 đang dùng. P2b xác minh bảng, lineage và độ đầy đủ trước khi bổ sung mỗi tín hiệu.

### Đo dữ liệu thật

**Chưa đo, maintainer đo trên máy có dữ liệu.** Làm theo [lệnh convert, import và activate](../trademark-catalog.md#tín-hiệu-distinctiveness-jsonl-2). Ghi thời gian convert/import, dung lượng JSONL, số serial sau lọc, số nhãn gộp, phân bố ba tier, SHA-256 wordlist và kết quả khi xem tối thiểu 20 ví dụ mỗi tier có đủ mẫu. Giữ JSONL và report thật ngoài Git.
