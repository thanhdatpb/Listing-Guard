package com.thanhdatpb.listingguard.api.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

/**
 * The validated deprecation settings (ADR-022, section 6) and the header values they produce. Built once at start-up; a
 * setting that is wrong stops the start with every problem named, never a silent skip.
 */
public final class ApiDeprecations {

    /** The least time between the Deprecation date and the Sunset date (ADR-022). */
    public static final Duration MINIMUM_NOTICE = Duration.ofDays(180);

    private static final String PREFIX = "listing-guard.api.deprecation.endpoints";
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    /**
     * One deprecated operation.
     *
     * @param method       upper-case HTTP method
     * @param path         the path template as configured
     * @param pattern      the template, parsed for matching request paths
     * @param deprecatedAt the Deprecation date
     * @param sunsetAt     the Sunset date, or null
     * @param link         the migration notes, or null
     */
    public record DeprecatedEndpoint(String method, String path, PathPattern pattern, Instant deprecatedAt, Instant sunsetAt, URI link) {

        /** {@code Deprecation}: {@code @} and seconds since the epoch, a structured-field date (RFC 9745). */
        public String deprecationHeader() {
            return "@" + deprecatedAt.getEpochSecond();
        }

        /** {@code Sunset}: an HTTP date (RFC 8594). */
        public Optional<String> sunsetHeader() {
            return Optional.ofNullable(sunsetAt).map(IMF_FIXDATE::format);
        }

        /** {@code Link} with {@code rel="deprecation"} (RFC 9745). */
        public Optional<String> linkHeader() {
            return Optional.ofNullable(link).map(uri -> "<" + uri + ">; rel=\"deprecation\"; type=\"text/html\"");
        }

        String operation() {
            return method + " " + path;
        }

        boolean matches(String requestMethod, String requestPath) {
            return method.equalsIgnoreCase(requestMethod) && pattern.matches(PathContainer.parsePath(requestPath));
        }
    }

    private final List<DeprecatedEndpoint> endpoints;

    private ApiDeprecations(List<DeprecatedEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    public static ApiDeprecations none() {
        return new ApiDeprecations(List.of());
    }

    /** Checks and parses the configured endpoints, or throws with every problem found. */
    public static ApiDeprecations of(List<ApiDeprecationProperties.Endpoint> configured) {
        List<String> problems = new ArrayList<>();
        List<DeprecatedEndpoint> endpoints = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ApiDeprecationProperties.Endpoint> entries = configured == null ? List.of() : configured;
        for (int i = 0; i < entries.size(); i++) {
            String label = PREFIX + "[" + i + "]";
            ApiDeprecationProperties.Endpoint entry = entries.get(i);
            int before = problems.size();

            String method = entry.method() == null ? "" : entry.method().trim().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                problems.add(label + ".method must be one of " + new java.util.TreeSet<>(METHODS) + ", found \"" + entry.method() + "\"");
            }
            PathPattern pattern = null;
            String path = entry.path() == null ? "" : entry.path().trim();
            if (!path.startsWith("/api/v1/") || path.chars().anyMatch(Character::isWhitespace)) {
                problems.add(label + ".path must be an /api/v1 path as openapi.json writes it, found \"" + entry.path() + "\"");
            } else {
                try {
                    pattern = PathPatternParser.defaultInstance.parse(path);
                } catch (PatternParseException e) {
                    problems.add(label + ".path is not a path template: " + e.getMessage());
                }
            }
            Instant deprecatedAt = instant(label + ".deprecated-at", entry.deprecatedAt(), true, problems);
            Instant sunsetAt = instant(label + ".sunset-at", entry.sunsetAt(), false, problems);
            if (deprecatedAt != null && sunsetAt != null && sunsetAt.isBefore(deprecatedAt.plus(MINIMUM_NOTICE))) {
                problems.add(label + ".sunset-at must be at least " + MINIMUM_NOTICE.toDays() + " days after deprecated-at (ADR-022)");
            }
            URI link = link(label + ".link", entry.link(), problems);
            if (problems.size() == before) {
                DeprecatedEndpoint endpoint = new DeprecatedEndpoint(method, path, pattern, deprecatedAt, sunsetAt, link);
                if (!seen.add(endpoint.operation())) {
                    problems.add(label + " names " + endpoint.operation() + " a second time");
                }
                endpoints.add(endpoint);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid deprecation settings (ADR-022): " + String.join("; ", problems));
        }
        return new ApiDeprecations(endpoints);
    }

    public List<DeprecatedEndpoint> endpoints() {
        return endpoints;
    }

    /** The deprecated operation a request belongs to, if any. */
    public Optional<DeprecatedEndpoint> find(String method, String path) {
        return endpoints.stream().filter(endpoint -> endpoint.matches(method, path)).findFirst();
    }

    /**
     * The entries that name no operation of the application.
     *
     * @param operations every operation as {@code METHOD /path/template}, for example {@code GET /api/v1/checks/{id}}
     */
    public List<String> unknownOperations(Set<String> operations) {
        return endpoints.stream().map(DeprecatedEndpoint::operation).filter(operation -> !operations.contains(operation)).toList();
    }

    private static Instant instant(String label, String value, boolean required, List<String> problems) {
        if (value == null || value.isBlank()) {
            if (required) {
                problems.add(label + " is required");
            }
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException notADate) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException notAnInstant) {
                problems.add(label + " must be a date (2027-01-01) or an ISO-8601 instant (2027-01-01T00:00:00Z), found \"" + value + "\"");
                return null;
            }
        }
    }

    private static URI link(String label, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            if (uri.isAbsolute() && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.toString().chars().noneMatch(c -> c == '<' || c == '>' || c == '"' || Character.isWhitespace(c))) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // reported below
        }
        problems.add(label + " must be an absolute https address, found \"" + value + "\"");
        return null;
    }
}
