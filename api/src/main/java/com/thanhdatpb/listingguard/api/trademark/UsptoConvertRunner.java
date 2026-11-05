package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.ConversionReport;
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
 * One-shot job: {@code --spring.profiles.active=uspto-convert --input=<file inside catalogs/raw> [--output=<name>]
 * [--overwrite]} converts one USPTO Trademark Applications Daily XML file (DTD V2.0) to {@code jsonl-1} in
 * {@code catalogs/raw/}, then the application exits (the profile turns the web server off). It reads and writes local
 * files and makes no network call; it imports no catalog (starting the application still applies migrations and
 * registers the trademark ruleset, like every job). It prints the {@code sha256} of the output to put in
 * {@code catalogs/manifest.yaml}. A file it cannot convert makes the process exit with a non-zero code.
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
        Path rawDir = Path.of(trademark.catalogsDir()).resolve("raw");
        String input = single(args, "input");
        String output = single(args, "output");
        log.info("Converting {} (USPTO Trademark Applications Daily, DTD V2.0) in {}, status map {}. This converter is "
                + "unverified against real data (ADR-009).", input, rawDir.toAbsolutePath(), statusMap.version());

        UsptoConvertJob.Result result = new UsptoConvertJob(statusMap,
                new TextNormalizer(rulesets.ruleset().normalization()), properties.allowOtherVersion())
                .run(rawDir, input, output, args.containsOption("overwrite"));

        ConversionReport report = result.report();
        log.info("Converted {}: version-no {} (version-date {}), {} records read, {} written, {} dropped, {} class "
                        + "codes left out. Report: {}", result.output().getFileName(), report.versionNo(),
                report.versionDate(), report.recordsRead(), report.recordsWritten(), report.droppedTotal(),
                report.ignoredClassCodes(), result.reportFile().getFileName());
        report.dropped().forEach((reason, count) -> log.info("  dropped {}: {}", reason, count));
        if (report.otherVersionAllowed()) {
            log.warn("The file declares version-no {}, not 2.0; it was read because "
                    + "listing-guard.trademark.uspto.allow-other-version is true.", report.versionNo());
        }
        if (!report.dataAvailable()) {
            log.warn("The file says it holds no data (data-available-code N); the output is empty.");
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
