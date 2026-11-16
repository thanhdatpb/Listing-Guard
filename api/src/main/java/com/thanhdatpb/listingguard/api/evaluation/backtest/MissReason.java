package com.thanhdatpb.listingguard.api.evaluation.backtest;

/**
 * Why a claimed listing was not caught (ADR-032). It is decided in this order, and the first that applies wins, so every
 * miss has exactly one reason. The texts are Vietnamese: they go into the report a manager reads.
 */
enum MissReason {

    /** no part of Listing Guard checks this kind of problem (copyright, counterfeit, image, other) */
    NOT_COVERED("Loại vấn đề này không có phần kiểm tra nào của Listing Guard xét (ví dụ bản quyền, hàng giả, ảnh)."),
    /** the claim is about a trademark and no trademark catalog was served */
    TRADEMARK_NOT_SERVED("Không có catalog nhãn hiệu nào đang được phục vụ nên phần nhãn hiệu không chạy."),
    /** the claim is about a trademark and the file names no mark */
    MARK_NOT_LABELED("Vụ claim về nhãn hiệu nhưng file không ghi mark liên quan, nên không xét được vì sao bị sót."),
    /** the labelled mark is not in the catalog that was served */
    MARK_NOT_IN_CATALOG("Mark liên quan không có trong catalog đang phục vụ."),
    /** the scanner found the labelled mark and a rule of the ruleset lowered its level below the catch line */
    LEVEL_LOWERED("Mark được tìm thấy nhưng mức rủi ro bị hạ (nhóm sản phẩm, từ thông dụng) xuống dưới ngưỡng bắt."),
    /** the scanner found the labelled mark at a level below the catch line */
    BELOW_THRESHOLD("Mark được tìm thấy nhưng ở mức rủi ro dưới ngưỡng bắt đã chọn."),
    /** the listing text holds a near spelling of the mark that the scanner did not match */
    SPELLING_VARIANT("Listing có một từ viết gần giống mark (khác chính tả) mà bộ quét không khớp."),
    /** the mark is in the catalog and its words are in the listing text, and the scanner reported nothing for it */
    NOT_MATCHED("Mark có trong catalog và có trong chữ của listing nhưng bộ quét không báo; cần người xem lại."),
    /** the mark does not appear in the listing text the file holds (the claim may rest on an image, a logo or other text) */
    MARK_NOT_IN_TEXT("Mark không xuất hiện trong phần chữ của listing có trong file (claim có thể dựa vào ảnh, logo hoặc chữ khác)."),
    /** the claim is about the listing itself and a rule found something below the catch line only */
    FINDING_BELOW_THRESHOLD("Có finding của rule nhưng ở mức nghiêm trọng dưới ngưỡng bắt đã chọn."),
    /** the claim is about the listing itself and no rule found anything */
    NO_RULE_FINDING("Không rule nào báo gì trên listing này.");

    private final String text;

    MissReason(String text) {
        this.text = text;
    }

    String text() {
        return text;
    }
}
