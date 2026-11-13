package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.editor.EditorCost;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;

/**
 * The sentences of the bulk check that more than one class needs (ADR-029), read from the console's messages (ADR-028) in the
 * language of the page. The messages of a finding are the rules' own and are never reworded here.
 */
final class BulkText {

    private BulkText() {
    }

    static String trademarkNotLoaded(ConsoleMessages messages) {
        return messages.get("bulk.reason.trademark.notloaded");
    }

    static String checkFailed(ConsoleMessages messages) {
        return messages.get("bulk.reason.failed");
    }

    /** Why a check did not run for lack of quota, with the day the quota comes back. */
    static String quota(ConsoleMessages messages, QuotaExceededException e) {
        return messages.get("bulk.reason.quota", e.used(), e.quota(), EditorCost.resetDate(e.resetAt()));
    }

    /** Why a check did not run because the plan does not include it. */
    static String plan(ConsoleMessages messages, PlanNotAllowedException e) {
        return messages.get("bulk.reason.plan", e.plan(), packName(messages, e.pack()));
    }

    static String packName(ConsoleMessages messages, Pack pack) {
        return switch (pack) {
            case LISTING_RULES -> messages.get("bulk.pack.listingrules");
            case TRADEMARK_RISK -> messages.get("bulk.pack.trademarkrisk");
            case VARIATION_CHECKS -> messages.get("bulk.pack.variationchecks");
            default -> messages.get("bulk.pack.other");
        };
    }

    /** The label of a severity or a trademark risk level ({@code error}, {@code warning}, {@code info}, {@code high}...). */
    static String level(ConsoleMessages messages, String level) {
        return switch (level) {
            case "error" -> messages.get("bulk.level.error");
            case "warning" -> messages.get("bulk.level.warning");
            case "info" -> messages.get("bulk.level.info");
            case "high" -> messages.get("bulk.level.high");
            case "medium" -> messages.get("bulk.level.medium");
            case "low" -> messages.get("bulk.level.low");
            default -> level;
        };
    }
}
