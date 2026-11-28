package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code workspace roundtrip} (ADR-063 §12) on synthetic files: no database, nothing written but the temporary folder it names. */
class WorkspaceRoundtripTest {

    private static String run(String file, String sheet, String fieldRow, String cloneTo) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        WorkspaceRoundtrip.run(file, sheet, fieldRow, cloneTo, new PrintStream(captured, true, StandardCharsets.UTF_8));
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aMacroTemplateComesBackWithItsCellsItsMacroAndAReportOfEveryDifference() throws IOException {
        Path dir = Files.createTempDirectory("lg-rt-test");
        Path file = dir.resolve("template.xlsm");
        Files.write(file, ExportFixtures.flatFileXlsm("R-"));

        String report = run(file.toString(), null, null, null);

        assertThat(report).contains("XLSM").contains("sheet 'Template'").contains("field names in row 3").contains("5 data rows read")
                .contains("Compared the workbook:").contains(" 0 different").contains("Compared the text file:").contains(" 0 different")
                .contains("macro project: kept byte for byte").contains("List validations: 2 list rules")
                .contains("1 cells had a tab or a line break replaced");
        assertThat(report).doesNotContain("THE MACRO PROJECT CHANGED");
        assertThat(report).contains("Not verified with a real Amazon file");
        // the files are in a folder of their own
        String written = report.lines().filter(l -> l.startsWith("Wrote ") && l.contains("roundtrip.xlsm")).findFirst().orElseThrow();
        Path out = Path.of(written.substring("Wrote ".length()));
        assertThat(out).exists();
        assertThat(out.getParent().resolve("roundtrip.txt")).exists();
    }

    @Test
    void aTextTemplateMakesOnlyTheTextFileAndTheFieldRowCanBeGiven() throws IOException {
        Path dir = Files.createTempDirectory("lg-rt-test");
        Path file = dir.resolve("template.tsv");
        Files.write(file, WorkspaceFixtures.flatFileTsv("T-"));

        String report = run(file.toString(), null, "3", null);

        assertThat(report).contains("TSV").contains("no workbook").contains("Compared the text file:").contains(" 0 different")
                .doesNotContain("Compared the workbook");
        assertThatThrownBy(() -> run(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cloneToPrintsThePairsOfTheColumnsTheColumnsWithNowhereToGoAndTheColumnsNothingFills() throws IOException {
        Path dir = Files.createTempDirectory("lg-rt-test");
        Path one = dir.resolve("one.xlsx");
        Path two = dir.resolve("two.xlsx");
        Files.write(one, ExportFixtures.flatFile("A-"));
        List<String> other = new ArrayList<>(ExportFixtures.FIELDS);
        other.remove("x_custom_flag");
        other.add("extra_column");
        Files.write(two, ExportFixtures.flatFile(other, List.of(), false));

        String report = run(one.toString(), "Template", null, two.toString());

        assertThat(report).contains("paired with two.xlsx").contains("item_sku  ->  item_sku").contains("item_name  ->  item_name")
                .contains("columns of the first template with nowhere to go:").contains("x_custom_flag")
                .contains("columns of the second template that nothing fills:").contains("extra_column");
    }
}
