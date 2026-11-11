package com.thanhdatpb.listingguard.image;

import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;

/** Detects only profile markers; decoding/color conversion remains ImageIO/ColorModel's responsibility. */
final class IccProfile {
    private static final int MAX_PROFILE_BYTES = 4 * 1024 * 1024;
    private IccProfile() { }
    static boolean validate(byte[] bytes, String format) throws IOException {
        if (format.equals("JPEG")) {
            Map<Integer, byte[]> chunks = new TreeMap<>();
            int expectedChunks = -1;
            int p = 2;
            while (p + 4 <= bytes.length && (bytes[p] & 255) == 255) {
                int marker = bytes[p + 1] & 255;
                if (marker == 0xda || marker == 0xd9) break;
                int length = ((bytes[p + 2] & 255) << 8) | (bytes[p + 3] & 255);
                if (length < 2 || p + 2L + length > bytes.length) break;
                if (marker == 0xe2 && length >= 16 && bytes[p + 4] == 'I' && bytes[p + 5] == 'C' && bytes[p + 6] == 'C' && bytes[p + 7] == '_'
                        && bytes[p + 8] == 'P' && bytes[p + 9] == 'R' && bytes[p + 10] == 'O' && bytes[p + 11] == 'F' && bytes[p + 12] == 'I' && bytes[p + 13] == 'L' && bytes[p + 14] == 'E' && bytes[p + 15] == 0) {
                    int sequence = bytes[p + 16] & 255, total = bytes[p + 17] & 255;
                    if (sequence < 1 || total < 1 || sequence > total || expectedChunks != -1 && total != expectedChunks || chunks.containsKey(sequence)) throw new IOException("invalid ICC profile chunks");
                    expectedChunks = total;
                    int start = p + 18, size = length - 16;
                    if (chunks.values().stream().mapToInt(a -> a.length).sum() + size > MAX_PROFILE_BYTES) throw new IOException("ICC profile too large");
                    chunks.put(sequence, java.util.Arrays.copyOfRange(bytes, start, start + size));
                }
                p += length + 2;
            }
            if (expectedChunks >= 0) {
                if (chunks.size() != expectedChunks) throw new IOException("incomplete ICC profile");
                ByteArrayOutputStream profile = new ByteArrayOutputStream(); chunks.values().forEach(part -> profile.writeBytes(part));
                validProfile(profile.toByteArray()); return true;
            }
        } else if (format.equals("PNG") && bytes.length >= 8) {
            int p = 8;
            while (p + 12 <= bytes.length) {
                long n = ((bytes[p] & 255L) << 24) | ((bytes[p + 1] & 255L) << 16) | ((bytes[p + 2] & 255L) << 8) | (bytes[p + 3] & 255L);
                if (n > Integer.MAX_VALUE || p + 12L + n > bytes.length) break;
                if (bytes[p + 4] == 'i' && bytes[p + 5] == 'C' && bytes[p + 6] == 'C' && bytes[p + 7] == 'P') {
                    int data = p + 8, end = data + (int) n, nul = data;
                    while (nul < end && bytes[nul] != 0) nul++;
                    if (nul == end || nul + 2 > end || bytes[nul + 1] != 0) throw new IOException("invalid PNG ICC profile chunk");
                    try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(bytes, nul + 2, end - nul - 2));
                         ByteArrayOutputStream profile = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int read;
                        while ((read = inflater.read(buffer)) != -1) { if (profile.size() + read > MAX_PROFILE_BYTES) throw new IOException("ICC profile too large"); profile.write(buffer, 0, read); }
                        validProfile(profile.toByteArray()); return true;
                    }
                }
                if (bytes[p + 4] == 'I' && bytes[p + 5] == 'E' && bytes[p + 6] == 'N' && bytes[p + 7] == 'D') break;
                p += (int) n + 12;
            }
        }
        return false;
    }
    private static void validProfile(byte[] bytes) throws IOException {
        try { ICC_Profile.getInstance(bytes); }
        catch (IllegalArgumentException e) { throw new IOException("invalid or unsupported ICC profile", e); }
    }
}
