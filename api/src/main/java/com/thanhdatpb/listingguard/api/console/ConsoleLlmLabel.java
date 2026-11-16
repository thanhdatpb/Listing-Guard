package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.LlmJudgement;
import java.util.List;

/**
 * The label the console puts on a trademark finding that a language model called a descriptive word (ADR-036 item 10). The
 * finding itself is the rules finding, kept; the label only says that a model thinks the word may be plain, and why. The label
 * is a message (English or Vietnamese, ADR-028); the reason is the model text and is shown as it is.
 */
public final class ConsoleLlmLabel {

    private ConsoleLlmLabel() {
    }

    /**
     * @return the model reason, an empty text when it gave none, or null when the finding carries no LLM label
     */
    public static String reason(List<TrademarkCheckResult.RiskAdjustmentView> adjustments) {
        if (adjustments == null) {
            return null;
        }
        for (TrademarkCheckResult.RiskAdjustmentView adjustment : adjustments) {
            if (AdjustmentReason.LLM_DESCRIPTIVE.code().equals(adjustment.reason())) {
                String note = adjustment.note() == null ? "" : adjustment.note().strip();
                return note.startsWith(LlmJudgement.LABEL) ? note.substring(LlmJudgement.LABEL.length()).strip() : note;
            }
        }
        return null;
    }

    /** The label and the reason as one sentence for a list that has no room for markup, or null when there is no label. */
    public static String sentence(ConsoleMessages messages, String reason) {
        if (reason == null) {
            return null;
        }
        return messages.get("console.llm.label") + (reason.isEmpty() ? "" : ": " + reason);
    }
}
