package com.thanhdatpb.listingguard.api.trademark.sync;

import com.thanhdatpb.listingguard.api.trademark.CatalogActivations;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogLock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.UsptoConvertProperties;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot job {@code --spring.profiles.active=uspto-sync} (ADR-009, PR A2d): keeps a trademark catalog of USPTO marks up to
 * date, then the application exits (the profile turns the web server off). It never runs in the API process.
 *
 * <pre>
 * --input-dir=&lt;folder inside catalogs/raw&gt;   register files you downloaded yourself (no network)
 * --backfile [--backfile-through=YYYY-MM-DD]  the files are the annual backfile (with --input-dir, or downloaded)
 * --no-activate | --force-activate            import without activating | activate despite a rollback or the ratio gate
 * --ignore-file=&lt;daily file&gt; --reason=&lt;text&gt;  leave a missing or broken daily file out on purpose
 * --max-files=&lt;n&gt;                             download at most n files in this run
 * --dry-run                                   say what would happen; change nothing
 * </pre>
 * Without {@code --input-dir} it downloads from the USPTO Open Data Portal when
 * {@code listing-guard.trademark.uspto.sync.download.enabled} is true (off by default; every endpoint is unverified), and
 * otherwise converts the stored files when they changed. A run that needs the operator (a missing file, a stopped download,
 * a version it did not activate because it is much smaller) ends with a non-zero code after it was recorded.
 */
@Component
@Profile("uspto-sync")
public class UsptoSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsptoSyncRunner.class);

    private final UsptoSyncProperties properties;
    private final UsptoConvertProperties convert;
    private final TrademarkProperties trademark;
    private final TrademarkRulesets rulesets;
    private final ResourceLoader resources;
    private final UsptoSyncStore store;
    private final TrademarkCatalogImporter importer;
    private final CatalogActivations activations;
    private final TrademarkCatalogLock lock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public UsptoSyncRunner(UsptoSyncProperties properties, UsptoConvertProperties convert, TrademarkProperties trademark,
                           TrademarkRulesets rulesets, ResourceLoader resources, UsptoSyncStore store,
                           TrademarkCatalogImporter importer, CatalogActivations activations, TrademarkCatalogLock lock,
                           JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.properties = properties;
        this.convert = convert;
        this.trademark = trademark;
        this.rulesets = rulesets;
        this.resources = resources;
        this.store = store;
        this.importer = importer;
        this.activations = activations;
        this.lock = lock;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException("uspto-sync takes options only (--input-dir=..., --backfile, ...), got "
                    + args.getNonOptionArgs().size() + " other argument(s).");
        }
        UsptoSyncJob.Options options = new UsptoSyncJob.Options(single(args, "input-dir"), args.containsOption("backfile"),
                date(single(args, "backfile-through")), args.containsOption("no-activate"),
                args.containsOption("force-activate"), args.containsOption("dry-run"),
                args.getOptionValues("ignore-file"), single(args, "reason"), number(single(args, "max-files")));
        UsptoSyncProperties.Download download = properties.download();
        UsptoBulkClient client = download.enabled() && options.inputDir() == null
                ? new UsptoBulkClient(download, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()))
                : null;
        log.info("uspto-sync: {}; download {} (base {}, key {}); keep {} catalog versions. The ODP endpoints and the "
                        + "converter are unverified against real data (ADR-009).",
                options.inputDir() != null ? "files of " + options.inputDir() : "no input folder",
                download.enabled() ? "on" : "off", download.baseUrl(), download.hasKey() ? "set" : "empty",
                properties.keepVersions());

        UsptoSyncJob.Result result = new UsptoSyncJob(properties, convert, trademark, rulesets, resources, store, importer,
                activations, lock, jdbc, transactions, client, Clock.systemUTC()).run(options);

        if (result.runId() != null) {
            log.info("uspto-sync run {}: {}. {}", result.runId(), result.status(), result.message());
        } else {
            result.notes().forEach(note -> log.info("  {}", note));
            log.info("{}", result.message());
        }
        if (result.needsAttention()) {
            throw new IllegalStateException("uspto-sync needs attention: " + result.message());
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

    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--backfile-through must be a date YYYY-MM-DD, got '" + text + "'.");
        }
    }

    private static Integer number(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--max-files must be a number, got '" + text + "'.");
        }
    }
}
