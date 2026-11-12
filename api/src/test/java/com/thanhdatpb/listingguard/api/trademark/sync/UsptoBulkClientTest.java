package com.thanhdatpb.listingguard.api.trademark.sync;

import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.live;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.xml;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.trademark.sync.FakeOdpServer.Behaviour;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoBulkClient.DownloadException;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoBulkClient.DownloadException.Reason;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoBulkClient.RemoteFile;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchive;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * {@link UsptoBulkClient} against a stand-in of the ODP API on the loopback address (no real network): the key goes to the
 * API host only and never into a message, a 429 or a lost connection is tried again, a missing file is not, a damaged
 * archive is fetched again and an unsafe one is refused, and a redirect cannot reach a host that is not allowed. The
 * sleeper records the waits instead of sleeping.
 */
class UsptoBulkClientTest {

    private static final String KEY = "odp-test-key-0123456789abcdef";
    private static final String DAILY = "TRTDXFAP";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T11:00:00Z"), ZoneOffset.UTC);
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(2);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(3);

    @TempDir
    Path dir;

    private FakeOdpServer server;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new FakeOdpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private UsptoSyncProperties.Download settings(Duration maxBackoff, String checksumField, DataSize maxFileSize,
                                                  List<String> hosts) {
        return new UsptoSyncProperties.Download(true, KEY, FakeOdpServer.KEY_HEADER, server.baseUrl(),
                "/api/v1/datasets/products/{product}", "/api/v1/datasets/products/files/{product}/{file}", DAILY, "TRTYRAP",
                "fileDataFromDate={from}&fileDataToDate={to}&includeFiles=true", "includeFiles=true", 31,
                "/bulkDataProductBag/0/productFileBag/fileDataBag",
                new UsptoSyncProperties.Fields("fileName", "fileSize", "fileDownloadURI", checksumField), "SHA-256", hosts,
                5, MIN_INTERVAL, 3, INITIAL_BACKOFF, maxBackoff, Duration.ofSeconds(5), Duration.ofSeconds(5), 60,
                maxFileSize, DataSize.ofKilobytes(64), "listing-guard-uspto-sync-test");
    }

    private UsptoBulkClient client() {
        return client(settings(Duration.ofSeconds(30), "", DataSize.ofMegabytes(10), List.of("127.0.0.1")));
    }

    private UsptoBulkClient client(UsptoSyncProperties.Download settings) {
        return new UsptoBulkClient(settings, CLOCK, sleeps::add);
    }

    private static byte[] day(String name) {
        return zip(name, xml(live("97000001", "20261110", "Zorvex")));
    }

