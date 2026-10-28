package com.thanhdatpb.listingguard.api.docs;

/**
 * Example payloads shown in the OpenAPI document. A test posts {@link #ETSY_REQUEST} to the real endpoint and
 * checks that it answers with the findings of {@link #ETSY_RESULT}, so that example cannot drift from the real
 * behavior. The problem examples copy the messages the code produces; ids and timestamps are made up.
 */
public final class ApiExamples {

    private ApiExamples() {
    }

    public static final String ETSY_REQUEST = """
            {
              "marketplace": "ETSY",
              "listing": {
                "title": "Handmade Ceramic Mug",
                "description": "Hand-thrown stoneware mug, 350 ml.",
                "tags": ["mug", "Mug", "gift_idea"],
                "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                "taxonomy_path": ["Home & Living", "Kitchen & Dining", "Mugs"],
                "materials": ["stoneware"],
                "images": ["https://example.com/mug-1.jpg"],
                "quantity": 0
              }
            }
            """;

    public static final String ETSY_RESULT = """
            {
              "checkId": "5331208a-583a-4ff3-a990-99a7440ae59f",
              "status": "COMPLETED",
              "marketplace": "ETSY",
              "rulesetVersion": "etsy-2026-11",
              "analysisVersion": "etsy-2026-11",
              "contentHash": "4a4c5b26f7cd836c45d914b623e5aa16b8c5e58690b685485a85bec74ca8bd08",
              "cached": false,
              "createdAt": "2026-10-28T09:15:30.123456Z",
              "summary": {"errors": 4, "warnings": 1, "infos": 0},
              "findings": [
                {
                  "ruleId": "required_fields",
                  "source": "RULE",
                  "severity": "ERROR",
                  "field": "who_made",
                  "value": null,
                  "message": "Thiếu trường bắt buộc 'who_made'.",
                  "suggestion": "Bổ sung giá trị cho trường 'who_made' trước khi publish."
                },
                {
                  "ruleId": "required_fields",
                  "source": "RULE",
                  "severity": "ERROR",
                  "field": "when_made",
                  "value": null,
                  "message": "Thiếu trường bắt buộc 'when_made'.",
                  "suggestion": "Bổ sung giá trị cho trường 'when_made' trước khi publish."
                },
                {
                  "ruleId": "price_quantity_positive",
                  "source": "RULE",
                  "severity": "ERROR",
                  "field": "quantity",
                  "value": "0",
                  "message": "Số lượng là 0, phải lớn hơn 0.",
                  "suggestion": "Nhập số lượng lớn hơn 0."
                },
                {
                  "ruleId": "tag_characters",
                  "source": "RULE",
                  "severity": "ERROR",
                  "field": "tags[2]",
                  "value": "gift_idea",
                  "message": "Tag 'gift_idea' chứa ký tự không hợp lệ: '_'.",
                  "suggestion": "Xoá hoặc thay các ký tự không hợp lệ trong tag này."
                },
                {
                  "ruleId": "duplicate_tags",
                  "source": "RULE",
                  "severity": "WARNING",
                  "field": "tags[1]",
                  "value": "Mug",
                  "message": "Tag 'Mug' trùng với tag ở vị trí 1.",
                  "suggestion": "Xoá tag trùng hoặc thay bằng một tag khác để tận dụng đủ chỗ."
                }
              ]
            }
            """;

    public static final String AMAZON_REQUEST = """
            {
              "marketplace": "AMAZON",
              "listing": {
                "title": "Hardside Carry-On Luggage Luggage Spinner Luggage! 20 Inch",
                "brand": "Roadster",
                "bullet_points": [
                  "Lightweight polycarbonate shell",
                  "Four 360-degree spinner wheels",
                  "TSA-approved lock",
                  "Expandable main compartment",
                  "Interior packing straps",
                  "Contact us at www.roadster.example"
                ],
                "description": "A durable hardside suitcase for short trips.",
                "search_terms": "carry on suitcase cabin bag spinner",
                "product_type": "LUGGAGE",
                "price": {"amount": 89.99, "currency_code": "USD"},
                "quantity": 12,
                "images": ["https://example.com/luggage-1.jpg"]
              }
            }
            """;

