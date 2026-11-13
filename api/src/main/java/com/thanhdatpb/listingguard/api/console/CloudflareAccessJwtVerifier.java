package com.thanhdatpb.listingguard.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Verifies the Cloudflare Access assertion before any identity claim is used. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CloudflareAccessJwtVerifier {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConsoleProperties properties;
    private final Supplier<Map<String, PublicKey>> keyLoader;
    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile Instant keysLoadedAt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public CloudflareAccessJwtVerifier(ObjectMapper mapper, Clock clock, ConsoleProperties properties) {
        this(mapper, clock, properties, () -> loadKeys(properties.resolvedAccessCertsUrl()));
    }

    CloudflareAccessJwtVerifier(ObjectMapper mapper, Clock clock, ConsoleProperties properties,
                                Supplier<Map<String, PublicKey>> keyLoader) {
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.keyLoader = keyLoader;
    }

    public String verifyEmail(String jwt) {
        try {
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) return null;
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) return null;
            String kid = header.path("kid").asText();
            if (kid.isBlank()) return null;
            PublicKey key = key(kid, false);
            if (key == null) key = key(kid, true);
            if (key == null) return null;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) return null;

            JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String expectedIssuer = properties.accessTeamDomain().contains("://")
                    ? properties.accessTeamDomain().replaceAll("/+$", "")
                    : "https://" + properties.accessTeamDomain().replaceAll("/+$", "");
            if (!expectedIssuer.equals(claims.path("iss").asText())) return null;
            if (!hasAudience(claims.path("aud"), properties.accessAud())) return null;
            Instant now = clock.instant();
            if (claims.path("exp").isMissingNode() || now.minus(CLOCK_SKEW).getEpochSecond() >= claims.path("exp").asLong()) return null;
            if (claims.has("nbf") && now.plus(CLOCK_SKEW).getEpochSecond() < claims.path("nbf").asLong()) return null;
            String email = claims.path("email").asText("").strip().toLowerCase(java.util.Locale.ROOT);
            return email.isBlank() || email.length() > 254 ? null : email;
        } catch (Exception ex) {
            return null;
        }
    }

    private PublicKey key(String kid, boolean forceRefresh) {
        synchronized (this) {
            if (forceRefresh || cachedKeys.isEmpty() ||
                    !clock.instant().isBefore(keysLoadedAt.plus(properties.accessCertCacheTtl()))) {
                try {
                    cachedKeys = Map.copyOf(keyLoader.get());
                    keysLoadedAt = clock.instant();
                } catch (RuntimeException ex) {
                    if (forceRefresh || cachedKeys.isEmpty()
                            || !clock.instant().isBefore(keysLoadedAt.plus(properties.accessCertCacheTtl()))) return null;
                }
            }
            return cachedKeys.get(kid);
        }
    }

    private static boolean hasAudience(JsonNode aud, String expected) {
        if (aud.isTextual()) return expected.equals(aud.asText());
        if (aud.isArray()) {
            for (JsonNode value : aud) if (expected.equals(value.asText())) return true;
        }
        return false;
    }

    private static Map<String, PublicKey> loadKeys(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("Cloudflare Access cert endpoint returned HTTP " + response.statusCode());
            JsonNode root = new ObjectMapper().readTree(response.body()).path("public_certs");
            Map<String, PublicKey> keys = new HashMap<>();
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (JsonNode cert : root) {
                String kid = cert.path("kid").asText();
                String pem = cert.path("cert").asText();
                if (!kid.isBlank() && !pem.isBlank()) {
                    X509Certificate parsed = (X509Certificate) factory.generateCertificate(
                            new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
                    keys.put(kid, parsed.getPublicKey());
                }
            }
            if (keys.isEmpty()) throw new IllegalStateException("Cloudflare Access cert endpoint returned no public keys.");
            return keys;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not load Cloudflare Access signing keys.", ex);
        }
    }
}
