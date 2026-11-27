package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The text of one person's alert e-mail (ADR-061): the number of listings, a table of {@code SKU - account - field - mark - level} with a
 * short excerpt under each row that knows its position, what was left out, and the links. Plain text, every sentence a message of both
 * languages. A cell is one line of at most a fixed width: white space is collapsed and control characters are dropped, so a SKU or a mark
 * cannot add a line or a header of its own. Nothing here knows more of a listing than the {@link Item}s it is given.
 */
public final class AlertDigest {

    private AlertDigest() {
    }

    /** One finding of the summary. {@code excerpt} is null when its position is not known. */
    public record Item(UUID listingId, String sku, String account, String field, String mark, Level level, String excerpt) {
    }

    /** @param listings how many distinct listings the summary is about; {@code rows} how many table rows are in the body */
    public record Digest(String subject, String body, int listings, int rows) {
    }

    private static final int CELL = 40;

    /**
     * @param admin     the summary of an ADMIN: it ends with how many listings each seller account has
     * @param openLink  the Console page of the new risks, empty when the deployment has no base URL
     * @param prefsLink the page where a person turns the e-mail off, empty when there is no base URL
     * @param maxRows   the most table rows; the rest is counted
     */
    public static Digest compose(ConsoleMessages messages, Locale locale, boolean admin, List<Item> items, int maxRows, String openLink,
                                 String prefsLink) {
        Set<UUID> listings = new LinkedHashSet<>();
        items.forEach(item -> listings.add(item.listingId()));
        StringBuilder body = new StringBuilder();
        body.append(messages.get(locale, "alert.intro", listings.size())).append("\n\n");
        body.append(row(messages.get(locale, "alert.col.sku"), messages.get(locale, "alert.col.account"), messages.get(locale, "alert.col.field"),
                messages.get(locale, "alert.col.mark"), messages.get(locale, "alert.col.level"))).append('\n');
        body.append("-".repeat(5 * CELL / 2)).append('\n');
        int shown = 0;
        for (Item item : items) {
            if (shown == maxRows) {
                break;
            }
            body.append(row(item.sku(), item.account(), item.field(), item.mark(), messages.get(locale, "reaudit.level." + item.level().code())))
                    .append('\n');
            if (item.excerpt() != null) {
                body.append("    ").append(messages.get(locale, "alert.excerpt", cell(item.excerpt(), 4 * CELL))).append('\n');
            }
            shown++;
        }
        if (items.size() > shown) {
            body.append('\n').append(messages.get(locale, "alert.more", items.size() - shown)).append('\n');
        }
        if (admin) {
            Map<String, Set<UUID>> byAccount = new LinkedHashMap<>();
            items.forEach(item -> byAccount.computeIfAbsent(item.account(), a -> new LinkedHashSet<>()).add(item.listingId()));
            body.append('\n').append(messages.get(locale, "alert.accounts")).append('\n');
            byAccount.forEach((account, ids) -> body.append("  ").append(messages.get(locale, "alert.accounts.line", cell(account, CELL), ids.size()))
                    .append('\n'));
        }
        if (!openLink.isBlank()) {
            body.append('\n').append(messages.get(locale, "alert.open")).append(' ').append(openLink).append('\n');
        }
        if (!prefsLink.isBlank()) {
            body.append(messages.get(locale, "alert.optout")).append(' ').append(prefsLink).append('\n');
        }
        body.append('\n').append(messages.get(locale, "alert.disclaimer")).append('\n');
        return new Digest(messages.get(locale, "alert.subject", listings.size()), body.toString(), listings.size(), shown);
    }

    private static String row(String sku, String account, String field, String mark, String level) {
        return cell(sku, CELL) + " | " + cell(account, CELL) + " | " + cell(field, 24) + " | " + cell(mark, CELL) + " | " + cell(level, 24);
    }

    /** One line of at most {@code max} characters: control characters and line breaks become a space, runs of white space one space. */
    static String cell(String text, int max) {
        if (text == null) {
            return "-";
        }
        String line = text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return "-";
        }
        if (line.codePointCount(0, line.length()) > max) {
            line = line.substring(0, line.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
        }
        return line;
    }
}