    public static final String AMAZON_RESULT = """
            {
              "checkId": "0d7f2c1d-9a11-4a58-9c6e-7f0c2f3e6a52",
              "status": "COMPLETED",
              "marketplace": "AMAZON",
              "rulesetVersion": "amazon-2026-11",
              "analysisVersion": "amazon-2026-11",
              "contentHash": "9c1e5f0a62b7d34e8a1f0c5d7b2e4a6893d1c0f5e7a2b4d6c8e0f1a3b5d7c9e1",
              "cached": false,
              "createdAt": "2026-10-28T09:20:11.482913Z",
              "summary": {"errors": 0, "warnings": 4, "infos": 0},
              "findings": [
                {
                  "ruleId": "title_repeated_words",
                  "source": "RULE",
                  "severity": "WARNING",
                  "field": "title",
                  "value": "Luggage",
                  "message": "Từ 'Luggage' xuất hiện 3 lần trong tiêu đề, tối đa 2 lần.",
                  "suggestion": "Bỏ bớt 1 lần xuất hiện của từ 'Luggage' trong tiêu đề."
                },
                {
                  "ruleId": "title_forbidden_chars",
                  "source": "RULE",
                  "severity": "WARNING",
                  "field": "title",
                  "value": "!",
                  "message": "Tiêu đề chứa ký tự không được phép: '!'.",
                  "suggestion": "Xoá ký tự '!' khỏi tiêu đề (chỉ được giữ khi nó là một phần của tên brand)."
                },
                {
                  "ruleId": "bullet_count",
                  "source": "RULE",
                  "severity": "WARNING",
                  "field": "bullet_points",
                  "value": "6",
                  "message": "Listing có 6 bullet point, tối đa 5.",
                  "suggestion": "Bỏ bớt 1 bullet point, giữ lại các ý quan trọng nhất."
                },
                {
                  "ruleId": "prohibited_content",
                  "source": "RULE",
                  "severity": "WARNING",
                  "field": "bullet_points[5]",
                  "value": "www.roadster.example",
                  "message": "Phát hiện url trong bullet_points[5]: 'www.roadster.example'.",
                  "suggestion": "Xoá url khỏi nội dung listing; không đưa liên kết ngoài hay thông tin liên hệ vào tiêu đề, bullet point hoặc mô tả."
                }
              ]
            }
            """;

    public static final String ETSY_RESULT_CACHED = """
            {
              "checkId": "5331208a-583a-4ff3-a990-99a7440ae59f",
              "status": "COMPLETED",
              "marketplace": "ETSY",
              "rulesetVersion": "etsy-2026-11",
              "analysisVersion": "etsy-2026-11",
              "contentHash": "4a4c5b26f7cd836c45d914b623e5aa16b8c5e58690b685485a85bec74ca8bd08",
              "cached": true,
              "createdAt": "2026-10-28T09:15:30.123456Z",
              "summary": {"errors": 0, "warnings": 0, "infos": 0},
              "findings": []
            }
            """;

    public static final String PROBLEM_INVALID_REQUEST = """
            {
              "type": "urn:listing-guard:problem:invalid-request",
              "title": "Bad Request",
              "status": 400,
              "detail": "The request is not valid; see errors.",
              "instance": "/api/v1/checks",
              "code": "invalid-request",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11",
              "errors": [{"field": "listing", "message": "must not be null"}]
            }
            """;

    public static final String PROBLEM_UNKNOWN_MARKETPLACE = """
            {
              "type": "urn:listing-guard:problem:unknown-marketplace",
              "title": "Bad Request",
              "status": 400,
              "detail": "Unknown marketplace: NOPE. Supported: AMAZON, ETSY.",
              "instance": "/api/v1/checks",
              "code": "unknown-marketplace",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_NOT_YET_SUPPORTED = """
            {
              "type": "urn:listing-guard:problem:marketplace-not-yet-supported",
              "title": "Unprocessable Entity",
              "status": 422,
              "detail": "Marketplace WALMART is planned but not supported yet. Supported: AMAZON, ETSY.",
              "instance": "/api/v1/checks",
              "code": "marketplace-not-yet-supported",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_UNAUTHORIZED = """
            {
              "type": "urn:listing-guard:problem:unauthorized",
              "title": "Unauthorized",
              "status": 401,
              "detail": "The X-API-Key header is missing.",
              "instance": "/api/v1/checks",
              "code": "unauthorized",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_NOT_FOUND = """
            {
              "type": "urn:listing-guard:problem:check-not-found",
              "title": "Not Found",
              "status": 404,
              "detail": "No check with id 5331208a-583a-4ff3-a990-99a7440ae59f",
              "instance": "/api/v1/checks/5331208a-583a-4ff3-a990-99a7440ae59f",
              "code": "check-not-found",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_RATE_LIMITED = """
            {
              "type": "urn:listing-guard:problem:rate-limited",
              "title": "Too Many Requests",
              "status": 429,
              "detail": "Too many requests for this API key. Retry after 2 second(s).",
              "instance": "/api/v1/checks",
              "code": "rate-limited",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_TOO_LARGE = """
            {
              "type": "urn:listing-guard:problem:payload-too-large",
              "title": "Payload Too Large",
              "status": 413,
              "detail": "The request body is larger than the limit of 262144 bytes.",
              "instance": "/api/v1/checks",
              "code": "payload-too-large",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;
}
