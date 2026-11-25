package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.util.WebUtils;

/** One short-lived continuation per editor surface and session. Only digests of checked data survive a response. */
public final class PilotDraft {
    public enum Surface { SINGLE, BULK }

    public record Fingerprint(String sku, String listing) implements Serializable {
        public static Fingerprint of(String sku, String listing) {
            return new Fingerprint(digest(sku), digest(listing));
        }
    }

    public record Update(String id, Set<Integer> changed) { }

    private record Previous(UUID client, String marketplace, String id, Instant expires, Map<Integer, Fingerprint> rows)
            implements Serializable { }

    private PilotDraft() { }

    public static Update advance(HttpSession session, Surface surface, Clock clock, UUID client, String marketplace,
                                 String supplied, Map<Integer, Fingerprint> rows) {
        String key = PilotDraft.class.getName() + "." + surface.name();
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        synchronized (WebUtils.getSessionMutex(session)) {
            Object value = session.getAttribute(key);
            session.setAttribute(key, new Previous(client, marketplace, id, now.plus(Duration.ofMinutes(30)), Map.copyOf(rows)));
            if (!(value instanceof Previous previous) || !previous.client().equals(client)
                    || !previous.marketplace().equals(marketplace) || !previous.id().equals(supplied)
                    || !now.isBefore(previous.expires())) {
                return new Update(id, Set.of());
            }
            Set<Integer> changed = new HashSet<>();
            rows.forEach((line, row) -> {
                Fingerprint old = previous.rows().get(line);
                if (old != null && Objects.equals(old.sku(), row.sku()) && !old.listing().equals(row.listing())) {
                    changed.add(line);
                }
            });
            return new Update(id, Set.copyOf(changed));
        }
    }

    private static String digest(String text) {
        if (text == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
