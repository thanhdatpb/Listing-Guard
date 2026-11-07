package com.thanhdatpb.listingguard.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

/**
 * The optional Streamable HTTP transport (ADR-017): the SDK's servlet on an embedded Tomcat, bound to one loopback or
 * private-network address ({@link McpServerConfig} refuses anything else). It has no authentication, so it must never be
 * exposed to the internet. The SDK validates {@code Host} and {@code Origin} (DNS-rebinding protection); a request that
 * names another origin is refused.
 */
final class HttpHost implements AutoCloseable {

    static final String ENDPOINT = "/mcp";
    private static final String SERVLET_NAME = "mcp";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final Tomcat tomcat;
    private final Path baseDir;
    private final int port;

    private HttpHost(Tomcat tomcat, Path baseDir, int port) {
        this.tomcat = tomcat;
        this.baseDir = baseDir;
        this.port = port;
    }

    /** The SDK transport, with Host and Origin checks for the address the server is bound to. */
    static HttpServletStreamableServerTransportProvider newTransport(McpServerConfig config) {
        List<String> hosts = new ArrayList<>(List.of("localhost", "127.0.0.1", "[::1]"));
        for (String form : HostAddresses.hostForms(config.httpBindAddress())) {
            if (!hosts.contains(form)) {
                hosts.add(form);
            }
        }
        List<String> allowedHosts = hosts.stream().map(h -> h + ":*").toList();
        List<String> allowedOrigins = hosts.stream().map(h -> "http://" + h + ":*").toList();
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(ENDPOINT)
                .maxRequestSize(MAX_REQUEST_BYTES)
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(allowedHosts)
                        .allowedOrigins(allowedOrigins)
                        .build())
                .build();
    }

    /** Starts Tomcat on the configured address and port (0 asks the system for a free port). */
    static HttpHost start(McpServerConfig config, HttpServletStreamableServerTransportProvider servlet)
            throws LifecycleException, IOException {
        Path baseDir = Files.createTempDirectory("listing-guard-mcp");
        Tomcat tomcat = new Tomcat();
        try {
            tomcat.setBaseDir(baseDir.toString());
            tomcat.getServer().setPort(-1); // no shutdown port

            Connector connector = new Connector("HTTP/1.1");
            connector.setPort(config.httpPort());
            connector.setProperty("address", config.httpBindAddress().getHostAddress());
            connector.setThrowOnFailure(true);
            connector.setXpoweredBy(false);
            tomcat.setConnector(connector);

            tomcat.getHost().setAutoDeploy(false);
            Context context = tomcat.addContext("", baseDir.toString());
            Wrapper wrapper = Tomcat.addServlet(context, SERVLET_NAME, servlet);
            wrapper.setAsyncSupported(true);
            context.addServletMappingDecoded(ENDPOINT, SERVLET_NAME);

            tomcat.start();
            return new HttpHost(tomcat, baseDir, connector.getLocalPort());
        } catch (LifecycleException | RuntimeException e) {
            stopQuietly(tomcat);
            deleteQuietly(baseDir);
            throw e;
        }
    }

    /** The port the server listens on (the real one when 0 was asked). */
    int port() {
        return port;
    }

    @Override
    public void close() {
        stopQuietly(tomcat);
        deleteQuietly(baseDir);
    }

    private static void stopQuietly(Tomcat tomcat) {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException | RuntimeException e) {
            // Shutting down: nothing useful is left to do with the failure.
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // A temporary directory that cannot be removed is left to the system.
        }
    }
}
