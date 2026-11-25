package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code account import <file.csv>} of the admin job (ADR-053): the console accounts of a pilot from one file, instead of one command
 * for each. The columns are {@code email}, {@code role} ({@code ADMIN} or {@code MEMBER}), {@code client} (a client name) and,
 * optionally, {@code display_name}; a header row names them, in any order.
 *
 * <p>It writes only what an account already is: an e-mail, a client and a role. {@code display_name} is read, checked and left out,
 * because an account has no such field and the file is not a reason to add personal data. It sends nothing: no e-mail is mailed and no
 * sign-in link is made. A second run of the same file changes nothing, and a file that changes a role changes that role.
 *
 * <p>All or nothing. Every row is checked first (the file, then the database); when any row has a problem, every problem is listed
 * with its line and nothing is written, so the file is corrected and run again. An e-mail that belongs to another client is a problem,
 * never a move, and a client that does not exist is a problem unless the caller allows creating it ({@code --create-clients}), so a
 * typed name cannot make a stray client. The log names account ids and counts, never an e-mail.
 */
@Component
public class ConsoleAccountImporter {

    /** The most rows of one file; a pilot has a handful of accounts. */
    public static final int MAX_ROWS = 500;
    public static final int MAX_DISPLAY_NAME_CHARS = 100;

    private static final Logger log = LoggerFactory.getLogger(ConsoleAccountImporter.class);
    private static final Pattern EMAIL = Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+");
    private static final int EMAIL_MAX = 254;

    public enum Action { CREATED, ROLE_UPDATED, UNCHANGED }

    /** One row that passed every check. {@code clientCreated} is true for the first row of a client that is created by this run. */
    public record Row(int line, String email, String client, ConsoleRole role, Action action, boolean clientCreated) {
    }

