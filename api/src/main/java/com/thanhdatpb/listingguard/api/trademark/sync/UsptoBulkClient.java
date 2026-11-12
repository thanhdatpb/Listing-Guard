package com.thanhdatpb.listingguard.api.trademark.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchiveException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The client of the USPTO Open Data Portal Bulk Datasets API for the {@code uspto-sync} job (ADR-009, PR A2d, Decision 5).
 * <b>Every path, parameter and field it uses is a setting and unverified</b>; no ODP page could be read.
 *
 * <ul>
 *   <li>The API key goes only in its header, only to the host of {@code base-url}, and never into a log, an exception or a
 *       stored value; a message that would contain it has it replaced.</li>
 *   <li>Redirects are followed here, not by the HTTP client: HTTPS only (plain HTTP only to this machine), only to an allowed
 *       host, at most {@code max-redirects}, and the key header is dropped as soon as the host differs from the API's.</li>
 *   <li>At least {@code min-interval} between the starts of two requests. {@code 429}, {@code 408}, {@code 5xx} (500, 502,
 *       503, 504), a timeout, a connection lost in the middle of a body, a size or a checksum that differs and a damaged
 *       archive are tried again, up to {@code max-attempts}, after a delay that doubles from {@code initial-backoff} to
 *       {@code max-backoff}; a {@code Retry-After} is honored and one longer than {@code max-backoff} stops the run. A
 *       {@code 404} is not tried again; {@code 401} and {@code 403} stop the run.</li>
 *   <li>A download goes to {@code <name>.part} and is renamed only after its checks pass.</li>
 * </ul>
 * {@link HttpURLConnection} is used because it has a read timeout per read, so a stalled body ends; the JDK
 * {@code HttpClient} has none for a streamed body.
 */
public class UsptoBulkClient {

    /** One file of a listing; {@code size}, {@code checksum} and {@code uri} are null when the listing does not give them. */
    public record RemoteFile(String name, Long size, String checksum, URI uri) {
    }

    /** What a listing found: the files, and whether the answer had a file list at all. */
    public record Listing(List<RemoteFile> files, int skipped, boolean listPresent) {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Checks a downloaded file before it is kept; throwing refuses it. */
    @FunctionalInterface
    public interface Verifier {
        void verify(Path file) throws IOException;
    }

    public record Downloaded(long sizeBytes, String sha256, int attempts) {
    }

    /** A listing or a download that did not succeed. The message never holds the key. */
    public static final class DownloadException extends Exception {

        public enum Reason {
            /** the source has no such file ({@code 404}, {@code 410}) */
            NOT_FOUND,
            /** tried {@code max-attempts} times, or an answer that is not tried again */
            FAILED,
            /** the archive breaks a safety rule; fetching it again gives the same archive */
            UNSAFE,
            /** {@code 401} or {@code 403}: the key; the whole run stops */
            REFUSED,
            /** the source asks to wait longer than {@code max-backoff}; the whole run stops */
            RATE_LIMITED
        }

        private final Reason reason;
        private final int attempts;

        DownloadException(Reason reason, String message, int attempts) {
            super(message, null, false, false);
            this.reason = reason;
            this.attempts = attempts;
        }

        public Reason reason() {
            return reason;
        }

        public int attempts() {
            return attempts;
        }

        /** True when no other file should be tried in this run. */
        public boolean stopsRun() {
            return reason == Reason.REFUSED || reason == Reason.RATE_LIMITED;
        }
    }

    /** A failure of one attempt that the next attempt may not have. */
    private static final class Retryable extends Exception {
        private final Duration retryAfter;

        Retryable(String message, Duration retryAfter) {
            super(message, null, false, false);
            this.retryAfter = retryAfter;
        }
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run(int attempt) throws IOException, DownloadException, Retryable;
    }

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> RETRIED = Set.of(408, 429, 500, 502, 503, 504);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BUFFER_BYTES = 64 * 1024;

    private final UsptoSyncProperties.Download settings;
    private final Clock clock;
    private final Sleeper sleeper;
    private final URI base;
    private Instant lastRequest;

