package com.thanhdatpb.listingguard.rag.policy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Parses text PDFs. A PDF has no heading tags, so headings are guessed from typography: a line set in a
 * clearly larger size than the body text, or a short bold line, starts a section. Best effort only; the
 * HTML parser is the reference implementation.
 */
public final class PdfPolicyParser implements PolicyParser {

    private static final double HEADING_SIZE_RATIO = 1.12;
    private static final double PARAGRAPH_GAP_IN_LINES = 1.7;
    private static final int MAX_BOLD_HEADING_CHARS = 100;
    private static final Pattern BULLET = Pattern.compile("^([\\u2022\\u25CF\\u25E6\\-\\u2013*]|\\d+[.)])\\s+.*");

    private record Line(String text, float size, float y, int page, boolean bold) {
    }

    @Override
    public ParsedPolicy parse(byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            LineCollector collector = new LineCollector();
            collector.setSortByPosition(true);
            collector.getText(pdf);
            return assemble(collector.lines);
        } catch (IOException e) {
            throw new PolicyParseException("Cannot read PDF", e);
        }
    }

    private static ParsedPolicy assemble(List<Line> lines) {
        SectionAssembler sections = new SectionAssembler();
        if (lines.isEmpty()) {
            return sections.build(null);
        }
        float bodySize = bodySize(lines);
        List<Float> headingSizes = new ArrayList<>(new TreeSet<>(lines.stream()
                .map(Line::size).filter(size -> size >= bodySize * HEADING_SIZE_RATIO).toList()).descendingSet());
        int boldLevel = headingSizes.size() + 1;

        StringBuilder paragraph = new StringBuilder();
        Line previous = null;
        Line pendingHeading = null;
        StringBuilder headingText = new StringBuilder();
        int headingLevel = 0;

        for (Line line : lines) {
            int level = headingLevel(line, bodySize, headingSizes, boldLevel);
            if (level > 0) {
                flushParagraph(sections, paragraph);
                if (pendingHeading != null && headingLevel == level && sameBlock(pendingHeading, line)) {
                    headingText.append(' ').append(line.text());
                } else {
                    flushHeading(sections, headingLevel, headingText);
                    headingLevel = level;
                    headingText.append(line.text());
                }
                pendingHeading = line;
            } else {
                flushHeading(sections, headingLevel, headingText);
                pendingHeading = null;
                boolean newBlock = previous == null || previous.page() != line.page()
                        || Math.abs(line.y() - previous.y()) > line.size() * PARAGRAPH_GAP_IN_LINES;
                if (paragraph.length() > 0 && (newBlock || BULLET.matcher(line.text()).matches())) {
                    flushParagraph(sections, paragraph);
                }
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line.text());
            }
            previous = line;
        }
        flushHeading(sections, headingLevel, headingText);
        flushParagraph(sections, paragraph);
        return sections.build(null);
    }

    private static boolean sameBlock(Line a, Line b) {
        return a.page() == b.page() && Math.abs(b.y() - a.y()) <= b.size() * PARAGRAPH_GAP_IN_LINES;
    }

    private static int headingLevel(Line line, float bodySize, List<Float> headingSizes, int boldLevel) {
        int index = headingSizes.indexOf(line.size());
        if (index >= 0) {
            return index + 1;
        }
        if (line.bold() && line.text().length() <= MAX_BOLD_HEADING_CHARS && !line.text().endsWith(".")
                && line.size() >= bodySize * 0.95) {
            return boldLevel;
        }
        return 0;
    }

    private static void flushHeading(SectionAssembler sections, int level, StringBuilder text) {
        if (text.length() > 0) {
            sections.heading(level, text.toString().trim(), null);
            text.setLength(0);
        }
    }

    private static void flushParagraph(SectionAssembler sections, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            sections.unit(paragraph.toString().trim());
            paragraph.setLength(0);
        }
    }

    /** The font size that carries the most characters. */
    private static float bodySize(List<Line> lines) {
        Map<Float, Integer> characters = new HashMap<>();
        for (Line line : lines) {
            characters.merge(line.size(), line.text().length(), Integer::sum);
        }
        return characters.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    private static final class LineCollector extends PDFTextStripper {

        private final List<Line> lines = new ArrayList<>();

        LineCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text.isBlank() || positions.isEmpty()) {
                return;
            }
            float size = Math.round(positions.get(0).getFontSizeInPt() * 2) / 2f;
            String fontName = positions.get(0).getFont().getName();
            boolean bold = fontName != null && fontName.toLowerCase().contains("bold");
            lines.add(new Line(text.trim(), size, positions.get(0).getYDirAdj(), getCurrentPageNo(), bold));
        }
    }
}
