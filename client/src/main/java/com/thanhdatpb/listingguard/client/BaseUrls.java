package com.thanhdatpb.listingguard.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks the base URL (ADR-023, the rule of ADR-017): {@code http} or {@code https}, a host, no user info, no query, no
 * fragment, and {@code https} unless the host is loopback or cleartext is allowed on purpose. A message never repeats the URL,
 * which could hold credentials.
 */
final class BaseUrls {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private BaseUrls() {
    }

    /** The checked URL without a trailing slash (a path prefix is kept). */
    static URI parse(String raw, boolean insecureHttpAllowed) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL");
        }
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not contain user info (credentials)");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query or a fragment");
        }
        if (!https && !isLoopbackHost(host) && !insecureHttpAllowed) {
            throw new IllegalArgumentException("baseUrl uses http to a host that is not loopback, so the API key would travel "
                    + "unencrypted. Use https, or allowInsecureHttp(true) for a trusted private network");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path);
    }

    /** {@code localhost} or a loopback IP literal; a host name is never looked up. */
    static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean v4 = IPV4.matcher(h).matches();
        boolean v6 = h.indexOf(':') >= 0 && IPV6.matcher(h).matches();
        if (!v4 && !v6) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress(); // a literal is parsed, never looked up
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
