package com.thanhdatpb.listingguard.api.console;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
}
