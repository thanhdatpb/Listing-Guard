package com.thanhdatpb.listingguard.mcp;

import io.modelcontextprotocol.server.McpSyncServer;
import java.util.concurrent.CountDownLatch;

/** A started server, on either transport: wait for it to end, ask for its port, close it. */
final class RunningServer implements AutoCloseable {

    private final McpSyncServer server;
    private final EofSignalingInputStream stdioInput;
    private final HttpHost http;
    private final CountDownLatch closed = new CountDownLatch(1);

    private RunningServer(McpSyncServer server, EofSignalingInputStream stdioInput, HttpHost http) {
        this.server = server;
        this.stdioInput = stdioInput;
        this.http = http;
    }

    static RunningServer stdio(McpSyncServer server, EofSignalingInputStream input) {
        return new RunningServer(server, input, null);
    }

    static RunningServer http(McpSyncServer server, HttpHost http) {
        return new RunningServer(server, null, http);
    }

    /** The local port of the HTTP transport, or {@code -1} on stdio. */
    int httpPort() {
        return http == null ? -1 : http.port();
    }

    /** Stdio: until the host closes the input. HTTP: until {@link #close()}. */
    void awaitTermination() throws InterruptedException {
        if (stdioInput != null) {
            stdioInput.awaitEnd();
        } else {
            closed.await();
        }
    }

    @Override
    public void close() {
        closed.countDown();
        try {
            server.closeGracefully();
        } catch (RuntimeException e) {
            // Closing: the transport may already be gone.
        }
        if (http != null) {
            http.close();
        }
    }
}
