package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The two language files of the console say the same things (ADR-028): every key is in both, every message is usable the way it
 * is used, and a message holds no markup. This reads the property files themselves, so it needs no Spring context.
 */
class ConsoleMessageBundlesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

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

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    void everyKeyOfTheEnglishPagesIsInTheVietnameseOnesAndTheOtherWayRound() throws IOException {
        Map<String, String> english = load("messages.properties");
        Map<String, String> vietnamese = load("messages_vi.properties");

        assertThat(english).isNotEmpty();
        assertThat(vietnamese.keySet()).as("keys of messages_vi.properties that messages.properties lacks")
                .containsExactlyInAnyOrderElementsOf(english.keySet());
    }

    @Test
    void everyKeyOfTheEnglishRuleExplanationsIsInTheVietnameseOnesAndTheOtherWayRound() throws IOException {
        Map<String, String> english = load("rules.properties");
        Map<String, String> vietnamese = load("rules_vi.properties");

        assertThat(english).isNotEmpty();
        assertThat(vietnamese.keySet()).as("keys of rules_vi.properties that rules.properties lacks")
                .containsExactlyInAnyOrderElementsOf(english.keySet());
    }

    @Test
    void theRuleFilesHoldRuleExplanationsOnlyAndThePageFilesHoldNone() throws IOException {
        for (String file : new String[] {"rules.properties", "rules_vi.properties"}) {
            assertThat(load(file).keySet()).as(file).allSatisfy(key -> assertThat(key).startsWith("rule."));
        }
        for (String file : new String[] {"messages.properties", "messages_vi.properties"}) {
            assertThat(load(file).keySet()).as(file).noneMatch(key -> key.startsWith("rule."));
        }
    }

    @Test
    void noMessageIsEmptyAndTheVietnameseFilesAreReadAsUtf8() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_vi.properties", "rules.properties", "rules_vi.properties"}) {
            assertThat(load(file)).as(file).allSatisfy((key, value) -> assertThat(value.strip()).as(key).isNotEmpty());
        }
        assertThat(load("messages_vi.properties").get("nav.signout")).isEqualTo("Đăng xuất");
    }

    @Test
    void aMessageHoldsNoMarkupBecauseEveryValueIsPrintedWithThText() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_vi.properties", "rules.properties", "rules_vi.properties"}) {
            assertThat(load(file)).as(file).allSatisfy((key, value) -> assertThat(value).as(key).doesNotContain("<").doesNotContain(">"));
        }
    }

    @Test
    void bothLanguagesUseTheSamePlaceholdersForAKey() throws IOException {
        Map<String, String> english = load("messages.properties");
        Map<String, String> vietnamese = load("messages_vi.properties");

        english.forEach((key, text) -> assertThat(placeholders(vietnamese.get(key))).as(key).isEqualTo(placeholders(text)));
    }

    /**
     * A message that is given arguments goes through MessageFormat: it must parse, and an apostrophe in it must be doubled or it
     * swallows the text after it. A message without arguments (and every "js." pattern, which JavaScript fills in) is printed as
     * written, so a doubled apostrophe there would show twice.
     */
    @Test
    void apostrophesFollowTheRuleOfTheWayTheMessageIsUsed() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_vi.properties", "rules.properties", "rules_vi.properties"}) {
            load(file).forEach((key, text) -> {
                boolean raw = key.startsWith("js.") || key.startsWith("rule.") || placeholders(text).isEmpty();
                if (raw) {
                    assertThat(text).as(file + " " + key + " is printed as written").doesNotContain("''");
                } else {
                    assertThat(text.replace("''", "")).as(file + " " + key + " goes through MessageFormat").doesNotContain("'");
                    new MessageFormat(text).format(new Object[] {"a", "b", "c", "d"});
                }
            });
        }
    }

    @Test
    void theSentencesOfJavaScriptAreKeysThatTakeNoArgumentsAndNothingElseIsAPattern() throws IOException {
        Map<String, String> english = load("messages.properties");

        english.forEach((key, text) -> {
            if (key.startsWith("js.")) {
                assertThat(key).as("a js. key is used by a template without arguments").isNotEmpty();
            }
        });
        assertThat(english.keySet().stream().filter(key -> key.startsWith("js.")).count()).isPositive();
    }
}
