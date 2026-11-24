# LG-07 · Đánh giá truy xuất chính sách

Runner `eval-retrieval` dùng cùng bộ câu hỏi được gán nhãn cho hai phương pháp. Baseline dùng cosine
pgvector hiện hành. Hybrid thử nghiệm lấy tối đa 30 ứng viên pgvector và 30 ứng viên từ PostgreSQL full-text
search. Keyword dùng `plainto_tsquery('simple', ...)` trên đường dẫn heading và nội dung chunk. Hai danh sách
được hợp nhất theo chunk ID bằng reciprocal rank fusion, với điểm `1 / (60 + rank)` từ mỗi danh sách.
Hybrid chỉ chạy trong eval. Endpoint truy xuất vẫn dùng pgvector baseline.

## Chạy offline

Chuẩn bị Java 21, cache Maven đầy đủ và Docker có sẵn image `pgvector/pgvector:pg16` cùng image phụ trợ
Testcontainers. Sau đó chạy ở thư mục gốc.

```bash
./mvnw -o -B -pl api -am test -Dtest=PolicyBenchmarkIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Kết quả được tạo ở `api/target/policy-benchmark.md`. Xem [báo cáo từ fixture](../../eval/retrieval/fixture-results.md).
Bài kiểm thử chạy benchmark hai lần và so sánh toàn bộ
báo cáo. Dữ liệu tổng hợp được tạo từ fixture HTML tự viết, nhãn VI/EN có tại
[`queries.yaml`](../../api/src/test/resources/policy-benchmark/queries.yaml). Mốc đánh giá cố định là
2026-11-01 UTC. Fixture qua ingest thật và pgvector thật với `HashingEmbeddingProvider`. Không cần tài khoản
ngoài, LLM hay truy cập trang chính sách. Corpus gồm các đoạn tự viết về production partner, mockup photo,
quyền sử dụng thiết kế, cách diễn đạt mơ hồ và tài liệu đối chứng thuộc marketplace khác.

Để đo corpus Etsy thật, chuẩn bị các tệp được phép sử dụng theo `policies/manifest.yaml`, ingest bằng model
đã cấu hình rồi chạy `docker compose run --rm eval-retrieval`. Kết quả ghi vào `eval/retrieval/results.md`.
Bộ nhãn Etsy đã duyệt ở [`eval/retrieval/queries.yaml`](../../eval/retrieval/queries.yaml) giữ nguyên.
Checkout hiện tại thiếu `policies/raw`, vì vậy chưa có baseline trên corpus Etsy thật. Bộ tổng hợp, đặc biệt
là các câu hỏi lặp lại từ khóa trong chunk, chỉ xác nhận pipeline và khả năng tái lập. Chưa thể dùng kết quả
để chọn thuật toán production, đánh giá embedding đa ngôn ngữ hoặc kết luận chính sách áp dụng thế nào.

## Cách đọc báo cáo

- **Recall@5** là tỷ lệ câu hỏi có ít nhất một section được gán nhãn trong top 5, theo quy ước eval hiện tại.
  **MRR@10** tính nghịch đảo hạng của kết quả phù hợp đầu tiên, hoặc 0 khi không có trong top 10.
- **No hits@10** đếm câu hỏi không có chunk trả về. **No relevant hits@10** đếm câu hỏi không có kết quả khớp
  nhãn trong top 10, dù có thể vẫn trả về các chunk khác.
- **Source/version coverage@5** là tỷ lệ nguồn được gán nhãn có ít nhất một section khớp nhãn ở đúng phiên bản
  trong top 5, trên tập nguồn duy nhất của từng ngôn ngữ. Danh mục báo cáo ghim từng tệp với URL, tiêu đề,
  version và SHA-256. Một source trong corpus hiện có một version cho mỗi marketplace.
- **Stale returned hits@5** là tỷ lệ chunk top 5 lấy từ bản chụp quá 90 ngày, tính theo ngày UTC của lần đánh
  giá. Đây là ngưỡng theo dõi benchmark. Tuổi bản chụp không xác định chính sách đã thay đổi hay còn hiệu lực.
- Benchmark không dùng LLM để sinh trích dẫn. Runner từ chối tạo báo cáo khi thiếu nguồn gán nhãn hoặc
  provenance nguồn sai. Các chunk được đối chiếu marketplace, title, URL gốc và version với danh mục.
  Báo cáo ghi metadata và tên section, không ghi nội dung chunk hay listing của người bán.

## Metadata và phương án nâng cấp

| Trường | Hiện trạng |
| --- | --- |
| URL và title | Lưu trong `policy_document`. URL chunk có thể thêm fragment của heading. |
| Hash | `source_hash` là SHA-256 của tệp nguồn đã ingest. Báo cáo kiểm tra định dạng và ghi đủ hash để truy xuất snapshot. |
| Version | `version` là thông tin cập nhật của tài liệu. Không dùng làm ngày có hiệu lực. |
| Ngày thu thập | `fetched_at` có độ chính xác tới ngày. `created_at` và `updated_at` là dấu thời gian thao tác cơ sở dữ liệu. |
| Ngày có hiệu lực và thời điểm thu thập chính xác | Chưa có. |

Đề xuất sau khi maintainer duyệt quy ước nguồn là thêm hai cột nullable `effective_on DATE` và
`retrieved_at TIMESTAMPTZ` vào `policy_document` qua migration Flyway additive. Ingest có thể đọc hai
trường tùy chọn từ manifest khi người vận hành có nguồn kiểm chứng và múi giờ rõ ràng. Chỉ backfill theo cặp
marketplace, source file và SHA-256 khớp bản chụp. Giữ `NULL` nếu hiện chỉ có ngày hoặc nếu nguồn không công
bố ngày hiệu lực. Không suy diễn từ version, giờ ingest hay đặt mặc định nửa đêm. Đề xuất này chưa đổi schema.

Rollback bằng revert commit benchmark. Dữ liệu policy hiện hành, khai báo embedding và API được giữ theo
quy trình ingest và search hiện tại.
