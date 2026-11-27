package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleMessagesTestSupport;
import com.thanhdatpb.listingguard.api.workspace.audit.AlertDigest.Item;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The text of an alert e-mail, in both languages, without a database (ADR-061). */
class AlertDigestTest {

    private final ConsoleMessages messages = ConsoleMessagesTestSupport.messages();
    private final UUID one = UUID.randomUUID();
    private final UUID two = UUID.randomUUID();

    private List<Item> items() {
        return List.of(
                new Item(one, "H-1", "Shop A", "title", "Zorvex", Level.HIGH, "…Cool ZORVEX Hoodie…"),
                new Item(one, "H-1", "Shop A", "description", "Zorvex", Level.MEDIUM, null),
                new Item(two, "M-9", "Shop B", "tags[2]", "Brimwick Otters", Level.HIGH, "Brimwick Otters"));
    }

    @Test
    void theEmailHasTheCountTheTableTheExcerptAndTheLinksInEnglish() {
        AlertDigest.Digest digest = AlertDigest.compose(messages, Locale.ENGLISH, false, items(), 40, "https://console.example.test/console/workspace?check=NEW_RISK",
                "https://console.example.test/console/workspace/audit");

        assertThat(digest.listings()).isEqualTo(2);
        assertThat(digest.rows()).isEqualTo(3);
        assertThat(digest.subject()).contains("2").contains("new risk");
        assertThat(digest.body()).contains("SKU", "Account", "Field", "Mark", "Level")
                .contains("H-1 ", "Shop A", "title", "Zorvex", "High risk", "Medium risk", "M-9", "tags[2]")
                .contains("Near the match: …Cool ZORVEX Hoodie…")
                .contains("https://console.example.test/console/workspace?check=NEW_RISK", "https://console.example.test/console/workspace/audit")
                .contains("does not give legal advice");
        // a member's e-mail is not the ADMIN's summary by seller account
        assertThat(digest.body()).doesNotContain("By seller account");
    }

    @Test
    void theEmailIsInVietnameseWhenAsked() {
        AlertDigest.Digest digest = AlertDigest.compose(messages, Locale.forLanguageTag("vi"), true, items(), 40, "", "");

        assertThat(digest.subject()).contains("2 listing").contains("rủi ro mới");
        assertThat(digest.body()).contains("Tài khoản", "Trường", "Nhãn", "Mức", "Quanh chỗ khớp:", "Theo tài khoản bán:")
                .contains("Shop A: 1 listing", "Shop B: 1 listing")
                .doesNotContain("By seller account").doesNotContain("Open in the Console");
    }

    @Test
    void anAdminGetsTheCountBySellerAccountAndALongTableIsCutAndCounted() {
        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(new Item(UUID.randomUUID(), "SKU-" + i, i < 4 ? "Shop A" : "Shop B", "title", "Mark", Level.HIGH, null));
        }

        AlertDigest.Digest digest = AlertDigest.compose(messages, Locale.ENGLISH, true, many, 5, "", "");

        assertThat(digest.rows()).isEqualTo(5);
        assertThat(digest.listings()).isEqualTo(7);
        assertThat(digest.body()).contains("and 2 more row(s)").contains("Shop A: 4 listing(s)").contains("Shop B: 3 listing(s)")
                .doesNotContain("SKU-5").doesNotContain("SKU-6");
    }

    @Test
    void aCellCannotAddALineOrAHeaderAndIsCut() {
        Item hostile = new Item(one, "A-1\nBcc: attacker@example.test\r\nX: y", "Shop\tA", "title", "Mark\u0000   with  gaps", Level.HIGH,
                "line one\nline two");

        String body = AlertDigest.compose(messages, Locale.ENGLISH, false, List.of(hostile), 40, "", "").body();

        assertThat(body).doesNotContain("\nBcc:").doesNotContain("\r").doesNotContain("\u0000").doesNotContain(" ");
        assertThat(body).contains("A-1 Bcc: attacker@example.test X: y").contains("Mark with gaps").contains("line one line two");
        assertThat(AlertDigest.cell("x".repeat(500), 40)).hasSize(40).endsWith("…");
        assertThat(AlertDigest.cell("  ", 40)).isEqualTo("-");
        assertThat(AlertDigest.cell(null, 40)).isEqualTo("-");
    }

    @Test
    void noLinkLineIsWrittenWhenTheDeploymentHasNoBaseUrl() {
        String body = AlertDigest.compose(messages, Locale.ENGLISH, false, items(), 40, "", "").body();

        assertThat(body).doesNotContain("Open in the Console").doesNotContain("Turn these e-mails off");
    }
}