    public UsptoBulkClient(UsptoSyncProperties.Download settings, Clock clock, Sleeper sleeper) {
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
        this.base = URI.create(settings.baseUrl());
    }

    static boolean isLoopback(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    // --- listing ---

    /**
     * The files of a product.
     *
     * @param query the query, its placeholders already replaced (for example the dates of a daily listing)
     */
    public Listing list(String product, String query) throws DownloadException {
        URI uri = onBase(settings.productPath().replace("{product}", encode(product)), query);
        String what = "the listing of " + product;
        return withRetries(what, attempt -> {
            HttpURLConnection connection = connect(uri, what, attempt);
            try {
                check(connection, what, attempt);
                return parse(read(connection, what), what, attempt);
            } finally {
                connection.disconnect();
            }
        });
    }

    private byte[] read(HttpURLConnection connection, String what) throws IOException, DownloadException {
        long limit = settings.maxListingSize().toBytes();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = connection.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                body.write(buffer, 0, n);
                if (body.size() > limit) {
                    throw new DownloadException(DownloadException.Reason.FAILED, what + " is larger than "
                            + "listing-guard.trademark.uspto.sync.download.max-listing-size (" + limit + " bytes).", 1);
                }
            }
        }
        return body.toByteArray();
    }

    private Listing parse(byte[] body, String what, int attempt) throws DownloadException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new DownloadException(DownloadException.Reason.FAILED, what + " is not JSON; check base-url and "
                    + "product-path (ADR-009, verification step 2).", attempt);
        }
        JsonNode list = root == null ? null : root.at(settings.filesPointer());
        if (list == null || list.isMissingNode() || list.isNull()) {
            return new Listing(List.of(), 0, false);
        }
        if (!list.isArray()) {
            throw new DownloadException(DownloadException.Reason.FAILED, what + ": " + settings.filesPointer()
                    + " is not a list (listing-guard.trademark.uspto.sync.download.files-pointer).", attempt);
        }
        UsptoSyncProperties.Fields fields = settings.fields();
        List<RemoteFile> files = new ArrayList<>();
        int skipped = 0;
        for (JsonNode item : list) {
            JsonNode name = item.get(fields.name());
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                skipped++;
                continue;
            }
            files.add(new RemoteFile(name.asText().trim(), size(item.get(fields.size())), checksum(item, fields),
                    uri(item.get(fields.url()))));
        }
        return new Listing(List.copyOf(files), skipped, true);
    }

    private static Long size(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong() < 0 ? null : node.asLong();
        }
        if (node.isTextual() && node.asText().trim().matches("[0-9]{1,18}")) {
            return Long.valueOf(node.asText().trim());
        }
        return null;
    }

    private static String checksum(JsonNode item, UsptoSyncProperties.Fields fields) {
        if (fields.checksum().isEmpty()) {
            return null;
        }
        JsonNode node = item.get(fields.checksum());
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText().trim();
    }

    private URI uri(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(node.asText().trim());
            return uri.isAbsolute() ? uri : base.resolve(uri);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // --- download ---

    /**
     * Downloads a file to {@code target} through {@code <target>.part}: the bytes are counted and hashed, compared with the
     * listed size, the {@code Content-Length} and the listed checksum when there is one, handed to the verifier, and only
     * then renamed. A failed attempt leaves no file.
     */
    public Downloaded download(String product, RemoteFile file, Path target, Verifier verifier) throws DownloadException {
        URI uri = file.uri() != null ? file.uri()
                : onBase(settings.filePath().replace("{product}", encode(product)).replace("{file}", encode(file.name())),
                null);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        String what = "the file " + file.name();
        if (file.size() != null && file.size() > settings.maxFileSize().toBytes()) {
            throw new DownloadException(DownloadException.Reason.FAILED, what + " is listed with " + file.size()
                    + " bytes, more than listing-guard.trademark.uspto.sync.download.max-file-size ("
                    + settings.maxFileSize().toBytes() + " bytes).", 0);
        }
        return withRetries(what, attempt -> {
            HttpURLConnection connection = connect(uri, what, attempt);
            try {
                check(connection, what, attempt);
                return receive(connection, file, part, target, verifier, what, attempt);
            } finally {
                connection.disconnect();
                Files.deleteIfExists(part);
            }
        });
    }

    private Downloaded receive(HttpURLConnection connection, RemoteFile file, Path part, Path target, Verifier verifier,
                               String what, int attempt) throws IOException, DownloadException, Retryable {
        long max = settings.maxFileSize().toBytes();
        long declared = connection.getContentLengthLong();
        if (declared > max || (file.size() != null && file.size() > max)) {
            throw new DownloadException(DownloadException.Reason.FAILED, what + " is larger than "
                    + "listing-guard.trademark.uspto.sync.download.max-file-size (" + max + " bytes).", attempt);
        }
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest listed = file.checksum() == null ? null : digest(settings.checksumAlgorithm());
        long count = 0;
        Files.createDirectories(part.getParent());
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                count += n;
                if (count > max) {
                    throw new DownloadException(DownloadException.Reason.FAILED, what + " is larger than "
                            + "listing-guard.trademark.uspto.sync.download.max-file-size (" + max + " bytes).", attempt);
                }
                sha256.update(buffer, 0, n);
                if (listed != null) {
                    listed.update(buffer, 0, n);
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new Retryable(what + ": the transfer broke off after " + count + " bytes (" + describe(e) + ")", null);
        }
        if (declared >= 0 && count != declared) {
            throw new Retryable(what + ": received " + count + " bytes, Content-Length says " + declared, null);
        }
        if (file.size() != null && count != file.size()) {
            throw new Retryable(what + ": received " + count + " bytes, the listing says " + file.size(), null);
        }
        if (listed != null && !HexFormat.of().formatHex(listed.digest()).equals(normalizeChecksum(file.checksum()))) {
            throw new Retryable(what + ": the " + settings.checksumAlgorithm() + " differs from the listing", null);
        }
        try {
            verifier.verify(part);
        } catch (UsptoArchiveException e) {
            if (e.unsafe()) {
                throw new DownloadException(DownloadException.Reason.UNSAFE, what + " is refused: " + e.getMessage(),
                        attempt);
            }
            throw new Retryable(what + " is damaged: " + e.getMessage(), null);
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Downloaded(count, HexFormat.of().formatHex(sha256.digest()), attempt);
    }

    private static String normalizeChecksum(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        int colon = text.indexOf(':');
        return colon >= 0 ? text.substring(colon + 1) : text;
    }

    // --- one exchange ---

    private <T> T withRetries(String what, Attempt<T> attempt) throws DownloadException {
        String last = null;
        for (int n = 1; n <= settings.maxAttempts(); n++) {
            Duration wait;
            try {
                return attempt.run(n);
            } catch (Retryable e) {
                last = e.getMessage();
                if (e.retryAfter != null && e.retryAfter.compareTo(settings.maxBackoff()) > 0) {
                    throw new DownloadException(DownloadException.Reason.RATE_LIMITED, what + ": the source asks to wait "
                            + e.retryAfter.toSeconds() + " s, longer than max-backoff (" + settings.maxBackoff().toSeconds()
                            + " s); the next run continues.", n);
                }
                wait = e.retryAfter != null ? e.retryAfter : backoff(n);
            } catch (UsptoArchiveException e) {
                // the verifier's own IO; handled in receive, so only an unexpected path gets here
                throw new DownloadException(e.unsafe() ? DownloadException.Reason.UNSAFE : DownloadException.Reason.FAILED,
                        scrub(what + ": " + e.getMessage()), n);
            } catch (IOException e) {
                last = what + ": " + describe(e);
                wait = backoff(n);
            }
            if (n < settings.maxAttempts()) {
                sleep(wait, what, n);
            }
        }
        throw new DownloadException(DownloadException.Reason.FAILED, scrub(what + ": failed after " + settings.maxAttempts()
                + " attempts; last: " + last), settings.maxAttempts());
    }

    private Duration backoff(int attempt) {
        Duration delay = settings.initialBackoff();
        for (int i = 1; i < attempt && delay.compareTo(settings.maxBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : delay;
    }

    private void sleep(Duration duration, String what, int attempt) throws DownloadException {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException(DownloadException.Reason.FAILED, what + ": interrupted.", attempt);
        }
    }

    /** Waits until {@code min-interval} has passed since the start of the previous request. */
    private void pace(String what, int attempt) throws DownloadException {
        Instant now = clock.instant();
        if (lastRequest != null) {
            Duration since = Duration.between(lastRequest, now);
            if (since.compareTo(settings.minInterval()) < 0) {
                sleep(settings.minInterval().minus(since), what, attempt);
            }
        }
        lastRequest = clock.instant();
    }

    private HttpURLConnection connect(URI uri, String what, int attempt) throws IOException, DownloadException {
        URI current = uri;
        for (int hop = 0; ; hop++) {
            requireAllowed(current, what, attempt);
            pace(what, attempt);
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(timeout(settings.connectTimeout()));
            connection.setReadTimeout(timeout(settings.readTimeout()));
            connection.setRequestProperty("User-Agent", settings.userAgent());
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (settings.hasKey() && sameOrigin(current, base)) {
                connection.setRequestProperty(settings.apiKeyHeader(), settings.apiKey());
            }
            int code = connection.getResponseCode();
            if (!REDIRECTS.contains(code)) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("a redirect (HTTP " + code + ") without a Location");
            }
            if (hop >= settings.maxRedirects()) {
                throw new DownloadException(DownloadException.Reason.FAILED, what + ": more than "
                        + settings.maxRedirects() + " redirects.", attempt);
            }
            try {
                current = current.resolve(new URI(location.trim()));
            } catch (URISyntaxException e) {
                throw new DownloadException(DownloadException.Reason.FAILED, what + ": a redirect to an address that is "
                        + "not valid.", attempt);
            }
        }
    }

    private void check(HttpURLConnection connection, String what, int attempt)
            throws IOException, DownloadException, Retryable {
        int code = connection.getResponseCode();
        if (code == 200) {
            return;
        }
        if (code == 404 || code == 410) {
            throw new DownloadException(DownloadException.Reason.NOT_FOUND, what + ": the source answered HTTP " + code
                    + ".", attempt);
        }
        if (code == 401 || code == 403) {
            throw new DownloadException(DownloadException.Reason.REFUSED, what + ": the source refused the request (HTTP "
                    + code + "); check LISTING_GUARD_USPTO_ODP_API_KEY and api-key-header.", attempt);
        }
        if (RETRIED.contains(code)) {
            throw new Retryable(what + ": HTTP " + code, retryAfter(connection.getHeaderField("Retry-After")));
        }
        throw new DownloadException(DownloadException.Reason.FAILED, what + ": the source answered HTTP " + code + ".",
                attempt);
    }

    private Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("[0-9]{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(text));
        }
        try {
            Duration wait = Duration.between(clock.instant(),
                    ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void requireAllowed(URI uri, String what, int attempt) throws DownloadException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(host);
        if (!(https || loopbackHttp) || uri.getRawUserInfo() != null || !settings.allowedHosts().contains(host)) {
            throw new DownloadException(DownloadException.Reason.FAILED, what + ": an address on host '" + host
                    + "' (scheme " + uri.getScheme() + ") is not allowed; listing-guard.trademark.uspto.sync.download."
                    + "allowed-hosts lists the hosts a download or a redirect may use, over HTTPS (ADR-009, verification "
                    + "step 5).", attempt);
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private URI onBase(String path, String query) {
        String root = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                : settings.baseUrl();
        return URI.create(root + path + (query == null || query.isBlank() ? "" : "?" + query));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int timeout(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    private String describe(IOException e) {
        return scrub(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

    /** Replaces the key wherever a message would carry it. */
    private String scrub(String text) {
        return settings.hasKey() && text != null ? text.replace(settings.apiKey(), "[redacted]") : text;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
