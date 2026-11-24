# ADR-045 — Ranh giới LG-P1–P4 và Java cho release

- Trạng thái: Proposed, ghi lại các quyết định đã triển khai và lựa chọn runtime cho v0.10.0.
- Liên quan [ADR-011](ADR-011-policy-grounded-analyzer.md), [ADR-020](ADR-020-spring-boot-4.md), [ADR-022](ADR-022-api-contract-and-compatibility.md) và [ADR-044](ADR-044-review-cases-and-decisions.md).

## Quyết định

- **LG-P1:** Policy Checks là pack riêng, opt-in cho Etsy. Workbench gửi yêu cầu rõ ràng của người dùng. Chỉ trả bằng chứng xác minh được và giữ giới hạn tài nguyên, quota, retention do operator duyệt. Xem [Policy AI Workbench](../implementation/policy-ai-workbench.md).
- **LG-P2:** Reviewer có grant xác minh và thu hồi case trong tenant. Similar-case retrieval dùng tín hiệu có cấu trúc trong SQL và loại case thu hồi hoặc hết hiệu lực. Xem [Verified Case Intelligence](../implementation/verified-case-intelligence.md).
- **LG-P3:** Policy impact so sánh snapshot được duyệt và giữ trạng thái đề xuất. Quyết định tenant và re-audit do người dùng yêu cầu. Active ruleset vẫn theo PR review và version bất biến. Xem [Policy monitor](../policy-monitor.md).
- **LG-P4:** Profile POD phát hành bất biến, tách quy định sàn, trademark và sản xuất. Thiếu giới hạn nhà in đã xác minh dẫn đến `INCOMPLETE`. Giữ RestClient. Xem [POD Readiness](../implementation/pod-readiness.md) và [đánh giá Spring AI](../implementation/spring-ai-decision.md).
- **Java:** chọn Temurin 21 LTS cho cả Docker build và runtime, gồm stage phụ hf-convert. CI, Maven compiler release và SDK đã dùng Java 21. Đồng bộ major giúp dùng cùng nền kiểm thử cho container, đồng thời tránh đưa nâng cấp JDK 25 và kiểm chứng plugin vào release đầu tiên cho Oracle A1. Dependabot chỉ cập nhật Temurin trong cùng major. Nâng major cần PR riêng và kiểm chứng build đa kiến trúc.

## Hệ quả

Release workflow tiếp tục phát hành hai kiến trúc. Chốt baseline ADR-022 trong PR riêng sau khi GitHub Release đã có contract. Các grant và thời hạn chưa được phê duyệt trong ADR-044 tiếp tục theo trạng thái hiện có.
