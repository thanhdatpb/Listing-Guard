package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudflareAccessJwtVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-13T03:00:00Z");
    private final ConsoleProperties properties = new ConsoleProperties(true, "https://console.example.test", true,
            "", 5, 30, Duration.ofMinutes(15), 5, ConsoleAuthMode.CLOUDFLARE_ACCESS,
            "https://example.cloudflareaccess.com", "aud-123", "https://certs.example.test", Duration.ofHours(1),
            "example.test", "", false, "");
    private KeyPair validPair;
    private KeyPair otherPair;

    @BeforeEach
    void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        validPair = generator.generateKeyPair();
        otherPair = generator.generateKeyPair();
    }

    @Test
    void acceptsValidJwtAndReturnsOnlyVerifiedEmailClaim() throws Exception {
        var verifier = verifier(Map.of("known", validPair));
        assertThat(verifier.verifyEmail(jwt(validPair, "known", "aud-123", now.plusSeconds(60).getEpochSecond(), null,
                " Person@Example.Test "))).isEqualTo("person@example.test");
    }

    @Test
    void rejectsBadSignatureWrongAudienceAndExpiredTokens() throws Exception {
        var verifier = verifier(Map.of("known", validPair));
        assertThat(verifier.verifyEmail(jwt(otherPair, "known", "aud-123", now.plusSeconds(60).getEpochSecond(), null,
                "person@example.test"))).isNull();
        assertThat(verifier.verifyEmail(jwt(validPair, "known", "wrong-aud", now.plusSeconds(60).getEpochSecond(), null,
                "person@example.test"))).isNull();
        assertThat(verifier.verifyEmail(jwt(validPair, "known", "aud-123", now.minusSeconds(31).getEpochSecond(), null,
                "person@example.test"))).isNull();
    }

    @Test
    void unknownKidRefreshesSigningKeysBeforeRejectingOrAccepting() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var verifier = new CloudflareAccessJwtVerifier(mapper, Clock.fixed(now, ZoneOffset.UTC), properties, () ->
                loads.incrementAndGet() == 1 ? Map.of("old", validPair.getPublic()) : Map.of("rotated", validPair.getPublic()));
        assertThat(verifier.verifyEmail(jwt(validPair, "rotated", "aud-123", now.plusSeconds(60).getEpochSecond(), null,
                "person@example.test"))).isEqualTo("person@example.test");
        assertThat(loads).hasValue(2);
    }

    @Test
    void rejectsNotYetValidTokenOutsideClockTolerance() throws Exception {
        var verifier = verifier(Map.of("known", validPair));
        assertThat(verifier.verifyEmail(jwt(validPair, "known", "aud-123", now.plusSeconds(60).getEpochSecond(),
                now.plusSeconds(31).getEpochSecond(), "person@example.test"))).isNull();
    }

    private CloudflareAccessJwtVerifier verifier(Map<String, KeyPair> keys) {
        return new CloudflareAccessJwtVerifier(mapper, Clock.fixed(now, ZoneOffset.UTC), properties,
                () -> keys.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().getPublic())));
    }

    private String jwt(KeyPair signingPair, String kid, String audience, long exp, Long nbf, String email) throws Exception {
        Map<String, Object> header = Map.of("alg", "RS256", "kid", kid, "typ", "JWT");
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", "https://example.cloudflareaccess.com");
        claims.put("aud", audience);
        claims.put("exp", exp);
        if (nbf != null) claims.put("nbf", nbf);
        claims.put("email", email);
        String input = enc(mapper.writeValueAsBytes(header)) + "." + enc(mapper.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + enc(signature.sign());
    }

    private String enc(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
