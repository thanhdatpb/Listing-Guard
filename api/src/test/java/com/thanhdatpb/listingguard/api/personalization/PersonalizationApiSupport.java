package com.thanhdatpb.listingguard.api.personalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.personalization.PersonalizationScreener;
import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.rules.RuleContext;
import com.thanhdatpb.listingguard.personalization.rules.TextRule;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the personalization API tests share: synthetic request bodies (invented names and numbers only), a text that never
 * repeats, and a ruleset registry whose screener has a rule that fails.
 */
final class PersonalizationApiSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private PersonalizationApiSupport() {
    }

    /** Letters only, 16 of them, so a text is never mistaken for anything else, never repeats and fits the default limit. */
    static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.substring(0, 16);
    }

    /** A request of the given marketplace and print profile (null for none) with one value per {@code label, text} pair. */
    static ObjectNode body(String marketplace, String printProfile, String... labelsAndTexts) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("marketplace", marketplace);
        if (printProfile != null) {
            body.put("print_profile", printProfile);
        }
        ArrayNode values = body.putArray("values");
        for (int i = 0; i < labelsAndTexts.length; i += 2) {
            ObjectNode value = values.addObject();
            if (labelsAndTexts[i] != null) {
                value.put("label", labelsAndTexts[i]);
            }
            value.put("text", labelsAndTexts[i + 1]);
        }
        return body;
    }

    /** An Etsy request with one unlabelled value. */
    static ObjectNode etsy(String text) {
        return body("ETSY", null, null, text);
    }

    static String emoji() {
        return new String(Character.toChars(0x1F600));
    }

    /** A rule that cannot run, for the tests of an INCOMPLETE result. */
    static TextRule failingRule() {
        return new TextRule() {
            @Override
            public String id() {
                return "failing_rule_for_tests";
            }

            @Override
            public List<TextFinding> apply(RuleContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /**
     * The registry of the application with a screener that has one rule, and it fails: the result of every screening is
     * INCOMPLETE. It registers the same ruleset rows as the real one.
     */
    static PersonalizationRulesets failingRulesets(PersonalizationProperties properties, ResourceLoader resources,
                                                   RulesetVersionRepository versions, TransactionTemplate transactions) {
        PersonalizationRulesets failing = new PersonalizationRulesets(properties, resources, versions, transactions) {
            @Override
            public PersonalizationScreener screener() {
                return new PersonalizationScreener(ruleset(), List.of(failingRule()));
            }
        };
        failing.afterSingletonsInstantiated();
        return failing;
    }
}
