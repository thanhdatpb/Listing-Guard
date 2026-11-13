package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * What the bulk check tests share: the requests a browser would send and the reading of the pages that come back. The pages are read
 * with regular expressions on purpose, so a test sees exactly what a browser would receive, and it posts text the way a browser does
 * (line breaks of a text area become {@code \r\n}).
 */
final class BulkPages {

    record ResultRow(int line, String sku, String statusCss, String status, int findings) {
    }

    private BulkPages() {
    }

    /** A browser sends the line breaks of a text area as CRLF. */
    static String browserText(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    static MockMultipartHttpServletRequestBuilder prepareRequest(ConsoleUser user, String marketplace, String text, String header) {
        MockMultipartHttpServletRequestBuilder request = multipart("/console/bulk/prepare").with(signedIn(user)).with(csrf());
        request.param("source", "paste").param("marketplace", marketplace).param("text", browserText(text));
        if (header != null) {
            request.param("header", header);
        }
        return request;
    }

    static MockMultipartHttpServletRequestBuilder fileRequest(ConsoleUser user, String marketplace, String name, byte[] bytes) {
        MockMultipartHttpServletRequestBuilder request = multipart("/console/bulk/prepare").with(signedIn(user)).with(csrf());
        request.file(new MockMultipartFile("file", name, "text/csv", bytes)).param("source", "file").param("marketplace", marketplace);
        return request;
    }

    static MockMultipartHttpServletRequestBuilder fileRequest(ConsoleUser user, String marketplace, String name, String text) {
        return fileRequest(user, marketplace, name, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The run a browser would send from the mapping step in {@code mappingHtml}: its hidden values, the option each drop-down shows as
     * selected, changed where {@code columns} says (column index to target key, {@code ""} for skip) and with the "remember" box as the
     * page shows it unless {@code remember} says otherwise.
     */
    static MockMultipartHttpServletRequestBuilder runRequest(ConsoleUser user, String mappingHtml, Map<Integer, String> columns,
                                                             Map<String, String> separators, Boolean remember) {
        MockMultipartHttpServletRequestBuilder request = multipart("/console/bulk/run").with(signedIn(user)).with(csrf());
        request.param("marketplace", hidden(mappingHtml, "marketplace")).param("delimiter", hidden(mappingHtml, "delimiter"))
                .param("header", hidden(mappingHtml, "header")).param("data", browserText(dataOf(mappingHtml)));
        int count = columnCount(mappingHtml);
        for (int i = 0; i < count; i++) {
            String selected = columns.containsKey(i) ? columns.get(i) : selected(mappingHtml, "col-" + i);
            request.param("col." + i, selected);
        }
        Map<String, String> chosen = new LinkedHashMap<>();
        Matcher separatorSelects = Pattern.compile("<select id=\"sep-([a-z_]+)\"").matcher(mappingHtml);
        while (separatorSelects.find()) {
            chosen.put(separatorSelects.group(1), selected(mappingHtml, "sep-" + separatorSelects.group(1)));
        }
        chosen.putAll(separators);
        chosen.forEach((target, separator) -> request.param("sep." + target, separator));
        boolean box = remember != null ? remember : Pattern.compile("<input[^>]*id=\"remember\"[^>]*checked").matcher(mappingHtml).find();
        if (box) {
            request.param("remember", "on");
        }
        return request;
    }

    static int columnCount(String html) {
        Matcher matcher = Pattern.compile("<select id=\"col-(\\d+)\"").matcher(html);
        int count = 0;
        while (matcher.find()) {
            count = Math.max(count, Integer.parseInt(matcher.group(1)) + 1);
        }
        return count;
    }

    /** The target each column's drop-down shows as selected, in column order. */
    static List<String> selectedTargets(String html) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < columnCount(html); i++) {
            targets.add(selected(html, "col-" + i));
        }
        return targets;
    }

    static String selected(String html, String selectId) {
        Matcher select = Pattern.compile("<select id=\"" + selectId + "\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(html);
        assertThat(select.find()).as("a select with id " + selectId).isTrue();
        Matcher option = Pattern.compile("<option value=\"([^\"]*)\"[^>]*\\bselected").matcher(select.group(1));
        return option.find() ? option.group(1) : "";
    }

    static String hidden(String html, String name) {
        Matcher matcher = Pattern.compile("<input type=\"hidden\" name=\"" + name + "\" value=\"([^\"]*)\"").matcher(html);
        assertThat(matcher.find()).as("a hidden value " + name).isTrue();
        return HtmlUtils.htmlUnescape(matcher.group(1));
    }

    /** The text the mapping step sends back, as a browser would read it (it drops the first line break of a text area). */
    static String dataOf(String html) {
        Matcher matcher = Pattern.compile("<textarea name=\"data\" hidden>(.*?)</textarea>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("the data field of the mapping step").isTrue();
        String text = HtmlUtils.htmlUnescape(matcher.group(1));
        return text.startsWith("\n") ? text.substring(1) : text;
    }

    static List<ResultRow> resultRows(String html) {
        Matcher body = Pattern.compile("<tbody>(.*?)</tbody>", Pattern.DOTALL).matcher(html);
        assertThat(body.find()).as("the result table").isTrue();
        List<ResultRow> rows = new ArrayList<>();
        Matcher row = Pattern.compile("<tr data-group=\"[A-Z]+\"[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(body.group(1));
        while (row.find()) {
            String cells = row.group(1);
            int line = Integer.parseInt(find(cells, "<td class=\"n\">(\\d+)</td>"));
            String sku = find(cells, "<td class=\"sku\">(.*?)</td>").replaceAll("<[^>]+>", "").trim();
            Matcher status = Pattern.compile("<span class=\"status ([a-z-]+)\">([^<]+)</span>").matcher(cells);
            assertThat(status.find()).isTrue();
            Matcher counts = Pattern.compile("<td class=\"n\">(\\d+)</td>").matcher(cells);
            counts.find();
            counts.find();
            rows.add(new ResultRow(line, HtmlUtils.htmlUnescape(sku).equals("—") ? null : HtmlUtils.htmlUnescape(sku),
                    status.group(1), HtmlUtils.htmlUnescape(status.group(2)), Integer.parseInt(counts.group(1))));
        }
        return rows;
    }

    /** The lines of the block to paste back into a Sheet, split the way a Sheet splits it. */
    static List<String> sheetLines(String html) {
        Matcher matcher = Pattern.compile("<textarea id=\"sheet-block\"[^>]*>(.*?)</textarea>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("the block for the Sheet").isTrue();
        return List.of(HtmlUtils.htmlUnescape(matcher.group(1)).split("\n", -1));
    }

    private static String find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        assertThat(matcher.find()).as(regex).isTrue();
        return matcher.group(1);
    }
}
