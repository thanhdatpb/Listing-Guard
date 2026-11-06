package com.thanhdatpb.listingguard.api.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import com.thanhdatpb.listingguard.personalization.PersonalizationScreener;
import com.thanhdatpb.listingguard.personalization.ScreeningResult;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * docs/input-schema.md, "Personalization screening", is a public contract. This test reads its example and its field tables
 * from the file and holds them to the real normalizer and rules, so the page cannot drift from the code unnoticed.
 */
class InputSchemaPersonalizationDocTest {

    private static final Path DOC = Path.of("..", "docs", "input-schema.md");
    // like the application's mapper: an unknown field is ignored, not refused
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final PersonalizationRuleset RULESET =
            PersonalizationRulesetLoader.loadClasspath("rulesets/personalization-screening-2026-11.yaml");

    private final PersonalizationNormalizer normalizer = new PersonalizationNormalizer();

    private static String section() throws IOException {
        assertThat(DOC).as("docs/input-schema.md is missing").exists();
        String text = Files.readString(DOC);
        int start = text.indexOf("## Personalization screening");
        assertThat(start).as("the 'Personalization screening' section").isNotNegative();
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

    private PersonalizationInput normalize(ObjectNode body) throws Exception {
        return normalizer.normalize(MAPPER.treeToValue(body, PersonalizationScreeningRequest.class));
    }

    @Test
    void theExampleIsReadAsDocumentedAndIsAnOrderTheRulesHaveNothingToSayAbout() throws Exception {
        PersonalizationInput input = normalize(example("personalization-order"));

        assertThat(input.printProfile()).isEqualTo("latin_basic");
        assertThat(input.productType()).isEqualTo("jersey");
        assertThat(input.values()).extracting(PersonalizationInput.Value::label).containsExactly("name", "number");
        assertThat(input.values()).extracting(PersonalizationInput.Value::text).containsExactly("MAX", "10");
        assertThat(RULESET.profiles()).containsKey(input.printProfile());

        ScreeningResult result = new PersonalizationScreener(RULESET).screen(input, null);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void everyFieldTheNormalizerReadsIsDocumentedAndNothingElseIsRequired() throws Exception {
        assertThat(documentedFields()).contains("marketplace", "product_type", "print_profile", "values", "label", "text");
    }

    @Test
    void unknownFieldsAreIgnoredAsTheRulesForEveryFieldPromise() throws Exception {
        ObjectNode body = example("personalization-order");
        PersonalizationInput plain = normalize(body);
        body.put("something_new", "x");
        ((ObjectNode) body.get("values").get(0)).put("extra", true);

        assertThat(normalize(body)).isEqualTo(plain);
    }

    @Test
    void theDocumentedDifferenceHoldsTheTextIsNotTrimmed() throws Exception {
        ObjectNode body = example("personalization-order");
        ((ObjectNode) body.get("values").get(0)).put("text", "  MAX ");

        assertThat(normalize(body).values().get(0).text()).isEqualTo("  MAX ");
    }
}
