package com.thanhdatpb.listingguard.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfPolicyParserTest {

    private final PdfPolicyParser parser = new PdfPolicyParser();

    private record Line(boolean bold, float size, float y, String text) {
    }

    private static byte[] pdf(Line... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (Line line : lines) {
                    content.beginText();
                    content.setFont(line.bold() ? bold : regular, line.size());
                    content.newLineAtOffset(50, line.y());
                    content.showText(line.text());
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> paths(ParsedPolicy policy) {
        return policy.sections().stream().map(section -> String.join(" > ", section.headingPath())).toList();
    }

    @Test
    void largerTextStartsSectionsAndLevelsFollowSize() throws IOException {
        ParsedPolicy policy = parser.parse(pdf(
                new Line(true, 20, 740, "1. Selling Basics"),
                new Line(false, 11, 710, "Keep these basics in mind."),
                new Line(false, 11, 696, "and follow them always."),
                new Line(false, 11, 666, "A second paragraph of body text."),
                new Line(true, 14, 630, "a. What can be sold"),
                new Line(false, 11, 600, "Etsy is a unique marketplace for makers."),
                new Line(true, 20, 560, "2. Community"),
                new Line(false, 11, 530, "Be kind to other members of the community.")));

        assertThat(paths(policy)).containsExactly("1. Selling Basics", "1. Selling Basics > a. What can be sold", "2. Community");
        assertThat(policy.sections().get(0).units())
                .containsExactly("Keep these basics in mind. and follow them always.", "A second paragraph of body text.");
        assertThat(policy.sections().get(1).units()).containsExactly("Etsy is a unique marketplace for makers.");
        assertThat(policy.lastUpdated()).isNull();
    }

    @Test
    void aShortBoldLineAtBodySizeIsTheLowestLevelHeading() throws IOException {
        ParsedPolicy policy = parser.parse(pdf(
                new Line(true, 18, 740, "Policy"),
                new Line(false, 11, 710, "Some introduction text for the policy."),
                new Line(true, 11, 670, "Short bold heading"),
                new Line(false, 11, 650, "Text under the short heading is here.")));

        assertThat(paths(policy)).containsExactly("Policy", "Policy > Short bold heading");
    }

    @Test
    void bulletLinesStartNewUnits() throws IOException {
        ParsedPolicy policy = parser.parse(pdf(
                new Line(true, 20, 740, "Rules"),
                new Line(false, 11, 710, "Sellers must:"),
                new Line(false, 11, 696, "- respond within two days"),
                new Line(false, 11, 682, "- ship on time")));

        assertThat(policy.sections().get(0).units())
                .containsExactly("Sellers must:", "- respond within two days", "- ship on time");
    }

    @Test
    void aBlankPageHasNoSections() throws IOException {
        assertThat(parser.parse(pdf()).sections()).isEmpty();
    }

    @Test
    void bytesThatAreNotAPdfAreRejected() {
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PolicyParseException.class);
    }

    @Test
    void parserIsChosenByFileExtension() {
        assertThat(PolicyParser.forFile("a.html")).isInstanceOf(HtmlPolicyParser.class);
        assertThat(PolicyParser.forFile("A.HTM")).isInstanceOf(HtmlPolicyParser.class);
        assertThat(PolicyParser.forFile("a.pdf")).isInstanceOf(PdfPolicyParser.class);
        assertThatThrownBy(() -> PolicyParser.forFile("a.docx")).isInstanceOf(PolicyParseException.class);
    }
}
