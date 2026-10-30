package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

/**
 * Run by {@link CatalogImportStreamingTest} in a JVM with a small heap: scans one file and prints the counts, keeping
 * nothing. If the scan held the file (or its records) in memory this process would run out of heap.
 *
 * <p>Arguments: the directory, the file name inside it and the SHA-256 of the file.
 */
public final class CatalogScanMain {

    private CatalogScanMain() {
    }

    public static void main(String[] args) {
        CatalogManifest.Entry entry = CatalogManifest.Entry.single("big-1", args[1], CatalogImport.FORMAT_VERSION,
                "Synthetic", "https://example.invalid/big", "test only", LocalDate.of(2026, 10, 30), args[2],
                Set.of(25), Set.of(MarkStatus.REGISTERED));
        Path dir = Path.of(args[0]);
        TextNormalizer normalizer = new TextNormalizer(TestRulesets.test().normalization());

        CatalogImport.verifyFiles(entry, dir);
        CatalogImport.ScanResult result = CatalogImport.scan(entry, dir, normalizer, record -> {
        });

        System.out.println("read=" + result.recordsRead() + " kept=" + result.recordsKept());
    }
}
