package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Every rule that can produce a finding has a sentence saying what it checks, in both languages (ADR-028). The rules are those of
 * the rulesets that {@code application.yml} registers, so a new rule, and a new ruleset version that adds one, is found here and
 * not by a seller reading a bare rule id. A rule that is switched off ({@code enabled: false}) cannot produce a finding and has
 * no sentence.
 */
class RuleExplanationsTest {

    /**
     * Rule ids that are allowed to have no explanation yet, each with the reason. Empty: add an id only for a reason a maintainer
     * accepts, and remove it as soon as its sentence is written (an entry that has one, or that names no rule, fails below).
     */
    private static final Map<String, String> EXCEPTIONS = Map.of();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static JsonNode yaml(String classpathLocation) throws IOException {
        try (InputStream in = new ClassPathResource(classpathLocation.replaceFirst("^classpath:", "")).getInputStream()) {
            return YAML.readTree(in);
        }
    }

    private static Map<String, String> load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> messages = new TreeMap<>();
        properties.forEach((key, value) -> messages.put((String) key, (String) value));
        return messages;
    }

    /** The rulesets in use, as the application registers them. */
    private static List<String> registeredRulesets() throws IOException {
        JsonNode guard = yaml("application.yml").path("listing-guard");
        List<String> locations = new ArrayList<>();
        guard.path("rulesets").path("locations").forEach(location -> locations.add(location.asText()));
        for (String pack : new String[] {"trademark", "variation", "personalization"}) {
            locations.add(guard.path(pack).path("ruleset").asText());
        }
        return locations;
    }

    private static Set<String> enabledRuleIds() throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String location : registeredRulesets()) {
            JsonNode rules = yaml(location).path("rules");
            assertThat(rules.isObject()).as(location + " has a rules block").isTrue();
            rules.fields().forEachRemaining(rule -> {
                if (rule.getValue().path("enabled").asBoolean(true)) {
                    ids.add(rule.getKey());
                }
            });
        }
        ids.addAll(RuleIds.ALL);
        return ids;
    }

    @Test
    void theScanFindsTheRulesOfEveryPack() throws IOException {
        assertThat(registeredRulesets()).hasSize(5).allSatisfy(location -> assertThat(location).startsWith("classpath:rulesets/"));
        assertThat(enabledRuleIds()).contains("title_length", "required_fields", "duplicate_sku", "whitespace_issue",
                "catalog_exact", "customization_text_input_count", "personalization_question_count", "etsy_variation_limits");
    }

    @Test
    void everyRuleHasAnExplanationInBothLanguagesExceptTheOnesListedAbove() throws IOException {
        Map<String, String> english = load("rules.properties");
        Map<String, String> vietnamese = load("rules_vi.properties");

        List<String> withoutVietnamese = new ArrayList<>();
        List<String> withoutEnglish = new ArrayList<>();
        for (String id : enabledRuleIds()) {
            if (EXCEPTIONS.containsKey(id)) {
                continue;
            }
            if (!vietnamese.containsKey("rule." + id)) {
                withoutVietnamese.add(id);
            }
            if (!english.containsKey("rule." + id)) {
                withoutEnglish.add(id);
            }
        }

        assertThat(withoutVietnamese).as("rule ids with no sentence in rules_vi.properties (add rule.<id>)").isEmpty();
        assertThat(withoutEnglish).as("rule ids with no sentence in rules.properties (add rule.<id>)").isEmpty();
    }

    @Test
    void anExceptionNamesARuleThatStillLacksItsSentence() throws IOException {
        Set<String> ids = enabledRuleIds();
        Map<String, String> vietnamese = load("rules_vi.properties");

        EXCEPTIONS.forEach((id, reason) -> {
            assertThat(reason).as("the reason for " + id).isNotBlank();
            assertThat(ids).as(id + " is no rule of a registered ruleset: delete the exception").contains(id);
            assertThat(vietnamese).as(id + " has its sentence now: delete the exception").doesNotContainKey("rule." + id);
        });
    }

    @Test
    void everySentenceBelongsToARuleThatCanProduceAFinding() throws IOException {
        Set<String> ids = enabledRuleIds();

        for (String file : new String[] {"rules.properties", "rules_vi.properties"}) {
            Set<String> stale = new TreeSet<>(load(file).keySet());
            stale.removeIf(key -> ids.contains(key.substring("rule.".length())));
            assertThat(stale).as(file + " explains rules that no registered ruleset has or that are switched off").isEmpty();
        }
    }

    @Test
    void aSentenceNamesNoNumberBecauseTheRulesetOwnsEveryNumber() throws IOException {
        for (String file : new String[] {"rules.properties", "rules_vi.properties"}) {
            load(file).forEach((key, text) -> assertThat(text).as(file + " " + key).doesNotContainPattern("\\d"));
        }
    }
}
