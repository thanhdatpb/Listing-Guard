package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.AdminCommands;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountImporter.Action;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountImporter.Result;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code account import} (ADR-053) against the real schema: the accounts of a pilot from one file, run twice, with a wrong role, a
 * repeated e-mail, an e-mail of another client, an unknown client, a dry run and the admin command that prints the list for
 * {@code LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS}. Invented e-mails and client names only.
 */
@SpringBootTest(properties = "listing-guard.console.access-allowed-emails=listed@pilot.example.test")
@Import(TestcontainersConfig.class)
class ConsoleAccountImportIntegrationTest {

    @Autowired
    ConsoleAccountImporter importer;

    @Autowired
    ConsoleAccountRepository accounts;

    @Autowired
    ClientRepository clients;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ConsoleAccountRoles roles;

    @Autowired
    JdbcTemplate jdbc;

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String client() {
        return "pilot" + suffix();
    }

    private long accountCount(String client) {
        return jdbc.queryForObject("SELECT count(*) FROM account a JOIN client c ON c.id = a.client_id WHERE c.name = ?", Long.class,
                client);
    }

    private static String file(String client, String id) {
        return "email,role,display_name,client\n"
                + "lead-" + id + "@pilot.example.test,ADMIN,Lead,\"" + client + "\"\n"
                + "Seller-One-" + id + "@pilot.example.test,member,Seller One," + client + "\n"
                + "seller-two-" + id + "@pilot.example.test,MEMBER,," + client + "\n";
    }

    @Test
    void importsTheAccountsOfAFileAndRunningItAgainChangesNothing() {
        String client = client();
        String id = suffix();

        Result first = importer.importTable(file(client, id), false, true);

        assertThat(first.ok()).isTrue();
        assertThat(first.rows()).extracting(ConsoleAccountImporter.Row::action).containsOnly(Action.CREATED);
        assertThat(first.rows().get(0).clientCreated()).as("the client is made by the first row that names it").isTrue();
        assertThat(first.rows().get(1).clientCreated()).isFalse();
        assertThat(first.emails()).containsExactly("lead-" + id + "@pilot.example.test", "seller-one-" + id + "@pilot.example.test",
                "seller-two-" + id + "@pilot.example.test");
        assertThat(first.displayNamesSeen()).isTrue();
        assertThat(accountCount(client)).isEqualTo(3);
        assertThat(accounts.findByEmail("seller-one-" + id + "@pilot.example.test").orElseThrow().getRole())
                .as("the role is read in any case and the e-mail in lower case").isEqualTo(ConsoleRole.MEMBER);
        assertThat(accounts.findByEmail("lead-" + id + "@pilot.example.test").orElseThrow().getRole()).isEqualTo(ConsoleRole.ADMIN);

        List<UUID> ids = accounts.findByClientIdOrderByEmail(clients.findByName(client).orElseThrow().getId()).stream()
                .map(ConsoleAccount::getId).toList();
        Result second = importer.importTable(file(client, id), false, true);

        assertThat(second.ok()).isTrue();
        assertThat(second.rows()).extracting(ConsoleAccountImporter.Row::action).containsOnly(Action.UNCHANGED);
        assertThat(second.rows()).extracting(ConsoleAccountImporter.Row::clientCreated).containsOnly(false);
        assertThat(accountCount(client)).as("no second copy").isEqualTo(3);
        assertThat(accounts.findByClientIdOrderByEmail(clients.findByName(client).orElseThrow().getId()).stream()
                .map(ConsoleAccount::getId).toList()).as("the same accounts").isEqualTo(ids);
    }

    @Test
    void aChangedRoleIsUpdatedAndKeepsTheAccount() {
        String client = client();
        String id = suffix();
        importer.importTable(file(client, id), false, true);
        UUID accountId = accounts.findByEmail("seller-two-" + id + "@pilot.example.test").orElseThrow().getId();

        Result result = importer.importTable(file(client, id).replace("seller-two-" + id + "@pilot.example.test,MEMBER",
                "seller-two-" + id + "@pilot.example.test,ADMIN"), false, false);

        assertThat(result.ok()).isTrue();
        assertThat(result.count(Action.ROLE_UPDATED)).isEqualTo(1);
        assertThat(result.count(Action.UNCHANGED)).isEqualTo(2);
        assertThat(accounts.findById(accountId).orElseThrow().getRole()).isEqualTo(ConsoleRole.ADMIN);
    }

