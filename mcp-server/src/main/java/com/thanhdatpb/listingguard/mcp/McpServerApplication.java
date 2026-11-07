package com.thanhdatpb.listingguard.mcp;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Entry point of the Listing Guard MCP server (ADR-017, {@code docs/mcp.md}). A client of the Listing Guard REST API for
 * AI agents: it needs {@code LISTING_GUARD_MCP_API_KEY} and {@code LISTING_GUARD_MCP_BASE_URL} and does not start
 * without them.
 */
public final class McpServerApplication {

    private McpServerApplication() {
    }

    public static void main(String[] args) {
        PrintStream err = System.err;
        // Standard output carries only protocol messages on stdio. Take the real stream for the protocol and point
        // System.out at standard error, so that a stray print can never corrupt the session.
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(err);
        int exit = Launcher.run(System.getenv(), new FileInputStream(FileDescriptor.in), protocolOut, err);
        System.exit(exit);
    }
}
