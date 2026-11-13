package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * No sentence is written in a console template or script (ADR-028): a page says what its messages say, in the language of its
 * reader. This is a plain scan of the sources, not a rendering, so a sentence that only shows on one page (an error, a notice) is
 * found too. The scan runs from the module directory, as surefire does.
 */
class ConsoleTextInTemplatesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/console");
    private static final Path SCRIPTS = Path.of("src/main/resources/static/console");
    private static final Path CONSOLE_JAVA = Path.of("src/main/java/com/thanhdatpb/listingguard/api/console");

    /**
     * Text that may stand in a template because it is not a sentence of ours: the identifiers the API itself uses, and a header and
     * two codes the page shows as examples. Each is the same in every language on purpose.
     */
    private static final Set<String> IDENTIFIERS = Set.of("listing-rules", "trademark-risk", "variation-checks",
            "personalization-screening", "ERROR", "WARNING", "HIGH", "MEDIUM", "LOW", "UNKNOWN", "X-API-Key", "i_did",
            "made_to_order");

    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    private static final Pattern ENTITY = Pattern.compile("&#?\\w+;");
    private static final Pattern VISIBLE_ATTRIBUTE =
            Pattern.compile("\\s(aria-label|aria-description|title|alt|placeholder|label|summary)\\s*=\\s*\"([^\"]*)\"");

    private static List<Path> files(Path directory, String suffix) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text between the tags, with comments left out; a quote inside a tag may hold a {@code >}. */
    static List<String> textNodes(String html) {
        String source = html.replaceAll("(?s)<!--.*?-->", "");
        List<String> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean inTag = false;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (!inTag) {
                if (c == '<') {
                    nodes.add(text.toString());
                    text.setLength(0);
                    inTag = true;
                } else {
                    text.append(c);
                }
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                inTag = false;
            }
        }
        nodes.add(text.toString());
        return nodes;
    }

    private static Set<String> keysOfTheEnglishPages() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("messages.properties").getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Set<String> keys = new TreeSet<>();
        properties.keySet().forEach(key -> keys.add((String) key));
        return keys;
    }

    @Test
    void thereAreTemplatesToScan() {
        assertThat(files(TEMPLATES, ".html")).hasSizeGreaterThanOrEqualTo(10);
        assertThat(files(SCRIPTS, ".js")).hasSize(2);
    }

    @Test
    void noTemplateHoldsASentence() {
        for (Path template : files(TEMPLATES, ".html")) {
            for (String node : textNodes(read(template))) {
                String text = ENTITY.matcher(node).replaceAll(" ").strip();
                if (LETTER.matcher(text).find()) {
                    assertThat(IDENTIFIERS).as(template.getFileName() + " has written text: \"" + text
                            + "\" (give it a key in messages.properties and messages_vi.properties)").contains(text);
                }
            }
        }
    }

    @Test
    void noTemplateWritesTheTextOfAnAttributeThatAReaderSeesOrHears() {
        for (Path template : files(TEMPLATES, ".html")) {
            Matcher matcher = VISIBLE_ATTRIBUTE.matcher(read(template).replaceAll("(?s)<!--.*?-->", ""));
            while (matcher.find()) {
                assertThat(LETTER.matcher(matcher.group(2)).find()).as(template.getFileName() + " writes " + matcher.group(1)
                        + "=\"" + matcher.group(2) + "\" (use th:" + matcher.group(1) + " with a message)").isFalse();
            }
        }
    }

    @Test
    void everyPageTakesItsLanguageFromTheRequestAndNoTemplateFixesOne() {
        for (Path template : files(TEMPLATES, ".html")) {
            String html = read(template);
            Matcher root = Pattern.compile("<html[^>]*>").matcher(html);
            assertThat(root.find()).as(template.getFileName() + " has an html element").isTrue();
            assertThat(root.group()).as(template.getFileName().toString()).contains("th:lang=\"${#locale.language}\"")
                    .doesNotContainPattern("\\slang=");
        }
    }

    @Test
    void noScriptHoldsASentence() {
        Pattern string = Pattern.compile("'((?:[^'\\\\\\n]|\\\\.)*)'|\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
        for (Path script : files(SCRIPTS, ".js")) {
            String code = read(script).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            Matcher matcher = string.matcher(code);
            while (matcher.find()) {
                String literal = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                boolean sentence = literal.matches("(?s).*\\s.*") && Pattern.compile("\\p{L}{2}").matcher(literal).find();
                assertThat(sentence && !literal.equals("use strict")).as(script.getFileName() + " has written text: \"" + literal
                        + "\" (the page writes it in a data-i18n-* attribute)").isFalse();
            }
        }
    }

    /**
     * The messages a page can ask for exist, and every message is asked for by something: a template ({@code #{key}}) or the
     * console's Java (a string that is a key). A key of a family built in code ({@code "editor.trademark.group." + risk}) counts
     * as used through its prefix.
     */
    @Test
    void everyKeyInUseExistsAndEveryKeyThatExistsIsInUse() throws IOException {
        Set<String> keys = keysOfTheEnglishPages();
        Set<String> prefixes = new TreeSet<>();
        keys.forEach(key -> prefixes.add(key.substring(0, key.indexOf('.'))));

        Set<String> used = new TreeSet<>();
        Set<String> families = new TreeSet<>();
        Matcher inTemplates = Pattern.compile("#\\{([A-Za-z0-9._-]+)").matcher(templatesText());
        while (inTemplates.find()) {
            used.add(inTemplates.group(1));
        }
        Pattern literal = Pattern.compile("\"([a-z][a-z0-9]*(?:\\.[a-z0-9]+)+\\.?)\"");
        try (Stream<Path> paths = Files.walk(CONSOLE_JAVA)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(read(source));
                while (matcher.find()) {
                    String text = matcher.group(1);
                    if (prefixes.contains(text.substring(0, text.indexOf('.')))) {
                        (text.endsWith(".") ? families : used).add(text);
                    }
                }
            }
        }

        assertThat(used).as("keys that a page or a console class asks for and no file has").isSubsetOf(keys);
        Set<String> unused = new TreeSet<>(keys);
        unused.removeAll(used);
        unused.removeIf(key -> families.stream().anyMatch(key::startsWith));
        assertThat(unused).as("keys that nothing asks for (delete them, or use them)").isEmpty();
    }

    private static String templatesText() {
        StringBuilder all = new StringBuilder();
        files(TEMPLATES, ".html").forEach(template -> all.append(read(template)).append('\n'));
        return all.toString();
    }
}