    @Test
    void aWrongRoleRefusesTheWholeFileAndWritesNothing() {
        String client = client();
        String id = suffix();
        String text = file(client, id) + "owner-" + id + "@pilot.example.test,OWNER,," + client + "\n";

        Result result = importer.importTable(text, false, true);

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).containsExactly("line 5: the role 'OWNER' is not ADMIN or MEMBER");
        assertThat(result.rows()).isEmpty();
        assertThat(clients.findByName(client)).as("not even the client").isEmpty();
        assertThat(accounts.findByEmail("lead-" + id + "@pilot.example.test")).isEmpty();
    }

    @Test
    void aRepeatedEmailInTheFileIsAProblemWhateverItsCase() {
        String client = client();
        String id = suffix();
        String text = "email,role,client\n"
                + "same-" + id + "@pilot.example.test,ADMIN," + client + "\n"
                + "SAME-" + id + "@Pilot.Example.Test,MEMBER," + client + "\n";

        Result result = importer.importTable(text, false, true);

        assertThat(result.problems()).containsExactly("line 3: the e-mail is already on line 2");
        assertThat(accountCount(client)).isZero();
    }

    @Test
    void everyProblemOfTheFileIsListedWithItsLineInLineOrder() {
        String text = "email,role,client\n"
                + "not-an-email,ADMIN,pilotx\n"
                + "ok-" + suffix() + "@pilot.example.test,,pilotx\n"
                + "ok2-" + suffix() + "@pilot.example.test,MEMBER,Bad Client!\n"
                + ",MEMBER,pilotx\n";

        Result result = importer.importTable(text, false, true);

        assertThat(result.problems()).hasSize(4);
        assertThat(result.problems().get(0)).startsWith("line 2: 'not-an-email' is not an e-mail address");
        assertThat(result.problems().get(1)).startsWith("line 3: the role '' is not ADMIN or MEMBER");
        assertThat(result.problems().get(2)).startsWith("line 4: 'Bad Client!' is not a client name");
        assertThat(result.problems().get(3)).isEqualTo("line 5: the e-mail is empty");
    }

    @Test
    void theHeaderMustNameEmailRoleAndClient() {
        Result noRole = importer.importTable("email,client\na@pilot.example.test,x\n", false, true);
        Result empty = importer.importTable("", false, true);
        Result headerOnly = importer.importTable("email,role,client\n", false, true);

        assertThat(noRole.problems()).containsExactly("line 1: the header has no 'role' column");
        assertThat(empty.ok()).isFalse();
        assertThat(headerOnly.problems()).containsExactly("the file has a header and no account rows");
    }

    @Test
    void aClientThatDoesNotExistIsAProblemUnlessTheCallerAllowsCreatingIt() {
        String client = client();
        String id = suffix();
        String text = "email,role,client\nnew-" + id + "@pilot.example.test,MEMBER," + client + "\n";

        Result refused = importer.importTable(text, false, false);

        assertThat(refused.problems()).hasSize(1);
        assertThat(refused.problems().get(0)).contains("client '" + client + "' does not exist").contains("--create-clients");
        assertThat(clients.findByName(client)).isEmpty();
        Result dry = importer.importTable(text, true, true);
        assertThat(dry.ok()).isTrue();
        assertThat(dry.rows().get(0).clientCreated()).isTrue();
        assertThat(clients.findByName(client)).as("a dry run writes nothing").isEmpty();
        assertThat(accounts.findByEmail("new-" + id + "@pilot.example.test")).isEmpty();
        assertThat(importer.importTable(text, false, true).ok()).isTrue();
        assertThat(clients.findByName(client)).isPresent();
    }

    @Test
    void anEmailOfAnotherClientIsNeverMoved() {
        String first = client();
        String second = client();
        String id = suffix();
        importer.importTable("email,role,client\nmoved-" + id + "@pilot.example.test,MEMBER," + first + "\n", false, true);

        Result result = importer.importTable("email,role,client\nmoved-" + id + "@pilot.example.test,ADMIN," + second + "\n",
                false, true);

        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0)).startsWith("line 2:").contains("belongs to another client");
        assertThat(clients.findByName(second)).as("no stray client either").isEmpty();
        assertThat(accountCount(first)).isEqualTo(1);
        assertThat(accounts.findByEmail("moved-" + id + "@pilot.example.test").orElseThrow().getRole()).isEqualTo(ConsoleRole.MEMBER);
    }

    @Test
    void theDisplayNameIsNotStoredBecauseAnAccountHasNoSuchField() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'account'",
                String.class);

        assertThat(columns).containsExactlyInAnyOrder("id", "email", "client_id", "created_at", "role");
    }

    @Test
    void theEmailsThatTheSignInSettingsWouldRefuseAreListed() {
        String id = suffix();
        String text = "email,role,client\nlisted@pilot.example.test,ADMIN,x" + id + "\nother-" + id + "@pilot.example.test,MEMBER,x" + id
                + "\n";

        Result result = importer.importTable(text, true, true);

        assertThat(result.notAllowed()).containsExactly("other-" + id + "@pilot.example.test");
    }

    // ---- the admin command

    @Test
    void theAdminCommandPrintsOneLinePerRowAndTheListForTheAllowedEmails(@TempDir Path directory) throws IOException {
        String client = client();
        String id = suffix();
        Path file = directory.resolve("accounts.csv");
        Files.writeString(file, "﻿" + file(client, id), StandardCharsets.UTF_8);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AdminCommands commands = new AdminCommands(provisioning, null, roles, importer,
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        commands.execute(new DefaultApplicationArguments("account", "import", file.toString(), "--create-clients", "--dry-run"));
        String dry = captured.toString(StandardCharsets.UTF_8);
        assertThat(dry).contains("[dry run] Line 2: would create lead-" + id + "@pilot.example.test (ADMIN, client " + client);
        assertThat(accountCount(client)).as("a dry run").isZero();

        captured.reset();
        commands.execute(new DefaultApplicationArguments("account", "import", file.toString(), "--create-clients"));
        String done = captured.toString(StandardCharsets.UTF_8);
        assertThat(done).contains("Line 2: created lead-" + id + "@pilot.example.test")
                .contains("3 row(s): 3 created, 0 role updated, 0 unchanged. No e-mail was sent.")
                .contains("display_name was read and not stored")
                .contains("LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS")
                .contains("lead-" + id + "@pilot.example.test,seller-one-" + id + "@pilot.example.test,seller-two-" + id
                        + "@pilot.example.test");

        captured.reset();
        commands.execute(new DefaultApplicationArguments("account", "import", file.toString()));
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("3 row(s): 0 created, 0 role updated, 3 unchanged.");
    }

    @Test
    void theAdminCommandFailsWithTheProblemsAndWritesNothingForAFileWithAWrongRole(@TempDir Path directory) throws IOException {
        String client = client();
        Path file = directory.resolve("accounts.csv");
        Files.writeString(file, "email,role,client\nbad-" + suffix() + "@pilot.example.test,BOSS," + client + "\n");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AdminCommands commands = new AdminCommands(provisioning, null, roles, importer,
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        assertThatThrownBy(() -> commands.execute(new DefaultApplicationArguments("account", "import", file.toString(),
                "--create-clients"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing was imported: 1 problem(s)");

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Problem, line 2: the role 'BOSS' is not ADMIN or MEMBER");
        assertThat(clients.findByName(client)).isEmpty();
        assertThatThrownBy(() -> commands.execute(new DefaultApplicationArguments("account", "import",
                directory.resolve("missing.csv").toString()))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be read as UTF-8 text");
    }
}
