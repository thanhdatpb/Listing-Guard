package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class McpServerConfigTest {

    private static final String KEY = ApiStub.KEY;

    private static Map<String, String> env(String... extra) {
        Map<String, String> env = new HashMap<>();
        env.put(McpServerConfig.API_KEY, KEY);
        env.put(McpServerConfig.BASE_URL, "http://localhost:8080");
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) {
                env.remove(extra[i]);
            } else {
                env.put(extra[i], extra[i + 1]);
            }
        }
        return env;
    }

    @Test
    void defaultsAreStdioAndLoopbackWithTwentySeconds() {
        McpServerConfig config = McpServerConfig.fromEnvironment(env());

        assertThat(config.transport()).isEqualTo(TransportKind.STDIO);
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.baseUri().toString()).isEqualTo("http://localhost:8080");
        assertThat(config.insecureHttpAllowed()).isFalse();
        assertThat(config.httpBindIsLoopback()).isTrue();
        assertThat(config.apiKey()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void aMissingOrBlankKeyStopsStartUpAndNamesTheVariable(String value) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.API_KEY, value)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.API_KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void aMissingOrBlankBaseUrlStopsStartUpAndNamesTheVariable(String value) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, value)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.BASE_URL);
    }

    @Test
    void surroundingWhitespaceOfTheKeyIsCut() {
        McpServerConfig config = McpServerConfig.fromEnvironment(env(McpServerConfig.API_KEY, "  " + KEY + "\n"));

        assertThat(config.apiKey()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lg_has a space_inside", "lg_tab\tinside", "lg_new\nline", "lg_việt_nam", "lg_\u0001control"})
    void aKeyThatCannotBeAHeaderValueIsRefusedWithoutPrintingIt(String key) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.API_KEY, key)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.API_KEY)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("inside").doesNotContain("lg_"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "file:///etc/passwd", "example.com", "not a url", "//example.com",
            "http://", "https:///path"})
    void aBaseUrlThatIsNotHttpWithAHostIsRefused(String url) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, url)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.BASE_URL);
    }

    @Test
    void aBaseUrlWithUserInfoIsRefusedAndTheMessageDoesNotRepeatIt() {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(
                env(McpServerConfig.BASE_URL, "https://alice:hunter2@listing-guard.example")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.BASE_URL)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("alice").doesNotContain("hunter2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://listing-guard.example?x=1", "https://listing-guard.example/#frag"})
    void aBaseUrlWithAQueryOrFragmentIsRefused(String url) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, url)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.BASE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://listing-guard.example", "http://10.0.0.5:8080", "http://192.168.1.10"})
    void cleartextHttpToAHostThatIsNotLoopbackIsRefusedUnlessAllowed(String url) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, url)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.BASE_URL)
                .hasMessageContaining(McpServerConfig.ALLOW_INSECURE_HTTP);

        McpServerConfig allowed = McpServerConfig.fromEnvironment(
                env(McpServerConfig.BASE_URL, url, McpServerConfig.ALLOW_INSECURE_HTTP, "true"));
        assertThat(allowed.insecureHttpAllowed()).isTrue();
        assertThat(allowed.baseUri().getScheme()).isEqualTo("http");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "https://listing-guard.example"})
    void loopbackHttpAndAnyHttpsAreAccepted(String url) {
        assertThat(McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, url)).baseUri().toString())
                .isEqualTo(url);
    }

    @Test
    void theTrailingSlashOfTheBaseUrlIsCutAndAPathPrefixIsKept() {
        assertThat(McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, "https://example.com/")).baseUri().toString())
                .isEqualTo("https://example.com");
        assertThat(McpServerConfig.fromEnvironment(env(McpServerConfig.BASE_URL, "https://example.com/lg/")).baseUri().toString())
                .isEqualTo("https://example.com/lg");
    }

    @Test
    void anUnknownTransportIsRefused() {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.TRANSPORT, "sse")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.TRANSPORT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "[::]", "8.8.8.8", "1.1.1.1", "203.0.113.7", "2001:db8::1", "example.com",
            "listing-guard.internal", "0177.0.0.1", "224.0.0.1", "999.1.1.1"})
    void theHttpTransportRefusesAWildcardPublicOrNamedAddress(String host) {
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(
                env(McpServerConfig.TRANSPORT, "http", McpServerConfig.HTTP_HOST, host)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.HTTP_HOST)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "localhost", "LOCALHOST", "::1", "[::1]", "10.1.2.3", "172.16.0.9",
            "172.31.255.1", "192.168.0.5", "169.254.10.10", "100.64.0.1", "fd00::1", "fe80::1"})
    void theHttpTransportAcceptsLoopbackAndPrivateAddresses(String host) {
        McpServerConfig config = McpServerConfig.fromEnvironment(
                env(McpServerConfig.TRANSPORT, "http", McpServerConfig.HTTP_HOST, host));

        assertThat(config.transport()).isEqualTo(TransportKind.HTTP);
        assertThat(config.httpPort()).isEqualTo(8765);
    }

    @Test
    void theHttpTransportDefaultsToLoopbackAndThePortIsChecked() {
        McpServerConfig config = McpServerConfig.fromEnvironment(env(McpServerConfig.TRANSPORT, "HTTP"));
        assertThat(config.httpBindIsLoopback()).isTrue();
        assertThat(config.httpBindAddress().getHostAddress()).isEqualTo("127.0.0.1");

        for (String port : new String[] {"0", "65536", "-1", "http", "80.5"}) {
            assertThatThrownBy(() -> McpServerConfig.fromEnvironment(
                    env(McpServerConfig.TRANSPORT, "http", McpServerConfig.HTTP_PORT, port)))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining(McpServerConfig.HTTP_PORT);
        }
    }

    @Test
    void theHostOfTheHttpTransportIsNotReadWhenTheTransportIsStdio() {
        // A wrong value that is not used must not stop a stdio server.
        McpServerConfig config = McpServerConfig.fromEnvironment(env(McpServerConfig.HTTP_HOST, "0.0.0.0"));

        assertThat(config.transport()).isEqualTo(TransportKind.STDIO);
    }

    @Test
    void theTimeoutAndTheInsecureFlagAreChecked() {
        assertThat(McpServerConfig.fromEnvironment(env(McpServerConfig.TIMEOUT_SECONDS, "7")).timeout())
                .isEqualTo(Duration.ofSeconds(7));
        for (String value : new String[] {"0", "301", "abc", "1.5"}) {
            assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.TIMEOUT_SECONDS, value)))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining(McpServerConfig.TIMEOUT_SECONDS);
        }
        assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(McpServerConfig.ALLOW_INSECURE_HTTP, "yes")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(McpServerConfig.ALLOW_INSECURE_HTTP);
    }

    @Test
    void toStringHidesTheKey() {
        McpServerConfig config = McpServerConfig.fromEnvironment(env());

        assertThat(config.toString()).doesNotContain(KEY).doesNotContain("SECRETKEY").contains("<hidden>");
    }

    @Test
    void noMessageOfAnyRefusalContainsTheKey() {
        String[][] bad = {
                {McpServerConfig.BASE_URL, "ftp://x"},
                {McpServerConfig.BASE_URL, "http://alice:pw@host"},
                {McpServerConfig.TRANSPORT, "sse"},
                {McpServerConfig.TIMEOUT_SECONDS, "x"},
                {McpServerConfig.ALLOW_INSECURE_HTTP, "maybe"},
        };
        for (String[] pair : bad) {
            assertThatThrownBy(() -> McpServerConfig.fromEnvironment(env(pair[0], pair[1])))
                    .isInstanceOf(ConfigException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("SECRETKEY"));
        }
    }
}
