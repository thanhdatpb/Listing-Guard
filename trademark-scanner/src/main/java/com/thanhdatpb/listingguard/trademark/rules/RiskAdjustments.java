package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.AdjustmentReason;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.config.Templates;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lowers the level of a catalog finding when the ruleset says the match is less likely to matter (ADR-009, "Product
 * classes and generic words"). Two independent reasons, both read from the ruleset:
 *
 * <ul>
 *   <li>{@code outside_product_classes}: the request named a product type, the mark has Nice classes and none of them
 *       is one of the product type's. The level drops by {@code out_of_class_downgrade_levels}, never below LOW. A mark
 *       with no Nice class is not lowered.
 *   <li>{@code common_word}: the mark is one of {@code generic_words} and the match has no context. Context is an
 *       exact or phrase match in one of {@code context_fields} (the word is presented as the product's own brand).
 *       Anything else (another field, or a fuzzy match) is capped at {@code max_risk_without_context}.
 * </ul>
 *
 * <p>Each reason is computed from the level the rule assigned, never from the other reason's result, so the order of the
 * checks changes nothing, and each adjustment states the original level. The finding takes the lowest level.
 */
final class RiskAdjustments {

    /** The level a finding ends with and the reasons it is lower than its rule's level (empty when it is not). */
    record Outcome(RiskLevel risk, List<RiskAdjustment> adjustments) {
    }

    private RiskAdjustments() {
    }

    static Outcome of(ScanContext context, CatalogMark mark, MatchType type, String fieldName, RiskLevel base) {
        TrademarkRuleset ruleset = context.ruleset();
        List<RiskAdjustment> adjustments = new ArrayList<>();
        RiskLevel risk = base;

        Set<Integer> productClasses = context.productClasses();
        if (productClasses != null && !mark.niceClasses().isEmpty()
                && mark.niceClasses().stream().noneMatch(productClasses::contains)) {
            TrademarkRuleset.ProductClasses settings = ruleset.productClasses();
            RiskLevel to = RiskLevel.values()[Math.max(0, base.ordinal() - settings.downgradeLevels())];
            if (to != base) {
                adjustments.add(new RiskAdjustment(AdjustmentReason.OUTSIDE_PRODUCT_CLASSES, base, to,
                        Templates.fill(settings.note(), Map.of("mark", mark.display(),
                                "mark_classes", numbers(mark.niceClasses()),
                                "product_type", context.productType(),
                                "product_classes", numbers(productClasses.stream().sorted().toList()),
                                "from", base.name(), "to", to.name()))));
                risk = lowest(risk, to);
            }
        }

        TrademarkRuleset.GenericWords generic = ruleset.genericWords();
        if (generic.words().contains(mark.normalized()) && !hasContext(generic, type, fieldName)) {
            RiskLevel to = lowest(base, generic.maxRiskWithoutContext());
            if (to != base) {
                adjustments.add(new RiskAdjustment(AdjustmentReason.COMMON_WORD, base, to,
                        Templates.fill(generic.note(), Map.of("mark", mark.display(),
                                "context_fields", String.join(", ", generic.contextFields()),
                                "from", base.name(), "to", to.name()))));
                risk = lowest(risk, to);
            }
        }
        return new Outcome(risk, adjustments);
    }

    /** An exact or phrase match in a field the ruleset lists: the word stands as the product's own brand. */
    private static boolean hasContext(TrademarkRuleset.GenericWords generic, MatchType type, String fieldName) {
        if (type != MatchType.EXACT && type != MatchType.PHRASE) {
            return false;
        }
        int bracket = fieldName.indexOf('[');
        return generic.contextFields().contains(bracket < 0 ? fieldName : fieldName.substring(0, bracket));
    }

    private static RiskLevel lowest(RiskLevel a, RiskLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static String numbers(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
