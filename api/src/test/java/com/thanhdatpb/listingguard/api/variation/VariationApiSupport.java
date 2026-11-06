package com.thanhdatpb.listingguard.api.variation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.variation.VariationChecker;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.rules.RuleContext;
import com.thanhdatpb.listingguard.variation.rules.VariationRule;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the variation API tests share: synthetic request bodies built from the loaded ruleset (no product type, theme or
 * attribute name is repeated here), and a ruleset registry whose checker has a rule that fails.
 */
final class VariationApiSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private VariationApiSupport() {
    }

    /** Letters only, so a value is never mistaken for anything else, and never repeats. */
    static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.toString();
    }

    static VariationRuleset.ProductTypeThemes apparelType(VariationRuleset ruleset) {
        return ruleset.themeAllowed().productTypes().values().stream()
                .filter(entry -> ruleset.apparel().covers(entry.productType())).findFirst().orElseThrow();
    }

    static VariationRuleset.Theme theme(VariationRuleset ruleset) {
        return ruleset.theme(apparelType(ruleset).themes().get(0)).orElseThrow();
    }

    /** A child with every attribute an apparel child needs: the theme's two and the required size attributes. */
    static ObjectNode child(VariationRuleset ruleset, String sku, String parentSku, String title, String color, String size) {
        ObjectNode child = MAPPER.createObjectNode();
        child.put("sku", sku);
        if (parentSku != null) {
            child.put("parent_sku", parentSku);
        }
        child.put("title", title);
        ObjectNode attributes = child.putObject("attributes");
        VariationRuleset.Theme theme = theme(ruleset);
        attributes.put(theme.attributes().get(0), color);
        attributes.put(theme.attributes().get(1), size);
        attributes.put(ruleset.apparel().range().sizeAttribute(), size);
        VariationRuleset.Range range = ruleset.apparel().range();
        for (String required : ruleset.apparel().requiredAttributes()) {
            if (attributes.has(required)) {
                continue;
            }
            List<String> allowed = ruleset.apparel().valueLists().get(required);
            String value = allowed.get(0);
            if (required.equals(range.classAttribute())) {
                value = allowed.stream().filter(entry -> !range.classes().containsKey(entry.toLowerCase())).findFirst()
                        .orElseThrow();
            }
            attributes.put(required, value);
        }
        return child;
    }

    /**
     * An Amazon family of one parent and two children. The second child points at {@code secondChildParentSku}, or at the
     * parent when that is null, so a body with a different value there has exactly one finding.
     */
    static ObjectNode amazonFamily(VariationRuleset ruleset, String unique, String secondChildParentSku) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("marketplace", "AMAZON");
        String parentSku = "P-" + unique;
        ObjectNode parent = body.putObject("parent");
        parent.put("sku", parentSku);
        parent.put("product_type", apparelType(ruleset).productType());
        parent.put("variation_theme", apparelType(ruleset).themes().get(0));
        parent.put("title", "Retro Sunset Tee " + unique);
        ArrayNode children = body.putArray("children");
        children.add(child(ruleset, "C1-" + unique, parentSku, "Retro Sunset Tee " + unique + " - Red - S", "Red", "S"));
        children.add(child(ruleset, "C2-" + unique, secondChildParentSku == null ? parentSku : secondChildParentSku,
                "Retro Sunset Tee " + unique + " - Red - M", "Red", "M"));
        return body;
    }

    /** An Etsy listing with one variation type more than the ruleset allows, one of them a property that cannot vary. */
    static ObjectNode etsyFamily(VariationRuleset ruleset, String unique) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("marketplace", "ETSY");
        ArrayNode properties = body.putArray("variation_properties");
        for (int i = 1; i < ruleset.etsyLimits().maxVariationTypes(); i++) {
            properties.add("Type " + i);
        }
        properties.add(ruleset.etsyLimits().unsupportedProperties().get(0));
        properties.add("Type extra");
        ArrayNode children = body.putArray("children");
        for (int i = 0; i < 2; i++) {
            int index = i;
            ObjectNode child = children.addObject();
            child.put("sku", "E" + i + "-" + unique);
            ObjectNode attributes = child.putObject("attributes");
            properties.forEach(property -> attributes.put(property.asText(), property.asText() + " " + index));
        }
        return body;
    }

    /** A rule that cannot run, for the tests of an INCOMPLETE result. */
    static VariationRule failingRule() {
        return new VariationRule() {
            @Override
            public String id() {
                return "failing_rule_for_tests";
            }

            @Override
            public Set<String> marketplaces() {
                return Set.of("AMAZON", "ETSY");
            }

            @Override
            public List<VariationFinding> apply(RuleContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /**
     * The registry of the application with a checker that has one rule, and it fails: the result of every check is
     * INCOMPLETE. It registers the same ruleset rows as the real one.
     */
    static VariationRulesets failingRulesets(VariationProperties properties, ResourceLoader resources,
                                             RulesetVersionRepository versions, TransactionTemplate transactions) {
        VariationRulesets failing = new VariationRulesets(properties, resources, versions, transactions) {
            @Override
            public VariationChecker checker() {
                return new VariationChecker(ruleset(), List.of(failingRule()));
            }
        };
        failing.afterSingletonsInstantiated();
        return failing;
    }
}
