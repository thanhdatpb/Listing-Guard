package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationRulesets;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import com.thanhdatpb.listingguard.personalization.ScreeningResult;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckService;
import com.thanhdatpb.listingguard.variation.Completeness;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
class PodReadiness {
    enum Status {
        INCOMPLETE, REVIEW_REQUIRED, NO_FINDINGS;

        public String messageKey() {
            return "pod.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    record Production(Status status, List<String> missing, List<String> concerns, VariationCheckResult variation,
                      ScreeningResult sample) {
        public FeedbackTarget variationTarget(int index) {
            return FeedbackTarget.variation(variation.checkId(), index);
        }
    }

    record Result(EditorResult editor, Production production, Status marketplace, Status trademark) {
        public int incompleteDimensions() {
            return (int) java.util.stream.Stream.of(marketplace, trademark, production.status())
                    .filter(status -> status == Status.INCOMPLETE).count();
        }
    }

    private final EditorCheckService editor;
    private final VariationCheckService variation;
    private final PersonalizationRulesets personalization;
    private final PlanService plans;

    PodReadiness(EditorCheckService editor, VariationCheckService variation, PersonalizationRulesets personalization, PlanService plans) {
        this.editor = editor;
        this.variation = variation;
        this.personalization = personalization;
        this.plans = plans;
    }

    Result check(UUID clientId, EditorMarketplace marketplace, PodProfiles.Profile profile, EditorForm form,
                 ObjectNode listing, PodDraft draft, List<PodDraft.Row> rows, BigDecimal width, BigDecimal height) {
        EditorResult existing = editor.check(clientId, marketplace, listing);
        Production production = production(clientId, marketplace, profile, form.productType(), draft, rows, width, height);
        return new Result(existing, production, marketplaceStatus(existing), trademarkStatus(existing));
    }

    private static Status marketplaceStatus(EditorResult result) {
        if (!result.rulesRan() || result.requiredFieldsFinding()
                || result.readiness().kind() == EditorResult.ReadinessKind.INCOMPLETE) {
            return Status.INCOMPLETE;
        }
        return result.readiness().kind() == EditorResult.ReadinessKind.NOT_READY
                || result.count("error") + result.count("warning") > 0 ? Status.REVIEW_REQUIRED : Status.NO_FINDINGS;
    }

    private static Status trademarkStatus(EditorResult result) {
        if (result.trademark().kind() != EditorResult.TrademarkKind.RESULT || result.trademark().riskLevel() == null
                || "UNKNOWN".equals(result.trademark().riskLevel())) {
            return Status.INCOMPLETE;
        }
        return result.trademarkCount() > 0 || !"LOW".equals(result.trademark().riskLevel())
                ? Status.REVIEW_REQUIRED : Status.NO_FINDINGS;
    }

    private Production production(UUID clientId, EditorMarketplace marketplace, PodProfiles.Profile profile,
                                  String productType, PodDraft draft, List<PodDraft.Row> rows,
                                  BigDecimal width, BigDecimal height) {
        List<String> missing = new ArrayList<>();
        List<String> concerns = new ArrayList<>();
        PodProfiles.PrintArea printArea = profile.printArea();
        if (printArea.sourceStatus() != PodProfiles.Verification.VERIFIED) {
            missing.add("pod.missing.source");
        }
        if (width == null || height == null) {
            missing.add("pod.missing.artwork");
        } else if (printArea.sourceStatus() == PodProfiles.Verification.VERIFIED
                && (width.compareTo(printArea.maxWidthMm()) > 0 || height.compareTo(printArea.maxHeightMm()) > 0)) {
            concerns.add("pod.finding.artwork");
        }
        checkRows(profile, rows, missing);
        VariationCheckResult variationResult = rows.isEmpty() ? null
                : checkVariations(clientId, draft, marketplace, productType, rows, missing);
        ScreeningResult sample = checkPersonalization(clientId, profile, draft, missing);
        Status status;
        if (!missing.isEmpty()) {
            status = Status.INCOMPLETE;
        } else if (!concerns.isEmpty() || !variationResult.findings().isEmpty()) {
            status = Status.REVIEW_REQUIRED;
        } else {
            status = Status.NO_FINDINGS;
        }
        return new Production(status, List.copyOf(missing), List.copyOf(concerns), variationResult, sample);
    }

    private static void checkRows(PodProfiles.Profile profile, List<PodDraft.Row> rows, List<String> missing) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.sku().isBlank())) {
            missing.add("pod.missing.sku");
        }
        if (profile.variationRules().contains("size") && rows.stream().anyMatch(row -> row.size().isBlank())) {
            missing.add("pod.missing.size");
        }
        if (profile.variationRules().contains("color") && rows.stream().anyMatch(row -> row.color().isBlank())) {
            missing.add("pod.missing.color");
        }
    }

    private VariationCheckResult checkVariations(UUID clientId, PodDraft draft, EditorMarketplace marketplace,
                                                 String productType, List<PodDraft.Row> rows, List<String> missing) {
        try {
            VariationCheckResult result = variation.check(clientId, draft.variations(marketplace, productType, rows));
            if (result.completeness() == Completeness.INCOMPLETE) {
                missing.add("pod.missing.variation");
            }
            return result;
        } catch (PlanNotAllowedException e) {
            missing.add("pod.missing.variation.plan");
        } catch (QuotaExceededException e) {
            missing.add("pod.missing.variation.quota");
        }
        return null;
    }

    private ScreeningResult checkPersonalization(UUID clientId, PodProfiles.Profile profile, PodDraft draft,
                                                 List<String> missing) {
        if (profile.personalizationFields().isEmpty()) {
            return null;
        }
        missing.add("pod.missing.personalization.source");
        if (!draft.personalizationConfigured()) {
            missing.add("pod.missing.personalization.field");
            return null;
        }
        try {
            plans.requirePack(clientId, Pack.PERSONALIZATION_SCREENING);
            // Exercise the existing character rules on an internal probe. No buyer fields, storage or quota charge.
            var values = profile.personalizationFields().stream()
                    .map(field -> new PersonalizationInput.Value(field, "Mẫu 2026")).toList();
            return personalization.screener().screen(new PersonalizationInput(null, profile.id(), values), null);
        } catch (PlanNotAllowedException e) {
            missing.add("pod.missing.personalization.plan");
            return null;
        }
    }
}
