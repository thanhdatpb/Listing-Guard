package com.thanhdatpb.listingguard.image;

/** Minimal bounded parser for JPEG APP1 Exif/TIFF orientation; absent tag means orientation 1. */
final class ExifOrientation {
    private ExifOrientation() { }
    static int read(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 255) != 0xff || (jpeg[1] & 255) != 0xd8) return 1;
        int p = 2;
        while (p + 4 <= jpeg.length && (jpeg[p] & 255) == 0xff) {
            int marker = jpeg[p + 1] & 255;
            if (marker == 0xda || marker == 0xd9) break;
            int len = u16(jpeg, p + 2, false);
            if (len < 2 || p + 2 + len > jpeg.length) break;
            int start = p + 4, size = len - 2;
            if (marker == 0xe1 && size >= 14 && jpeg[start] == 'E' && jpeg[start + 1] == 'x' && jpeg[start + 2] == 'i' && jpeg[start + 3] == 'f'
                    && jpeg[start + 4] == 0 && jpeg[start + 5] == 0) return tiff(jpeg, start + 6, size - 6);
            p += 2 + len;
        }
        return 1;
    }
    private static int tiff(byte[] b, int start, int length) {
        if (length < 8) return -1;
        boolean le = b[start] == 'I' && b[start + 1] == 'I';
        if (!le && !(b[start] == 'M' && b[start + 1] == 'M')) return -1;
        if (u16(b, start + 2, le) != 42) return -1;
        long offset = u32(b, start + 4, le);
        if (offset < 8 || offset + 2 > length) return -1;
        int dir = start + (int) offset;
        int count = u16(b, dir, le);
        if (count > 256 || dir + 2L + count * 12L > start + length) return -1;
        for (int i = 0; i < count; i++) {
            int entry = dir + 2 + i * 12;
            if (u16(b, entry, le) == 0x0112) {
                if (u16(b, entry + 2, le) != 3 || u32(b, entry + 4, le) != 1) return -1;
                return u16(b, entry + 8, le);
            }
        }
        return 1;
    }
    private static int u16(byte[] b, int p, boolean le) { return le ? (b[p] & 255) | ((b[p + 1] & 255) << 8) : ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static long u32(byte[] b, int p, boolean le) { return le ? (b[p] & 255L) | ((b[p + 1] & 255L) << 8) | ((b[p + 2] & 255L) << 16) | ((b[p + 3] & 255L) << 24) : ((b[p] & 255L) << 24) | ((b[p + 1] & 255L) << 16) | ((b[p + 2] & 255L) << 8) | (b[p + 3] & 255L); }
}
