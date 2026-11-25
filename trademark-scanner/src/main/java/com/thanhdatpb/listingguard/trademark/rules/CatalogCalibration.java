package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Tier;
import com.thanhdatpb.listingguard.trademark.config.Templates;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies the optional catalog calibration after precedence, so demoting a match cannot replace it with another span. */
public final class CatalogCalibration {
    public static final String SKIPPED_ID = "catalog_distinctiveness";
    private static final Set<String> DESCRIPTIVE_FIELDS = Set.of("title", "tags", "description");

    private final TrademarkRuleset.CatalogCalibration settings;
    private final Set<String> brandFields;
    private final Set<String> brandTokens;

    public CatalogCalibration(ScanContext context) {
        settings = context.ruleset().calibration();
        var fields = new java.util.HashSet<>(context.ruleset().genericWords().contextFields());
        fields.addAll(Set.of("brand", "shop_name"));
        brandFields = Set.copyOf(fields);
        brandTokens = context.fields().stream().filter(field -> brandFields.contains(root(field.name())))
                .flatMap(field -> field.tokens().stream()).map(token -> token.text()).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean missingTier(Candidate candidate) {
        return candidate.catalogMark() != null && (candidate.catalogMark().distinctiveness() == null
                || candidate.catalogMark().distinctiveness().tier() == null);
    }

    public Candidate apply(Candidate candidate) {
        var mark = candidate.catalogMark();
        if (mark == null || missingTier(candidate)) {
            return candidate;
        }
        var signals = mark.distinctiveness();
        RiskLevel base = candidate.riskAdjustments().isEmpty() ? candidate.risk()
                : candidate.riskAdjustments().getFirst().from();
        String field = root(candidate.field());
        if (mark.tokenCount() > 1 || candidate.tokenTo() > candidate.tokenFrom()
                || candidate.type() == MatchType.PHRASE || signals.tier() == Tier.STRONG
                || brandFields.contains(field) || brandTokens.contains(mark.normalized())) {
            return candidate.withAdjustment(base, List.of());
        }
        if (candidate.type() != MatchType.EXACT || !DESCRIPTIVE_FIELDS.contains(field)) {
            return candidate;
        }
        var cap = signals.tier() == Tier.WEAK ? settings.weak() : settings.ordinary();
        if (base.ordinal() <= cap.risk().ordinal()) {
            return candidate.withAdjustment(base, List.of());
        }
        var reason = signals.tier() == Tier.WEAK ? AdjustmentReason.CATALOG_WEAK_EXACT
                : AdjustmentReason.CATALOG_ORDINARY_EXACT;
        String note = Templates.fill(cap.note(), Map.of("mark", mark.display(), "signals", summary(signals),
                "from", base.name(), "to", cap.risk().name()));
        return candidate.withAdjustment(cap.risk(), List.of(new RiskAdjustment(reason, base, cap.risk(), note, signals)));
    }

    private static String summary(DistinctivenessSignals signals) {
        List<String> evidence = new ArrayList<>();
        if (Boolean.TRUE.equals(signals.dictionaryWord())) {
            evidence.add("từ có trong từ điển");
        }
        if (signals.sameMarkCount() != null) {
            evidence.add(signals.sameMarkCount() + " hồ sơ serial live cùng nhãn");
        }
        if (signals.sameMarkClassCount() != null) {
            evidence.add(signals.sameMarkClassCount() + " nhóm Nice trong bộ lọc");
        }
        if (signals.reason() == DistinctivenessSignals.Reason.DICTIONARY_UNAVAILABLE) {
            evidence.add("chưa có từ điển");
        }
        return evidence.isEmpty() ? "chỉ có tier trong catalog" : String.join(", ", evidence);
    }

    private static String root(String field) {
        int bracket = field.indexOf('[');
        return bracket < 0 ? field : field.substring(0, bracket);
    }
}
