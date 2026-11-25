package com.thanhdatpb.listingguard.api.client;

import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountImporter;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.editor.PodProfileAdminCommands;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.CatalogAdminCommands;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/**
 * The commands of the admin job. Results go to the given stream; the audit trail (client, key id, time) is written
 * by {@link ClientProvisioning}. The only place a secret is printed is the output of {@code key create}, once.
 *
 * <pre>
 * client create &lt;name&gt;           client list
 * client activate &lt;name&gt;         client deactivate &lt;name&gt;
 * key create &lt;client&gt; [--label=&lt;text&gt;]     key list &lt;client&gt;     key revoke &lt;keyId&gt;
 * plan set &lt;client&gt; &lt;plan&gt;      (ADR-013: FREE, SELLER or PRO)
 * catalog list     catalog activate &lt;version&gt;     catalog rollback [--to=&lt;version&gt;]   (ADR-009, PR A2d)
 * account list &lt;client&gt;     account role &lt;email&gt; &lt;ADMIN|MEMBER&gt;   (ADR-031)
 * account import &lt;file.csv&gt; [--dry-run] [--create-clients]   (ADR-053, accounts of a pilot from one file)
 * pod-profile import &lt;file.csv&gt; [--out=&lt;dir&gt;]   (ADR-048, a supplier spec file into new POD profile versions)
 * </pre>
 */
public final class AdminCommands {

    static final String USAGE = """
            Usage:
              client create <name>
              client list
              client activate <name>
              client deactivate <name>
              key create <client> [--label=<text>]
              key list <client>
              key revoke <keyId>
              plan set <client> <plan>   (plan: FREE, SELLER or PRO)
              account list <client>
              account role <email> <role>   (role: ADMIN or MEMBER; an ADMIN edits the client's trademark watchlist in the console)
              account import <file.csv> [--dry-run] [--create-clients]   (columns email, role, client, display_name; ADR-053)
            """ + CatalogAdminCommands.USAGE + PodProfileAdminCommands.USAGE;

    private final ClientProvisioning provisioning;
    private final CatalogAdminCommands catalog;
    private final ConsoleAccountRoles accounts;
    private final ConsoleAccountImporter importer;
    private final PrintStream out;

    /** Without the {@code catalog} commands. */
    public AdminCommands(ClientProvisioning provisioning, PrintStream out) {
        this(provisioning, null, out);
    }

    /** @param catalog the {@code catalog} commands (ADR-009, PR A2d); null leaves them out */
    public AdminCommands(ClientProvisioning provisioning, CatalogAdminCommands catalog, PrintStream out) {
        this(provisioning, catalog, null, out);
    }

    /** @param accounts the {@code account} commands (ADR-031); null leaves them out */
    public AdminCommands(ClientProvisioning provisioning, CatalogAdminCommands catalog, ConsoleAccountRoles accounts,
                         PrintStream out) {
        this(provisioning, catalog, accounts, null, out);
    }

    /** @param importer {@code account import} (ADR-053); null leaves it out */
    public AdminCommands(ClientProvisioning provisioning, CatalogAdminCommands catalog, ConsoleAccountRoles accounts,
                         ConsoleAccountImporter importer, PrintStream out) {
        this.provisioning = provisioning;
        this.catalog = catalog;
        this.accounts = accounts;
        this.importer = importer;
        this.out = out;
    }

