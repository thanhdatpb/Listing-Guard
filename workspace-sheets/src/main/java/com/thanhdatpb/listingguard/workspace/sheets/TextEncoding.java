package com.thanhdatpb.listingguard.workspace.sheets;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** The encodings a tab-delimited export may use (ADR-063). Only these: a name from a form is looked up here, never given to {@link Charset#forName}. */
public enum TextEncoding {
    UTF_8("UTF-8", StandardCharsets.UTF_8, false),
    UTF_8_BOM("UTF-8-BOM", StandardCharsets.UTF_8, true),
    /** what Excel calls "Unicode Text": UTF-16 little endian with a byte order mark */
    UTF_16LE("UTF-16LE", StandardCharsets.UTF_16LE, true),
    ISO_8859_1("ISO-8859-1", StandardCharsets.ISO_8859_1, false),
    WINDOWS_1252("windows-1252", Charset.forName("windows-1252"), false);

    private final String code;
    private final Charset charset;
    private final boolean bom;

    TextEncoding(String code, Charset charset, boolean bom) {
        this.code = code;
        this.charset = charset;
        this.bom = bom;
    }

    /** The name the settings and the forms use. */
    public String code() {
        return code;
    }

    public Charset charset() {
        return charset;
    }

    public boolean bom() {
        return bom;
    }

    public static Optional<TextEncoding> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (TextEncoding encoding : values()) {
            if (encoding.code.toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(encoding);
            }
        }
        return Optional.empty();
    }
}
