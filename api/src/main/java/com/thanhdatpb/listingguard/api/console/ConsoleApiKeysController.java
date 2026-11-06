package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.client.ApiKey;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.IssuedKey;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.KeyLimitReachedException;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.KeyNotFoundException;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning.RevokeOutcome;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The "API keys" page of the console (ADR-014, part B4a): the keys of the signed-in account's client, a form to create one and
 * a confirmation page to revoke one. Everything goes through {@link ClientProvisioning}, so a key made here is the same key
 * the admin job makes, with the same audit lines.
 *
 * <p>The secret of a new key is in the response of the POST that created it and nowhere else: not in a redirect, the session,
 * a URL or a log, and the response is marked {@code no-store}. Reloading that page asks the browser to send the form again,
 * which makes a new key (the limit bounds it) rather than showing the old secret.
 *
 * <p>A key of another client is a 404, never a 403, so the page does not tell whether an id is in use.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleApiKeysController {

    static final String LIST_VIEW = "console/api-keys";
    static final String REVOKE_VIEW = "console/api-key-revoke";

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** What the table shows of a key, times in UTC. The hash is never part of it. */
    public record KeyRow(String keyId, String label, String created, String lastUsed) {

        static KeyRow of(ApiKey key) {
            return new KeyRow(key.getKeyId(), key.getLabel(), TIME.format(key.getCreatedAt()),
                    key.getLastUsedAt() == null ? null : TIME.format(key.getLastUsedAt()));
        }
    }

    private final ClientProvisioning provisioning;
    private final ConsoleProperties properties;

    public ConsoleApiKeysController(ClientProvisioning provisioning, ConsoleProperties properties) {
        this.provisioning = provisioning;
        this.properties = properties;
    }

    @GetMapping("/console/api-keys")
    public String list(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        populate(model, user, "");
        return LIST_VIEW;
    }

    /**
     * Creates a key and shows its secret once, in this response. A label that is too long or holds a control character is sent
     * back with a 400 and nothing is created; a client that already has the most active keys gets a 409.
     */
    @PostMapping("/console/api-keys")
    public String create(@RequestParam(name = "label", defaultValue = "") String label,
                         @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        String cleaned = label.strip();
        String problem = problemWith(cleaned);
        if (problem != null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            populate(model, user, label);
            model.addAttribute("error", problem);
            return LIST_VIEW;
        }
        try {
            IssuedKey issued = provisioning.createKey(user.clientId(), cleaned.isEmpty() ? null : cleaned, properties.maxActiveKeys());
            populate(model, user, "");
            model.addAttribute("issued", issued);
        } catch (KeyLimitReachedException ex) {
            response.setStatus(HttpStatus.CONFLICT.value());
            populate(model, user, label);
            model.addAttribute("error", "You already have " + ex.limit() + " active keys, the most allowed. Revoke one to create another.");
        }
        return LIST_VIEW;
    }

    /** The confirmation step: it only describes the key and asks. Nothing is revoked by a GET. */
    @GetMapping("/console/api-keys/{keyId:[a-z0-9]{1,16}}/revoke")
    public String confirmRevoke(@PathVariable String keyId, @AuthenticationPrincipal ConsoleUser user, Model model,
                                HttpServletResponse response) {
        noStore(response);
        KeyRow key = provisioning.activeKeys(user.clientId()).stream()
                .filter(found -> found.getKeyId().equals(keyId))
                .map(KeyRow::of)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("email", user.email());
        model.addAttribute("key", key);
        return REVOKE_VIEW;
    }

    @PostMapping("/console/api-keys/{keyId:[a-z0-9]{1,16}}/revoke")
    public String revoke(@PathVariable String keyId, @AuthenticationPrincipal ConsoleUser user, RedirectAttributes redirect) {
        try {
            RevokeOutcome outcome = provisioning.revokeKey(user.clientId(), keyId);
            redirect.addFlashAttribute("notice", outcome == RevokeOutcome.REVOKED
                    ? "Key " + keyId + " is revoked. Applications that use it are refused from now on."
                    : "Key " + keyId + " was already revoked.");
        } catch (KeyNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/console/api-keys";
    }

    private void populate(Model model, ConsoleUser user, String label) {
        List<KeyRow> rows = provisioning.activeKeys(user.clientId()).stream().map(KeyRow::of).toList();
        model.addAttribute("email", user.email());
        model.addAttribute("keys", rows);
        model.addAttribute("activeCount", rows.size());
        model.addAttribute("maxActiveKeys", properties.maxActiveKeys());
        model.addAttribute("canCreate", rows.size() < properties.maxActiveKeys());
        model.addAttribute("maxLabelLength", ClientProvisioning.MAX_LABEL_LENGTH);
        model.addAttribute("label", label);
    }

    private static String problemWith(String label) {
        if (label.length() > ClientProvisioning.MAX_LABEL_LENGTH) {
            return "The label is longer than " + ClientProvisioning.MAX_LABEL_LENGTH + " characters.";
        }
        if (CONTROL.matcher(label).find()) {
            return "The label cannot hold control characters.";
        }
        return null;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
