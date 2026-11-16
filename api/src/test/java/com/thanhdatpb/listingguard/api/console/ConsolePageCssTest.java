package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.brand.BrandTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The style sheets of the console pages (ADR-040, ADR-041): none writes a color code of its own, none keeps the old color names that were
 * aliases of tokens, every variable one reads is a token or a shade {@code app.css} derives, and the sheets that were retired are gone.
 * {@link ConsoleAppCssTest} holds {@code app.css} to the stricter rules; this holds the others, the pages' own sheets. Runs from the
 * module directory.
 */
class ConsolePageCssTest {

    private static final Path CONSOLE = Path.of("src/main/resources/static/console");
    private static final Pattern COLOR_CODE = Pattern.compile("#[0-9a-fA-F]{3,8}(?![\\w-])|\\b(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch)\\(");
    /** The names the page sheets were written with before they pointed at tokens (they were aliases in editor.css). */
    private static final Set<String> OLD_NAMES = Set.of("--bg", "--fg", "--muted", "--line", "--card", "--accent-fg", "--error", "--info",
            "--ok", "--mark");

    private static List<Path> sheets() throws IOException {
        try (Stream<Path> files = Files.list(CONSOLE)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".css")).sorted().toList();
        }
    }

    private static String source(Path sheet) {
        return BrandTokens.read(sheet).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void thePageSheetsAreTheOnesThatAreStillNeeded() throws IOException {
        assertThat(sheets()).extracting(path -> path.getFileName().toString())
                .containsExactly("app.css", "bulk.css", "console.css", "editor.css", "watchlist.css");
    }

    @Test
    void noSheetWritesAColorCode() throws IOException {
        for (Path sheet : sheets()) {
            Matcher matcher = COLOR_CODE.matcher(source(sheet));
            List<String> found = new ArrayList<>();
            while (matcher.find()) {
                found.add(matcher.group());
            }
            assertThat(found).as(sheet + ": color codes (use a token)").isEmpty();
        }
    }

    @Test
    void noSheetUsesTheOldColorNamesOrAVariableNobodyDeclares() throws IOException {
        String tokens = BrandTokens.read(BrandTokens.BRAND.resolve("tokens.css")) + "\n" + BrandTokens.read(BrandTokens.APP_CSS);
        Set<String> known = new TreeSet<>();
        Matcher declared = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(tokens);
        while (declared.find()) {
            known.add(declared.group(1));
        }
        for (Path sheet : sheets()) {
            String css = source(sheet);
            Set<String> used = new TreeSet<>();
            Matcher reads = Pattern.compile("var\\((--[a-z0-9-]+)").matcher(css);
            while (reads.find()) {
                used.add(reads.group(1));
            }
            assertThat(used).as(sheet + ": old color names").doesNotContainAnyElementsOf(OLD_NAMES);
            assertThat(used).as(sheet + ": variables that nothing declares").isSubsetOf(known);
            if (!sheet.getFileName().toString().equals("app.css")) {
                assertThat(Pattern.compile("(?m)^\\s*--[a-z0-9-]+\\s*:").matcher(css).find())
                        .as(sheet + " declares a variable of its own").isFalse();
            }
        }
    }

    @Test
    void thePagesThatMovedLoadNoRetiredSheet() {
        Path templates = Path.of("src/main/resources/templates/console");
        for (String page : List.of("dashboard", "editor", "bulk", "bulk-fragments", "edit-mode", "rewrite", "rewrite-page", "feedback",
                "feedback-saved", "findings")) {
            String html = BrandTokens.read(templates.resolve(page + ".html"));
            assertThat(html).as(page).doesNotContain("/console/rewrite.css").doesNotContain("/console/console.css");
        }
        assertThat(BrandTokens.read(templates.resolve("bulk.html"))).doesNotContain("/console/editor.css");
        assertThat(BrandTokens.read(templates.resolve("reaudit.html"))).doesNotContain("/console/bulk.css");
    }
}
