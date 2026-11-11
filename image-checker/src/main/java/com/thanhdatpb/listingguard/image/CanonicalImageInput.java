package com.thanhdatpb.listingguard.image;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over a versioned, length-prefixed serialization of marketplace/profile and ordered image metadata/bytes. */
public record CanonicalImageInput(String hash) {
    public static final String FORMAT = "listing-guard/image-input/v1";

    public static CanonicalImageInput of(ImageCheckInput input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, FORMAT);
            field(digest, input.marketplace().name());
            field(digest, input.profile());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(input.images().size()).array());
            for (ImageItem item : input.images()) {
                field(digest, item.role().name());
                field(digest, item.source().name());
                byte[] image = item.bytesUnsafe();
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(image.length).array());
                digest.update(image);
            }
            return new CanonicalImageInput(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot hash image input", e); }
    }

    private static void field(MessageDigest digest, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(data.length).array());
        digest.update(data);
    }
}
