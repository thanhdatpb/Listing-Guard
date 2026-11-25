package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The account block at the foot of the console's sidebar (ADR-040): who is signed in and for which client. It is the signed-in
 * account's own e-mail and client name, shown to that account only; a sign-in page has no account, so the block is absent there.
 * One read of the client's row per page, by primary key. A client name that the sign-in made up (an identifier) is left out, so
 * the e-mail alone names the account.
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleAccountAdvice {

    /** {@code initial} is one upper-case letter for the avatar; {@code client} is empty when the row cannot be read. */
    public record AccountView(String initial, String email, String client) {
    }

    /** The name a magic-link account gets for its client ({@code MagicLinkService}): an identifier, not something to show. */
    private static final java.util.regex.Pattern GENERATED_NAME = java.util.regex.Pattern.compile("web-[0-9a-f]{24}");

    private final ClientRepository clients;
    private final ConsoleAccountRoles roles;
    private final ConsolePilot pilot;

    /** {@code readiness} is the "Sẵn sàng" page (ADR-053): the pilot's own, for an ADMIN. */
    public record Navigation(boolean pilot, boolean manage, boolean policyChanges, boolean pod, boolean review, boolean readiness) { }

    @ModelAttribute("consoleNavigation")
    public Navigation navigation() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean pilotEnabled = pilot.enabled();
        boolean manage = !pilotEnabled || authentication != null
                && authentication.getPrincipal() instanceof ConsoleUser user && roles.isAdmin(user);
        return new Navigation(pilotEnabled, manage, pilot.policyChanges(), pilot.pod(), pilot.review(), pilotEnabled && manage);
    }

    public ConsoleAccountAdvice(ClientRepository clients, ConsoleAccountRoles roles, ConsolePilot pilot) {
        this.clients = clients;
        this.roles = roles;
        this.pilot = pilot;
    }

    @ModelAttribute("consoleAccount")
    public AccountView consoleAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ConsoleUser user)) {
            return null;
        }
        String client = clients.findById(user.clientId()).map(Client::getName)
                .filter(name -> !GENERATED_NAME.matcher(name).matches()).orElse("");
        return new AccountView(initial(user.email()), user.email(), client);
    }

    static String initial(String email) {
        if (email == null || email.isBlank()) {
            return "?";
        }
        return email.strip().substring(0, email.strip().offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }
}
