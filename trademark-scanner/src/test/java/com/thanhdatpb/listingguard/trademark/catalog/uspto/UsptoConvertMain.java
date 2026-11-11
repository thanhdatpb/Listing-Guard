package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Run by {@link UsptoCatalogConverterTest} in a JVM with a small heap: converts one file and prints the counts. If the
 * converter held the file (or its records) in memory this process would run out of heap.
 *
 * <p>Arguments: the XML file and the output file.
 */
public final class UsptoConvertMain {

    private UsptoConvertMain() {
    }

    public static void main(String[] args) throws Exception {
        UsptoStatusMap statusMap = UsptoTestXml.statusMap();
        UsptoCatalogConverter converter = new UsptoCatalogConverter(statusMap, UsptoTestXml.permissive(statusMap),
                new TextNormalizer(TestRulesets.test().normalization()), UsptoCatalogConverter.Options.defaults());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            ConversionReport report = converter.convert(
                    List.of(UsptoCatalogConverter.Source.file(Path.of(args[0]).getFileName().toString(), Path.of(args[0]))),
                    out);
            System.out.println("read=" + report.recordsRead() + " written=" + report.recordsWritten());
        }
    }
}
