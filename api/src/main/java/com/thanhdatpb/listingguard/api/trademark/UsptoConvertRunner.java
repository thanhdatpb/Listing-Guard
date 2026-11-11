package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.ConversionReport;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoCatalogConverter;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConvertFilters;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConvertFiltersException;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMapException;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=uspto-convert --input=<file inside catalogs/raw> [--input=<file> ...]}
 * (or {@code --input-dir=<folder inside catalogs/raw>}) {@code [--output=<name>] [--overwrite] [--strict]} converts
 * USPTO Trademark Applications Daily XML files (DTD V2.0) to one {@code jsonl-1} file in {@code catalogs/raw/}, then the
 * application exits (the profile turns the web server off). For each serial number only the record with the latest
 * transaction date is kept, and only the marks the filters keep (ADR-009, PR A2c). It reads and writes local files and
 * makes no network call; it imports no catalog (starting the application still applies migrations and registers the
 * trademark ruleset, like every job). It prints the {@code sha256} of the output to put in {@code
 * catalogs/manifest.yaml}. A run that cannot convert makes the process exit with a non-zero code; a run that finished
 * with a problem (not strict) writes its output, says {@code INCOMPLETE} and warns.
 *
 * <p>The converter is <b>unverified against real data</b> (ADR-009): it follows the public DTD and documentation.
 * A runner and not an HTTP endpoint on purpose, like {@code trademark-import}.
 */
@Component
@Profile("uspto-convert")
public class UsptoConvertRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsptoConvertRunner.class);

    private final TrademarkProperties trademark;
    private final UsptoConvertProperties properties;
    private final TrademarkRulesets rulesets;
    private final ResourceLoader resources;

    public UsptoConvertRunner(TrademarkProperties trademark, UsptoConvertProperties properties,
                              TrademarkRulesets rulesets, ResourceLoader resources) {
        this.trademark = trademark;
        this.properties = properties;
        this.rulesets = rulesets;
        this.resources = resources;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        UsptoStatusMap statusMap;
        try {
            statusMap = UsptoStatusMap.parse(resources.getResource(properties.statusMap())
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (UsptoStatusMapException e) {
            throw new UsptoStatusMapException("USPTO status map " + properties.statusMap() + ": " + e.getMessage(), e);
        }
        UsptoConvertFilters filters;
        try {
            filters = UsptoConvertFilters.parse(resources.getResource(properties.filters())
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (UsptoConvertFiltersException e) {
            throw new UsptoConvertFiltersException("USPTO conversion filters " + properties.filters() + ": "
                    + e.getMessage(), e);
        }
        Path rawDir = Path.of(trademark.catalogsDir()).resolve("raw");
        List<String> inputs = args.getOptionValues("input");
        String inputDir = single(args, "input-dir");
        String output = single(args, "output");
        boolean strict = properties.strict() || args.containsOption("strict");
        log.info("Converting {} in {} (USPTO Trademark Applications Daily, DTD V2.0), status map {}, filters {}{}. This "
                        + "converter is unverified against real data (ADR-009).",
                inputDir != null ? "every .xml file of " + inputDir : inputs == null ? "no file" : inputs.size() + " file(s)",
                rawDir.toAbsolutePath(), statusMap.version(), filters.version(), strict ? ", strict" : "");

        UsptoConvertJob.Result result = new UsptoConvertJob(statusMap, filters,
                new TextNormalizer(rulesets.ruleset().normalization()),
                new UsptoCatalogConverter.Options(properties.allowOtherVersion(), strict, properties.maxDistinctSerials()))
                .run(rawDir, inputs, inputDir, output, args.containsOption("overwrite"));

        ConversionReport report = result.report();
        log.info("Converted {} files into {} ({} failed): {} records read, {} written, {} replaced by a later "
                        + "transaction, {} removed because their latest status is not live, {} filtered, {} with an error; "
                        + "{} distinct serial numbers, {} class codes left out. Report: {}",
                report.files().size() - report.filesFailed(), result.output().getFileName(), report.filesFailed(),
                report.recordsRead(), report.recordsWritten(), report.superseded(), report.deadRemoved(),
                report.filteredTotal(), report.recordErrors(), report.distinctSerials(), report.ignoredClassCodes(),
                result.reportFile().getFileName());
        report.filtered().forEach((reason, count) -> log.info("  filtered {}: {}", reason, count));
        report.deadByStatus().forEach((code, count) -> log.info("  removed, latest status {}: {}", code, count));
        report.errors().forEach((code, count) -> log.warn("  record error {}: {}", code, count));
        report.errorDetails().stream().limit(10).forEach(e -> log.warn("  {}: record {} (line {}, column {}): {}",
                e.file(), e.record(), e.line(), e.column(), e.code()));
        report.files().stream().filter(f -> f.status() == ConversionReport.FileStatus.FAILED)
                .forEach(f -> log.warn("  file left out: {}", f.error()));
        if (report.otherVersionAllowed()) {
            log.warn("A file declares a version-no other than 2.0; it was read because "
                    + "listing-guard.trademark.uspto.allow-other-version is true.");
        }
        report.files().stream().filter(f -> f.status() == ConversionReport.FileStatus.OK && !f.dataAvailable())
                .forEach(f -> log.warn("{} says it holds no data (data-available-code N).", f.name()));
        if (report.completeness() == Completeness.INCOMPLETE) {
            log.warn("The conversion is INCOMPLETE: a file was left out or a record could not be placed, so an older "
                    + "record may stand for a serial number whose latest record was lost. Do not rely on this catalog; fix "
                    + "the input and convert again (--strict stops at the first problem).");
        }
        log.info("To import it, list {} in catalogs/manifest.yaml with sha256: {}", result.output().getFileName(),
                report.outputSha256());
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException("Pass --" + name + " once.");
        }
        return values.get(0);
    }
}
