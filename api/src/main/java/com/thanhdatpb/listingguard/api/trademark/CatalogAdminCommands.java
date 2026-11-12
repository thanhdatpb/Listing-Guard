package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Activation;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.CatalogVersion;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * The {@code catalog} commands of the admin job (ADR-009, PR A2d): list the stored catalog versions and the activation log,
 * activate a version, roll back to the one before. {@code activate} and {@code rollback} hold {@link TrademarkCatalogLock},
 * so they never interleave with a {@code uspto-sync} run, and refuse while one runs.
 *
 * <pre>
 * catalog list
 * catalog activate &lt;version&gt; [--note=&lt;text&gt;]
 * catalog rollback [--to=&lt;version&gt;] [--note=&lt;text&gt;]
 * </pre>
 */
@Component
public class CatalogAdminCommands {

    /** The usage lines, indented like the other admin commands. */
    public static final String USAGE = """
              catalog list
              catalog activate <version> [--note=<text>]
              catalog rollback [--to=<version>] [--note=<text>]\
            """;

    private static final int HISTORY = 10;

    private final CatalogActivations activations;
    private final TrademarkCatalogLock lock;

    public CatalogAdminCommands(CatalogActivations activations, TrademarkCatalogLock lock) {
        this.activations = activations;
        this.lock = lock;
    }

    /**
     * @param command {@code list}, {@code activate} or {@code rollback}
     * @param args    the arguments after the command
     * @throws IllegalArgumentException for a missing or refused argument
     * @throws IllegalStateException    when a sync holds the lock or there is nothing to roll back
     */
    public void execute(String command, List<String> args, ApplicationArguments arguments, PrintStream out) {
        switch (command) {
            case "list" -> list(out);
            case "activate" -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("Missing a catalog version; `catalog list` shows them.");
                }
                activate(args.get(0), option(arguments, "note"), out);
            }
            case "rollback" -> rollback(option(arguments, "to"), option(arguments, "note"), out);
            default -> throw new IllegalArgumentException("Unknown command 'catalog " + command + "'.");
        }
    }

    private void list(PrintStream out) {
        List<CatalogVersion> versions = activations.catalogs();
        Optional<Activation> current = activations.current();
        Long served = current.map(Activation::catalogVersionId).orElse(null);
        if (versions.isEmpty()) {
            out.println("No catalog version is stored.");
        } else {
            out.println("Catalog versions, newest first (* = the version of the latest activation):");
            out.println(String.format("  %-56s %10s  %-11s  %-6s  %s", "VERSION", "ENTRIES", "SOURCE DATE", "MADE BY",
                    "IMPORTED"));
            for (CatalogVersion version : versions) {
                out.println(String.format("%s %-56s %10d  %-11s  %-7s  %s", served != null && served == version.id() ? "*"
                        : " ", version.version(), version.entries(), version.sourceDate(),
                        version.madeBySync() ? "sync" : "import", version.importedAt()));
            }
        }
        List<Activation> history = activations.history(HISTORY);
        if (history.isEmpty()) {
            out.println("No catalog version has been activated.");
        } else {
            out.println("Latest activations, newest first:");
            for (Activation activation : history) {
                out.println(String.format("  %-24s %-9s %s%s%s", activation.activatedAt(), activation.reason(),
                        activation.catalogVersion(), activation.catalogVersionId() == null ? " (deleted)" : "",
                        activation.syncRunId() == null ? "" : "  uspto-sync run " + activation.syncRunId()));
            }
        }
        out.println("The activation log decides what is served only where listing-guard.trademark.activation.follow is "
                + "true (TRADEMARK_FOLLOW_ACTIVATION); elsewhere listing-guard.trademark.catalog-version does.");
    }

    private void activate(String version, String note, PrintStream out) {
        try (TrademarkCatalogLock.Held held = acquire()) {
            Activation activation = activations.activate(version, CatalogActivations.Reason.MANUAL, null, note);
            out.println("Catalog version " + activation.catalogVersion() + " activated (activation " + activation.id()
                    + "). A runtime that follows the activation log serves it within its refresh interval; one that "
                    + "serves listing-guard.trademark.catalog-version is not changed.");
        }
    }

    private void rollback(String to, String note, PrintStream out) {
        try (TrademarkCatalogLock.Held held = acquire()) {
            String before = activations.current().map(Activation::catalogVersion).orElse(null);
            Activation activation = activations.rollback(to, note);
            out.println("Rolled back from " + before + " to " + activation.catalogVersion() + " (activation "
                    + activation.id() + "). The uspto-sync job imports new versions but does not activate one by itself "
                    + "until `catalog activate <version>` ends this rollback.");
        }
    }

    private TrademarkCatalogLock.Held acquire() {
        return lock.tryAcquire().orElseThrow(() -> new IllegalStateException("A uspto-sync run or another catalog command "
                + "holds the catalog lock; try again when it has finished."));
    }

    private static String option(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Pass --" + name + "=<value> once.");
        }
        return values.get(0);
    }
}
