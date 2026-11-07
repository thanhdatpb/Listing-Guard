package com.thanhdatpb.listingguard.api.report;

import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

/**
 * The command line of the {@code health-report} job (ADR-018). The dates are kept as the text that was typed: the same check
 * as the endpoint's ({@link ReportQuery#parse}) reads them, so the job and the API refuse the same periods.
 *
 * @param client      a client name or id, or null when every active client is requested
 * @param allClients  whether to report every active client
 * @param from        the first day, or null to take the default period (both dates or neither)
 * @param to          the last day, or null
 * @param marketplace {@code ETSY} or {@code AMAZON}, or null for all
 */
public record HealthReportOptions(String client, boolean allClients, String from, String to, String marketplace) {

    static final String USAGE = """
            Usage:
              (--client=<client name|id> | --all-clients) [--from=YYYY-MM-DD --to=YYYY-MM-DD]
              [--marketplace=ETSY|AMAZON]
              Without --from and --to the period is the last complete UTC days before today.""";

    private static final Set<String> KNOWN = Set.of("client", "all-clients", "from", "to", "marketplace");

    /**
     * @throws IllegalArgumentException for a missing, repeated, unknown or inconsistent option
     */
    static HealthReportOptions parse(ApplicationArguments args) {
        for (String name : args.getOptionNames()) {
            // names with a dot are Spring's own settings, passed the same way (--spring.profiles.active=...)
            if (!name.contains(".") && !KNOWN.contains(name)) {
                throw usage("Unknown option --" + name + ".");
            }
        }
        String from = optional(args, "from");
        String to = optional(args, "to");
        if ((from == null) != (to == null)) {
            throw usage("Pass --from and --to together, or neither of them.");
        }
        String client = optional(args, "client");
        boolean allClients = args.containsOption("all-clients");
        List<String> allClientValues = args.getOptionValues("all-clients");
        if (allClientValues != null && !allClientValues.isEmpty()) {
            throw usage("--all-clients is a flag and does not take a value.");
        }
        if (allClients == (client != null)) {
            throw usage("Pass exactly one of --client=<client name|id> or --all-clients.");
        }
        return new HealthReportOptions(client, allClients, from, to, optional(args, "marketplace"));
    }

    private static String optional(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            throw usage("Pass --" + name + " once.");
        }
        String value = values.get(0).trim();
        if (value.isEmpty()) {
            throw usage("--" + name + " is empty.");
        }
        return value;
    }

    private static IllegalArgumentException usage(String problem) {
        return new IllegalArgumentException(problem + "\n" + USAGE);
    }
}
