package com.thanhdatpb.listingguard.rag.policy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * Parses marketplace policy pages. Only the content containers are read ({@code div.introduction} and
 * {@code article.prose-article}; {@code main}, {@code article} or {@code body} as fallbacks), so header,
 * footer, overlays, scripts and images never reach the chunker.
 *
 * <p>Three heading styles occur in real pages and are all recognized:
 * <ul>
 *   <li>real {@code h1}-{@code h6} tags, with or without an {@code id} (the id becomes the anchor);</li>
 *   <li>a paragraph that starts with {@code <strong>} ("a. What can be sold"), which is a low-level
 *       heading, and whatever follows the strong text in that paragraph is its first body paragraph;</li>
 *   <li>pages with no heading tags at all, made only of such strong-led paragraphs.</li>
 * </ul>
 * Tables of contents (lists of in-page links, runs of {@code <strong>} items) are navigation and skipped.
 */
public final class HtmlPolicyParser implements PolicyParser {

    private static final String CONTENT_SELECTOR = "div.introduction, article.prose-article";
    private static final String NOISE_SELECTOR =
            "script, style, noscript, template, nav, header, footer, aside, form, button, iframe, svg, figure, img";
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "section", "article",
            "blockquote", "dl", "pre");
    /**
     * Levels of headings that are not real h1-h6 tags (which keep their number, 1-6, so a real heading always
     * closes all of these). A numbered "a." or "1." item is a letter-level heading, "i." a roman-level one,
     * and a heading with no number belongs to the nearest numbered heading above it, one step deeper.
     */
    private static final int LETTER_LEVEL = 70;
    private static final int ROMAN_LEVEL = 80;
    private static final int CHILD_STEP = 5;
    private static final int MAX_STRONG_HEADING_CHARS = 150;
    private static final String ROMAN = "i|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii";
    private static final Pattern DIGIT_MARKER = Pattern.compile("^\\d+\\.\\s+\\S.*");
    private static final Pattern LETTER_MARKER = Pattern.compile("^[a-z]\\.\\s+\\S.*");
    private static final Pattern ROMAN_MARKER = Pattern.compile("^(" + ROMAN + ")\\.\\s+\\S.*");
    /** A paragraph that is only a short roman-numbered label ("i. Messages"), not a sentence. */
    private static final Pattern ROMAN_LABEL = Pattern.compile("^(" + ROMAN + ")\\.\\s+[^.:;!?]{1,80}$");
    private static final Pattern LAST_UPDATED = Pattern.compile("^Last updated on\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            dateFormat("MMM d, yyyy"),
            dateFormat("MMMM d, yyyy"));

    private static DateTimeFormatter dateFormat(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    @Override
    public ParsedPolicy parse(byte[] content) {
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(content), null, "");
        } catch (IOException e) {
            throw new PolicyParseException("Cannot read HTML", e);
        }
        Elements roots = document.select(CONTENT_SELECTOR);
        if (roots.isEmpty()) {
            roots = document.select("main");
        }
        if (roots.isEmpty()) {
            roots = document.select("article");
        }
        if (roots.isEmpty()) {
            roots = document.select("body");
        }
        Walker walker = new Walker();
        for (Element root : roots) {
            root.select(NOISE_SELECTOR).remove();
            walker.visitChildren(root);
        }
        return walker.result();
    }

    private static final class Walker {

        private final SectionAssembler sections = new SectionAssembler();
        private LocalDate lastUpdated;

        ParsedPolicy result() {
            return sections.build(lastUpdated);
        }

        /** Block children are visited one by one; runs of inline text between them become one unit. */
        void visitChildren(Element parent) {
            StringBuilder inline = new StringBuilder();
            for (Node node : parent.childNodes()) {
                if (node instanceof Element element && BLOCK_TAGS.contains(element.normalName())) {
                    flushInline(inline);
                    visit(element);
                } else if (node instanceof TextNode text) {
                    inline.append(text.text()).append(' ');
                } else if (node instanceof Element element) {
                    inline.append(element.text()).append(' ');
                }
            }
            flushInline(inline);
        }

        private void flushInline(StringBuilder inline) {
            sections.unit(clean(inline.toString()));
            inline.setLength(0);
        }

        private void visit(Element element) {
            String tag = element.normalName();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" ->
                        sections.heading(tag.charAt(1) - '0', clean(element.text()), element.id());
                case "p" -> paragraph(element);
                case "ul", "ol" -> list(element);
                case "li" -> sections.unit(textWithBreaks(element));
                case "table" -> table(element);
                default -> visitChildren(element);
            }
        }

        private void paragraph(Element p) {
            String text = clean(p.text());
            Matcher updated = LAST_UPDATED.matcher(text);
            if (updated.matches()) {
                lastUpdated = parseDate(updated.group(1));
                return;
            }
            if (isStrongList(p)) {
                return;
            }
            Element strong = leadingStrong(p);
            if (strong != null) {
                String heading = clean(strong.text());
                Element rest = p.clone();
                rest.selectFirst("strong, b").remove();
                String restText = textWithBreaks(rest);
                // "<strong>Note</strong>: ..." is an emphasized label, not a heading, with or without the colon inside
                boolean label = heading.endsWith(":") || restText.startsWith(":");
                if (!heading.isEmpty() && heading.length() <= MAX_STRONG_HEADING_CHARS && !label) {
                    sections.heading(strongLevel(heading), heading, null);
                    sections.unit(restText);
                    return;
                }
            }
            if (p.children().isEmpty() && ROMAN_LABEL.matcher(text).matches()) {
                sections.heading(strongLevel(text), text, null);
                return;
            }
            sections.unit(textWithBreaks(p));
        }

        /** Level for a heading that is not a real h-tag; see the level constants. */
        private int strongLevel(String heading) {
            int marked = markerLevel(heading);
            if (marked > 0) {
                return marked;
            }
            int parent = sections.deepestOpenLevel(LETTER_LEVEL, Walker::isNumbered);
            return parent < 0 ? LETTER_LEVEL : parent + CHILD_STEP;
        }

        /** Level given by a leading "1.", "a." or "i." marker, or -1 when the heading has none. */
        private int markerLevel(String heading) {
            if (DIGIT_MARKER.matcher(heading).matches()) {
                return LETTER_LEVEL;
            }
            Matcher roman = ROMAN_MARKER.matcher(heading);
            if (roman.matches()) {
                String token = roman.group(1);
                // "i.", "v." and "x." are also letters: they are the next letter after "h.", "u." and "w."
                boolean asLetter = token.length() == 1 && isNextLetter(token.charAt(0), sections.openHeadingAt(LETTER_LEVEL));
                return asLetter ? LETTER_LEVEL : ROMAN_LEVEL;
            }
            return LETTER_MARKER.matcher(heading).matches() ? LETTER_LEVEL : -1;
        }

        private static boolean isNextLetter(char letter, String openLetterHeading) {
            return openLetterHeading != null && !openLetterHeading.isEmpty()
                    && openLetterHeading.charAt(0) == (char) (letter - 1) && openLetterHeading.length() > 1
                    && openLetterHeading.charAt(1) == '.';
        }

        private static boolean isNumbered(String heading) {
            return DIGIT_MARKER.matcher(heading).matches() || ROMAN_MARKER.matcher(heading).matches()
                    || LETTER_MARKER.matcher(heading).matches();
        }

        private void list(Element list) {
            if (isNavigation(list)) {
                return;
            }
            for (Element item : list.children()) {
                if ("li".equals(item.normalName())) {
                    sections.unit("- " + textWithBreaks(item));
                }
            }
        }

        private void table(Element table) {
            for (Element row : table.select("tr")) {
                List<String> cells = row.select("th, td").stream().map(cell -> clean(cell.text())).filter(s -> !s.isEmpty()).toList();
                if (!cells.isEmpty()) {
                    sections.unit(String.join(" | ", cells));
                }
            }
        }

        /** A list whose every item starts with a link to a place on a page is a table of contents. */
        private static boolean isNavigation(Element list) {
            Elements items = list.children();
            if (items.isEmpty()) {
                return false;
            }
            for (Element item : items) {
                Element link = item.selectFirst("a[href*='#']");
                if (link == null || !clean(item.text()).startsWith(clean(link.text()))) {
                    return false;
                }
            }
            return true;
        }

        /** A paragraph that is only a run of strong items (usually separated by line breaks) lists headings. */
        private static boolean isStrongList(Element p) {
            if (p.children().stream().filter(c -> isStrong(c)).count() < 2) {
                return false;
            }
            Element rest = p.clone();
            rest.select("strong, b, br").remove();
            return clean(rest.text()).isEmpty();
        }

        private static Element leadingStrong(Element p) {
            for (Node node : p.childNodes()) {
                if (node instanceof TextNode text && clean(text.text()).isEmpty()) {
                    continue;
                }
                return node instanceof Element element && isStrong(element) ? element : null;
            }
            return null;
        }

        private static boolean isStrong(Element element) {
            return "strong".equals(element.normalName()) || "b".equals(element.normalName());
        }

        /** Text of an element with {@code <br>} kept as line breaks and spaces normalized. */
        private static String textWithBreaks(Element element) {
            Element copy = element.clone();
            copy.select("br").after("\\n");
            String[] lines = copy.text().split("\\\\n");
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                String cleaned = clean(line);
                if (!cleaned.isEmpty()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(cleaned);
                }
            }
            return out.toString();
        }

        private static LocalDate parseDate(String text) {
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    return LocalDate.parse(clean(text), format);
                } catch (DateTimeParseException e) {
                    // try the next format
                }
            }
            return null;
        }

        private static String clean(String text) {
            return text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        }
    }
}
