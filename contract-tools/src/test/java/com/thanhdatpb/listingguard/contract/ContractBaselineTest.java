package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The baseline folder of ADR-022: one OpenAPI document of one release, and at most one input schema of the same release. */
class ContractBaselineTest {

    @TempDir
    Path directory;

    private void touch(String name) throws IOException {
        Files.writeString(directory.resolve(name), "{}");
    }

    @Test
    void theCommittedBaselineIsOneReleasedContract() {
        ContractBaseline baseline = ContractBaseline.find(Path.of("..", "docs", "api", "baseline"));

        assertThat(baseline.version()).matches("v\\d+\\.\\d+\\.\\d+");
        assertThat(baseline.openApi().getFileName()).hasToString("openapi-" + baseline.version() + ".json");
    }

    @Test
    void oneOpenApiDocumentAndAnOptionalInputSchemaOfTheSameRelease() throws IOException {
        touch("openapi-v1.2.3.json");
        assertThat(ContractBaseline.find(directory).inputSchema()).isEmpty();

        touch("input-schema-v1.2.3.json");
        ContractBaseline baseline = ContractBaseline.find(directory);
        assertThat(baseline.version()).isEqualTo("v1.2.3");
        assertThat(baseline.inputSchema()).contains(directory.resolve("input-schema-v1.2.3.json"));
    }

    @Test
    void noneOrTwoOpenApiDocumentsAreRefused() throws IOException {
        assertThatThrownBy(() -> ContractBaseline.find(directory)).hasMessageContaining("exactly one openapi-v<version>.json");

        touch("openapi-v1.2.3.json");
        touch("openapi-v1.2.4.json");
        assertThatThrownBy(() -> ContractBaseline.find(directory)).hasMessageContaining("found 2");
    }

    @Test
    void anInputSchemaOfAnotherReleaseOrAnUnknownFileIsRefused() throws IOException {
        touch("openapi-v1.2.3.json");
        touch("input-schema-v1.2.2.json");
        assertThatThrownBy(() -> ContractBaseline.find(directory)).hasMessageContaining("is not of the OpenAPI baseline's release v1.2.3");

        Files.delete(directory.resolve("input-schema-v1.2.2.json"));
        touch("openapi-latest.json");
        assertThatThrownBy(() -> ContractBaseline.find(directory)).hasMessageContaining("Unexpected files");
    }
}
