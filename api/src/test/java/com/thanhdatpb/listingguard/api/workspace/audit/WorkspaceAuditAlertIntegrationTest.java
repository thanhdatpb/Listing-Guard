package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The alert e-mail of the background audit (ADR-061): one summary for each person after a run that found a new risk, the person responsible for
 * a listing gets theirs and every ADMIN gets all with the count by seller account, only a short excerpt and never the whole listing, a person who
 * turned it off or who reached the day's cap gets none (and what they were not told is told later), nobody is told twice, and another client's
 * listings never reach this client's people. The mailer is a mock; every address and listing is synthetic.
 */
class WorkspaceAuditAlertIntegrationTest extends AuditIntegrationBase {

    private static final String LONG_DESCRIPTION = "This is a very long description of a soft hoodie that goes on and on about the fabric and the stitching "
            + "and the cold days, with a ZORVEX print on the front, and then it goes on about washing and shipping and gift wrap for a while longer.";

    @Autowired
    AlertService alerts;

    @BeforeEach
    void freshMailer() {
        reset(mailer);
    }

    private record World(Team team, SellerAccount shop, Template template, Listing hoodie, Listing sticker) {
    }

    /** A hoodie of the member that the new catalog makes a risk (in its description too), and an unowned sticker that the watchlist makes one. */
    private World world() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop A");
        Template template = etsyTemplate(team, shop);
        serve("a", "Qualmark");
        Listing hoodie = insert(team, shop, template, "H-1", fields("Soft Zorvex hoodie for you", LONG_DESCRIPTION), team.memberAccount().getId());
        Listing sticker = insert(team, shop, template, "S-1", fields("Brimwick Otters sticker", "A plain description."), null);
        audit();
        reset(mailer);
        return new World(team, shop, template, hoodie, sticker);
    }

    private void newRisks(World w) {
        serve("b", "Qualmark", "Zorvex");
        watch(w.team(), "Brimwick Otters");
        audit();
    }

    private record Mail(String to, String subject, String body) {
    }

    /**
     * What was sent to the people of these teams. The database is shared by every test of the run, so an audit here also works the accounts
     * of other tests and may e-mail their (synthetic) people; a test looks only at its own.
     */
    private List<Mail> sentTo(Team... teams) {
        java.util.Set<String> addresses = new java.util.HashSet<>();
        for (Team team : teams) {
            addresses.add(team.admin().email());
            addresses.add(team.member().email());
        }
        List<Mail> mails = new java.util.ArrayList<>();
        for (var invocation : org.mockito.Mockito.mockingDetails(mailer).getInvocations()) {
            if (invocation.getMethod().getName().equals("send") && addresses.contains((String) invocation.getArgument(0))) {
                mails.add(new Mail(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            }
        }
        return mails;
    }

    private List<Mail> mails(Team team, int expected) {
        List<Mail> mails = sentTo(team);
        assertThat(mails).as("e-mails sent to " + team.client().getName()).hasSize(expected);
        return mails;
    }

    @Test
    void eachPersonGetsOneSummaryAfterTheRunTheOwnerTheirListingsAndTheAdminAllOfThemByAccount() {
        World w = world();

        newRisks(w);

        List<Mail> mails = mails(w.team(), 2);
        Mail member = mails.stream().filter(m -> m.to().equals(w.team().member().email())).findFirst().orElseThrow();
        Mail admin = mails.stream().filter(m -> m.to().equals(w.team().admin().email())).findFirst().orElseThrow();
        // the member is told of the hoodie they are responsible for and of nothing else
        assertThat(member.body()).contains("H-1", "Shop A", "title", "Zorvex", "High risk").doesNotContain("S-1").doesNotContain("By seller account");
        assertThat(member.subject()).contains("1").contains("new risk");
        // the admin is told of both, and of how many each seller account has
        assertThat(admin.body()).contains("H-1", "S-1", "Brimwick Otters", "By seller account:", "Shop A: 2 listing(s)");
        assertThat(admin.subject()).contains("2");
        assertThat(admin.body()).contains("https://console.example.test/console/workspace?check=NEW_RISK")
                .contains("https://console.example.test/console/workspace/audit");
        // what was told is marked, so a second send tells nobody again
        reset(mailer);
        assertThat(alerts.send(w.team().client().getId(), null)).isZero();
        assertThat(sentTo(w.team())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_audit WHERE client_id = ? AND state = 'NEW_RISK' AND alerted_at IS NULL",
                Long.class, w.team().client().getId())).isZero();
    }

    @Test
    void theEmailQuotesAShortPieceAroundTheMatchAndNeverTheWholeListing() {
        World w = world();

        newRisks(w);

        for (Mail mail : mails(w.team(), 2)) {
            assertThat(mail.body()).doesNotContain(LONG_DESCRIPTION).doesNotContain("goes on about washing").doesNotContain("A plain description.");
            assertThat(mail.body().length()).isLessThan(2500);
        }
        // the title is short enough to be quoted around the match; the text of the description is not in the e-mail at all
        assertThat(mails(w.team(), 2).stream().filter(m -> m.to().equals(w.team().member().email())).findFirst().orElseThrow().body())
                .contains("Near the match:");
    }

    @Test
    void aPersonWhoTurnedItOffGetsNoneAndTheAdminStillDoes() {
        World w = world();
        store.setEmailEnabled(w.team().client().getId(), w.team().memberAccount().getId(), false);

        newRisks(w);

        List<Mail> mails = mails(w.team(), 1);
        assertThat(mails.get(0).to()).isEqualTo(w.team().admin().email());
        assertThat(store.emailEnabled(w.team().client().getId(), w.team().memberAccount().getId())).isFalse();
        // one person's switch is theirs: the admin's is still on, and another client's people are untouched
        assertThat(store.emailEnabled(w.team().client().getId(), w.team().adminAccount().getId())).isTrue();
        assertThat(store.emailDisabled(team().client().getId())).isEmpty();
    }

    @Test
    void theDayCapStopsTheEmailAndWhatWasNotToldIsToldWhenTheCapAllowsIt() {
        World w = world();
        UUID client = w.team().client().getId();
        // the sticker is unowned: only the admin can be told. The admin already had two e-mails today (the cap of this test)
        store.logSent(client, w.team().adminAccount().getId(), null, java.time.LocalDate.now(java.time.ZoneOffset.UTC), 1);
        store.logSent(client, w.team().adminAccount().getId(), null, java.time.LocalDate.now(java.time.ZoneOffset.UTC), 1);

        newRisks(w);

        // the member (under the cap) was told of the hoodie; the admin was not told of anything
        List<Mail> mails = mails(w.team(), 1);
        assertThat(mails.get(0).to()).isEqualTo(w.team().member().email());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_audit WHERE client_id = ? AND state = 'NEW_RISK' AND alerted_at IS NULL",
                Long.class, client)).isEqualTo(1);
        // tomorrow: the cap counts today's rows only
        jdbc.update("UPDATE workspace_alert_log SET sent_on = sent_on - 1 WHERE client_id = ?", client);
        reset(mailer);
        assertThat(alerts.send(client, null)).isEqualTo(1);
        assertThat(mails(w.team(), 1).get(0).body()).contains("S-1").doesNotContain("H-1");
    }

    @Test
    void aPersonNeverGetsMoreThanOneEmailForOneRunEvenWhenTheyAreAnAdminAndTheOwner() {
        World w = world();
        // the admin becomes responsible for the hoodie as well as being an admin
        Listing hoodie = listings.find(w.team().client().getId(), w.hoodie().id()).orElseThrow();
        assertThat(listings.changeOwner(w.team().client().getId(), hoodie.id(), w.team().adminAccount().getId(), hoodie.version(),
                w.team().adminAccount().getId())).isTrue();

        newRisks(w);

        List<Mail> mails = mails(w.team(), 1);
        assertThat(mails.get(0).to()).isEqualTo(w.team().admin().email());
        assertThat(mails.get(0).body()).contains("H-1", "S-1");
    }

    @Test
    void anotherClientsListingsNeverReachThisClientsPeopleAndTheOtherClientIsToldOfItsOwn() {
        World mine = world();
        World theirs = world();

        serve("c", "Qualmark", "Zorvex");
        watch(mine.team(), "Brimwick Otters");
        watch(theirs.team(), "Brimwick Otters");
        audit();

        List<Mail> mails = sentTo(mine.team(), theirs.team());
        assertThat(mails).hasSize(4);
        for (Mail mail : mails) {
            boolean ofMine = mail.to().equals(mine.team().admin().email()) || mail.to().equals(mine.team().member().email());
            boolean ofTheirs = mail.to().equals(theirs.team().admin().email()) || mail.to().equals(theirs.team().member().email());
            assertThat(ofMine ^ ofTheirs).as(mail.to()).isTrue();
        }
        assertThat(mails.stream().map(Mail::to)).containsExactlyInAnyOrder(mine.team().admin().email(), mine.team().member().email(),
                theirs.team().admin().email(), theirs.team().member().email());
        // both worlds use the same SKUs, so what proves the separation is the count and the account: each admin summary has its own two listings
        for (Mail mail : mails) {
            if (mail.to().startsWith("admin-")) {
                assertThat(mail.body()).contains("Shop A: 2 listing(s)");
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_alert_log WHERE client_id = ?", Long.class, mine.team().client().getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_alert_log WHERE client_id = ?", Long.class, theirs.team().client().getId())).isEqualTo(2);
    }

    @Test
    void aFailedSendLeavesTheListingsUntoldSoTheNextRunTriesAgain() {
        World w = world();
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(mailer).send(anyString(), anyString(), anyString());

        newRisks(w);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_audit WHERE client_id = ? AND state = 'NEW_RISK' AND alerted_at IS NULL",
                Long.class, w.team().client().getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_alert_log WHERE client_id = ?", Long.class, w.team().client().getId())).isZero();
        reset(mailer);
        assertThat(alerts.send(w.team().client().getId(), null)).isEqualTo(2);
    }
}
