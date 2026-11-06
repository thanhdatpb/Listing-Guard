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
              "rulesetVersion": "etsy-2026-12",
              "analysisVersion": "etsy-2026-12",
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
              "rulesetVersion": "amazon-2026-12",
              "analysisVersion": "amazon-2026-12",
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
              "rulesetVersion": "etsy-2026-12",
              "analysisVersion": "etsy-2026-12",
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
              "detail": "Too many requests for this client. Retry after 2 second(s).",
              "instance": "/api/v1/checks",
              "code": "rate-limited",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    /** Synthetic names only: the marks of the test catalog and the invented place "Northvale". */
    public static final String TRADEMARK_REQUEST = """
            {
              "marketplace": "ETSY",
              "listing": {
                "title": "Zorvex Style Northvale Baseball Jersey",
                "description": "Soft cotton jersey.",
                "tags": ["jersey", "royal blue white pinstripe"],
                "price": {"amount": 2999, "divisor": 100, "currency_code": "USD"},
                "quantity": 5
              }
            }
            """;

    public static final String TRADEMARK_RESULT = """
            {
              "checkId": "0b9f4c1e-7d2a-4e58-9a63-2f1d8c5b7a40",
              "marketplace": "ETSY",
              "rulesetVersion": "trademark-risk-2026-11",
              "catalogVersion": "synthetic-2026-11-0123456789ab",
              "analysisVersion": "9f2c0e7a4b1d5c3e8a6f0b2d4c6e8a0b1c3d5e7f9a1b3c5d7e9f0a2b4c6d8e0f",
              "cached": false,
              "completeness": "COMPLETE",
              "riskLevel": "HIGH",
              "failedRuleIds": [],
              "disclaimer": "Kết quả chỉ là cảnh báo tham khảo, không phải kết luận pháp lý về việc một nội dung có vi phạm nhãn hiệu hay không.",
              "createdAt": "2026-10-30T09:15:30.123456Z",
              "expiresAt": "2026-11-29T09:15:30.123456Z",
              "findings": [
                {
                  "ruleId": "catalog_exact",
                  "risk": "HIGH",
                  "field": "title",
                  "start": 0,
                  "end": 6,
                  "snippet": "Zorvex",
                  "mark": "ZORVEX",
                  "matchType": "EXACT",
                  "score": 1.0,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": ["S-001", "S-002"],
                  "message": "Từ 'Zorvex' ở title trùng với nhãn hiệu 'ZORVEX' trong danh mục tham chiếu.",
                  "suggestion": "Xem lại việc dùng 'ZORVEX' ở title. Đây chỉ là cảnh báo tham khảo, không phải kết luận pháp lý."
                },
                {
                  "ruleId": "geo_sport_combination",
                  "risk": "MEDIUM",
                  "field": "title",
                  "start": 13,
                  "end": 31,
                  "snippet": "Northvale Baseball",
                  "mark": null,
                  "matchType": "CONTEXT",
                  "score": null,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": [],
                  "message": "title có địa danh 'northvale' đi cùng từ thể thao 'baseball' (cách nhau 1 từ, ngưỡng 3 từ). Tổ hợp này có thể gợi đến một đội thể thao.",
                  "suggestion": "Xem lại cách mô tả sản phẩm ở title. Đây chỉ là cảnh báo tham khảo, không phải kết luận pháp lý."
                },
                {
                  "ruleId": "pure_color_description",
                  "risk": "LOW",
                  "field": "tags[1]",
                  "start": 0,
                  "end": 26,
                  "snippet": "royal blue white pinstripe",
                  "mark": null,
                  "matchType": "CONTEXT",
                  "score": null,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": [],
                  "message": "Cụm 'royal blue white pinstripe' ở tags[1] chỉ gồm 4 từ chỉ màu và hoa văn. Không phát hiện tham chiếu nhãn hiệu nào trong cụm này.",
                  "suggestion": "Thông tin này chỉ có ý nghĩa tham khảo cho riêng cụm đó, không phải kết luận pháp lý về cả listing."
                }
              ]
            }
            """;

    public static final String TRADEMARK_RESULT_CACHED = """
            {
              "checkId": "0b9f4c1e-7d2a-4e58-9a63-2f1d8c5b7a40",
              "marketplace": "ETSY",
              "rulesetVersion": "trademark-risk-2026-11",
              "catalogVersion": "synthetic-2026-11-0123456789ab",
              "analysisVersion": "9f2c0e7a4b1d5c3e8a6f0b2d4c6e8a0b1c3d5e7f9a1b3c5d7e9f0a2b4c6d8e0f",
              "cached": true,
              "completeness": "COMPLETE",
              "riskLevel": "HIGH",
              "failedRuleIds": [],
              "disclaimer": "Kết quả chỉ là cảnh báo tham khảo, không phải kết luận pháp lý về việc một nội dung có vi phạm nhãn hiệu hay không.",
              "createdAt": "2026-10-30T09:15:30.123456Z",
              "expiresAt": "2026-11-29T09:15:30.123456Z",
              "findings": [
                {
                  "ruleId": "catalog_exact",
                  "risk": "HIGH",
                  "field": "title",
                  "start": 0,
                  "end": 6,
                  "snippet": "Zorvex",
                  "mark": "ZORVEX",
                  "matchType": "EXACT",
                  "score": 1.0,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": ["S-001", "S-002"],
                  "message": "Từ 'Zorvex' ở title trùng với nhãn hiệu 'ZORVEX' trong danh mục tham chiếu.",
                  "suggestion": "Xem lại việc dùng 'ZORVEX' ở title. Đây chỉ là cảnh báo tham khảo, không phải kết luận pháp lý."
                },
                {
                  "ruleId": "geo_sport_combination",
                  "risk": "MEDIUM",
                  "field": "title",
                  "start": 13,
                  "end": 31,
                  "snippet": "Northvale Baseball",
                  "mark": null,
                  "matchType": "CONTEXT",
                  "score": null,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": [],
                  "message": "title có địa danh 'northvale' đi cùng từ thể thao 'baseball' (cách nhau 1 từ, ngưỡng 3 từ). Tổ hợp này có thể gợi đến một đội thể thao.",
                  "suggestion": "Xem lại cách mô tả sản phẩm ở title. Đây chỉ là cảnh báo tham khảo, không phải kết luận pháp lý."
                },
                {
                  "ruleId": "pure_color_description",
                  "risk": "LOW",
                  "field": "tags[1]",
                  "start": 0,
                  "end": 26,
                  "snippet": "royal blue white pinstripe",
                  "mark": null,
                  "matchType": "CONTEXT",
                  "score": null,
                  "insideColorPhrase": false,
                  "otherRuleIds": [],
                  "sourceIds": [],
                  "message": "Cụm 'royal blue white pinstripe' ở tags[1] chỉ gồm 4 từ chỉ màu và hoa văn. Không phát hiện tham chiếu nhãn hiệu nào trong cụm này.",
                  "suggestion": "Thông tin này chỉ có ý nghĩa tham khảo cho riêng cụm đó, không phải kết luận pháp lý về cả listing."
                }
              ]
            }
            """;

    public static final String PROBLEM_TRADEMARK_NOT_CONFIGURED = """
            {
              "type": "urn:listing-guard:problem:trademark-not-configured",
              "title": "Service Unavailable",
              "status": 503,
              "detail": "Trademark checks are unavailable: no catalog version is configured (listing-guard.trademark.catalog-version)",
              "instance": "/api/v1/trademark-checks",
              "code": "trademark-not-configured",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String PROBLEM_TRADEMARK_NOT_FOUND = """
            {
              "type": "urn:listing-guard:problem:trademark-check-not-found",
              "title": "Not Found",
              "status": 404,
              "detail": "No trademark check with id 0b9f4c1e-7d2a-4e58-9a63-2f1d8c5b7a40",
              "instance": "/api/v1/trademark-checks/0b9f4c1e-7d2a-4e58-9a63-2f1d8c5b7a40",
              "code": "trademark-check-not-found",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    // ---- variation checks (ADR-015); synthetic SKUs and titles only

    public static final String VARIATION_REQUEST_AMAZON = """
            {
              "marketplace": "AMAZON",
              "parent": {"sku": "TEE-P", "product_type": "SHIRT", "variation_theme": "Color-Size", "title": "Retro Sunset Tee"},
              "children": [
                {"sku": "TEE-RED-S", "parent_sku": "TEE-P", "title": "Retro Sunset Tee - Red - S",
                 "attributes": {"color": "Red", "size": "S", "size_system": "as1", "size_class": "alpha", "body_type": "regular"}},
                {"sku": "TEE-RED-M", "parent_sku": "TEE-X", "title": "Retro Sunset Tee - Red - M",
                 "attributes": {"color": "Red", "size": "M", "size_system": "as1", "size_class": "alpha", "body_type": "regular"}}
              ]
            }
            """;

    public static final String VARIATION_REQUEST_ETSY = """
            {
              "marketplace": "ETSY",
              "variation_properties": ["Primary color", "Size", "Holiday"],
              "children": [
                {"sku": "TEE-RED-S", "attributes": {"Primary color": "Red", "Size": "S", "Holiday": "Christmas"}},
                {"sku": "TEE-RED-M", "attributes": {"Primary color": "Red", "Size": "M", "Holiday": "Christmas"}}
              ]
            }
            """;

    public static final String VARIATION_RESULT = """
            {
              "checkId": "3c7a91f2-5d84-4b16-8e0a-6f2b9d4c1e73",
              "marketplace": "AMAZON",
              "rulesetVersion": "variation-checks-2026-11",
              "analysisVersion": "d1f0b7c4e9a3582b6c0e4f7a1d9b3c5e8f2a4b6d0c1e3f5a7b9d1c3e5f708192",
              "cached": false,
              "completeness": "COMPLETE",
              "failedRuleIds": [],
              "disclaimer": "Kết quả chỉ là cảnh báo tham khảo dựa trên nguồn thứ cấp và quy tắc nội bộ, không phải xác nhận hay kết luận của Amazon hoặc Etsy.",
              "createdAt": "2026-11-06T09:15:30.123456Z",
              "expiresAt": "2026-12-06T09:15:30.123456Z",
              "summary": {"errors": 0, "warnings": 1, "infos": 0},
              "findings": [
                {"ruleId": "parent_child_structure", "kind": "child_parent_sku_mismatch", "severity": "WARNING",
                 "sourceStatus": "SECONDARY", "field": "children[1].parent_sku", "value": "TEE-X",
                 "relatedFields": ["parent.sku"],
                 "message": "Child thứ 2 có parent_sku 'TEE-X', khác SKU của parent 'TEE-P'.",
                 "suggestion": "Sửa parent_sku của child này cho trùng SKU của parent 'TEE-P', hoặc đưa child sang parent đúng."}
              ]
            }
            """;

    public static final String VARIATION_RESULT_CACHED = """
            {
              "checkId": "3c7a91f2-5d84-4b16-8e0a-6f2b9d4c1e73",
              "marketplace": "AMAZON",
              "rulesetVersion": "variation-checks-2026-11",
              "analysisVersion": "d1f0b7c4e9a3582b6c0e4f7a1d9b3c5e8f2a4b6d0c1e3f5a7b9d1c3e5f708192",
              "cached": true,
              "completeness": "COMPLETE",
              "failedRuleIds": [],
              "disclaimer": "Kết quả chỉ là cảnh báo tham khảo dựa trên nguồn thứ cấp và quy tắc nội bộ, không phải xác nhận hay kết luận của Amazon hoặc Etsy.",
              "createdAt": "2026-11-06T09:15:30.123456Z",
              "expiresAt": "2026-12-06T09:15:30.123456Z",
              "summary": {"errors": 0, "warnings": 1, "infos": 0},
              "findings": [
                {"ruleId": "parent_child_structure", "kind": "child_parent_sku_mismatch", "severity": "WARNING",
                 "sourceStatus": "SECONDARY", "field": "children[1].parent_sku", "value": "TEE-X",
                 "relatedFields": ["parent.sku"],
                 "message": "Child thứ 2 có parent_sku 'TEE-X', khác SKU của parent 'TEE-P'.",
                 "suggestion": "Sửa parent_sku của child này cho trùng SKU của parent 'TEE-P', hoặc đưa child sang parent đúng."}
              ]
            }
            """;

    public static final String PROBLEM_VARIATION_NOT_FOUND = """
            {
              "type": "urn:listing-guard:problem:variation-check-not-found",
              "title": "Not Found",
              "status": 404,
              "detail": "No variation check with id 3c7a91f2-5d84-4b16-8e0a-6f2b9d4c1e73 was found for this client.",
              "instance": "/api/v1/variation-checks/3c7a91f2-5d84-4b16-8e0a-6f2b9d4c1e73",
              "code": "variation-check-not-found",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11"
            }
            """;

    public static final String USAGE_RESULT = """
            {
              "enforced": true,
              "plan": "SELLER",
              "monthlyQuota": 1000,
              "used": 42,
              "remaining": 958,
              "periodStart": "2026-11-01",
              "resetAt": "2026-12-01T00:00:00Z",
              "packs": ["batch", "listing-rules", "sheet-connector", "trademark-risk", "variation-checks"]
            }
            """;

    public static final String PROBLEM_PLAN_NOT_ALLOWED = """
            {
              "type": "urn:listing-guard:problem:plan-not-allowed",
              "title": "Forbidden",
              "status": 403,
              "detail": "The FREE plan does not include trademark-risk. Ask the operator for a plan that does.",
              "instance": "/api/v1/trademark-checks",
              "code": "plan-not-allowed",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11",
              "plan": "FREE",
              "pack": "trademark-risk"
            }
            """;

    public static final String PROBLEM_QUOTA_EXCEEDED = """
            {
              "type": "urn:listing-guard:problem:quota-exceeded",
              "title": "Too Many Requests",
              "status": 429,
              "detail": "The monthly quota of the FREE plan is used up (20 of 20). It resets at 2026-12-01T00:00:00Z. Results already stored are still answered from the cache.",
              "instance": "/api/v1/checks",
              "code": "quota-exceeded",
              "requestId": "7f0c2f3e-6a52-4a58-9c6e-0d7f2c1d9a11",
              "plan": "FREE",
              "quota": 20,
              "used": 20,
              "resetAt": "2026-12-01T00:00:00Z"
            }
            """;

    /** The limits of {@code etsy-2026-12}; a test holds it equal to what the endpoint answers. */
    public static final String RULESET_LIMITS_ETSY = """
            {
              "marketplace": "ETSY",
              "rulesetVersion": "etsy-2026-12",
              "productType": null,
              "limits": [
                {"field": "title", "ruleId": "title_length", "kind": "MAX_LENGTH", "value": 140, "unit": "CODE_POINTS",
                 "severity": "ERROR", "sourceStatus": "VERIFIED", "enabled": true},
                {"field": "title", "ruleId": "title_repeated_special_chars", "kind": "MAX_OCCURRENCES", "value": 1,
                 "unit": "OCCURRENCES", "severity": "WARNING", "sourceStatus": "SECONDARY", "enabled": true},
                {"field": "title", "ruleId": "title_word_count", "kind": "MAX_COUNT", "value": 15, "unit": "WORDS",
                 "severity": "INFO", "sourceStatus": "SECONDARY", "enabled": true},
                {"field": "title", "ruleId": "title_repeated_words", "kind": "MAX_OCCURRENCES", "value": 1,
                 "unit": "OCCURRENCES", "severity": "WARNING", "sourceStatus": "SECONDARY", "enabled": true},
                {"field": "tags", "ruleId": "tag_count", "kind": "MAX_COUNT", "value": 13, "unit": "ITEMS",
                 "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true},
                {"field": "tags", "ruleId": "tag_length", "kind": "MAX_LENGTH", "value": 20, "unit": "CODE_POINTS",
                 "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true},
                {"field": "personalization.questions", "ruleId": "personalization_question_count", "kind": "MAX_COUNT",
                 "value": 5, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED", "enabled": true},
                {"field": "personalization.questions[type=UPLOAD]", "ruleId": "personalization_question_count",
                 "kind": "MAX_COUNT", "value": 1, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
                 "enabled": true},
                {"field": "personalization.questions[].question_text", "ruleId": "personalization_question_text",
                 "kind": "MAX_LENGTH", "value": 45, "unit": "CODE_POINTS", "severity": "ERROR", "sourceStatus": "VERIFIED",
                 "enabled": true},
                {"field": "personalization.questions[type=DROPDOWN].options", "ruleId": "personalization_dropdown_options",
                 "kind": "MAX_COUNT", "value": 30, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
                 "enabled": true},
                {"field": "personalization.questions[type=DROPDOWN].options", "ruleId": "personalization_dropdown_options",
                 "kind": "MAX_LENGTH", "value": 20, "unit": "CODE_POINTS", "severity": "ERROR", "sourceStatus": "VERIFIED",
                 "enabled": true},
                {"field": "personalization.questions[type=UPLOAD].max_files", "ruleId": "personalization_upload_files",
                 "kind": "MAX_COUNT", "value": 10, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
                 "enabled": true},
                {"field": "personalization.questions[].instructions", "ruleId": "personalization_instructions_length",
                 "kind": "MAX_LENGTH", "value": 120, "unit": "CODE_POINTS", "severity": "WARNING",
                 "sourceStatus": "SECONDARY", "enabled": true}
              ]
            }
            """;

    public static final String PROBLEM_RULESET_NOT_FOUND = """
            {
              "type": "urn:listing-guard:problem:ruleset-not-found",
              "title": "Not Found",
              "status": 404,
              "detail": "No ruleset is active for marketplace WALMART. Supported: AMAZON, ETSY.",
              "instance": "/api/v1/rulesets/WALMART/limits",
              "code": "ruleset-not-found",
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
