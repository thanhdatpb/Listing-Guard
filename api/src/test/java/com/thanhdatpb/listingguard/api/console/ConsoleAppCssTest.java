package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.brand.BrandTokens;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The rules of the console's base style sheet (ADR-040), held by a scan of its source: it writes no color of its own (the tokens are the
 * only source of color, spacing, radius and shadow), uses only variables that exist, has no gradient, no emoji and no one-sided
 * colored border on a card. The contrast of the colors it uses is {@link ConsoleContrastTest}. Runs from the module directory.
 */
class ConsoleAppCssTest {

    private static final String SOURCE = withoutComments(BrandTokens.read(BrandTokens.APP_CSS));
    private static final Pattern COLOR_CODE = Pattern.compile("#[0-9a-fA-F]{3,8}(?![\\w-])|\\b(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\\(");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern NAMED_COLOR = Pattern.compile(
            "\\b(?:white|black|red|blue|green|yellow|orange|purple|gray|grey|silver|navy|teal|pink|brown|gold)\\b");
    private static final Set<String> COLOR_PROPERTIES = Set.of("color", "background", "background-color", "border", "border-color",
            "border-top", "border-right", "border-bottom", "border-left", "outline", "outline-color", "fill", "stroke", "box-shadow",
            "caret-color", "accent-color", "text-decoration-color");

    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private record Rule(String selector, List<String[]> declarations) {
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        Matcher matcher = RULE.matcher(SOURCE);
        while (matcher.find()) {
            List<String[]> declarations = new ArrayList<>();
            for (String part : matcher.group(2).split(";")) {
                int colon = part.indexOf(':');
                if (colon > 0) {
                    declarations.add(new String[] {part.substring(0, colon).strip(), part.substring(colon + 1).strip()});
                }
            }
            rules.add(new Rule(matcher.group(1).strip(), declarations));
        }
        return rules;
    }

    @Test
    void thereAreRulesToScan() {
        assertThat(rules()).hasSizeGreaterThan(100);
    }

    @Test
    void theSheetWritesNoColorCodeOfItsOwn() {
        Matcher matcher = COLOR_CODE.matcher(SOURCE);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertThat(found).as("color codes in app.css (use a token, or derive a shade with color-mix)").isEmpty();
    }

    @Test
    void noColorPropertyNamesAColorByWord() {
        for (Rule rule : rules()) {
            for (String[] declaration : rule.declarations()) {
                if (COLOR_PROPERTIES.contains(declaration[0])) {
                    assertThat(NAMED_COLOR.matcher(declaration[1]).find())
                            .as(rule.selector() + " { " + declaration[0] + ": " + declaration[1] + " }").isFalse();
                }
            }
        }
    }

    @Test
    void everyVariableTheSheetReadsIsATokenOrAShadeItDerives() {
        String tokens = BrandTokens.read(BrandTokens.BRAND.resolve("tokens.css"));
        Set<String> known = new TreeSet<>();
        Matcher declared = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(tokens + "\n" + SOURCE);
        while (declared.find()) {
            known.add(declared.group(1));
        }
        Set<String> used = new TreeSet<>();
        Matcher reads = Pattern.compile("var\\((--[a-z0-9-]+)").matcher(SOURCE);
        while (reads.find()) {
            used.add(reads.group(1));
        }
        assertThat(used).as("variables read by app.css that nothing declares").isSubsetOf(known);
    }

    @Test
    void theSheetDeclaresNoVariableThatTheTokensAlreadyHave() {
        String tokens = BrandTokens.read(BrandTokens.BRAND.resolve("tokens.css"));
        Set<String> inTokens = new TreeSet<>();
        Matcher token = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(tokens);
        while (token.find()) {
            inTokens.add(token.group(1));
        }
        Set<String> own = new TreeSet<>();
        Matcher declared = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(SOURCE);
        while (declared.find()) {
            own.add(declared.group(1));
        }
        assertThat(own).as("tokens are the one source: app.css may only add shades, never redefine a token").doesNotContainAnyElementsOf(inTokens);
        assertThat(own).as("the shades app.css derives").contains("--hover", "--surface-2", "--line-soft", "--ink-soft", "--border-strong");
    }

    @Test
    void noGradientAndNoEmoji() {
        assertThat(SOURCE).doesNotContain("gradient(");
        assertThat(SOURCE.chars().filter(c -> c > 127).toArray()).as("non-ASCII characters in app.css").isEmpty();
    }

    @Test
    void noRuleGivesAnythingAColoredEdgeOnOneSideAndNoCardHasAOneSidedBorder() {
        for (Rule rule : rules()) {
            for (String[] declaration : rule.declarations()) {
                String property = declaration[0];
                assertThat(property).as(rule.selector()).isNotIn("border-left", "border-inline-start", "border-left-color", "border-left-width");
                if (rule.selector().contains("lg-card")) {
                    assertThat(property).as(rule.selector() + " is a card").doesNotStartWith("border-top").doesNotStartWith("border-right")
                            .doesNotStartWith("border-bottom").doesNotStartWith("border-left");
                }
            }
        }
    }

    @Test
    void theSharedTemplatesHoldNoInlineStyle() {
        for (String name : List.of("layout.html", "login.html", "verify.html")) {
            String html = BrandTokens.read(Path.of("src/main/resources/templates/console", name)).replaceAll("(?s)<!--.*?-->", "");
            assertThat(html).as(name).doesNotContain("style=\"").doesNotContain("<style");
        }
    }
}
