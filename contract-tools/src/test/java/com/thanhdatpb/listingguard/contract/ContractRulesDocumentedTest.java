package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ADR-022 "Rules of the check" and {@link Rule} list the same rules with the same classes, in the same order. */
class ContractRulesDocumentedTest {

    private static final Path ADR = Path.of("..", "docs", "adr", "ADR-022-api-contract-and-compatibility.md");
    private static final Pattern ROW = Pattern.compile("^\\| `([a-z0-9-]+)` \\| ([a-z-]+) \\| .+\\|$");

    @Test
    void theAdrTableIsTheRulesOfTheCode() throws IOException {
        List<String> lines = Files.readAllLines(ADR, StandardCharsets.UTF_8);
        int start = lines.indexOf("#### Rules of the check");
        assertThat(start).as("ADR-022 has the section \"#### Rules of the check\"").isNotNegative();

        Map<String, String> documented = new LinkedHashMap<>();
        for (String line : lines.subList(start, lines.size())) {
            if (line.startsWith("### ")) {
                break;
            }
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                assertThat(documented.put(row.group(1), row.group(2))).as("rule documented twice: " + row.group(1)).isNull();
            }
        }
        Map<String, String> code = Arrays.stream(Rule.values())
                .collect(Collectors.toMap(Rule::id, rule -> rule.changeClass().label(), (a, b) -> a, LinkedHashMap::new));

        assertThat(documented).as("rule id -> class in ADR-022").containsExactlyEntriesOf(code);
    }
}
