# Unreleased changes

Changes on `main` since v0.9.0 (see [v0.9.0.md](v0.9.0.md)) that have not shipped yet.

Every pull request that changes the API runs the compatibility check (`./mvnw -B -pl contract-tools verify`, ADR-022) and copies the
additive and non-breaking lines of `contract-tools/target/contract/compatibility-report.md` here, with a short section for the
change. When the next version is cut, move this file to `docs/releases/v<version>.md` and start a new one.

## What is new

### ReviewCase (LG-05B)

API thử nghiệm mặc định tắt. Quyền ghi được cấp riêng cho từng API key.
Xem [hướng dẫn ReviewCase](../review-cases.md). Thay đổi additive theo báo cáo tương thích:

- `operation-added` at `GET /api/v1/review-cases`
- `operation-added` at `GET /api/v1/review-cases/{id}`
- `operation-added` at `GET /api/v1/review-cases/{id}/events`
- `operation-added` at `POST /api/v1/review-cases`
- `operation-added` at `POST /api/v1/review-cases/{id}/decisions`