    /**
     * @param problems   every problem of the file, "line N: ..." in line order; when not empty nothing was written
     * @param emails     the e-mail of every valid row, normalized, in file order, without repeats: the value for
     *                   {@code LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS}
     * @param notAllowed the part of {@code emails} that the current sign-in settings (the allowed e-mails and domains) would refuse;
     *                   empty when the settings are not known here
     * @param displayNamesSeen whether the file had a {@code display_name} column, which is read and not stored
     */
    public record Result(boolean dryRun, List<Row> rows, List<String> problems, List<String> emails, List<String> notAllowed,
                         boolean displayNamesSeen) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public long count(Action action) {
            return rows.stream().filter(row -> row.action() == action).count();
        }
    }

    private record Parsed(int line, String email, ConsoleRole role, String client) {
    }

    private final ConsoleAccountRepository accounts;
    private final ClientRepository clients;
    private final ClientProvisioning provisioning;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ConsoleProperties> properties;

    public ConsoleAccountImporter(ConsoleAccountRepository accounts, ClientRepository clients, ClientProvisioning provisioning,
                                  TransactionTemplate transactions, ObjectProvider<ConsoleProperties> properties) {
        this.accounts = accounts;
        this.clients = clients;
        this.provisioning = provisioning;
        this.transactions = transactions;
        this.properties = properties;
    }

    /**
     * @param text          the file, decoded as UTF-8 (a byte order mark is ignored)
     * @param dryRun        check everything and report what would happen, write nothing
     * @param createClients create a client whose name no client has, instead of refusing the row
     */
    public Result importTable(String text, boolean dryRun, boolean createClients) {
        List<String> problems = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        boolean displayNames = read(text, problems, parsed);
        List<String> emails = parsed.stream().map(Parsed::email).distinct().toList();
        if (!problems.isEmpty() && parsed.isEmpty()) {
            return new Result(dryRun, List.of(), sorted(problems), emails, notAllowed(emails), displayNames);
        }

        Map<String, Client> known = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        Set<String> created = new HashSet<>();
        for (Parsed row : parsed) {
            Client client = known.computeIfAbsent(row.client(), name -> clients.findByName(name).orElse(null));
            boolean clientCreated = false;
            if (client == null) {
                if (!createClients) {
                    problems.add("line " + row.line() + ": client '" + row.client() + "' does not exist; create it with "
                            + "'client create " + row.client() + "' or run the import with --create-clients");
                    continue;
                }
                clientCreated = created.add(row.client());
            }
            Optional<ConsoleAccount> existing = accounts.findByEmail(row.email());
            Action action;
            if (existing.isEmpty()) {
                action = Action.CREATED;
            } else if (client == null || !existing.get().getClientId().equals(client.getId())) {
                problems.add("line " + row.line() + ": this e-mail already belongs to another client; an account is not moved "
                        + "by an import");
                continue;
            } else {
                action = existing.get().getRole() == row.role() ? Action.UNCHANGED : Action.ROLE_UPDATED;
            }
            rows.add(new Row(row.line(), row.email(), row.client(), row.role(), action, clientCreated));
        }
        if (!problems.isEmpty()) {
            return new Result(dryRun, List.of(), sorted(problems), emails, notAllowed(emails), displayNames);
        }
        if (!dryRun) {
            apply(rows);
        }
        return new Result(dryRun, List.copyOf(rows), List.of(), emails, notAllowed(emails), displayNames);
    }

    private void apply(List<Row> rows) {
        for (Row row : rows) {
            if (row.clientCreated()) {
                provisioning.createClient(row.client());
            }
        }
        transactions.executeWithoutResult(status -> {
            int created = 0;
            int updated = 0;
            for (Row row : rows) {
                if (row.action() == Action.UNCHANGED) {
                    continue;
                }
                UUID clientId = clients.findByName(row.client()).orElseThrow().getId();
                if (row.action() == Action.CREATED) {
                    ConsoleAccount account = accounts.saveAndFlush(new ConsoleAccount(row.email(), clientId, row.role()));
                    log.info("Console account {} of client {} created with role {} (account import)", account.getId(), clientId,
                            row.role());
                    created++;
                } else {
                    ConsoleAccount account = accounts.findByEmail(row.email()).orElseThrow();
                    ConsoleRole previous = account.getRole();
                    account.setRole(row.role());
                    accounts.saveAndFlush(account);
                    log.info("Console account {} of client {}: role {} -> {} (account import)", account.getId(), clientId,
                            previous, row.role());
                    updated++;
                }
            }
            log.info("Account import: {} created, {} role updated", created, updated);
        });
    }

    /** The checks that need no database. @return whether the file has a {@code display_name} column */
    private static boolean read(String text, List<String> problems, List<Parsed> parsed) {
        char delimiter = DelimitedText.detectDelimiter(text);
        List<DelimitedText.Row> table;
        try {
            table = DelimitedText.parse(text, delimiter).rows();
        } catch (DelimitedText.FormatException e) {
            problems.add("the file cannot be read: " + e.getMessage());
            return false;
        }
        Map<String, Integer> columns = null;
        int dataRows = 0;
        Map<String, Integer> firstLine = new HashMap<>();
        for (DelimitedText.Row row : table) {
            if (row.blank()) {
                continue;
            }
            if (columns == null) {
                columns = header(row.cells());
                for (String required : List.of("email", "role", "client")) {
                    if (!columns.containsKey(required)) {
                        problems.add("line " + row.line() + ": the header has no '" + required + "' column");
                    }
                }
                if (!problems.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (++dataRows > MAX_ROWS) {
                problems.add("line " + row.line() + ": more than " + MAX_ROWS + " rows; split the file");
                return columns.containsKey("display_name");
            }
            String email = cell(row, columns, "email").toLowerCase(Locale.ROOT);
            String roleCell = cell(row, columns, "role");
            String client = cell(row, columns, "client");
            String displayName = cell(row, columns, "display_name");
            int before = problems.size();
            if (email.isEmpty()) {
                problems.add("line " + row.line() + ": the e-mail is empty");
            } else if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
                problems.add("line " + row.line() + ": '" + shown(email) + "' is not an e-mail address");
            }
            Optional<ConsoleRole> role = ConsoleRole.parse(roleCell);
            if (role.isEmpty()) {
                problems.add("line " + row.line() + ": the role '" + shown(roleCell) + "' is not ADMIN or MEMBER");
            }
            if (!ClientProvisioning.isValidName(client)) {
                problems.add("line " + row.line() + ": '" + shown(client) + "' is not a client name (1 to 32 characters from "
                        + "a-z 0-9 _ - that start with a letter or digit)");
            }
            if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_CHARS) {
                problems.add("line " + row.line() + ": the display name is longer than " + MAX_DISPLAY_NAME_CHARS + " characters");
            }
            if (problems.size() > before) {
                continue;
            }
            Integer earlier = firstLine.putIfAbsent(email, row.line());
            if (earlier != null) {
                problems.add("line " + row.line() + ": the e-mail is already on line " + earlier);
                continue;
            }
            parsed.add(new Parsed(row.line(), email, role.orElseThrow(), client));
        }
        if (columns == null) {
            problems.add("the file has no header row; the first row names the columns: email, role, client, display_name");
            return false;
        }
        if (dataRows == 0) {
            problems.add("the file has a header and no account rows");
        }
        return columns.containsKey("display_name");
    }

    private static Map<String, Integer> header(List<String> cells) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = switch (fold(cells.get(i))) {
                case "email", "emailaddress", "mail" -> "email";
                case "role", "vaitro" -> "role";
                case "client", "clientname", "khachhang" -> "client";
                case "displayname", "name", "hoten", "ten" -> "display_name";
                default -> null;
            };
            if (name != null) {
                columns.putIfAbsent(name, i);
            }
        }
        return columns;
    }

    private static String cell(DelimitedText.Row row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.cells().size() ? "" : row.cells().get(index).strip();
    }

    private static String fold(String header) {
        String decomposed = Normalizer.normalize(header.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        for (char c : decomposed.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c == 'đ') {
                out.append('d');
            }
        }
        return out.toString();
    }

    /** A value of the file in a message, cut so a long cell cannot flood the output. */
    private static String shown(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    private static List<String> sorted(List<String> problems) {
        return problems.stream().sorted(java.util.Comparator.comparingInt(ConsoleAccountImporter::lineOf)).toList();
    }

    private static int lineOf(String problem) {
        if (problem.startsWith("line ")) {
            int end = problem.indexOf(':');
            try {
                return Integer.parseInt(problem.substring(5, end));
            } catch (RuntimeException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private List<String> notAllowed(List<String> emails) {
        ConsoleProperties settings = properties.getIfAvailable();
        if (settings == null) {
            return List.of();
        }
        Set<String> allowedEmails = settings.allowedEmails();
        Set<String> allowedDomains = settings.allowedDomains();
        return emails.stream().filter(email -> {
            int at = email.lastIndexOf('@');
            return !allowedEmails.contains(email) && !(at > 0 && allowedDomains.contains(email.substring(at + 1)));
        }).toList();
    }
}