    private RemoteFile listed(UsptoBulkClient client, String name) throws DownloadException {
        return client.list(DAILY, "fileDataFromDate=2026-11-01&fileDataToDate=2026-11-12&includeFiles=true").files()
                .stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static UsptoBulkClient.Verifier zipCheck() {
        return path -> UsptoArchive.inspect(path, UsptoArchive.Limits.defaults());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private List<String> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Test
    void aListingNamesTheFilesAndTheKeyGoesToTheApiHostOnly() throws Exception {
        byte[] bytes = day("apc261110.zip");
        server.offer(DAILY, "apc261110.zip", bytes);

        UsptoBulkClient.Listing listing = client().list(DAILY,
                "fileDataFromDate=2026-11-01&fileDataToDate=2026-11-12&includeFiles=true");

        assertThat(listing.listPresent()).isTrue();
        assertThat(listing.files()).containsExactly(new RemoteFile("apc261110.zip", (long) bytes.length, null,
                URI.create(server.baseUrl() + "/files/apc261110.zip")));
        FakeOdpServer.Request request = server.requests().get(0);
        assertThat(request.path()).isEqualTo("/api/v1/datasets/products/" + DAILY);
        assertThat(request.query()).isEqualTo("fileDataFromDate=2026-11-01&fileDataToDate=2026-11-12&includeFiles=true");
        assertThat(request.key()).isEqualTo(KEY);
    }

    @Test
    void aDownloadIsCheckedThenRenamed() throws Exception {
        byte[] bytes = day("apc261110.zip");
        server.offer(DAILY, "apc261110.zip", bytes);
        UsptoBulkClient client = client();

        UsptoBulkClient.Downloaded downloaded = client.download(DAILY, listed(client, "apc261110.zip"),
                dir.resolve("apc261110.zip"), zipCheck());

        assertThat(downloaded).isEqualTo(new UsptoBulkClient.Downloaded(bytes.length, sha256(bytes), 1));
        assertThat(Files.readAllBytes(dir.resolve("apc261110.zip"))).isEqualTo(bytes);
        assertThat(leftovers()).containsExactly("apc261110.zip");
    }

    @Test
    void aConnectionLostInTheMiddleOfTheBodyIsTriedAgain() throws Exception {
        byte[] bytes = day("apc261110.zip");
        server.offer(DAILY, "apc261110.zip", bytes).behave("apc261110.zip", Behaviour.DROP_ONCE);
        UsptoBulkClient client = client();

        UsptoBulkClient.Downloaded downloaded = client.download(DAILY, listed(client, "apc261110.zip"),
                dir.resolve("apc261110.zip"), zipCheck());

        assertThat(downloaded.attempts()).isEqualTo(2);
        assertThat(downloaded.sha256()).isEqualTo(sha256(bytes));
        assertThat(server.downloads("apc261110.zip")).isEqualTo(2);
        assertThat(sleeps).as("the backoff before the second attempt").contains(INITIAL_BACKOFF);
        assertThat(leftovers()).containsExactly("apc261110.zip");
    }

    @Test
    void a429IsTriedAgainAfterItsRetryAfter() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip")).behave("apc261110.zip", Behaviour.RATE_LIMITED_ONCE);
        UsptoBulkClient client = client();

        UsptoBulkClient.Downloaded downloaded = client.download(DAILY, listed(client, "apc261110.zip"),
                dir.resolve("apc261110.zip"), zipCheck());

        assertThat(downloaded.attempts()).isEqualTo(2);
        assertThat(sleeps).as("Retry-After: 1").contains(Duration.ofSeconds(1)).doesNotContain(INITIAL_BACKOFF);
    }

    @Test
    void aRetryAfterLongerThanMaxBackoffStopsTheRun() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip")).behave("apc261110.zip", Behaviour.RATE_LIMITED_ONCE);
        UsptoBulkClient client = client(settings(Duration.ofMillis(500), "", DataSize.ofMegabytes(10),
                List.of("127.0.0.1")));
        RemoteFile file = listed(client, "apc261110.zip");

        assertThatThrownBy(() -> client.download(DAILY, file, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED);
                    assertThat(e.stopsRun()).isTrue();
                });
        assertThat(server.downloads("apc261110.zip")).isEqualTo(1);
    }

    @Test
    void aFileThatFailsEveryTimeIsGivenUpAfterMaxAttemptsWithoutTheKeyInTheMessage() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip")).behave("apc261110.zip", Behaviour.FAIL);
        UsptoBulkClient client = client();
        RemoteFile file = listed(client, "apc261110.zip");

        assertThatThrownBy(() -> client.download(DAILY, file, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.FAILED);
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.getMessage()).contains("HTTP 500").doesNotContain(KEY);
                });
        assertThat(server.downloads("apc261110.zip")).isEqualTo(3);
        assertThat(sleeps).contains(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2));
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aFileTheSourceDoesNotHaveIsNotTriedAgain() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip")).behave("apc261110.zip", Behaviour.MISSING);
        UsptoBulkClient client = client();
        RemoteFile file = listed(client, "apc261110.zip");

        assertThatThrownBy(() -> client.download(DAILY, file, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
        assertThat(server.downloads("apc261110.zip")).isEqualTo(1);
    }

    @Test
    void aRefusedKeyStopsTheRunAndIsNotPrinted() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip")).behave("apc261110.zip", Behaviour.REFUSED);
        UsptoBulkClient client = client();
        RemoteFile file = listed(client, "apc261110.zip");

        assertThatThrownBy(() -> client.download(DAILY, file, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(e.stopsRun()).isTrue();
                    assertThat(e.getMessage()).contains("LISTING_GUARD_USPTO_ODP_API_KEY").doesNotContain(KEY);
                });
        assertThat(client.toString()).doesNotContain(KEY);
        assertThat(settings(Duration.ofSeconds(30), "", DataSize.ofMegabytes(10), List.of("127.0.0.1")).toString())
                .doesNotContain(KEY).contains("(set)");
    }

    @Test
    void aDamagedArchiveIsFetchedAgainAndAnUnsafeOneIsNot() throws Exception {
        server.offer(DAILY, "apc261110.zip", "<html>an error page, not a zip</html>".getBytes(StandardCharsets.UTF_8));
        server.offer(DAILY, "apc261111.zip", zip("../apc261111.xml",
                xml(live("97000002", "20261111", "Brasktone")).getBytes(StandardCharsets.UTF_8)));
        UsptoBulkClient client = client();
        RemoteFile damaged = listed(client, "apc261110.zip");
        RemoteFile unsafe = listed(client, "apc261111.zip");

        assertThatThrownBy(() -> client.download(DAILY, damaged, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.FAILED);
                    assertThat(e.getMessage()).contains("damaged");
                });
        assertThat(server.downloads("apc261110.zip")).isEqualTo(3);

        assertThatThrownBy(() -> client.download(DAILY, unsafe, dir.resolve("apc261111.zip"), zipCheck()))
                .isInstanceOfSatisfying(DownloadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSAFE))
                .hasMessageContaining("'..' segment");
        assertThat(server.downloads("apc261111.zip")).as("an unsafe archive is not fetched again").isEqualTo(1);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aSizeOrChecksumThatDiffersFromTheListingIsRefused() throws Exception {
        byte[] bytes = day("apc261110.zip");
        server.offer(DAILY, "apc261110.zip", bytes);
        UsptoBulkClient client = client();
        RemoteFile listed = listed(client, "apc261110.zip");

        RemoteFile wrongSize = new RemoteFile(listed.name(), listed.size() + 1, null, listed.uri());
        assertThatThrownBy(() -> client.download(DAILY, wrongSize, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOf(DownloadException.class).hasMessageContaining("the listing says");

        UsptoBulkClient checked = client(settings(Duration.ofSeconds(30), "sha", DataSize.ofMegabytes(10),
                List.of("127.0.0.1")));
        RemoteFile wrongChecksum = new RemoteFile(listed.name(), listed.size(), "sha256:" + "0".repeat(64), listed.uri());
        assertThatThrownBy(() -> checked.download(DAILY, wrongChecksum, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOf(DownloadException.class).hasMessageContaining("SHA-256 differs");
        RemoteFile rightChecksum = new RemoteFile(listed.name(), listed.size(), "sha256:" + sha256(bytes), listed.uri());
        assertThat(checked.download(DAILY, rightChecksum, dir.resolve("apc261110.zip"), zipCheck()).sha256())
                .isEqualTo(sha256(bytes));
    }

    @Test
    void aFileLargerThanTheLimitIsRefusedAtOnce() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip"));
        UsptoBulkClient client = client(settings(Duration.ofSeconds(30), "", DataSize.ofBytes(10), List.of("127.0.0.1")));
        RemoteFile file = listed(client, "apc261110.zip");

        assertThatThrownBy(() -> client.download(DAILY, file, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOf(DownloadException.class).hasMessageContaining("max-file-size");
        assertThat(server.downloads("apc261110.zip")).isZero();
    }

    @Test
    void aRedirectReachesOnlyAnAllowedHostAndTheKeyStaysBehind() throws Exception {
        byte[] bytes = day("apc261112.zip");
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip"))
                .redirect("apc261110.zip", "https://elsewhere.invalid/apc261110.zip")
                .offer(DAILY, "apc261111.zip", day("apc261111.zip"))
                .redirect("apc261111.zip", "http://localhost:" + server.port() + "/files/apc261112.zip")
                .offer("OTHER", "apc261112.zip", bytes);

        UsptoBulkClient strict = client();
        RemoteFile elsewhere = listed(strict, "apc261110.zip");
        assertThatThrownBy(() -> strict.download(DAILY, elsewhere, dir.resolve("apc261110.zip"), zipCheck()))
                .isInstanceOf(DownloadException.class).hasMessageContaining("elsewhere.invalid")
                .hasMessageContaining("allowed-hosts");
        assertThat(server.downloads("apc261110.zip")).isEqualTo(1);

        UsptoBulkClient allowing = client(settings(Duration.ofSeconds(30), "", DataSize.ofMegabytes(10),
                List.of("127.0.0.1", "localhost")));
        RemoteFile redirected = new RemoteFile("apc261111.zip", (long) bytes.length, null,
                URI.create(server.baseUrl() + "/files/apc261111.zip"));
        assertThat(allowing.download(DAILY, redirected, dir.resolve("apc261111.zip"), zipCheck()).sha256())
                .isEqualTo(sha256(bytes));
        List<FakeOdpServer.Request> requests = server.requests();
        FakeOdpServer.Request first = requests.stream().filter(r -> r.path().equals("/files/apc261111.zip")).findFirst()
                .orElseThrow();
        FakeOdpServer.Request second = requests.stream().filter(r -> r.path().equals("/files/apc261112.zip")).findFirst()
                .orElseThrow();
        assertThat(first.key()).as("the API host gets the key").isEqualTo(KEY);
        assertThat(second.key()).as("another host never does").isNull();
    }

    @Test
    void requestsAreSpacedByTheMinimumInterval() throws Exception {
        server.offer(DAILY, "apc261110.zip", day("apc261110.zip"));
        UsptoBulkClient client = client();

        client.list(DAILY, "includeFiles=true");
        client.list(DAILY, "includeFiles=true");

        assertThat(sleeps).containsExactly(MIN_INTERVAL);
    }

    @Test
    void aListingWithoutAFileListIsEmptyAndSaysSoButAWrongShapeFails() throws Exception {
        UsptoBulkClient client = client();

        server.listing("{\"count\":0}");
        UsptoBulkClient.Listing none = client.list(DAILY, "includeFiles=true");
        assertThat(none.listPresent()).isFalse();
        assertThat(none.files()).isEmpty();

        server.listing("{\"bulkDataProductBag\":[{\"productFileBag\":{\"fileDataBag\":{\"fileName\":\"x\"}}}]}");
        assertThatThrownBy(() -> client.list(DAILY, "includeFiles=true")).isInstanceOf(DownloadException.class)
                .hasMessageContaining("is not a list");

        server.listing("<html>maintenance</html>");
        assertThatThrownBy(() -> client.list(DAILY, "includeFiles=true")).isInstanceOf(DownloadException.class)
                .hasMessageContaining("is not JSON");
    }
}
