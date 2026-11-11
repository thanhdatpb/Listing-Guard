package com.thanhdatpb.listingguard.image;

import java.util.Objects;

/** One submitted byte sequence and its caller-declared marketplace role/source. */
public final class ImageItem {
    private final byte[] bytes;
    private final ImageRole role;
    private final ImageSource source;

    public ImageItem(byte[] bytes, ImageRole role, ImageSource source) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.role = Objects.requireNonNull(role, "role");
        this.source = source == null ? ImageSource.UNKNOWN : source;
    }
    public byte[] bytes() { return bytes.clone(); }
    byte[] bytesUnsafe() { return bytes; }
    public ImageRole role() { return role; }
    public ImageSource source() { return source; }
}
