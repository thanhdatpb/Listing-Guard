package com.thanhdatpb.listingguard.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the configuration, the API client, the six tools and the chosen transport together (ADR-017). Nothing is bound
 * or opened until the settings have been checked: a missing or wrong setting stops here, with a message that names the
 * setting and prints no value.
 */
final class Launcher {

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_CONFIGURATION = 2;

    private Launcher() {
    }

    /**
     * Runs the server until it ends.
     *
     * @param env   the environment (the settings)
     * @param in    the stdio input (used only by the stdio transport)
     * @param out   the stdio output, which carries only protocol messages (used only by the stdio transport)
     * @param err   where start-up problems are printed
     * @return the process exit code
     */
    static int run(Map<String, String> env, InputStream in, OutputStream out, PrintStream err) {
        McpServerConfig config;
        try {
            config = McpServerConfig.fromEnvironment(env);
        } catch (ConfigException e) {
            err.println("listing-guard-mcp: cannot start. " + e.getMessage());
            return EXIT_CONFIGURATION;
        }
        try (RunningServer server = start(config, in, out)) {
            server.awaitTermination();
            return EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_FAILED;
        } catch (Exception e) {
            // The class only: a message could name an address or a path, and none of it helps an agent's user.
            err.println("listing-guard-mcp: failed to start the " + config.transport() + " transport ("
                    + e.getClass().getSimpleName() + ").");
            return EXIT_FAILED;
        }
    }

    /** Builds and starts the server on the configured transport. */
    static RunningServer start(McpServerConfig config, InputStream in, OutputStream out) throws Exception {
        var mapper = ToolResults.newMapper();
        ToolCatalog catalog = new ToolCatalog(mapper);
        ToolRunner runner = new ToolRunner(new ListingGuardApiClient(config), new ToolResults(mapper), mapper);

        if (config.insecureHttpAllowed() && "http".equals(config.baseUri().getScheme())
                && !HostAddresses.isLoopbackHost(config.baseUri().getHost())) {
            log.warn("The API key is sent over plain http to a host that is not loopback ({} is true). "
                    + "Use this only on a trusted private network.", McpServerConfig.ALLOW_INSECURE_HTTP);
        }

        if (config.transport() == TransportKind.HTTP) {
            HttpServletStreamableServerTransportProvider transport = HttpHost.newTransport(config);
            McpSyncServer server = ListingGuardMcpServer.forStreamableHttp(transport, catalog, runner);
            HttpHost host;
            try {
                host = HttpHost.start(config, transport);
            } catch (Exception e) {
                server.close();
                throw e;
            }
            log.info("Listing Guard MCP server started: transport=streamable-http, listening on {}:{}{}, api={}",
                    config.httpBindAddress().getHostAddress(), host.port(), HttpHost.ENDPOINT, config.baseUri());
            log.warn("The HTTP transport has no authentication: whoever can reach this port acts as the client of "
                    + "the API key and spends its quota. Never expose it to the internet.");
            return RunningServer.http(server, host);
        }

        EofSignalingInputStream input = new EofSignalingInputStream(in);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, out);
        McpSyncServer server = ListingGuardMcpServer.forStdio(transport, catalog, runner);
        log.info("Listing Guard MCP server started: transport=stdio, api={}", config.baseUri());
        return RunningServer.stdio(server, input);
    }
}
