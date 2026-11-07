package com.thanhdatpb.listingguard.mcp;

/** How an agent reaches the server (ADR-017): STDIO is the default, Streamable HTTP is optional and off by default. */
public enum TransportKind {
    STDIO,
    HTTP
}
