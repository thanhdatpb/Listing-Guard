package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What the workspace adds to the shared layout is inert while the flag is off (ADR-059): every addition is inside a block that renders only
 * for {@code workspaceEnabled == true}, written inline so that no whitespace is left behind. Without those blocks the layout holds no mark
 * of the listing workspace. (The full byte-for-byte statement was checked against the layout of the base commit when the change was made; this holds
 * the rule for the next change.)
 */
class WorkspaceLayoutTest {

    private static final String OPEN = "<th:block th:if=\"${workspaceEnabled == true}\">";

    static String withoutWorkspaceBlocks(String text) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int start = text.indexOf(OPEN, from);
            if (start < 0) {
                out.append(text.substring(from));
                return out.toString();
            }
            out.append(text, from, start);
            int depth = 1;
            int at = start + OPEN.length();
            while (depth > 0) {
                int open = text.indexOf("<th:block", at);
                int close = text.indexOf("</th:block>", at);
                if (open >= 0 && open < close) {
                    depth++;
                    at = open + "<th:block".length();
                } else {
                    depth--;
                    at = close + "</th:block>".length();
                }
            }
            from = at;
        }
    }

    @Test
    void theLayoutHoldsNoWorkspaceMarkupOutsideItsFlaggedBlocks() throws IOException {
        String layout = Files.readString(Path.of("src/main/resources/templates/console/layout.html"), StandardCharsets.UTF_8);
        // the workspace console mode (ADR-064) has its own flag, {@code workspaceMode}, which is false whenever the workspace is off: its menu is one element
        // glued to the end of the classic menu, and its one icon is one block glued to the sprite
        String modeNav = "<nav class=\"app-nav app-nav--workspace\" th:if=\"${workspaceMode == true}\"";
        int navStart = layout.indexOf(modeNav);
        assertThat(navStart).as("the workspace console menu").isGreaterThan(0);
        assertThat(layout.substring(0, navStart)).endsWith("</nav>");
        int navEnd = layout.indexOf("</nav>", layout.indexOf("<!--/* The slot of the notifications page")) + "</nav>".length();
        String modeIcon = "<th:block th:if=\"${workspaceMode == true}\">";
        int iconStart = layout.indexOf(modeIcon);
        assertThat(iconStart).isGreaterThan(0);
        assertThat(layout.substring(0, iconStart)).endsWith("</th:block>");
        int iconEnd = layout.indexOf("</th:block>", iconStart) + "</th:block>".length();
        String withoutMode = layout.substring(0, navStart) + layout.substring(navEnd);
        withoutMode = withoutMode.replace(layout.substring(iconStart, iconEnd), "");
        String rest = withoutWorkspaceBlocks(withoutMode);

        assertThat(layout).contains(OPEN).contains("lg-i-workspace").contains("#{workspace.nav.listings}");
        // (the demo workspace of ADR-057 is another feature and uses the word too)
        assertThat(rest).doesNotContain("workspaceEnabled").doesNotContain("workspace.nav").doesNotContain("/console/workspace")
                .doesNotContain("lg-i-workspace").doesNotContain("nav.group.workspace").doesNotContain("lg-i-s-draft").doesNotContain("lg-i-upload")
                .doesNotContain("lg-i-accounts").doesNotContain("lg-i-guess").doesNotContain("lg-i-unchecked");
        // the sprite and the menu each have exactly one flagged block, glued to the element before it (no whitespace when the flag is off)
        assertThat(layout.split(java.util.regex.Pattern.quote(OPEN), -1)).hasSize(3);
        assertThat(layout).contains("</symbol>" + OPEN).contains("</a>" + OPEN);
    }
}
