package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the module can never do (ADR-017), checked on its own sources and POM: it only reads and checks, reaches exactly six
 * API paths with GET and POST, and has no database and no internal service behind it.
 */
class ReadOnlyBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void theClientCanOnlyIssueGetAndPostToTheSixApiPaths() {
        assertThat(Arrays.stream(Endpoint.values()).map(Endpoint::method).collect(Collectors.toSet()))
                .isSubsetOf("GET", "POST");
        assertThat(Arrays.stream(Endpoint.values()).map(Endpoint::path)).containsExactlyInAnyOrder(
                "/api/v1/checks",
                "/api/v1/trademark-checks",
                "/api/v1/variation-checks",
                "/api/v1/personalization-screenings",
                "/api/v1/rulesets/{marketplace}/limits",
                "/api/v1/usage");
    }

    @Test
    void everyToolMapsToADifferentEndpointOfThatList() {
        ToolCatalog catalog = new ToolCatalog(ToolResults.newMapper());

        assertThat(catalog.tools()).extracting(ToolSpec::endpoint).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(Endpoint.values());
        for (ToolSpec tool : catalog.tools()) {
            assertThat(tool.readOnly()).as(tool.name()).isEqualTo(tool.endpoint().method().equals("GET"));
        }
    }

    @Test
    void noSourceUsesAWriteMethodOrBuildsARequestFromAnArbitraryMethod() throws IOException {
        Pattern write = Pattern.compile("\\.(PUT|DELETE|PATCH|HEAD|OPTIONS)\\(|\"(PUT|DELETE|PATCH)\"|\\.method\\([^)]");
        for (Path source : mainSources()) {
            assertThat(write.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    @Test
    void noSourceReachesTheDatabaseOrAnotherModuleOfTheRepository() throws IOException {
        Pattern forbidden = Pattern.compile(
                "import (java\\.sql|javax\\.sql|jakarta\\.persistence|org\\.springframework|org\\.postgresql"
                        + "|com\\.thanhdatpb\\.listingguard\\.(?!mcp\\b))");
        for (Path source : mainSources()) {
            assertThat(forbidden.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    @Test
    void thePomHasNoDatabaseNoSpringAndNoOtherModuleOfTheRepository() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).doesNotContain("postgresql").doesNotContain("jdbc").doesNotContain("org.springframework")
                .doesNotContain("spring-boot").doesNotContain("flyway");
        // The only reference to the repository's own group is the parent.
        long own = Pattern.compile("<groupId>com\\.thanhdatpb\\.listingguard</groupId>").matcher(pom).results().count();
        assertThat(own).as("the parent only").isEqualTo(1);
    }

    @Test
    void theOnlyEnvironmentVariablesReadAreTheDocumentedOnes() throws IOException {
        Set<String> documented = Set.of(
                McpServerConfig.API_KEY, McpServerConfig.BASE_URL, McpServerConfig.ALLOW_INSECURE_HTTP,
                McpServerConfig.TIMEOUT_SECONDS, McpServerConfig.TRANSPORT, McpServerConfig.HTTP_HOST,
                McpServerConfig.HTTP_PORT);
        Pattern name = Pattern.compile("\"(LISTING_GUARD_[A-Z_]+)\"");
        for (Path source : mainSources()) {
            name.matcher(Files.readString(source)).results()
                    .forEach(m -> assertThat(documented).as(source + " " + m.group(1)).contains(m.group(1)));
        }
    }
}
