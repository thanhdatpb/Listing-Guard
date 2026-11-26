# ADR-054 — Owner-based distinctiveness

- Trạng thái: Proposed. Bổ sung ADR-046; giữ tài nguyên ngưỡng 2026-11 để tái lập.
- Schema **VERIFIED**, ngưỡng và cách quy tên **UNVERIFIED**.

## Nguồn và lineage

Đã đọc README, profile, lookup `party_types.csv` và chạy `DESCRIBE SELECT * FROM read_parquet(https…)` bằng DuckDB
trên cả ba shard `data/owner` của revision `50e430b4c021614e1302bddb45a4afcdcb7823f2`.
Bảng `owner` dùng `serial_no VARCHAR`, `own_name VARCHAR`, `own_type_cd VARCHAR`, `file_dt_source DATE`
và `file_name_source VARCHAR`. Không đọc cột địa chỉ hoặc tên thay thế.

Chỉ xét serial live đã qua các bộ lọc. Owner phải cùng ngày và tên file với bản case file thắng theo ADR-030.
Trong snapshot đó, lấy tất cả tên ở mã giai đoạn cao nhất trong 10–69 của lookup: từ applicant ban đầu,
qua chủ lúc công bố/registrant, tới chủ kế tiếp. Không xét mã 70 (correspondent) hoặc lấy lại tên ở snapshot cũ.
Giữ đồng chủ ở cùng giai đoạn. Nếu một serial cùng mark thiếu tên chủ hợp lệ, count của mark là null để tránh
coi dữ liệu một phần là đủ. Bảng vắng có thể chuyển đổi với count null và báo cáo; bảng sai schema dừng và in cột thật.

Viết hoa, thay dấu câu bằng khoảng trắng, gộp khoảng trắng và bỏ lặp các hậu tố cuối tên theo cấu hình immutable;
dấu chấm giữa chữ cái của hậu tố được gộp để `L.L.C.` và `LLC` khớp nhau. Đây là ước lượng từ tên, không xác minh
pháp nhân, quan hệ công ty hoặc tình trạng chuyển nhượng pháp lý.

## Tier và tương thích

2026-12 xét thiếu wordlist, drawing không xác định rồi thiếu owner trước khi xét ngưỡng. Một token có ít nhất ba chủ
là WEAK. Từ trong từ điển với tối đa hai chủ là ORDINARY. Một token ngoài từ điển có một chủ hoặc chuỗi nhiều token
standard character có một chủ là STRONG. Các trường hợp khác ORDINARY. `same_mark_count` vẫn là số serial.
Trường JSONL-2 `owner_count_same_mark` tùy chọn dùng JSONB V48. V52 giữ bản sao số đếm ở finding để GET và
cache vẫn có bằng chứng tại thời điểm kiểm tra khi catalog đã chuyển hoặc bị dọn. 2026-11 giữ đầu ra JSONL cũ.
Không thay logic xếp mức của ruleset scanner. Catalog thật và bộ probe **chưa đo**. Xem [runbook](../catalog-activation-runbook.md).