    /** @throws IllegalArgumentException for a command that is unknown, incomplete or refused; the message says why */
    public void execute(ApplicationArguments arguments) {
        List<String> args = arguments.getNonOptionArgs();
        if (args.size() < 2) {
            throw usage("Missing command.");
        }
        String group = args.get(0);
        String command = args.get(1);
        switch (group + " " + command) {
            case "client create" -> {
                Client client = provisioning.createClient(argument(args, 2, "a client name"));
                out.println("Client '" + client.getName() + "' created. Create its first key with: key create " + client.getName());
            }
            case "client list" -> listClients();
            case "client activate" -> {
                Client client = provisioning.setActive(argument(args, 2, "a client name"), true);
                out.println("Client '" + client.getName() + "' is active.");
            }
            case "client deactivate" -> {
                Client client = provisioning.setActive(argument(args, 2, "a client name"), false);
                out.println("Client '" + client.getName() + "' is inactive; its keys stop working within the cache time.");
            }
            case "key create" -> createKey(argument(args, 2, "a client name"), arguments);
            case "key list" -> listKeys(argument(args, 2, "a client name"));
            case "key revoke" -> {
                ApiKey key = provisioning.revokeKey(argument(args, 2, "a key id"));
                out.println("Key " + key.getKeyId() + " is revoked; it stops working within the cache time.");
            }
            case "plan set" -> setPlan(argument(args, 2, "a client name"), argument(args, 3, "a plan"));
            case "catalog list", "catalog activate", "catalog rollback" -> {
                if (catalog == null) {
                    throw usage("The catalog commands are not available here.");
                }
                try {
                    catalog.execute(command, args.subList(2, args.size()), arguments, out);
                } catch (IllegalArgumentException e) {
                    throw usage(e.getMessage());
                }
            }
            case "pod-profile import" -> {
                try {
                    PodProfileAdminCommands.execute(command, args.subList(2, args.size()), arguments, out);
                } catch (IllegalArgumentException e) {
                    throw usage(e.getMessage());
                }
            }
            case "account list" -> listAccounts(argument(args, 2, "a client name"));
            case "account role" -> setRole(argument(args, 2, "an email"), argument(args, 3, "a role"));
            case "account import" -> importAccounts(argument(args, 2, "a file"), arguments);
            default -> throw usage("Unknown command '" + group + " " + command + "'.");
        }
    }

    private void createKey(String clientName, ApplicationArguments arguments) {
        List<String> labels = arguments.getOptionValues("label");
        ClientProvisioning.IssuedKey issued = provisioning.createKey(clientName, labels == null || labels.isEmpty() ? null : labels.get(0));
        out.println("Key " + issued.keyId() + " created for client '" + clientName + "'.");
        out.println("API key (shown only now; store it safely, it cannot be read again):");
        out.println(issued.secret());
    }

    private void listClients() {
        List<Client> clients = provisioning.clients();
        if (clients.isEmpty()) {
            out.println("No clients.");
            return;
        }
        out.println(String.format("%-32s %-8s %-8s %s", "NAME", "ACTIVE", "PLAN", "CREATED"));
        clients.forEach(client -> out.println(String.format("%-32s %-8s %-8s %s", client.getName(), client.isActive(),
                client.getPlanCode(), client.getCreatedAt())));
    }

    private void setPlan(String clientName, String planArgument) {
        PlanCode plan = PlanCode.parse(planArgument).orElseThrow(() -> usage("Unknown plan '" + planArgument
                + "'. The plans are " + Arrays.toString(PlanCode.values()) + "."));
        String previous = provisioning.setPlan(clientName, plan);
        if (previous.equals(plan.name())) {
            out.println("Client '" + clientName + "' is already on the " + plan + " plan; nothing changed.");
        } else {
            out.println("Client '" + clientName + "' moved from the " + previous + " plan to the " + plan
                    + " plan. It takes effect at once; the quota is only applied where listing-guard.plans.enabled is true.");
        }
    }

    private ConsoleAccountRoles accounts() {
        if (accounts == null) {
            throw usage("The account commands are not available here.");
        }
        return accounts;
    }

    private void listAccounts(String clientName) {
        List<ConsoleAccount> list;
        try {
            list = accounts().accounts(clientName);
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        }
        if (list.isEmpty()) {
            out.println("Client '" + clientName + "' has no console accounts.");
            return;
        }
        out.println(String.format("%-40s %-8s %s", "EMAIL", "ROLE", "ID"));
        list.forEach(account -> out.println(String.format("%-40s %-8s %s", account.getEmail(), account.getRole(),
                account.getId())));
    }

