# Đánh giá Spring AI cho Listing Guard

**Đề xuất LG-19: No-Go cho runtime.** Giữ adapter `RestClient` trong ADR-011. Mẫu thử tách riêng xác nhận Maven phân giải được Spring AI 2.0.1 trên Java 21 và Spring Boot 4.1.2. Việc thêm hai starter làm tăng số artifact và cần tắt tường minh nhiều loại model để ứng dụng khởi động khi chưa có credential. Chưa thấy lợi ích đo được cho Policy Checks hoặc POD Readiness.

## Ma trận tương thích

| Hạng mục | Kiến trúc Listing Guard hiện tại | Spring AI 2.0.1 và phạm vi đã kiểm chứng |
| --- | --- | --- |
| Java, Spring Boot, Maven | Java 21, Boot 4.1.2 và Maven multi-module. `RestClient` nằm trong Spring Web. | Hai starter OpenAI và pgvector được Maven phân giải trong ứng dụng Boot 4.1.2 riêng. Tài liệu Spring AI công bố hỗ trợ Boot 4.0.x và 4.1.x. |
| Phụ thuộc, schema JSON | `LlmProvider` có một adapter. Kiểm tra JSON và citation nằm trong mã của dự án. | `ChatClient` hỗ trợ Structured Output. Trong cây phụ thuộc thử, Maven chọn VicTools 5.0.0 và loại ba yêu cầu 4.38.0 của generator, module Jackson và module Swagger. Chưa chạy kiểm thử schema tích hợp. |
| Kiểm soát prompt và context | `PolicyAnalyzer` đưa dữ liệu vào trường xác định. Profile giới hạn nhóm truy vấn, chunks và context. | `ChatClient` và Advisors có thể hỗ trợ tạo request. Chưa thử tính tương đương trên fixture ADR-011. |
| Truy xuất và citation | `PolicyRetriever` liệt kê chunks được phép, PostgreSQL/pgvector thực hiện truy xuất và `CitationVerifier` kiểm tra nguồn. | VectorStore có adapter pgvector. Chưa thử tương thích với schema, filter theo marketplace/locale/version, hoặc đường kiểm chứng citation. |
| Observability và chi phí | Adapter có timeout, lỗi được phân loại và metadata token theo dữ liệu provider. | Có tích hợp observation. Chưa đo token, chi phí hay xác minh thuộc tính trace khi dùng listing thật. |
| Security và kiểm thử | Provider opt-in, kiểm thử hiện tại dùng scripted provider hoặc HTTP giả. | Ứng dụng thử khởi động không credential khi mọi modality được tắt. Chưa thử provider hoạt động, lỗi HTTP, retry hoặc prompt injection qua API. |
| Migration và rollback | Luồng deterministic hoạt động độc lập. Provider có thể tắt qua cấu hình. | Nếu thử tiếp, giữ adapter sau `LlmProvider`, chọn provider opt-in, giữ `PolicyRetriever` và `CitationVerifier` hiện tại để có thể quay về RestClient bằng cấu hình. |

Tham chiếu [ADR-011](../adr/ADR-011-policy-grounded-analyzer.md), [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) và [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html).

## Đo trên ứng dụng thử riêng

Mẫu dùng Spring Boot 4.1.2, Java 21.0.13 và một lớp `@SpringBootApplication` gọi `SpringApplication.run(...).close()`. Cấu hình đối chứng dùng `spring-boot-starter-webmvc`. Cấu hình Spring AI thêm BOM 2.0.1, `spring-ai-starter-model-openai` và `spring-ai-starter-vector-store-pgvector`. Không kết nối DB, HTTP provider hoặc model tính phí.

| Phép đo | Đối chứng | Với Spring AI |
| --- | ---: | ---: |
| Số artifact phân giải, bỏ mục bị Maven loại | 39 | 135 |
| Trung vị thời gian chạy tiến trình tới khi đóng context | 1,640 s | 2,000 s |
| Trung vị RSS đỉnh | 121.012 KiB | 137.920 KiB |
| Số lần đo thành công, sau một lần làm nóng cache cho mỗi cấu hình | 5/5 | 5/5 |

Dùng `org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree -Dverbose` để lấy cây phụ thuộc và `org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath` để dựng classpath. Cả hai lệnh chạy với từng cấu hình và trả mã 0. Hai cấu hình chạy xen kẽ với `-XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -Xms64m -Xmx256m`. Thời gian đo từ lúc tạo tiến trình đến lúc tiến trình thoát bằng đồng hồ monotonic. RSS lấy từ `wait4` trên Linux. Sample chạy trên máy CI dùng chung với tác vụ kiểm thử, nên các con số chỉ mô tả mẫu này.

Ứng dụng thử chạy non-web, bỏ `DataSourceAutoConfiguration`, đặt `spring.ai.vectorstore.type=none` và đặt `spring.ai.model.{chat,embedding,audio.speech,audio.transcription,image,moderation}=none` cho từng modality. Lần chạy đầu chỉ tắt chat và embedding. Lần đó cấu hình Spring AI thoát với mã 1 do bean `openAiSdkAudioSpeechModel` yêu cầu credential. Phép đo nêu trên dùng cấu hình đã tắt đầy đủ.

Chưa đo startup hoặc RSS của toàn bộ Listing Guard với Spring AI, chưa đánh giá chất lượng retrieval, độ tương đương citation, token, chi phí và telemetry khi bật provider. Giữ quyết định No-Go cho đến khi có lợi ích định lượng trên luồng Policy Checks và đảm bảo khởi động không credential, kiểm chứng citation và xử lý lỗi tương đương.
