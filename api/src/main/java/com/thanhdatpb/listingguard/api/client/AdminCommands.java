package com.thanhdatpb.listingguard.api.client;

import java.io.PrintStream;
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
              key revoke <keyId>""";

    private final ClientProvisioning provisioning;
    private final PrintStream out;

    public AdminCommands(ClientProvisioning provisioning, PrintStream out) {
        this.provisioning = provisioning;
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
        out.println(String.format("%-32s %-8s %s", "NAME", "ACTIVE", "CREATED"));
        clients.forEach(client -> out.println(String.format("%-32s %-8s %s", client.getName(), client.isActive(), client.getCreatedAt())));
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
