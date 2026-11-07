package com.thanhdatpb.listingguard.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Start-up settings, all from the environment (ADR-017). The API key is a secret: {@link #toString()} hides it, and
 * every {@link ConfigException} names a setting without printing a value.
 *
 * @param apiKey             the key of one client; sent only in the {@code X-API-Key} header
 * @param baseUri            the API's base URL (no user info, no query, no trailing slash)
 * @param timeout            connection and request timeout toward the API
 * @param transport          STDIO (default) or HTTP
 * @param httpBindAddress    where the Streamable HTTP transport listens (loopback or private network only)
 * @param httpPort           the port of the Streamable HTTP transport (0 asks the system for a free one; tests only)
 * @param insecureHttpAllowed {@code http} to a host that is not loopback is accepted (a trusted private network)
 */
public record McpServerConfig(
        String apiKey,
        URI baseUri,
        Duration timeout,
        TransportKind transport,
        InetAddress httpBindAddress,
        int httpPort,
        boolean insecureHttpAllowed) {

    public static final String API_KEY = "LISTING_GUARD_MCP_API_KEY";
    public static final String BASE_URL = "LISTING_GUARD_MCP_BASE_URL";
    public static final String ALLOW_INSECURE_HTTP = "LISTING_GUARD_MCP_ALLOW_INSECURE_HTTP";
    public static final String TIMEOUT_SECONDS = "LISTING_GUARD_MCP_TIMEOUT_SECONDS";
    public static final String TRANSPORT = "LISTING_GUARD_MCP_TRANSPORT";
    public static final String HTTP_HOST = "LISTING_GUARD_MCP_HTTP_HOST";
    public static final String HTTP_PORT = "LISTING_GUARD_MCP_HTTP_PORT";

    static final int DEFAULT_TIMEOUT_SECONDS = 20;
    static final String DEFAULT_HTTP_HOST = "127.0.0.1";
    static final int DEFAULT_HTTP_PORT = 8765;

    /** Visible ASCII only: the key goes into a header value, and a space or a control character must never get there. */
    private static final Pattern KEY_CHARACTERS = Pattern.compile("^[\\x21-\\x7E]+$");

    public McpServerConfig {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey");
        }
        if (httpPort < 0 || httpPort > 65535) {
            throw new IllegalArgumentException("httpPort");
        }
    }

    /**
     * Reads and checks the settings.
     *
     * @throws ConfigException when a setting is missing or wrong (the message never contains a value)
     */
    public static McpServerConfig fromEnvironment(Map<String, String> env) {
        String key = required(env, API_KEY).strip();
        if (!KEY_CHARACTERS.matcher(key).matches()) {
            throw new ConfigException(API_KEY + " contains characters that cannot be sent in an HTTP header "
                    + "(only visible ASCII characters, no spaces).");
        }
        String baseUrl = required(env, BASE_URL);
        boolean insecure = bool(env, ALLOW_INSECURE_HTTP, false);
        URI baseUri = parseBaseUrl(baseUrl, insecure);
        int timeoutSeconds = integer(env, TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS, 1, 300);
        TransportKind transport = parseTransport(env.get(TRANSPORT));

        InetAddress bind = InetAddress.getLoopbackAddress();
        int port = DEFAULT_HTTP_PORT;
        if (transport == TransportKind.HTTP) {
            bind = parseBindAddress(valueOrDefault(env, HTTP_HOST, DEFAULT_HTTP_HOST));
            port = integer(env, HTTP_PORT, DEFAULT_HTTP_PORT, 1, 65535);
        }
        return new McpServerConfig(key, baseUri, Duration.ofSeconds(timeoutSeconds), transport, bind, port, insecure);
    }

    /** True when the HTTP transport listens on a loopback address. */
    public boolean httpBindIsLoopback() {
        return httpBindAddress.isLoopbackAddress();
    }

    @Override
    public String toString() {
        return "McpServerConfig[baseUri=" + baseUri
                + ", timeout=" + timeout
                + ", transport=" + transport
                + ", httpBindAddress=" + httpBindAddress.getHostAddress()
                + ", httpPort=" + httpPort
                + ", insecureHttpAllowed=" + insecureHttpAllowed
                + ", apiKey=<hidden>]";
    }

    private static URI parseBaseUrl(String raw, boolean insecureAllowed) {
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new ConfigException(BASE_URL + " is not a valid URL.");
        }
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !"http".equalsIgnoreCase(scheme)) {
            throw new ConfigException(BASE_URL + " must start with http:// or https://.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new ConfigException(BASE_URL + " must name a host.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ConfigException(BASE_URL + " must not contain user info (credentials).");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ConfigException(BASE_URL + " must not contain a query or a fragment.");
        }
        if (!https && !HostAddresses.isLoopbackHost(host) && !insecureAllowed) {
            throw new ConfigException(BASE_URL + " uses http to a host that is not loopback, so the API key would "
                    + "travel unencrypted. Use https, or set " + ALLOW_INSECURE_HTTP
                    + "=true for a trusted private network.");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path);
    }

    private static TransportKind parseTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return TransportKind.STDIO;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "stdio" -> TransportKind.STDIO;
            case "http" -> TransportKind.HTTP;
            default -> throw new ConfigException(TRANSPORT + " must be 'stdio' or 'http'.");
        };
    }

    private static InetAddress parseBindAddress(String raw) {
        String host = raw.strip();
        InetAddress address;
        if ("localhost".equalsIgnoreCase(host)) {
            address = InetAddress.getLoopbackAddress();
        } else {
            address = HostAddresses.literal(host).orElseThrow(() -> new ConfigException(HTTP_HOST
                    + " must be an IP address (or localhost); a host name is not looked up."));
        }
        if (!HostAddresses.isPrivateOrLoopback(address)) {
            throw new ConfigException(HTTP_HOST + " must be a loopback or private-network address. A wildcard or "
                    + "public address is refused: the HTTP transport has no authentication and must not be exposed "
                    + "to the internet.");
        }
        return address;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(name + " is not set.");
        }
        return value;
    }

    private static String valueOrDefault(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean bool(Map<String, String> env, String name, boolean fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ConfigException(name + " must be 'true' or 'false'.");
        };
    }

    private static int integer(Map<String, String> env, String name, int fallback, int min, int max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < min || parsed > max) {
                throw new ConfigException(name + " must be between " + min + " and " + max + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be a whole number.");
        }
    }
}
