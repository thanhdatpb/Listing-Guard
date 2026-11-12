package com.thanhdatpb.listingguard.api.policymonitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.policymonitor.PolicyMonitorSnapshotStore.DocumentSnapshot;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyMonitorSnapshotStore.SectionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyDiffEngineTest {
    private static final String URL = "https://www.etsy.com/legal/sellers/";
    private static final String RULESET = """
            marketplace: ETSY
            version: etsy-test
            rules:
              changed_rule:
                source:
                  status: secondary
                  urls: [https://www.etsy.com/legal/sellers/]
                  section: Seller Policy > Changed
            """;

    @TempDir Path temp;
    private Path raw;
    private Path manifest;
    private MemorySnapshots snapshots;
    private PolicyDiffEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        raw = Files.createDirectories(temp.resolve("raw/etsy"));
        manifest = temp.resolve("manifest.yaml");
        snapshots = new MemorySnapshots();
        engine = new PolicyDiffEngine(snapshots, Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void reportsAddedRemovedChangedSectionsVersionMismatchAndRuleMapping() throws IOException {
        writePage("2026-11-10", "old paragraph", "section to remove");
        writeManifest("2026-01-01", "2026-11-09");
        engine.run(manifest, temp.resolve("raw"), List.of(new PolicyDiffEngine.RuleSource("test", RULESET)), 30, false);

        writePage("2026-11-10", "revised paragraph", null, "brand new paragraph");
        writeManifest("2026-01-01", "2026-11-11");
        PolicyDiffEngine.RunFile report = engine.run(manifest, temp.resolve("raw"),
                List.of(new PolicyDiffEngine.RuleSource("test", RULESET)), 30, false);

        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo("VERSION_CHANGED");
            assertThat(change.documentHashChanged()).isTrue();
            assertThat(change.sections()).extracting(PolicyDiffEngine.SectionChange::type)
                    .containsExactlyInAnyOrder("SECTION_CHANGED", "SECTION_REMOVED", "SECTION_ADDED");
            assertThat(change.sections()).filteredOn(section -> section.type().equals("SECTION_CHANGED"))
                    .singleElement().satisfies(section -> {
                        assertThat(section.path()).isEqualTo("Seller Policy > Changed");
                        assertThat(section.excerpt()).contains("revised paragraph").hasSizeLessThanOrEqualTo(200);
                    });
        });
        assertThat(report.alerts()).extracting(PolicyDiffEngine.Alert::code)
                .containsExactlyInAnyOrder("FETCHED_AT_STALE", "VERSION_MISMATCH");
        assertThat(report.rulesToReview()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("changed_rule");
            assertThat(rule.sourceStatus()).isEqualTo("secondary");
            assertThat(rule.rulesetVersion()).isEqualTo("etsy-test");
        });
    }

    @Test
    void newAndRemovedDocumentsAreReported() throws IOException {
        writePage("2026-11-10", "hello", null);
        writeManifest("2026-11-12", "2026-11-10");
        PolicyDiffEngine.RunFile first = engine.run(manifest, temp.resolve("raw"), List.of(), 30, false);
        assertThat(first.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo("DOCUMENT_NEW");
            assertThat(change.sections()).extracting(PolicyDiffEngine.SectionChange::type).containsOnly("SECTION_ADDED");
        });

        Files.writeString(manifest, "documents: []\n");
        PolicyDiffEngine.RunFile removed = engine.run(manifest, temp.resolve("raw"), List.of(), 30, true);
        assertThat(removed.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo("DOCUMENT_REMOVED");
            assertThat(change.sections()).extracting(PolicyDiffEngine.SectionChange::type).containsOnly("SECTION_REMOVED");
        });
        assertThat(snapshots.readAll()).hasSize(1); // dry-run did not remove the saved snapshot
    }

    @Test
    void secondIdenticalRunHasAnEmptyChangeReport() throws IOException {
        writePage("2026-11-10", "stable paragraph", null);
        writeManifest("2026-11-12", "2026-11-10");
        engine.run(manifest, temp.resolve("raw"), List.of(), 30, false);
        PolicyDiffEngine.RunFile second = engine.run(manifest, temp.resolve("raw"), List.of(), 30, false);
        assertThat(second.empty()).isTrue();
        assertThat(second.changes()).isEmpty();
        assertThat(second.alerts()).isEmpty();
        assertThat(second.rulesToReview()).isEmpty();
    }

    private void writePage(String updated, String changed, String removed, String added) throws IOException {
        StringBuilder html = new StringBuilder("<html><body><article class=\"prose-article\"><p>Last updated on ")
                .append(LocalDate.parse(updated).getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                .append(' ').append(LocalDate.parse(updated).getDayOfMonth()).append(", ").append(LocalDate.parse(updated).getYear())
                .append("</p><h2>Changed</h2><p>").append(changed).append("</p>");
        if (removed != null) html.append("<h2>Removed</h2><p>").append(removed).append("</p>");
        if (added != null) html.append("<h2>Added</h2><p>").append(added).append("</p>");
        Files.writeString(raw.resolve("seller-policy.html"), html.append("</article></body></html>").toString());
    }

    private void writePage(String updated, String changed, String removed) throws IOException {
        writePage(updated, changed, removed, null);
    }

    private void writeManifest(String fetchedAt, String version) throws IOException {
        Files.writeString(manifest, """
                documents:
                  - file: seller-policy.html
                    marketplace: ETSY
                    title: Seller Policy
                    url: %s
                    fetched_at: %s
                    version: %s
                """.formatted(URL, fetchedAt, version));
    }

    private static final class MemorySnapshots implements PolicyMonitorSnapshotStore {
        private List<DocumentSnapshot> values = new ArrayList<>();
        @Override public List<DocumentSnapshot> readAll() { return List.copyOf(values); }
        @Override public void replaceAll(List<DocumentSnapshot> documents) { values = new ArrayList<>(documents); }
    }
}
