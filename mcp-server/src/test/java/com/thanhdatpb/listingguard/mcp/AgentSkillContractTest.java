package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AgentSkillContractTest {

    private static final Path SKILL_DIRECTORY = Path.of("..", "skills", "listing-guard").toAbsolutePath().normalize();
    private static final Path SKILL_FILE = SKILL_DIRECTORY.resolve("SKILL.md");
    private static final Pattern TOOL_REFERENCE = Pattern.compile("(?:call|Call|→)\\s*`([a-z]+(?:_[a-z]+)+)`");
    private static final Pattern SNAKE_CASE_TOKEN = Pattern.compile("`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private static final Set<String> SKILL_TERMS = Set.of("product_type", "print_profile", "title_length", "duplicate_tags");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern RULE_ID_CELL = Pattern.compile("\\|\\s*`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`\\s*\\|");
    private static final int SKILL_MAX_LINES = 150;
    private static final Path REPAIR_LOOP = SKILL_DIRECTORY.resolve("references").resolve("repair-loop.md");
    private static final Path REPAIR_LOOP_EXAMPLE = SKILL_DIRECTORY.resolve("examples").resolve("repair-loop-etsy.md");
    private static final Path RULE_IDS = SKILL_DIRECTORY.resolve("references").resolve("common-rule-ids.md");

    @Test
    void skillHasValidFrontmatterAndReferencesOnlyCatalogTools() throws IOException {
        String skill = Files.readString(SKILL_FILE);
        String[] lines = skill.split("\\R", 5);

        assertThat(lines).as("YAML frontmatter opens and closes").hasSize(5);
        assertThat(lines[0]).isEqualTo("---");
        assertThat(lines[3]).isEqualTo("---");
        assertThat(lines[1]).matches("name: [a-z0-9-]+");
        assertThat(lines[1]).isEqualTo("name: listing-guard");
        assertThat(lines[2]).matches("description: [^\\r\\n]+\\.");
        assertThat(lines[2]).contains("Use Listing Guard to check Etsy or Amazon listings");

        Set<String> catalogNames = new HashSet<>();
        new ToolCatalog(ToolResults.newMapper()).tools().forEach(tool -> catalogNames.add(tool.name()));
        Matcher matcher = TOOL_REFERENCE.matcher(skill);
        Set<String> mentionedTools = new HashSet<>();
        while (matcher.find()) {
            mentionedTools.add(matcher.group(1));
        }

        assertThat(mentionedTools).isNotEmpty().allSatisfy(name -> assertThat(catalogNames).contains(name));
        catalogNames.forEach(name -> assertThat(skill).contains("`" + name + "`"));

        Matcher tokenMatcher = SNAKE_CASE_TOKEN.matcher(skill);
        Set<String> codeTokens = new HashSet<>();
        while (tokenMatcher.find()) {
            codeTokens.add(tokenMatcher.group(1));
        }
        codeTokens.removeAll(SKILL_TERMS);
        assertThat(codeTokens).allSatisfy(name -> assertThat(catalogNames).contains(name));
    }

    @Test
    void everyFileOfTheSkillNamesOnlyCatalogToolsAndKnownRuleIds() throws IOException {
        Set<String> catalogNames = catalogToolNames();
        Set<String> knownTokens = new HashSet<>(catalogNames);
        knownTokens.addAll(SKILL_TERMS);
        Matcher ruleIds = RULE_ID_CELL.matcher(Files.readString(RULE_IDS));
        while (ruleIds.find()) {
            knownTokens.add(ruleIds.group(1));
        }

        try (var files = Files.walk(SKILL_DIRECTORY)) {
            List<Path> markdownFiles = files.filter(path -> path.toString().endsWith(".md")).toList();
            assertThat(markdownFiles).contains(REPAIR_LOOP, REPAIR_LOOP_EXAMPLE);
            for (Path markdown : markdownFiles) {
                String prose = FENCED_BLOCK.matcher(Files.readString(markdown)).replaceAll("");
                Matcher calls = TOOL_REFERENCE.matcher(prose);
                while (calls.find()) {
                    assertThat(catalogNames).as("tool %s named in %s", calls.group(1), markdown).contains(calls.group(1));
                }
                Matcher tokens = SNAKE_CASE_TOKEN.matcher(prose);
                while (tokens.find()) {
                    assertThat(knownTokens).as("token %s in %s", tokens.group(1), markdown).contains(tokens.group(1));
                }
            }
        }
    }

    private static Set<String> catalogToolNames() {
        Set<String> names = new HashSet<>();
        new ToolCatalog(ToolResults.newMapper()).tools().forEach(tool -> names.add(tool.name()));
        return names;
    }

    @Test
    void skillStaysCompactAndPointsToTheRepairLoopReference() throws IOException {
        String skill = Files.readString(SKILL_FILE);

        assertThat(skill.lines().count()).as("SKILL.md lines").isLessThanOrEqualTo(SKILL_MAX_LINES);
        assertThat(skill).contains("## Bounded repair loop").contains("(references/repair-loop.md)");
        assertThat(REPAIR_LOOP).exists();
        assertThat(Files.readString(SKILL_DIRECTORY.resolve("examples").resolve("README.md")))
                .contains("(repair-loop-etsy.md)");
    }

    @Test
    void repairLoopNamesTheToolsAndStopConditionsItRelies() throws IOException {
        String skill = Files.readString(SKILL_FILE);
        String reference = Files.readString(REPAIR_LOOP);
        String example = Files.readString(REPAIR_LOOP_EXAMPLE);
        List<String> loopTools = List.of(ToolCatalog.GET_CAPABILITIES, ToolCatalog.GET_LIMITS, ToolCatalog.GET_USAGE,
                ToolCatalog.CHECK_LISTING, ToolCatalog.CHECK_TRADEMARK, ToolCatalog.CHECK_LISTING_EDIT);
        List<String> stopSignals = List.of("quota-exceeded", "rate-limited", "plan-not-allowed", "not-configured",
                "INCOMPLETE");

        loopTools.forEach(tool -> assertThat(reference).as("repair-loop.md names %s", tool).contains("`" + tool + "`"));
        List.of(ToolCatalog.GET_CAPABILITIES, ToolCatalog.GET_LIMITS, ToolCatalog.GET_USAGE, ToolCatalog.CHECK_LISTING,
                ToolCatalog.CHECK_TRADEMARK).forEach(tool ->
                        assertThat(example).as("the example calls %s", tool).contains("`" + tool + "`"));
        stopSignals.forEach(signal -> {
            assertThat(skill).as("SKILL.md names %s", signal).contains(signal);
            assertThat(reference).as("repair-loop.md names %s", signal).contains(signal);
        });
        assertThat(skill).contains("never edit or publish").contains("legally safe or cleared");
    }

    @Test
    void skillMarkdownLinksResolveToExistingFiles() throws IOException {
        try (var files = Files.walk(SKILL_DIRECTORY)) {
            List<Path> markdownFiles = files.filter(path -> path.toString().endsWith(".md")).toList();
            for (Path markdown : markdownFiles) {
                String content = Files.readString(markdown);
                Matcher matcher = MARKDOWN_LINK.matcher(content);
                while (matcher.find()) {
                    String target = matcher.group(1).trim();
                    if (target.isBlank() || target.startsWith("#") || target.startsWith("http:")
                            || target.startsWith("https:") || target.startsWith("mailto:")) {
                        continue;
                    }
                    String path = target.split("#", 2)[0].split("\\?", 2)[0];
                    if (!path.isBlank()) {
                        assertThat(markdown.getParent().resolve(path).normalize())
                                .as("link %s in %s", target, markdown)
                                .exists();
                    }
                }
            }
        }
    }
}
