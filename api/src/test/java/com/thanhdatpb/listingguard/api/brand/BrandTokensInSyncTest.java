package com.thanhdatpb.listingguard.api.brand;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The console serves a copy of the design pack (ADR-040): {@code static/brand/} must say what {@code docs/design/} says. When the
 * design changes, {@code scripts/sync-brand-assets.sh} copies it and this test then passes; a copy that was edited by hand, or a pack
 * that changed and was not copied, fails here. Runs from the module directory, as surefire does.
 */
class BrandTokensInSyncTest {

    private static final Pattern FONT_URL = Pattern.compile("url\\('([^']+)'\\)");

    /** The css without its first line (the comment that says where the file lives) and with every font path made the same. */
    private static String comparable(Path css) {
        String text = BrandTokens.read(css);
        return FONT_URL.matcher(text.substring(text.indexOf('\n') + 1)).replaceAll("url('FONT')");
    }

    @Test
    void tokensCssIsTheDesignPacksFileExceptTheFontPaths() {
        assertThat(comparable(BrandTokens.BRAND.resolve("tokens.css")))
                .isEqualTo(comparable(BrandTokens.DESIGN.resolve("tokens.css")));
    }

    @Test
    void everyFontTheTokensNameIsServedAndEqualsThePacksFile() throws IOException {
        String css = BrandTokens.read(BrandTokens.BRAND.resolve("tokens.css"));
        Matcher matcher = FONT_URL.matcher(css);
        int found = 0;
        while (matcher.find()) {
            found++;
            Path served = BrandTokens.BRAND.resolve(matcher.group(1));
            assertThat(served).as("the font " + matcher.group(1) + " that tokens.css names").exists();
            assertThat(Files.readAllBytes(served)).as(matcher.group(1))
                    .isEqualTo(Files.readAllBytes(BrandTokens.DESIGN.resolve(matcher.group(1))));
        }
        assertThat(found).as("@font-face rules").isEqualTo(12);
        assertThat(BrandTokens.BRAND.resolve("fonts/OFL-LICENSE.txt")).exists();
    }

    @Test
    void theLogosAndIconsAreCopiesOfThePacksFiles() throws IOException {
        List<String> pairs = new ArrayList<>();
        try (Stream<Path> logos = Files.list(BrandTokens.DESIGN.resolve("logo"))) {
            for (Path source : logos.sorted().toList()) {
                String name = source.getFileName().toString();
                Path served = name.endsWith(".svg") ? BrandTokens.BRAND.resolve("logo").resolve(name)
                        : name.equals("favicon.ico") ? BrandTokens.BRAND.resolve(name)
                        : BrandTokens.BRAND.resolve("icons").resolve(name);
                assertThat(served).as("the copy of logo/" + name).exists();
                if (name.endsWith(".svg")) {
                    assertThat(BrandTokens.read(served)).as(name).isEqualTo(BrandTokens.read(source));
                } else {
                    assertThat(Files.readAllBytes(served)).as(name).isEqualTo(Files.readAllBytes(source));
                }
                pairs.add(name);
            }
        }
        assertThat(pairs).contains("lockup-horizontal.svg", "lockup-horizontal-reverse.svg", "symbol.svg", "favicon.ico", "icon-180.png");
    }

    /** The two {@code theme-color} metas of the layout are the page ground of each scheme, so they cannot drift from the tokens. */
    @Test
    void theThemeColorsOfTheLayoutAreThePageGroundOfEachScheme() {
        BrandTokens tokens = BrandTokens.load();
        String layout = BrandTokens.read(Path.of("src/main/resources/templates/console/layout.html"));
        assertThat(layout).contains("content=\"" + hex(tokens.color(false, "surface-0")) + "\" media=\"(prefers-color-scheme: light)\"")
                .contains("content=\"" + hex(tokens.color(true, "surface-0")) + "\" media=\"(prefers-color-scheme: dark)\"");
    }

    private static String hex(int[] color) {
        return String.format("#%02x%02x%02x", color[0], color[1], color[2]);
    }
}
