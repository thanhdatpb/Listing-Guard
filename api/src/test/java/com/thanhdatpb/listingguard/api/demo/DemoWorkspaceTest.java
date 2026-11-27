package com.thanhdatpb.listingguard.api.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.client.AdminRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DemoWorkspaceTest {
    @Test
    void disabledAdminJobRefusesBothCommands() {
        var runner = new AdminRunner(null, null, null, null);
        for (String command : new String[]{"seed", "reset"}) {
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("demo", command)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Demo workspace is disabled.");
        }
    }

    @Test
    void fixtureCoversBothMarketplacesAndHasUniqueSkus() {
        var fixture = DemoFixtures.listings("listings.csv");
        assertThat(fixture).hasSize(150);
        assertThat(fixture).extracting(DemoFixtures.Listing::sku).doesNotHaveDuplicates();
        assertThat(fixture).extracting(row -> row.marketplace().code()).contains("AMAZON", "ETSY");
        assertThat(fixture).anyMatch(row -> row.title().isEmpty()).anyMatch(row -> row.title().contains("Zibblefrax"));
    }

    @Test
    void demoClockRestoresRealTimeWhenSeedFails() {
        DemoClock clock = new DemoClock();
        Instant fake = Instant.parse("2026-01-01T12:00:00Z");
        assertThatThrownBy(() -> clock.at(fake, () -> {
            assertThat(clock.withZone(ZoneOffset.ofHours(7)).instant()).isEqualTo(fake);
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(clock.instant()).isNotEqualTo(fake);
    }

    @Test
    void localExampleUsesOnlyDeclaredApplicationAndComposeVariables() throws Exception {
        Path root = Files.isDirectory(Path.of("deploy")) ? Path.of(".") : Path.of("..");
        String app = Files.readString(root.resolve("api/src/main/resources/application.yml"));
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        var declarations = Pattern.compile("^(\\w+)=(.*)$");
        for (String line : Files.readAllLines(root.resolve("deploy/env.demo-local.example"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var entry = declarations.matcher(line);
            assertThat(entry.matches()).as(line).isTrue();
            String key = entry.group(1);
            if (key.startsWith("LISTING_GUARD_")) {
                assertThat(app).as(key).contains("${" + key + ":");
            }
            if (!key.equals("COMPOSE_PROJECT_NAME")) {
                assertThat(compose).as(key).contains("${" + key);
            }
        }
    }
}
