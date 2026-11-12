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
