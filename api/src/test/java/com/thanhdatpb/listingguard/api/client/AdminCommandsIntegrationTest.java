package com.thanhdatpb.listingguard.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.security.ClientAuthenticator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The admin commands against the real schema, and the admin profile as the one-shot job it is. */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Testcontainers
class AdminCommandsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> JOB_DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientAuthenticator authenticator;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("com.thanhdatpb.listingguard.audit");
    private ListAppender<ILoggingEvent> audit;
    private ByteArrayOutputStream captured;
    private AdminCommands commands;

    @BeforeEach
    void setUp() {
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
        captured = new ByteArrayOutputStream();
        commands = new AdminCommands(provisioning, new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(audit);
    }

    private static String newName() {
        return "adm" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String run(String... args) {
        captured.reset();
        commands.execute(new DefaultApplicationArguments(args));
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String secretIn(String output) {
        return output.lines().filter(line -> line.startsWith("lg_")).findFirst().orElseThrow();
    }

    /** The key id is the second word of the first line: "Key abcd1234 created for client ...". */
    private static String keyIdIn(String output) {
        return output.lines().findFirst().orElseThrow().split(" ")[1];
    }

    @Test
    void createsAClientThenAKeyThatWorksAndPrintsItsSecretOnlyOnce() {
        String name = newName();

        assertThat(run("client", "create", name)).contains("Client '" + name + "' created");
        String created = run("key", "create", name, "--label=primary key");

        String secret = secretIn(created);
        assertThat(created).contains("shown only now");
        assertThat(authenticator.authenticate(secret)).hasValueSatisfying(client -> assertThat(client.name()).isEqualTo(name));

        String listing = run("key", "list", name);
        assertThat(listing).contains("primary key").doesNotContain(secret).doesNotContain(ApiKeyHasher.sha256Hex(secret));
        assertThat(run("client", "list")).contains(name);
    }

    @Test
    void deactivatesAndActivatesAClient() {
        String name = newName();
        run("client", "create", name);
        String secret = secretIn(run("key", "create", name));

        assertThat(run("client", "deactivate", name)).contains("inactive");
        assertThat(authenticator.authenticate(secret)).isEmpty();
        assertThat(run("client", "activate", name)).contains("is active");
        assertThat(authenticator.authenticate(secret)).isPresent();
    }

    @Test
    void revokesAKeyById() {
        String name = newName();
        run("client", "create", name);
        String created = run("key", "create", name);

        assertThat(run("key", "revoke", keyIdIn(created))).contains("is revoked");

        assertThat(authenticator.authenticate(secretIn(created))).isEmpty();
        assertThat(run("key", "list", name)).contains(keyIdIn(created));
    }

    @Test
    void everyChangingCommandIsAuditedWithClientKeyIdAndTimeAndNoSecret() {
        String name = newName();
        run("client", "create", name);
        String created = run("key", "create", name);
        String secret = secretIn(created);
        String keyId = keyIdIn(created);
        run("client", "deactivate", name);
        run("client", "activate", name);
        run("key", "revoke", keyId);
        run("client", "list");
        run("key", "list", name);

        List<String> lines = audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).hasSize(5).allSatisfy(line -> assertThat(line).contains("client=" + name)
                .containsPattern(" at=\\d{4}-\\d{2}-\\d{2}T").doesNotContain(secret));
        assertThat(lines.get(1)).contains("action=key.create").contains("keyId=" + keyId);
        assertThat(lines.get(4)).contains("action=key.revoke").contains("keyId=" + keyId);
    }

    @Test
    void refusesWhatIsUnknownIncompleteOrInvalidWithAMessageAndTheUsage() {
        assertThatThrownBy(() -> run()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing command").hasMessageContaining("Usage:");
        assertThatThrownBy(() -> run("client", "frobnicate")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown command");
        assertThatThrownBy(() -> run("client", "create")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing a client name");
        assertThatThrownBy(() -> run("client", "create", "Bad Name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run("key", "create", newName())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No client named");
    }

    @Test
    void theAdminProfileRunsOneCommandWithoutAWebServerAndEnds() {
        String name = newName();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ListingGuardApplication.class).run(
                "--spring.profiles.active=admin", "--spring.datasource.url=" + JOB_DATABASE.getJdbcUrl(),
                "--spring.datasource.username=" + JOB_DATABASE.getUsername(), "--spring.datasource.password=" + JOB_DATABASE.getPassword(),
                "client", "create", name)) {
            assertThat(context.getClass().getName()).doesNotContain("Web");
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM client WHERE name = ?", Integer.class, name))
                    .isEqualTo(1);
        }
    }
}
