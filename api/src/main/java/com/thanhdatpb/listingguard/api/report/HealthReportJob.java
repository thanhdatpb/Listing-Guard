package com.thanhdatpb.listingguard.api.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes the listing health report of one client, or of every active client, for a period (ADR-018): {@code report.json} and
 * {@code report.html} in a directory of its own, {@code <out>/<UTC timestamp>/}. It asks {@link HealthReportService} for every
 * number, as the endpoint and the console page do, so the three cannot differ; it has no query of its own and reads no file.
 *
 * <p>Every report is computed before anything is written, so a refusal (a bad period, an unknown client) leaves no half
 * report, and a directory that already exists is an error, never overwritten. A run costs no unit and writes nothing to the
 * database.
 */
public final class HealthReportJob {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter DIRECTORY =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** One client's entry of {@code report.json}: {@code report} is exactly what the endpoint answers for that client. */
    public record ClientEntry(String client, UUID clientId, HealthReport report) {
    }

    /** The whole {@code report.json}. */
    public record RunFile(Instant generatedAt, HealthReport.PeriodView period, String marketplace, List<ClientEntry> clients) {
    }

    /** What a finished run reports to its caller. */
    public record Result(Path dir, int clients, ReportQuery query) {
    }

    private final ClientRepository clients;
    private final HealthReportService service;
    private final ObjectMapper mapper;
    private final String trademarkDisclaimer;
    private final Path outDir;
    private final PrintStream out;
    private final Clock clock;

    public HealthReportJob(ClientRepository clients, HealthReportService service, ObjectMapper mapper, String trademarkDisclaimer,
                           Path outDir, PrintStream out, Clock clock) {
        this.clients = clients;
        this.service = service;
        this.mapper = mapper;
        this.trademarkDisclaimer = trademarkDisclaimer;
        this.outDir = outDir;
        this.out = out;
        this.clock = clock;
    }

    /**
     * @throws IllegalArgumentException if the period, the marketplace or the client cannot be used
     * @throws InvalidReportPeriodException  if a date is malformed or reversed
     * @throws ReportPeriodTooLongException  if the period is longer than the limit
     * @throws IllegalStateException         if the directory of this second already exists
     */
    public Result run(HealthReportOptions options) {
        ReportQuery query = options.from() == null
                ? service.defaultQuery(options.marketplace())
                : service.query(options.from(), options.to(), options.marketplace());
        List<Client> selected = select(options.client());
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        List<ClientEntry> entries = new ArrayList<>();
        for (Client client : selected) {
            entries.add(new ClientEntry(client.getName(), client.getId(), service.summary(client.getId(), query)));
        }
        RunFile run = new RunFile(now, new HealthReport.PeriodView(query.from(), query.to(), query.days()), query.marketplace(),
                List.copyOf(entries));

        Path dir = outDir.resolve(DIRECTORY.format(now));
        try {
            Files.createDirectories(outDir);
            Files.createDirectory(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("report.json").toFile(), run);
            Files.writeString(dir.resolve("report.html"), HealthReportHtml.render(run, trademarkDisclaimer), StandardCharsets.UTF_8);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("The report directory " + dir + " already exists. Nothing was overwritten; run the job "
                    + "again in a second.");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report to " + dir, e);
        }
        out.println("Health report for " + entries.size() + " client(s), " + query.from() + " to " + query.to() + ", written to "
                + dir.toAbsolutePath() + " (report.html, report.json).");
        return new Result(dir, entries.size(), query);
    }

    /** {@code all} is every active client (a client named {@code all} is reached by its id), else one client by id or name. */
    private List<Client> select(String selector) {
        if (selector.equals("all")) {
            List<Client> active = clients.findAllByOrderByNameAsc().stream().filter(Client::isActive).toList();
            if (active.isEmpty()) {
                throw new IllegalArgumentException("There is no active client. Create one with: docker compose run --rm admin client create <name>");
            }
            return active;
        }
        Optional<Client> found = UUID_TEXT.matcher(selector).matches()
                ? clients.findById(UUID.fromString(selector))
                : clients.findByName(selector);
        Client client = found.orElseThrow(() -> new IllegalArgumentException("No client named or with the id '" + selector
                + "'. List the clients with: docker compose run --rm admin client list"));
        if (!client.isActive()) {
            throw new IllegalArgumentException("Client '" + client.getName() + "' is inactive. Activate it with: "
                    + "docker compose run --rm admin client activate " + client.getName());
        }
        return List.of(client);
    }
}
