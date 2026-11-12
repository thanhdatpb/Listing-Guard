package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.UsptoConvertProperties;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * One-shot job {@code --spring.profiles.active=tsdr-status} (ADR-009, "TSDR status check"): checks the status of marks of the
 * served catalog against the USPTO TSDR and writes a report, then the application exits (the profile turns the web server off).
 * It never runs in the API process.
 *
 * <pre>
 * (no option)                              ask the TSDR about the marks recent findings named (needs download.enabled and a key)
 * --input-dir=&lt;folder inside catalogs/raw&gt;  read TSDR files you saved yourself (XML or JSON); no network, no key
 * --max-marks=&lt;n&gt;                          ask about at most n marks in this run (never more than the setting)
 * --dry-run                                say which marks would be asked about; ask nothing, write nothing
 * </pre>
 * A run that could not finish (a refused key, a limit that lasts, another run holding the lock) ends with a non-zero code after
 * it was recorded.
 */
@Component
@Profile("tsdr-status")
public class TsdrStatusRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TsdrStatusRunner.class);

    private final TsdrProperties properties;
    private final TrademarkProperties trademark;
    private final UsptoConvertProperties convert;
    private final ResourceLoader resources;
    private final TsdrStatusStore store;
    private final TsdrStatusLock lock;
    private final TrademarkCatalogRuntime runtime;

    public TsdrStatusRunner(TsdrProperties properties, TrademarkProperties trademark, UsptoConvertProperties convert,
                            ResourceLoader resources, TsdrStatusStore store, TsdrStatusLock lock,
                            TrademarkCatalogRuntime runtime) {
        this.properties = properties;
        this.trademark = trademark;
        this.convert = convert;
        this.resources = resources;
        this.store = store;
        this.lock = lock;
        this.runtime = runtime;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException("tsdr-status takes options only (--input-dir=..., --max-marks=..., --dry-run), got "
                    + args.getNonOptionArgs().size() + " other argument(s).");
        }
        TsdrStatusJob.Options options = new TsdrStatusJob.Options(single(args, "input-dir"), number(single(args, "max-marks")),
                args.containsOption("dry-run"));
        TsdrProperties.Download download = properties.download();
        boolean live = options.inputDir() == null && !options.dryRun();
        if (live && download.enabled() && !download.hasKey()) {
            throw new IllegalArgumentException("listing-guard.trademark.tsdr.download.enabled is true but "
                    + "LISTING_GUARD_TSDR_API_KEY is empty.");
        }
        TsdrClient client = live && download.enabled()
                ? new TsdrClient(download, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis())) : null;
        log.info("tsdr-status: {}; calls {} (base {}, key {}); at most {} marks. The key header, the status field names and "
                        + "the answer for an unknown serial number are unverified (ADR-009).",
                options.inputDir() != null ? "files of " + options.inputDir() : options.dryRun() ? "dry run" : "live",
                download.enabled() ? "on" : "off", download.baseUrl(), download.hasKey() ? "set" : "empty",
                properties.maxMarksPerRun());

        UsptoStatusMap statusMap = UsptoStatusMap.parse(
                resources.getResource(convert.statusMap()).getContentAsString(StandardCharsets.UTF_8));
        TsdrStatusJob job = new TsdrStatusJob(properties, trademark, statusMap, store, lock, this::served, client,
                Clock.systemUTC());
        TsdrStatusJob.Result result = job.run(options);

        result.notes().forEach(note -> log.info("  {}", note));
        log.info("tsdr-status{}: {}", result.runId() == null ? "" : " run " + result.runId(), result.message());
        if (result.reportDir() != null) {
            log.info("tsdr-status report: {}", result.reportDir());
        }
        if (result.needsAttention()) {
            throw new IllegalStateException("tsdr-status needs attention: " + result.message());
        }
    }

    /** The catalog the API serves; the job refuses to run without one (it picks marks from it). */
    private TsdrStatusJob.ServedCatalog served() {
        String marketplace = trademark.marketplaces().isEmpty() ? "ETSY" : trademark.marketplaces().get(0);
        try {
            TrademarkCatalogRuntime.Serving serving = runtime.serving(marketplace);
            return new TsdrStatusJob.ServedCatalog(serving.catalogVersionId(), serving.catalogVersion());
        } catch (RuntimeException e) {
            throw new IllegalStateException("tsdr-status needs a served trademark catalog (listing-guard.trademark.catalog-version "
                    + "or activation.follow): " + e.getMessage());
        }
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Pass --" + name + "=<value> once.");
        }
        return values.get(0).trim();
    }

    private static Integer number(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--max-marks must be a number, got '" + text + "'.");
        }
    }
}
