package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A signed-in console session for a test, without the e-mail round trip (that is tested by
 * {@code ConsoleMagicLinkIntegrationTest}). The principal is the same {@link ConsoleUser} the sign-in stores.
 */
public final class ConsoleTestSupport {

    private ConsoleTestSupport() {
    }

    /** The console user of a client created for the test; the e-mail is synthetic and unique. */
    public static ConsoleUser userOf(TestClient client) {
        return new ConsoleUser("editor-" + UUID.randomUUID() + "@example.test", client.id());
    }

    public static RequestPostProcessor signedIn(ConsoleUser user) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSOLE"))));
    }

    /** The form of an Etsy listing with every field the rules require, so a check of it finds no ERROR. */
    public static Map<String, String> etsyForm(String title) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("marketplace", "ETSY");
        form.put("title", title);
        form.put("tags", "mug\nceramic");
        form.put("description", "A synthetic mug.");
        form.put("price", "19.99");
        form.put("quantity", "5");
        form.put("category", "Home");
        form.put("whoMade", "i_did");
        form.put("whenMade", "made_to_order");
        return form;
    }

    /** A signed-in press of "Check" with a CSRF token. */
    public static MockHttpServletRequestBuilder checkPost(ConsoleUser user, Map<String, String> form) {
        MockHttpServletRequestBuilder request = post("/console/check").with(signedIn(user)).with(csrf());
        form.forEach(request::param);
        return request;
    }

    /** The inner HTML of the {@code <section>} with this id. */
    public static String section(String html, String id) {
        Matcher matcher = Pattern.compile("<section[^>]*id=\"" + id + "\"[^>]*>(.*?)</section>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("a section with id " + id).isTrue();
        return matcher.group(1);
    }
}
