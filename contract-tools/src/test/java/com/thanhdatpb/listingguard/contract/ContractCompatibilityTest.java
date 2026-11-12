package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate of ADR-022: the committed contract ({@code docs/api/openapi.json}, and {@code docs/api/input-schema.json} once a
 * release shipped it) is compared with the baseline of the latest published release in {@code docs/api/baseline/}. Any breaking
 * change that {@code docs/api/breaking-changes.yaml} does not declare fails the build. The full report, with the additive changes
 * for the release notes, is printed and written to {@code target/contract/compatibility-report.md}.
 *
 * <p>Fast form, no Docker: {@code ./mvnw -B -pl contract-tools verify}.
 */
class ContractCompatibilityTest {

    private static final Path API = Path.of("..", "docs", "api");
    private static final Path ADR = Path.of("..", "docs", "adr");
    private static final Path REPORT = Path.of("target", "contract", "compatibility-report.md");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCurrentContractIsCompatibleWithTheLatestRelease() throws IOException {
        ContractBaseline baseline = ContractBaseline.find(API.resolve("baseline"));
        List<Change> openApiChanges = CompatibilityChecker.compareOpenApi(read(baseline.openApi()), read(API.resolve("openapi.json")));
        List<Change> inputChanges = baseline.inputSchema().isPresent()
                ? CompatibilityChecker.compareInputSchemas(read(baseline.inputSchema().get()), read(API.resolve("input-schema.json")))
                : null;
        BreakingChangeExceptions exceptions = BreakingChangeExceptions.load(API.resolve("breaking-changes.yaml"));

        String report = CompatibilityReport.markdown(baseline, openApiChanges, inputChanges, exceptions);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);
        System.out.println(report);

        List<Change> all = inputChanges == null ? openApiChanges
                : java.util.stream.Stream.concat(openApiChanges.stream(), inputChanges.stream()).toList();
        assertThat(exceptions.problems(baseline.version(), all, ADR))
                .as("ADR-022: the contract has breaking changes against " + baseline.version()
                        + " that docs/api/breaking-changes.yaml does not declare, or that file is wrong. Make the change additive, or "
                        + "declare it with a reason and an ADR amendment. Report: contract-tools/" + REPORT.toString().replace('\\', '/'))
                .isEmpty();
    }

    private JsonNode read(Path file) throws IOException {
        return mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }
}
