package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the SDK can never do (ADR-023), checked on its own sources and POM: only {@code GET} and {@code POST} to the seven paths,
 * no Spring, no database, no logging framework and no other module of the repository.
 */
class ClientBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void theSdkCanOnlyIssueGetAndPostToTheSevenApiPaths() {
        assertThat(Arrays.stream(Operation.values()).map(Operation::method).collect(Collectors.toSet()))
                .isSubsetOf("GET", "POST");
        assertThat(Arrays.stream(Operation.values()).map(Operation::path)).containsExactlyInAnyOrder(
                "/api/v1/checks",
                "/api/v1/trademark-checks",
                "/api/v1/variation-checks",
                "/api/v1/personalization-screenings",
                "/api/v1/rulesets/{marketplace}/limits",
                "/api/v1/usage",
                "/api/v1/capabilities");
    }

    @Test
    void noSourceUsesAWriteMethodOrBuildsARequestFromAnArbitraryMethod() throws IOException {
        Pattern write = Pattern.compile("\\.(PUT|DELETE|PATCH|HEAD|OPTIONS)\\(|\"(PUT|DELETE|PATCH)\"|\\.method\\([^)]");
        for (Path source : mainSources()) {
            assertThat(write.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    @Test
    void noSourceImportsSpringADatabaseALoggerOrAnotherModule() throws IOException {
        Pattern forbidden = Pattern.compile(
                "import (static )?(java\\.sql|javax\\.sql|jakarta\\.|org\\.springframework|org\\.postgresql|org\\.slf4j"
                        + "|java\\.util\\.logging|ch\\.qos|org\\.apache\\.logging|tools\\.jackson"
                        + "|com\\.thanhdatpb\\.listingguard\\.(?!client\\b))");
        for (Path source : mainSources()) {
            assertThat(forbidden.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    @Test
    void noSourceWritesToStandardStreams() throws IOException {
        Pattern print = Pattern.compile("System\\.(out|err)|printStackTrace\\(");
        for (Path source : mainSources()) {
            assertThat(print.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }

    @Test
    void thePomDependsOnJacksonOnlyBesidesTestLibraries() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String dependencies = pom.substring(pom.indexOf("<dependencies>"), pom.indexOf("</dependencies>"));

        List<String> artifacts = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(dependencies).results()
                .map(m -> m.group(1)).toList();
        assertThat(artifacts).containsExactlyInAnyOrder("jackson-databind", "jackson-datatype-jsr310", "junit-jupiter",
                "assertj-core");
        assertThat(pom).doesNotContain("org.springframework").doesNotContain("spring-boot").doesNotContain("postgresql");
        long own = Pattern.compile("<groupId>com\\.thanhdatpb\\.listingguard</groupId>").matcher(pom).results().count();
        assertThat(own).as("the parent only").isEqualTo(1);
    }

    @Test
    void noPublicSignatureExposesAJacksonType() throws IOException {
        Pattern jacksonInSignature = Pattern.compile("public [^=;{]*\\b(JsonNode|ObjectMapper|JsonParser|ObjectNode)\\b");
        for (Path source : mainSources()) {
            assertThat(jacksonInSignature.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }
}
