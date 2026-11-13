package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.editor.EditorCost;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;

/**
 * The Vietnamese sentences of the bulk check that more than one class needs (ADR-029). The page's own words are in
 * {@code templates/console/bulk.html}; the messages of a finding are the rules' own and are never reworded here.
 */
final class BulkText {

    static final String TRADEMARK_NOT_LOADED = "chưa nạp danh mục nhãn hiệu trên máy chủ này";
    static final String CHECK_FAILED = "kiểm tra gặp lỗi (xem nhật ký của máy chủ)";

    private BulkText() {
    }

    /** Why a check did not run for lack of quota, with the day the quota comes back. */
    static String quota(QuotaExceededException e) {
        return "hết quota tháng (đã dùng " + e.used() + " trên " + e.quota() + " đơn vị), quota đặt lại ngày "
                + EditorCost.resetDate(e.resetAt()) + " (UTC)";
    }

    /** Why a check did not run because the plan does not include it. */
    static String plan(PlanNotAllowedException e) {
        return "gói " + e.plan() + " không gồm " + packName(e.pack());
    }

    static String packName(Pack pack) {
        return switch (pack) {
            case LISTING_RULES -> "kiểm tra quy tắc listing";
            case TRADEMARK_RISK -> "kiểm tra nhãn hiệu";
            case VARIATION_CHECKS -> "kiểm tra biến thể";
            default -> "chức năng này";
        };
    }

    static String severityLabel(String level) {
        return switch (level) {
            case "error" -> "Lỗi";
            case "warning" -> "Cảnh báo";
            case "info" -> "Thông tin";
            case "high" -> "Rủi ro cao";
            case "medium" -> "Rủi ro trung bình";
            case "low" -> "Rủi ro thấp";
            default -> level;
        };
    }
}
