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
 * What the SDK can never do (ADR-023), checked on its own sources and POM: only the documented API operations,
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
    void theSdkCanOnlyIssueGetAndPostAndTheOnePutOfTheWatchlist() {
        assertThat(Arrays.stream(Operation.values()).map(Operation::method).collect(Collectors.toSet()))
                .isSubsetOf("GET", "POST", "PUT");
        // ADR-031 amends ADR-023: one PUT, the replacement of the calling client's own watchlist, and no other
        assertThat(Arrays.stream(Operation.values()).filter(o -> o.method().equals("PUT")))
                .containsExactly(Operation.TRADEMARK_WATCHLIST_REPLACE);
        assertThat(Operation.TRADEMARK_WATCHLIST_REPLACE.path()).isEqualTo("/api/v1/trademark-watchlist");
        assertThat(Arrays.stream(Operation.values()).map(Operation::path)).containsExactlyInAnyOrder(
                "/api/v1/checks",
                "/api/v1/trademark-checks",
                "/api/v1/variation-checks",
                "/api/v1/listing-edits",
                "/api/v1/personalization-screenings",
                "/api/v1/rulesets/{marketplace}/limits",
                "/api/v1/usage",
                "/api/v1/capabilities",
                "/api/v1/trademark-watchlist",
                "/api/v1/trademark-watchlist");
    }

    @Test
    void noSourceUsesAWriteMethodOrBuildsARequestFromAnArbitraryMethod() throws IOException {
        Pattern write = Pattern.compile("\\.(DELETE|PATCH|HEAD|OPTIONS)\\(|\"(DELETE|PATCH)\"|\\.method\\([^)]");
        // a PUT is allowed in the transport (the one request builder) and in Operation (its one constant), nowhere else
        Pattern put = Pattern.compile("\\.PUT\\(|\"PUT\"");
        for (Path source : mainSources()) {
            String text = Files.readString(source);
            assertThat(write.matcher(text).find()).as(source.toString()).isFalse();
            String name = source.getFileName().toString();
            if (!name.equals("HttpTransport.java") && !name.equals("Operation.java")) {
                assertThat(put.matcher(text).find()).as(source.toString()).isFalse();
            }
        }
        assertThat(Files.readString(MAIN.resolve("com/thanhdatpb/listingguard/client/HttpTransport.java"))
                .split("\\.PUT\\(", -1)).as("one PUT in the transport").hasSize(2);
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
