package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LauncherTest {

    private static Map<String, String> env(String... extra) {
        Map<String, String> env = new HashMap<>();
        env.put(McpServerConfig.API_KEY, ApiStub.KEY);
        env.put(McpServerConfig.BASE_URL, "http://127.0.0.1:1");
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) {
                env.remove(extra[i]);
            } else {
                env.put(extra[i], extra[i + 1]);
            }
        }
        return env;
    }

    private record Outcome(int exit, String out, String err) {
    }

    private static Outcome run(Map<String, String> env, byte[] stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> Launcher.run(env,
                new ByteArrayInputStream(stdin), out, new PrintStream(err, true, StandardCharsets.UTF_8)));
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aMissingKeyOrBaseUrlStopsTheStartWithExitTwoAndNamesTheSetting() {
        Outcome noKey = run(env(McpServerConfig.API_KEY, null), new byte[0]);
        assertThat(noKey.exit()).isEqualTo(Launcher.EXIT_CONFIGURATION);
        assertThat(noKey.err()).contains(McpServerConfig.API_KEY);
        assertThat(noKey.out()).isEmpty();

        Outcome noUrl = run(env(McpServerConfig.BASE_URL, null), new byte[0]);
        assertThat(noUrl.exit()).isEqualTo(Launcher.EXIT_CONFIGURATION);
        assertThat(noUrl.err()).contains(McpServerConfig.BASE_URL);
        assertThat(noUrl.out()).isEmpty();
    }

    @Test
    void aWildcardBindAddressStopsTheStartWithoutPrintingTheValue() {
        Outcome outcome = run(env(McpServerConfig.TRANSPORT, "http", McpServerConfig.HTTP_HOST, "0.0.0.0"), new byte[0]);

        assertThat(outcome.exit()).isEqualTo(Launcher.EXIT_CONFIGURATION);
        assertThat(outcome.err()).contains(McpServerConfig.HTTP_HOST).doesNotContain("0.0.0.0");
        assertThat(outcome.out()).isEmpty();
    }

    @Test
    void noMessageOfAFailedStartContainsTheKey() {
        for (Map<String, String> bad : List.of(
                env(McpServerConfig.BASE_URL, "ftp://x"),
                env(McpServerConfig.TRANSPORT, "sse"),
                env(McpServerConfig.BASE_URL, "http://example.com"))) {
            Outcome outcome = run(bad, new byte[0]);

            assertThat(outcome.exit()).isEqualTo(Launcher.EXIT_CONFIGURATION);
            assertThat(outcome.err()).doesNotContain(ApiStub.KEY).doesNotContain("SECRETKEY");
        }
    }

    @Test
    void stdioEndsWhenTheHostClosesTheInput() {
        Outcome outcome = run(env(), new byte[0]);

        assertThat(outcome.exit()).isEqualTo(Launcher.EXIT_OK);
        assertThat(outcome.err()).doesNotContain(ApiStub.KEY);
    }

    @Test
    void aPortThatIsInUseFailsTheStartWithExitOneAndNoDetailsOfTheAddress() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Outcome outcome = run(env(McpServerConfig.TRANSPORT, "http", McpServerConfig.HTTP_HOST, "127.0.0.1",
                    McpServerConfig.HTTP_PORT, String.valueOf(taken.getLocalPort())), new byte[0]);

            assertThat(outcome.exit()).isEqualTo(Launcher.EXIT_FAILED);
            assertThat(outcome.err()).contains("failed to start the HTTP transport")
                    .doesNotContain(String.valueOf(taken.getLocalPort())).doesNotContain(ApiStub.KEY);
        }
    }
}
