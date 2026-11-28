package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the classic console shows, held byte for byte (ADR-064). The golden files in {@code src/test/resources/console-classic} were taken from
 * {@code main} before the console mode existed; a console that does not set {@code listing-guard.console.mode}, or sets it to {@code classic},
 * must still render the same menu and the same home page for the same accounts. The values that change by run (the hash of the build in an
 * address, a token, the random names of the accounts) are replaced by a fixed word before the comparison; nothing else is touched.
 *
 * <p>To take the files again (a deliberate change of the classic console, never a side effect): run the test with {@code -Dlg.golden.write=true}.
 */
abstract class ConsoleClassicGoldenBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository accounts;

    /** The marker of the files of this group: {@code workspace} (the workspace is on) or {@code plain}. */
    abstract String group();

    record Team(Client client, ConsoleUser admin, ConsoleUser member, String token) {
    }

    Team team() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Client client = provisioning.createClient("golden" + token);
        ConsoleUser admin = new ConsoleUser("golden-admin-" + token + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("golden-member-" + token + "@example.test", client.getId());
        accounts.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        accounts.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, member, token);
    }

    String page(Team team, ConsoleUser user, String path, String language) throws Exception {
        var result = mvc.perform(get(path).with(signedIn(user)).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8), team);
    }

    static String normalize(String html, Team team) {
        return html.replaceAll("v=[0-9a-f]{6,}", "v=BUILD")
                .replaceAll("(name=\"_csrf\" value=\")[^\"]*", "$1TOKEN")
                .replace(team.token(), "TOKEN12")
                .replace(team.client().getId().toString(), "CLIENT-ID");
    }

    void same(String name, String actual) throws IOException {
        Path file = Path.of("src/test/resources/console-classic", group() + "-" + name + ".html");
        if (Boolean.getBoolean("lg.golden.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(file).as("golden file " + file).exists();
        assertThat(actual).as(name).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The status of a path for a signed-in person. */
    int status(ConsoleUser user, String path) throws Exception {
        return mvc.perform(get(path).with(signedIn(user))).andReturn().getResponse().getStatus();
    }

    /** The home page and a page that has only the menu and a form (the single check), for each role and language; and the routes as they were. */
    void everyPage() throws Exception {
        Team team = team();
        // the routes of the Sheet tools answer as they did, for both roles; the paths of the workspace are there only when the workspace is on
        for (ConsoleUser user : java.util.List.of(team.admin(), team.member())) {
            for (String path : java.util.List.of("/console/check", "/console/bulk", "/console/check/edit", "/console/reaudit", "/console/history")) {
                assertThat(status(user, path)).as(path).isEqualTo(200);
            }
            assertThat(status(user, "/console/workspace")).as("/console/workspace").isEqualTo("workspace".equals(group()) ? 200 : 404);
        }
        for (String language : new String[] {"vi", "en"}) {
            same("home-admin-" + language, page(team, team.admin(), "/console", language));
            same("home-member-" + language, page(team, team.member(), "/console", language));
            same("check-admin-" + language, page(team, team.admin(), "/console/check", language));
            same("check-member-" + language, page(team, team.member(), "/console/check", language));
        }
    }
}
