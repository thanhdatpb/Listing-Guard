# LG-09 · Analyzer Profile và truy xuất chính sách có giới hạn

LG-09 triển khai ADR-011 PR 4. `AnalyzerPolicyRetrieval` nhận marketplace, ngôn ngữ listing (`en` hoặc `vi`)
và `Listing` đã chuẩn hóa. Kết quả nội bộ gồm trạng thái, descriptor và các chunk hợp lệ còn nguyên văn.
Tính năng chưa được nối vào API và không gọi LLM. `/api/v1/checks` chạy rule engine theo luồng hiện hành.

## Profile và descriptor

Profile Etsy nằm trong `rag-analyzer/src/main/resources/analyzer/etsy-policy-2026-11.yaml`. Loader ghim
version và SHA-256 theo byte của YAML (LF) và kiểm tra trước khi parse. Settings và các danh sách trong
profile không thể sửa sau khi load. Khi cần thay đổi nguồn, probe hoặc giới hạn, thêm profile với tên và
version mới, ghim hash mới và chọn profile một cách tường minh sau review. Giữ nguyên file đã phát hành.

Descriptor nội bộ chứa version và hash profile, fingerprint corpus, version chunker, cấu hình pipeline ingest, model embedding và version thuật toán retrieval. Fingerprint SHA-256 lấy từ các dòng `source_file<TAB>source_hash<TAB>version`
theo thứ tự tên file. LG-10 sẽ bổ sung định danh prompt và LLM khi tạo `analysisVersion` chính thức.
`analysisVersion` của các API hiện có vẫn theo hợp đồng hiện hành.

## Giới hạn và cách lấy dữ liệu

- Tối đa sáu truy vấn. Một truy vấn ghép tiêu đề với tối đa 32 tag, một truy vấn mô tả, và bốn probe lấy từ
  profile theo ngôn ngữ listing. Khi trường thiếu, bỏ truy vấn listing tương ứng. Hai truy vấn listing có
  tổng ngân sách 6.000 đơn vị UTF-16, trong đó mô tả tối đa 4.000.
- Gọi `EmbeddingProvider.embed(List, QUERY)` một lần cho nhóm truy vấn. Số request HTTP thực tế phụ thuộc
  batch size, retry và đặc tính của provider hiện có. Tất cả các vector được kiểm tra độ dài 768 và giá trị
  hữu hạn. Pgvector lọc marketplace và ID tài liệu đã xác minh trước khi xếp hạng. Tối đa ba kết quả mỗi nhóm.
- PostgreSQL loại chunk có body trên 48.000 byte, hoặc URL/section path trên 8.192 byte, trước khi chuyển
  về ứng dụng. Sau xếp hạng chọn score cao trước, dùng chunk ID để loại trùng. Kết quả tối đa 12 chunk và
  tổng 12.000 đơn vị UTF-16 trên nội dung, title, section path, URL, tên file và version. Bỏ qua chunk không
  vừa ngân sách và giữ nguyên văn các chunk đã chọn để kiểm tra trích dẫn. LG-10 cần tính phần bao JSON
  hoặc prompt khi áp thêm giới hạn cho payload gửi LLM.
- Đọc corpus và truy xuất trong một giao dịch read-only với snapshot repeatable-read để tránh ghép metadata
  và chunk từ hai bản ingest. SQL có timeout 5 giây mỗi statement và giao dịch có timeout 30 giây.
  Provider vẫn áp dụng timeout transport riêng. LG-10 cần thêm deadline và giới hạn đồng thời ở tầng gọi
  analyzer theo ADR-011.
- Không lưu hoặc log listing, query hay embedding của listing. Loại các trường ảnh, customization và
  personalization khỏi bộ tạo truy vấn. Khi tích hợp, metadata và nội dung sẽ đi vào tầng phân tích như
  dữ liệu không đáng tin cậy. Provider từ xa nhận các trường văn bản đã giới hạn nếu triển khai bật luồng gọi.

## Provenance và trạng thái

Nguồn được giới hạn theo tên file, URL chính tắc và policy version cụ thể của manifest Etsy. Profile
khai báo ngôn ngữ của corpus là tiếng Anh. Ngôn ngữ của probe có thể là tiếng Anh hoặc tiếng Việt. Schema
hiện chưa có trường locale hoặc ngày hiệu lực, nên locale là metadata của profile và độ mới dùng
`fetched_at`. Bản chụp quá 90 ngày hoặc phiên bản cũ hơn profile bị từ chối. Bản mới hơn cần một profile mới
được duyệt. Corpus fingerprint ghim các snapshot đã ingest, không suy diễn tính hiệu lực pháp lý.

| Trạng thái | Ý nghĩa |
| --- | --- |
| `UNSUPPORTED_MARKETPLACE` / `UNSUPPORTED_LOCALE` | Profile chưa hỗ trợ marketplace hoặc ngôn ngữ yêu cầu. |
| `MISSING_CORPUS` / `EMPTY_CORPUS` | Thiếu tài liệu bắt buộc hoặc một tài liệu bắt buộc chưa có chunk. |
| `STALE_CORPUS` / `POLICY_VERSION_MISMATCH` | Snapshot quá cũ hoặc policy version chưa được profile cho phép. |
| `INVALID_PROVENANCE` / `NO_USABLE_EVIDENCE` | Metadata không nhất quán hoặc không có chunk nằm trong giới hạn. |
| `EMBEDDING_NOT_CONFIGURED` / `CORPUS_MODEL_MISMATCH` / `EMBEDDING_FAILED` | Provider chưa cấu hình, model khác corpus, hoặc lỗi khi tạo vector. |
| `CORPUS_PIPELINE_MISMATCH` | Cấu hình chunking của corpus khác cấu hình đang dùng. Ingest lại trước khi phân tích. |
| `RETRIEVAL_TIMEOUT` / `RETRIEVAL_ERROR` | Hết thời gian chờ hoặc lỗi truy xuất. |
| `READY` | Có bằng chứng đủ metadata trong giới hạn. Bước xác minh finding và citation thuộc LG-10. |

Checkout không có `policies/raw`; test dùng corpus tổng hợp, hashing embeddings và PostgreSQL/pgvector
thật. Cần snapshot được phép sử dụng và khớp profile khi tích hợp LG-10. Rollback bằng revert commit.
Không có migration hay thay đổi OpenAPI ở bước này.
