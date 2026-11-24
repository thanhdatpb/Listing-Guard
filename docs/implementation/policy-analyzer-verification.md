# LG-10 · Policy Analyzer và xác minh trích dẫn

`PolicyAnalysisService` nối `AnalyzerPolicyRetrieval` của LG-09 với `LlmProvider` của LG-08. Đây là service
nội bộ, có mặt khi `listing-guard.analyzer.enabled=true`. Chưa có endpoint, migration, lưu kết quả hoặc cache mới.
`/api/v1/checks` tiếp tục chạy rule engine riêng.

## Luồng xử lý

1. Đọc snapshot retrieval và kiểm tra trạng thái. Corpus thiếu, hết hạn, sai version hoặc lỗi retrieval làm
   kết quả `INCOMPLETE` trước khi gọi LLM. Kiểm tra lại marketplace, locale nguồn, URL, version, ID, hash và
   ngày thu thập của từng chunk theo profile. ID và hash lấy từ metadata đáng tin cậy của snapshot.
2. Tạo prompt với system instructions cố định. Phần user là một JSON riêng chứa ngôn ngữ, allowlist trường
   listing và các chunk được đưa vào xác minh. Ngân sách đo sau khi escape JSON, tối đa 6.000 đơn vị UTF-16
   cho object listing và 12.000 cho mảng evidence. Tối đa 12 chunks. Adapter gửi một lần gọi, tối đa 1.500
   output tokens, không tools. Chỉ trường title, description, tags, materials, who_made, when_made và
   category được gửi. Trường ảnh, customization và personalization nằm ngoài payload.
3. Parse một object có đúng mảng `findings`, tối đa 10 items. Kiểm tra trường bắt buộc, loại dữ liệu,
   khoá trùng, dữ liệu nối đuôi, thuộc tính ngoài schema và enum. Không nhận `confidence` hay `status` do
   model tự khai. Cả lỗi HTTP, timeout, refusal, output bị cắt và schema lỗi đều trả trạng thái nội bộ
   `INCOMPLETE`. Item sai schema bị loại riêng. Trong luồng này, `ERROR` bị loại theo enum schema
   `WARNING/INFO`. Bộ verifier cũ vẫn giữ quy tắc clamp để tương thích với caller trước đây.
4. `CitationVerifier` chỉ nhận chunk đã xuất hiện trong payload gửi đi. Kiểm tra độ dài quote, trích nguyên
   văn và giá trị được dẫn từ listing. Đối chiếu `sourceHash` với snapshot và yêu cầu khoảng
   `[start, end)` theo đơn vị UTF-16 khớp **chính xác** với quote trong text gốc. Không đặt ranh giới
   giữa một cặp surrogate. Citation trả lại quote và metadata từ nguồn đã kiểm chứng.
5. Nếu có item bị loại, trả `INCOMPLETE` với thống kê và các mục rà soát còn hợp lệ. Nếu model trả mảng
   rỗng, trả `INSUFFICIENT_EVIDENCE`. Nếu mọi mục được giữ, trả `REVIEW_REQUIRED` để người dùng xem
   đoạn trích. Kết quả chỉ đưa lời nhắc rà soát bằng VI/EN, không chuyển tiếp message, suggestion hay
   value thô của model. Những trạng thái này không cấp phép đăng listing.

`analysisVersion` là tiền tố `policy-analyzer:` kèm 16 ký tự đầu của SHA-256 trên descriptor JSON có
thứ tự thuộc tính ổn định. Descriptor chứa descriptor retrieval, phiên bản và hash prompt/schema,
định danh model LLM, phiên bản thuật toán xác minh và ngôn ngữ truy vấn. Tách ngôn ngữ trong định danh
tránh dùng lại kết quả bằng ngôn ngữ khác sau khi có cache.

Service giới hạn bốn lượt đồng thời trên mỗi instance và deadline 40 giây cho caller. Lượt quá hạn
được yêu cầu dừng bằng interrupt. Slot được giữ tới khi worker thoát. Adapter LLM vẫn áp deadline
tối đa 30 giây. Chỉ các bộ đếm xác minh được log. Không ghi prompt, response thô hay listing vào
database hoặc log. Nếu bật provider từ xa ở luồng tích hợp sau này, provider sẽ nhận các trường listing
văn bản đã giới hạn.

## Giới hạn và bàn giao LG-11

Các kiểm tra trên xác nhận nguồn và trích dẫn về mặt kỹ thuật. Chúng chưa chứng minh rằng đoạn trích
hỗ trợ nhận định về mặt ngữ nghĩa hay rằng chính sách áp dụng cho listing cụ thể. Vì vậy kết quả chỉ giữ
đoạn trích và lời nhắc rà soát do code tạo. Kiểm thử prompt injection xác nhận seller text và policy
text chỉ nằm trong JSON data, và việc model đề nghị phê duyệt hoặc nâng severity ra ngoài schema không
tạo được kết quả hợp lệ. Mô hình có thể chọn một đoạn trích không liên quan dù đoạn đó có thật.

Kiểm thử dùng corpus tổng hợp và fake provider. Checkout thiếu `policies/raw`, vì vậy chưa xác nhận trên
corpus Etsy thật. Model có thể đếm offsets kém và làm giảm số đoạn trích được giữ. LG-11 có thể dùng
service và descriptor để triển khai endpoint, auth/tenant, quota, persistence và cache. Cần escape nội dung
quote khi hiển thị và chỉ lưu dữ liệu đã được duyệt trong hợp đồng. Rollback bằng tắt
`listing-guard.analyzer.enabled` hoặc revert commit. ADR-011 vẫn Proposed.
