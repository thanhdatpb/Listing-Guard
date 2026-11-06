package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ApiKey;
import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.IssuedKey;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The "API keys" page (ADR-014 B4a): the list, a new key whose secret is shown once, the label rules, the limit of active keys
 * (3 here, set through the setting), the confirmation before a revocation, another account's key, and CSRF.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.console.max-active-keys=3"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleApiKeysIntegrationTest {

    private static final int LIMIT = 3;
    private static final Pattern SECRET = Pattern.compile("value=\"(lg_[A-Za-z0-9_-]{43})\"");

    /** An account with a client that has no key yet. */
    private record Account(Client client, ConsoleUser user) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ApiKeyRepository keys;

    @MockitoBean
    JavaMailSender mailSender;

    private Account account() {
        Client client = provisioning.createClient("c" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return new Account(client, new ConsoleUser("keys-" + UUID.randomUUID() + "@example.test", client.getId()));
    }

    private String page(Account account) throws Exception {
        return mvc.perform(get("/console/api-keys").with(signedIn(account.user()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private IssuedKey issue(Account account, String label) {
        return provisioning.createKey(account.client().getName(), label);
    }

    private int usageStatus(String secret) throws Exception {
        return mvc.perform(get("/api/v1/usage").header("X-API-Key", secret)).andReturn().getResponse().getStatus();
    }

    private String create(Account account, String label, int expectedStatus) throws Exception {
        return mvc.perform(post("/console/api-keys").with(signedIn(account.user())).with(csrf()).param("label", label))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    private static String secretIn(String html) {
        Matcher matcher = SECRET.matcher(html);
        assertThat(matcher.find()).as("a new key in the page").isTrue();
        return matcher.group(1);
    }

    @Test
    void theListHoldsTheActiveKeysOfTheAccountOnlyWithoutAnySecret() throws Exception {
        Account mine = account();
        Account other = account();
        IssuedKey kept = issue(mine, "billing sync");
        IssuedKey revoked = issue(mine, "retired one");
        provisioning.revokeKey(revoked.keyId());
        IssuedKey foreign = issue(other, "someone else");
        keys.touch(kept.keyId(), Instant.parse("2026-11-15T10:00:00Z"));

        String html = page(mine);

        assertThat(html).contains(kept.keyId()).contains("billing sync").contains("2026-11-15 10:00").contains("(1 of " + LIMIT + ")");
        assertThat(html).doesNotContain(revoked.keyId()).doesNotContain("retired one");
        assertThat(html).doesNotContain(foreign.keyId()).doesNotContain("someone else");
        assertThat(html).doesNotContain(kept.secret()).doesNotContain(revoked.secret()).doesNotContain(foreign.secret());
        assertThat(html).containsPattern("aria-current=\"page\"[^>]*>API keys<");
    }

    @Test
    void aKeyThatWasNeverUsedSaysNever() throws Exception {
        Account mine = account();
        issue(mine, null);

        assertThat(page(mine)).contains("never").contains("(no label)");
    }

    @Test
    void anAccountWithoutKeysSaysSo() throws Exception {
        assertThat(page(account())).contains("You have no active keys.").contains("id=\"create-form\"");
    }

    @Test
    void aNewKeyIsShownOnceWorksAndIsNeverShownAgain() throws Exception {
        Account mine = account();

        MockHttpServletResponse response = mvc.perform(post("/console/api-keys").with(signedIn(mine.user())).with(csrf())
                        .param("label", "preview app"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse();
        String html = response.getContentAsString();
        String secret = secretIn(html);

        assertThat(html).contains("shown only once").contains("id=\"copy-secret\"").contains("preview app");
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(usageStatus(secret)).isEqualTo(200);
        assertThat(provisioning.activeKeys(mine.client().getId())).singleElement().satisfies(key -> {
            assertThat(key.getLabel()).isEqualTo("preview app");
            assertThat(key.getKeyHash()).isNotEqualTo(secret);
        });

        String later = page(mine);
        assertThat(later).doesNotContain(secret).doesNotContain("id=\"new-key\"");
    }

    @Test
    void thePageUsesNoInlineScriptAndTheCopyButtonScriptIsServedBehindTheSignIn() throws Exception {
        Account mine = account();

        String html = create(mine, "", 200);

        assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)").doesNotContainPattern("(?i)\\son[a-z]+=\"").doesNotContain("javascript:");
        assertThat(html).containsPattern("<script[^>]*src=\"/console/keys\\.js\"");
        String script = mvc.perform(get("/console/keys.js").with(signedIn(mine.user()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(script).contains("navigator.clipboard").doesNotContain("fetch(").doesNotContain("XMLHttpRequest");
        mvc.perform(get("/console/keys.js")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void aLabelIsTrimmedAndBlankMeansNoLabel() throws Exception {
        Account mine = account();

        create(mine, "  preview app  ", 200);
        create(mine, "   ", 200);

        assertThat(provisioning.activeKeys(mine.client().getId())).extracting(ApiKey::getLabel).containsExactly("preview app", null);
    }

    @Test
    void aLabelIsPrintedAsTextNeverAsMarkup() throws Exception {
        Account mine = account();

        String created = create(mine, "<b>bold</b> & \"quoted\"", 200);

        for (String html : new String[] {created, page(mine)}) {
            assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;").doesNotContain("<b>bold</b>");
        }
    }

    @Test
    void aLabelOverTheLimitOrWithAControlCharacterIsRefusedAndNothingIsCreated() throws Exception {
        Account mine = account();

        String tooLong = create(mine, "x".repeat(ClientProvisioning.MAX_LABEL_LENGTH + 1), 400);
        String control = create(mine, "a\u0007b", 400);

        assertThat(tooLong).contains("longer than " + ClientProvisioning.MAX_LABEL_LENGTH).doesNotContain("id=\"new-key\"");
        assertThat(control).contains("control characters").doesNotContain("id=\"new-key\"");
        assertThat(provisioning.activeKeys(mine.client().getId())).isEmpty();

        create(mine, "x".repeat(ClientProvisioning.MAX_LABEL_LENGTH), 200);
        assertThat(provisioning.activeKeys(mine.client().getId())).hasSize(1);
    }

    @Test
    void theLimitStopsTheNextKeyKeysOfTheOperatorCountAndARevocationFreesAPlace() throws Exception {
        Account mine = account();
        IssuedKey fromTheOperator = issue(mine, "made by the operator");
        create(mine, "second", 200);
        create(mine, "third", 200);

        String refused = create(mine, "fourth", 409);

        assertThat(refused).contains("You already have " + LIMIT + " active keys").doesNotContain("id=\"new-key\"");
        assertThat(provisioning.activeKeys(mine.client().getId())).hasSize(LIMIT);
        String full = page(mine);
        assertThat(full).contains("id=\"limit-message\"").doesNotContain("id=\"create-form\"").contains("(" + LIMIT + " of " + LIMIT + ")");

        mvc.perform(post("/console/api-keys/" + fromTheOperator.keyId() + "/revoke").with(signedIn(mine.user())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        create(mine, "fourth", 200);

        assertThat(provisioning.activeKeys(mine.client().getId())).extracting(ApiKey::getLabel).containsExactly("second", "third", "fourth");
    }

    @Test
    void theLimitIsPerAccount() throws Exception {
        Account busy = account();
        Account free = account();
        for (int i = 0; i < LIMIT; i++) {
            issue(busy, "k" + i);
        }

        create(free, "mine", 200);

        assertThat(provisioning.activeKeys(free.client().getId())).hasSize(1);
        assertThat(provisioning.activeKeys(busy.client().getId())).hasSize(LIMIT);
    }

    @Test
    void aRevocationNeedsAConfirmationAndThenStopsTheKey() throws Exception {
        Account mine = account();
        IssuedKey issued = issue(mine, "to revoke");
        String revokeUrl = "/console/api-keys/" + issued.keyId() + "/revoke";

        String confirm = mvc.perform(get(revokeUrl).with(signedIn(mine.user()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(confirm).contains(issued.keyId()).contains("to revoke").contains("action=\"" + revokeUrl + "\"").contains("method=\"post\"");
        assertThat(usageStatus(issued.secret())).as("a GET revokes nothing").isEqualTo(200);

        mvc.perform(post(revokeUrl).with(signedIn(mine.user())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/api-keys"))
                .andExpect(flash().attribute("notice", containsString("is revoked")));

        assertThat(usageStatus(issued.secret())).isEqualTo(401);
        assertThat(page(mine)).doesNotContain(issued.keyId()).contains("You have no active keys.");
        mvc.perform(post(revokeUrl).with(signedIn(mine.user())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", containsString("already revoked")));
        mvc.perform(get(revokeUrl).with(signedIn(mine.user()))).andExpect(status().isNotFound());
    }

    @Test
    void aKeyOfAnotherAccountIsA404EverywhereAndStaysActive() throws Exception {
        Account owner = account();
        Account stranger = account();
        IssuedKey issued = issue(owner, "private");
        String revokeUrl = "/console/api-keys/" + issued.keyId() + "/revoke";

        mvc.perform(get(revokeUrl).with(signedIn(stranger.user()))).andExpect(status().isNotFound());
        mvc.perform(post(revokeUrl).with(signedIn(stranger.user())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/console/api-keys/nokey000/revoke").with(signedIn(stranger.user()))).andExpect(status().isNotFound());
        mvc.perform(post("/console/api-keys/nokey000/revoke").with(signedIn(stranger.user())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/console/api-keys/BAD..ID/revoke").with(signedIn(owner.user()))).andExpect(status().isNotFound());

        assertThat(usageStatus(issued.secret())).isEqualTo(200);
        assertThat(provisioning.activeKeys(owner.client().getId())).hasSize(1);
    }

    @Test
    void aPostWithoutACsrfTokenIsRefusedAndChangesNothing() throws Exception {
        Account mine = account();
        IssuedKey issued = issue(mine, "kept");

        mvc.perform(post("/console/api-keys").with(signedIn(mine.user())).param("label", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/console/api-keys/" + issued.keyId() + "/revoke").with(signedIn(mine.user()))).andExpect(status().isForbidden());

        assertThat(provisioning.activeKeys(mine.client().getId())).hasSize(1);
        assertThat(usageStatus(issued.secret())).isEqualTo(200);
    }

    @Test
    void theFormsCarryTheCsrfToken() throws Exception {
        Account mine = account();
        IssuedKey issued = issue(mine, "kept");

        String list = page(mine);
        String confirm = mvc.perform(get("/console/api-keys/" + issued.keyId() + "/revoke").with(signedIn(mine.user())))
                .andReturn().getResponse().getContentAsString();

        assertThat(list).containsPattern("<form id=\"create-form\"[^>]*>\\s*<input type=\"hidden\" name=\"_csrf\"");
        assertThat(confirm).contains("name=\"_csrf\"");
    }

    @Test
    void aVisitorWhoIsNotSignedInIsSentToTheSignInPage() throws Exception {
        mvc.perform(get("/console/api-keys")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/console/api-keys").with(csrf()).param("label", "x")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/console/api-keys/abcd1234/revoke")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void theAuditLogHasTheSameLinesAsTheAdminJobAndNeverTheSecret() throws Exception {
        Account mine = account();
        Logger audit = (Logger) LoggerFactory.getLogger("com.thanhdatpb.listingguard.audit");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        audit.addAppender(lines);
        try {
            String secret = secretIn(create(mine, "audited", 200));
            String keyId = provisioning.activeKeys(mine.client().getId()).get(0).getKeyId();
            mvc.perform(post("/console/api-keys/" + keyId + "/revoke").with(signedIn(mine.user())).with(csrf()))
                    .andExpect(status().is3xxRedirection());

            String all = String.join("\n", lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(all).contains("audit action=key.create client=" + mine.client().getName() + " keyId=" + keyId + " at=");
            assertThat(all).contains("audit action=key.revoke client=" + mine.client().getName() + " keyId=" + keyId + " at=");
            assertThat(all).doesNotContain(secret);
        } finally {
            audit.detachAppender(lines);
        }
    }
}
