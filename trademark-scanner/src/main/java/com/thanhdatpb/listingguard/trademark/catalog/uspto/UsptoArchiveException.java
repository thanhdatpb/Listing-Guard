package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import java.io.IOException;

/**
 * A USPTO bulk-data archive that is refused (ADR-009, PR A2d). {@link Kind#UNSAFE}: the archive is readable but breaks a
 * safety rule (an entry name that could leave a folder, too many entries, an entry too large or compressed too well, no XML
 * entry); fetching it again gives the same archive, so it is not retried. {@link Kind#DAMAGED}: the archive cannot be read
 * to its end or an entry's CRC-32 or size differs; a new download may fix it. The message names the archive and the entry,
 * never the content.
 */
public final class UsptoArchiveException extends IOException {

    public enum Kind { UNSAFE, DAMAGED }

    private final Kind kind;

    public UsptoArchiveException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public UsptoArchiveException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean unsafe() {
        return kind == Kind.UNSAFE;
    }
}
