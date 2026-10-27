package com.thanhdatpb.listingguard.api.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Writes small self-made policy corpora into a temporary directory (the real corpus is never used in tests). */
final class PolicyFixtures {

    private PolicyFixtures() {
    }

    /** A page in the shape of the real ones: a content article plus a Last updated line. */
    static String page(String lastUpdated, String bodyHtml) {
        return "<html><body><header><p>Site header</p></header><article class=\"prose prose-article\">" + bodyHtml
                + "<p class=\"text-gray-lighter\">Last updated on " + lastUpdated + "</p></article></body></html>";
    }

    static void writeRaw(Path policiesDir, String marketplace, String file, String html) throws IOException {
        Path dir = policiesDir.resolve("raw").resolve(marketplace.toLowerCase(Locale.ROOT));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), html, StandardCharsets.UTF_8);
    }

    /** One manifest entry; the URL is derived from the file name. */
    static String entry(String file, String marketplace, String title, String fetchedAt, String version) {
        return """
                  - file: %s
                    marketplace: %s
                    title: %s
                    url: https://example.com/%s/
                    fetched_at: %s
                    version: %s
                """.formatted(file, marketplace, title, file.replace(".html", ""), fetchedAt, version);
    }

    static void writeManifest(Path policiesDir, String... entries) throws IOException {
        Files.writeString(policiesDir.resolve("manifest.yaml"), "documents:\n" + String.join("", entries), StandardCharsets.UTF_8);
    }
}
