package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run by {@link UsptoDailyConverterTest} in a JVM with a small heap: converts one file and prints the counts. If the
 * converter held the file (or its records) in memory this process would run out of heap.
 *
 * <p>Arguments: the XML file and the output file.
 */
public final class UsptoConvertMain {

    private UsptoConvertMain() {
    }

    public static void main(String[] args) throws Exception {
        UsptoDailyConverter converter = new UsptoDailyConverter(
                UsptoStatusMap.loadClasspath("uspto/uspto-status-map-2026-11.yaml"),
                new TextNormalizer(TestRulesets.test().normalization()), false);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(args[0])));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            ConversionReport report = converter.convert(in, out);
            System.out.println("read=" + report.recordsRead() + " written=" + report.recordsWritten());
        }
    }
}
