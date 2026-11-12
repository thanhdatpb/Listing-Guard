package com.thanhdatpb.listingguard.api.policymonitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyMonitorReportRetentionTest {

    @TempDir Path temp;

    @Test
    void keepsTwelveNewestTimestampedReportsAndLeavesOtherDirectoriesAlone() throws Exception {
        Path out = Files.createDirectory(temp.resolve("out"));
        for (int day = 1; day <= 15; day++) {
            Path report = Files.createDirectory(out.resolve("202611" + String.format("%02d", day) + "T010000Z"));
            Files.writeString(report.resolve("report.html"), "synthetic report " + day);
        }
        Path unrelated = Files.createDirectory(out.resolve("manual-notes"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");

        PolicyMonitorJob.retainRecentReports(out, 12);

        try (var entries = Files.list(out)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .contains("20261104T010000Z", "20261115T010000Z", "manual-notes")
                    .doesNotContain("20261101T010000Z", "20261102T010000Z", "20261103T010000Z");
        }
        assertThat(Files.readString(unrelated.resolve("keep.txt"))).isEqualTo("keep");
    }
}
