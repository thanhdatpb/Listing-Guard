package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The same synthetic marks in {@link InMemoryTrademarkCatalog} and in {@link PostgresTrademarkCatalog} must answer
 * the same. This is what makes the scanner tests, which use the in-memory catalog, evidence about the database.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PostgresTrademarkCatalogContractTest {

    private static final List<String> EXACT_FORMS = List.of("zorvex", "zorvix", "blue harbor tools", "brask",
            "quillmark", "zork", "harbor light", "zorvex tools", "blue harbor", "missing", "old brand", "other class");

    private static final List<String> FUZZY_FORMS = List.of("zorvx", "zorvexa", "zorveks", "quilmark", "quillmarc",
            "blue harbor tool", "blue harbour tools", "harbor", "brisk", "braks", "plain", "zork", "zorvex",
            "harbor lite", "xylophone");

    private static final List<Double> THRESHOLDS = List.of(0.2, 0.3, 0.45, 0.6, 0.8);

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    DataSource dataSource;

    @TempDir
    Path dir;

    private TrademarkCatalog postgres;
    private TrademarkCatalog inMemory;

    @BeforeEach
    void importTheSyntheticMarks() {
        String label = "contract-" + UUID.randomUUID().toString().substring(0, 8);
        TrademarkFixtures.syntheticDir(dir, label);
        PreparedCatalog prepared = TrademarkFixtures.prepare(dir, label, rulesets.ruleset());
        TrademarkCatalogImporter.Result result = TrademarkFixtures.importCatalog(importer, dir, label, rulesets.ruleset());
        postgres = new PostgresTrademarkCatalog(jdbc, transactions, result.catalogVersionId(), prepared.version());
        inMemory = new InMemoryTrademarkCatalog(prepared.version(),
                prepared.entries().stream().map(PreparedCatalog.Entry::mark).toList());
    }

    @Test
    void findExactAnswersTheSame() {
        assertThat(postgres.findExact(EXACT_FORMS)).isEqualTo(inMemory.findExact(EXACT_FORMS));
        assertThat(postgres.findExact(EXACT_FORMS)).containsOnlyKeys("zorvex", "zorvix", "blue harbor tools", "brask",
                "quillmark", "zork", "harbor light");
        assertThat(postgres.findExact(List.of())).isEmpty();
    }

    @Test
    void theMetadataArrivesWithTheMark() {
        CatalogMark zorvex = postgres.findExact(List.of("zorvex")).get("zorvex");

        assertThat(zorvex.display()).isEqualTo("ZORVEX");
        assertThat(zorvex.niceClasses()).containsExactly(25, 35);
        assertThat(zorvex.status().name()).isEqualTo("REGISTERED");
        assertThat(zorvex.commonWord()).isTrue();
        assertThat(zorvex.sourceIds()).containsExactly("S-001", "S-002");
        assertThat(postgres.findExact(List.of("brask")).get("brask").sourceIds()).containsExactly("S-006", "S-007");
        assertThat(postgres.findExact(List.of("quillmark")).get("quillmark").commonWord()).isFalse();
    }

    @Test
    void fuzzyCandidatesAnswerTheSameAtEveryThreshold() {
        for (double threshold : THRESHOLDS) {
            Map<String, List<CatalogMark>> fromDatabase = postgres.fuzzyCandidates(FUZZY_FORMS, threshold);
            Map<String, List<CatalogMark>> fromMemory = inMemory.fuzzyCandidates(FUZZY_FORMS, threshold);

            assertThat(fromDatabase).as("threshold %s", threshold).isEqualTo(fromMemory);
            assertThat(List.copyOf(fromDatabase.keySet())).as("form order at %s", threshold)
                    .isEqualTo(List.copyOf(fromMemory.keySet()));
        }
        assertThat(postgres.fuzzyCandidates(FUZZY_FORMS, 0.3).get("zorvx")).extracting(CatalogMark::normalized)
                .containsExactly("zork", "zorvex", "zorvix");
        assertThat(postgres.fuzzyCandidates(List.of(), 0.3)).isEmpty();
    }

    @Test
    void theThresholdIsTheOneThatWasPassed() {
        Map<String, List<CatalogMark>> loose = postgres.fuzzyCandidates(List.of("zorvx"), 0.2);
        Map<String, List<CatalogMark>> strict = postgres.fuzzyCandidates(List.of("zorvx"), 0.8);

        assertThat(loose.get("zorvx")).hasSizeGreaterThan(1);
        assertThat(strict).isEmpty();
    }

    @Test
    void theSessionSettingOfThresholdChangesNothing() throws SQLException {
        Map<String, List<CatalogMark>> expected = postgres.fuzzyCandidates(FUZZY_FORMS, 0.3);
        List<Connection> held = new ArrayList<>();
        try {
            // set a strange value on every pooled connection, so whichever one the adapter gets has it
            for (int i = 0; i < 10; i++) {
                Connection connection = dataSource.getConnection();
                held.add(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET pg_trgm.similarity_threshold = 0.99");
                }
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        assertThat(postgres.fuzzyCandidates(FUZZY_FORMS, 0.3)).isEqualTo(expected);
        assertThat(postgres.fuzzyCandidates(List.of("zorvx"), 0.2).get("zorvx")).hasSizeGreaterThan(1);
        // and it does not leak its own value into the session: the local setting ended with the transaction
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT current_setting('pg_trgm.similarity_threshold')");
            var rows = statement.getResultSet();
            rows.next();
            assertThat(rows.getString(1)).isIn("0.99", "0.3");
            statement.execute("RESET pg_trgm.similarity_threshold");
        }
    }
}
