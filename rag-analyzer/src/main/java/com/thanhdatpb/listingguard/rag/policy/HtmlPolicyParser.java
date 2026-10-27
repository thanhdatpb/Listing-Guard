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
    private static final int STRONG_HEADING_LEVEL = 7;
    private static final int MAX_STRONG_HEADING_CHARS = 150;
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
                    sections.heading(STRONG_HEADING_LEVEL, heading, null);
                    sections.unit(restText);
                    return;
                }
            }
            sections.unit(textWithBreaks(p));
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
