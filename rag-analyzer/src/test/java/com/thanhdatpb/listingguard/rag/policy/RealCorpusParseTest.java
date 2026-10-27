package com.thanhdatpb.listingguard.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Parses the policy files saved under {@code policies/raw/} and checks that each page's own "Last updated"
 * date matches the manifest version. The raw files are git-ignored, so this test is skipped where they are
 * absent (for example in CI). It also writes {@code target/corpus-chunks.txt}, a readable dump of every
 * chunk, which is how chunk boundaries are reviewed.
 */
class RealCorpusParseTest {

    private static final Path POLICIES = Path.of("..", "policies");

    @Test
    void localCorpusParsesAndMatchesTheManifest() throws IOException {
        Path manifestFile = POLICIES.resolve("manifest.yaml");
        assumeTrue(Files.exists(manifestFile), "no policies/manifest.yaml");
        PolicyManifest manifest = PolicyManifestLoader.load(manifestFile);
        PolicyChunker chunker = new PolicyChunker(new PolicyChunker.Config(1500, 200));

        List<String> dump = new ArrayList<>();
        List<String> full = new ArrayList<>();
        int checked = 0;
        for (PolicyManifest.Entry entry : manifest.documents()) {
            Path file = POLICIES.resolve("raw").resolve(entry.marketplace().toLowerCase(Locale.ROOT)).resolve(entry.file());
            if (!Files.exists(file)) {
                continue;
            }
            checked++;
            ParsedPolicy parsed = PolicyParser.forFile(entry.file()).parse(Files.readAllBytes(file));
            List<PolicyChunk> chunks = chunker.chunk(entry.title(), parsed);

            assertThat(parsed.sections()).as(entry.file() + " sections").isNotEmpty();
            assertThat(chunks).as(entry.file() + " chunks").isNotEmpty();
            assertThat(parsed.lastUpdated()).as(entry.file() + " Last updated").isNotNull();
            assertThat(parsed.lastUpdated().toString()).as(entry.file() + " version").isEqualTo(entry.version());

            dump.add("=== " + entry.file() + " | " + entry.title() + " | lastUpdated=" + parsed.lastUpdated()
                    + " | sections=" + parsed.sections().size() + " | chunks=" + chunks.size());
            for (PolicyChunk chunk : chunks) {
                full.add("##### " + entry.file() + " #" + chunk.ordinal() + " | " + chunk.sectionPath());
                full.add(chunk.content());
                full.add("");
                dump.add(String.format("%3d | %4d chars | %s | #%s%n      %s", chunk.ordinal(), chunk.content().length(),
                        chunk.sectionPath(), chunk.anchor(), chunk.content().replace("\n", " ").substring(0,
                                Math.min(280, chunk.content().replace("\n", " ").length()))));
            }
        }
        assumeTrue(checked > 0, "no raw policy files saved locally");
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "corpus-chunks.txt"), dump, StandardCharsets.UTF_8);
        Files.write(Path.of("target", "corpus-chunks-full.txt"), full, StandardCharsets.UTF_8);
    }
}
