package com.thanhdatpb.listingguard.api.variation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.variation.VariationChecker;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.VariationResult;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * docs/input-schema.md, "Variation checks", is a public contract. This test reads its examples and its field tables from the
 * file and holds them to the real normalizer and rules, so the page cannot drift from the code unnoticed.
 */
class InputSchemaVariationDocTest {

    private static final Path DOC = Path.of("..", "docs", "input-schema.md");
    // like the application's mapper: an unknown field is ignored, not refused
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final VariationRuleset RULESET = VariationRulesetLoader.loadClasspath("rulesets/variation-checks-2026-11.yaml");

    private final VariationNormalizer normalizer = new VariationNormalizer();
    private final VariationChecker checker = new VariationChecker(RULESET);

    private static String section() throws IOException {
        assertThat(DOC).as("docs/input-schema.md is missing").exists();
        String text = Files.readString(DOC);
        int start = text.indexOf("## Variation checks");
        assertThat(start).as("the 'Variation checks' section").isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    /** The JSON in the fenced block whose info string is {@code json <name>}. */
    private static ObjectNode example(String name) throws IOException {
        Matcher block = Pattern.compile("```json " + name + "\\R(.*?)\\R```", Pattern.DOTALL).matcher(section());
        assertThat(block.find()).as("example '%s' in docs/input-schema.md", name).isTrue();
        return (ObjectNode) MAPPER.readTree(block.group(1));
    }

    private static List<String> documentedFields() throws IOException {
        List<String> fields = new ArrayList<>();
        Matcher row = Pattern.compile("^\\| `([a-z_]+)` \\|", Pattern.MULTILINE).matcher(section());
        while (row.find()) {
            fields.add(row.group(1));
        }
        return fields;
    }

    private VariationInput normalize(String marketplace, ObjectNode body) throws Exception {
        return normalizer.normalize(marketplace, MAPPER.treeToValue(body, VariationCheckRequest.class));
    }

    @Test
    void theAmazonExampleIsReadAsDocumentedAndIsAFamilyTheRulesHaveNothingToSayAbout() throws Exception {
        VariationInput input = normalize("AMAZON", example("variation-amazon"));

        assertThat(input.parent().sku()).isEqualTo("TEE-P");
        assertThat(input.parent().productType()).isEqualTo("SHIRT");
        assertThat(input.parent().variationTheme()).isEqualTo("Color-Size");
        assertThat(input.children()).hasSize(2);
        assertThat(input.children().get(1).parentSku()).isEqualTo("TEE-P");
        assertThat(input.children().get(1).attributes()).containsEntry("size", "M").containsEntry("size_class", "alpha");

        VariationResult result = checker.check(input);
        assertThat(result.findings()).isEmpty();
        assertThat(RULESET.themeAllowed().find(input.parent().productType())).isPresent();
    }

    @Test
    void theEtsyExampleIsReadAsDocumentedAndIsWithinTheLimits() throws Exception {
        VariationInput input = normalize("ETSY", example("variation-etsy"));

        assertThat(input.parent()).isNull();
        assertThat(input.variationProperties()).containsExactly("Primary color", "Size");
        assertThat(input.children()).hasSize(2);
        assertThat(input.children().get(0).attributes()).containsEntry("Primary color", "Red").containsEntry("Size", "S");
        assertThat(input.variationProperties().size()).isLessThanOrEqualTo(RULESET.etsyLimits().maxVariationTypes());

        assertThat(checker.check(input).findings()).isEmpty();
    }

    @Test
    void everyFieldTheNormalizerReadsIsDocumentedAndNothingElseIsRequired() throws Exception {
        assertThat(documentedFields()).contains("marketplace", "parent", "variation_properties", "children", "sku",
                "product_type", "variation_theme", "title", "parent_sku", "attributes");
    }

    @Test
    void unknownFieldsAreIgnoredAsTheRulesForEveryFieldPromise() throws Exception {
        ObjectNode body = example("variation-amazon");
        VariationInput plain = normalize("AMAZON", body);
        body.put("something_new", "x");
        ((ObjectNode) body.get("parent")).put("another", 1);
        ((ObjectNode) body.get("children").get(0)).put("extra", true);

        assertThat(normalize("AMAZON", body)).isEqualTo(plain);
    }
}