    private void setRole(String email, String roleArgument) {
        ConsoleRole role = ConsoleRole.parse(roleArgument).orElseThrow(() -> usage("Unknown role '" + roleArgument
                + "'. The roles are " + Arrays.toString(ConsoleRole.values()) + "."));
        ConsoleRole previous;
        try {
            previous = accounts().setRole(email, role);
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        }
        out.println(previous == role ? "The account is already " + role + "; nothing changed."
                : "The account is now " + role + " (was " + previous + "). It takes effect on its next console page.");
    }

    /** The most bytes of an account file; a pilot has a handful of accounts. */
    private static final long IMPORT_MAX_BYTES = 1_000_000;

    /**
     * {@code account import <file.csv> [--dry-run] [--create-clients]} (ADR-053). Prints one line for each row, the counts and the
     * e-mails to paste into {@code LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS}. A file with a problem is listed line by line and
     * nothing is written. It sends no e-mail.
     */
    private void importAccounts(String file, ApplicationArguments arguments) {
        if (importer == null) {
            throw usage("The account commands are not available here.");
        }
        String text;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(file);
            if (java.nio.file.Files.size(path) > IMPORT_MAX_BYTES) {
                throw new IllegalArgumentException("The file is larger than " + IMPORT_MAX_BYTES + " bytes.");
            }
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(path))).toString();
        } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("The file '" + file + "' cannot be read as UTF-8 text.");
        }
        ConsoleAccountImporter.Result result = importer.importTable(text, arguments.containsOption("dry-run"),
                arguments.containsOption("create-clients"));
        if (!result.ok()) {
            result.problems().forEach(problem -> out.println("Problem, " + problem));
            throw new IllegalArgumentException("Nothing was imported: " + result.problems().size()
                    + " problem(s) in the file. Correct the file and run the command again.");
        }
        String dry = result.dryRun() ? "[dry run] " : "";
        for (ConsoleAccountImporter.Row row : result.rows()) {
            String what = switch (row.action()) {
                case CREATED -> result.dryRun() ? "would create" : "created";
                case ROLE_UPDATED -> result.dryRun() ? "would set the role of" : "role updated for";
                case UNCHANGED -> "unchanged";
            };
            out.println(dry + "Line " + row.line() + ": " + what + " " + row.email() + " (" + row.role() + ", client "
                    + row.client() + (row.clientCreated() ? ", client " + (result.dryRun() ? "would be created" : "created") : "")
                    + ")");
        }
        out.println(dry + result.rows().size() + " row(s): " + result.count(ConsoleAccountImporter.Action.CREATED) + " created, "
                + result.count(ConsoleAccountImporter.Action.ROLE_UPDATED) + " role updated, "
                + result.count(ConsoleAccountImporter.Action.UNCHANGED) + " unchanged. No e-mail was sent.");
        if (result.displayNamesSeen()) {
            out.println("display_name was read and not stored: an account has no such field.");
        }
        out.println("For LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS (add these, then recreate the api service):");
        out.println(String.join(",", result.emails()));
        if (!result.notAllowed().isEmpty()) {
            out.println("Not allowed to sign in with the current settings: " + String.join(", ", result.notAllowed()));
        }
    }

    private void listKeys(String clientName) {
        List<ApiKey> keys = provisioning.keys(clientName);
        if (keys.isEmpty()) {
            out.println("Client '" + clientName + "' has no keys.");
            return;
        }
        out.println(String.format("%-10s %-8s %-30s %-30s %-30s %s", "KEY ID", "ACTIVE", "CREATED", "LAST USED", "REVOKED", "LABEL"));
        keys.forEach(key -> out.println(String.format("%-10s %-8s %-30s %-30s %-30s %s", key.getKeyId(), key.isActive(),
                key.getCreatedAt(), key.getLastUsedAt() == null ? "-" : key.getLastUsedAt(),
                key.getRevokedAt() == null ? "-" : key.getRevokedAt(), key.getLabel() == null ? "" : key.getLabel())));
    }

    private static String argument(List<String> args, int index, String what) {
        if (args.size() <= index) {
            throw usage("Missing " + what + ".");
        }
        return args.get(index);
    }

    private static IllegalArgumentException usage(String problem) {
        return new IllegalArgumentException(problem + "\n" + USAGE);
    }
}
