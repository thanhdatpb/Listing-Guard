package com.thanhdatpb.listingguard.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configured API keys. Several keys can be valid at once so a client key can be rotated without downtime:
 * add the new key, move the client over, then remove the old one.
 *
 * <p>Each entry is {@code name:key}. The name identifies the client in logs and rate limiting; the key is never
 * logged, never part of an error message, and only its SHA-256 is kept in memory. Malformed configuration makes
 * {@link #parse} fail, which stops the application from starting.
 */
public final class ApiKeys {

    public static final int MIN_KEY_LENGTH = 32;

    private static final Pattern ENTRY = Pattern.compile("^([a-z0-9][a-z0-9_-]{0,31}):([A-Za-z0-9._~+/=-]{" + MIN_KEY_LENGTH + ",256})$");
    private static final Pattern NAME_AND_REST = Pattern.compile("^([^:]*):(.*)$");

    private record Entry(String name, byte[] digest) {
    }

    private final List<Entry> entries;

    private ApiKeys(List<Entry> entries) {
        this.entries = entries;
    }

    /** @throws IllegalStateException if there is no key, or any entry is malformed or duplicated */
    public static ApiKeys parse(List<String> configured) {
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> digests = new HashSet<>();
        int position = 0;
        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            position++;
            Matcher matcher = ENTRY.matcher(raw.trim());
            if (!matcher.matches()) {
                throw new IllegalStateException("API key entry #" + position + " is malformed (" + describe(raw.trim())
                        + "). Expected name:key, where name is 1-32 characters from a-z 0-9 _ - and the key is at least "
                        + MIN_KEY_LENGTH + " characters from A-Z a-z 0-9 . _ ~ + / = -");
            }
            String name = matcher.group(1);
            byte[] digest = sha256(matcher.group(2));
            if (!names.add(name)) {
                throw new IllegalStateException("API key name '" + name + "' is configured twice");
            }
            if (!digests.add(HexFormat.of().formatHex(digest))) {
                throw new IllegalStateException("API key of '" + name + "' is the same as another configured key");
            }
            entries.add(new Entry(name, digest));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("No API keys configured. Set LISTING_GUARD_API_KEYS to name:key pairs, comma "
                    + "separated; each key needs at least " + MIN_KEY_LENGTH + " characters.");
        }
        return new ApiKeys(List.copyOf(entries));
    }

    /**
     * Checks a presented key against every configured key (no early exit on a match, digests compared in constant time).
     *
     * @return the name of the matching key, or empty
     */
    public Optional<String> authenticate(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        byte[] digest = sha256(presented);
        String match = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(digest, entry.digest()) && match == null) {
                match = entry.name();
            }
        }
        return Optional.ofNullable(match);
    }

    public List<String> names() {
        return entries.stream().map(Entry::name).toList();
    }

    /** What is wrong with an entry, without ever repeating the key itself. */
    private static String describe(String entry) {
        Matcher parts = NAME_AND_REST.matcher(entry);
        if (!parts.matches()) {
            return "there is no ':' between name and key";
        }
        if (!parts.group(1).matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return "the name is not valid";
        }
        if (parts.group(2).length() < MIN_KEY_LENGTH) {
            return "the key of '" + parts.group(1) + "' is shorter than " + MIN_KEY_LENGTH + " characters";
        }
        return "the key of '" + parts.group(1) + "' contains a character that is not allowed, or is too long";
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
