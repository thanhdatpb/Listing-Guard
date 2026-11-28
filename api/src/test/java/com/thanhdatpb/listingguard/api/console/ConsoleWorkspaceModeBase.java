package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** What the workspace console and the classic one show to an ADMIN and a MEMBER (ADR-064); the properties come from the subclass. */
abstract class ConsoleWorkspaceModeBase {

    /** The paths of the Sheet tools, as the pages of the other tools could link to them. */
    static final Pattern SHEET_LINK = Pattern.compile("href=\"/console/(bulk|check/edit|reaudit|history)([/?\"])");

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository accounts;

    record Team(Client client, ConsoleUser admin, ConsoleUser member) {
    }

    Team team() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Client client = provisioning.createClient("mode" + token);
        ConsoleUser admin = new ConsoleUser("mode-admin-" + token + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("mode-member-" + token + "@example.test", client.getId());
        accounts.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        accounts.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, member);
    }

    MvcResult fetch(ConsoleUser user, String path, String language) throws Exception {
        return mvc.perform(get(path).with(signedIn(user)).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language))).andReturn();
    }

    String page(ConsoleUser user, String path, String language) throws Exception {
        MvcResult result = fetch(user, path, language);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    int status(ConsoleUser user, String path) throws Exception {
        return fetch(user, path, "vi").getResponse().getStatus();
    }

    int postStatus(ConsoleUser user, String path) throws Exception {
        return mvc.perform(post(path).with(signedIn(user)).with(csrf())).andReturn().getResponse().getStatus();
    }

    /** The links of the menu, in order: the hrefs of {@code app-nav__link} anchors of the sidebar. */
    static List<String> menu(String html) {
        int from = html.indexOf("<nav class=\"app-nav");
        int to = html.indexOf("</nav>", from);
        assertThat(from).as("a menu").isGreaterThanOrEqualTo(0);
        List<String> links = new ArrayList<>();
        Matcher m = Pattern.compile("<a class=\"app-nav__link\" href=\"([^\"]+)\"").matcher(html.substring(from, to));
        while (m.find()) {
            links.add(m.group(1));
        }
        return links;
    }

    /** The group labels of the menu, in order. */
    static List<String> groups(String html) {
        int from = html.indexOf("<nav class=\"app-nav");
        int to = html.indexOf("</nav>", from);
        List<String> labels = new ArrayList<>();
        Matcher m = Pattern.compile("<p class=\"app-nav__label[^\"]*\">(?:<svg.*?</svg>)?(?:<span>)?([^<]+)").matcher(html.substring(from, to));
        while (m.find()) {
            labels.add(m.group(1));
        }
        return labels;
    }
}
