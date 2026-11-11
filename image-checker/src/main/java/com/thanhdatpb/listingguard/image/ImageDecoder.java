package com.thanhdatpb.listingguard.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Inspects stream dimensions before decoding; has no file or network access. */
final class ImageDecoder {
    DecodedImage decode(byte[] encoded, String[] allowlist, long maxPixels) throws IOException {
        if (encoded.length == 0) throw new IOException("empty image");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (stream == null) throw new IOException("unreadable image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("unsupported or corrupt image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toUpperCase(java.util.Locale.ROOT);
                if (!java.util.Arrays.asList(allowlist).contains(format)) throw new IOException("unsupported format");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > maxPixels) throw new IOException("decoded pixel safety limit exceeded");
                boolean hasIccProfile = IccProfile.validate(encoded, format);
                var types = reader.getImageTypes(0);
                while (types.hasNext()) {
                    ColorSpace cs = types.next().getColorModel().getColorSpace();
                    if (cs.getType() == ColorSpace.TYPE_CMYK) throw new IOException("CMYK images are unsupported");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IOException("decoder returned no image");
                ColorSpace colorSpace = decoded.getColorModel().getColorSpace();
                if (isCmyk(colorSpace)) throw new IOException("CMYK images are unsupported");
                // Embedded profiles are converted by ColorModel.getRGB; untagged channel samples are interpreted as sRGB.
                BufferedImage srgb = toSrgb(decoded, hasIccProfile);
                int orientation = format.equals("JPEG") ? ExifOrientation.read(encoded) : 1;
                if (orientation < 1 || orientation > 8) throw new IOException("invalid EXIF orientation");
                BufferedImage oriented = orient(srgb, orientation);
                return new DecodedImage(oriented, new ImageMetadata(format, oriented.getWidth(), oriented.getHeight(), orientation,
                        decoded.getColorModel().hasAlpha(), hasIccProfile));
            } finally { reader.dispose(); }
        }
    }

    static boolean isCmyk(ColorSpace colorSpace) { return colorSpace.getType() == ColorSpace.TYPE_CMYK; }

    private static BufferedImage toSrgb(BufferedImage input, boolean embeddedProfile) {
        int type = input.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(input.getWidth(), input.getHeight(), type);
        var model = input.getColorModel();
        for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
            Object pixel = input.getRaster().getDataElements(x, y, null);
            if (embeddedProfile || model.getColorSpace().isCS_sRGB() || model instanceof java.awt.image.IndexColorModel || model.isAlphaPremultiplied()) {
                out.setRGB(x, y, input.getRGB(x, y)); continue;
            }
            int[] components = model.getComponents(pixel, null, 0);
            int[] sizes = model.getComponentSize();
            int alpha = model.hasAlpha() ? scale(components[components.length - 1], sizes[sizes.length - 1]) : 255;
            int count = model.getNumColorComponents();
            int red, green, blue;
            if (count == 1) red = green = blue = scale(components[0], sizes[0]);
            else if (count == 3) { red = scale(components[0], sizes[0]); green = scale(components[1], sizes[1]); blue = scale(components[2], sizes[2]); }
            else { out.setRGB(x, y, input.getRGB(x, y)); continue; }
            out.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
        }
        return out;
    }
    private static int scale(int value, int bits) { long max = bits >= 31 ? 0x7fffffffL : (1L << bits) - 1; return (int) ((value * 255L + max / 2) / max); }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        int sw = source.getWidth(), sh = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? sh : sw, swap ? sw : sh,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int dx, dy;
            switch (orientation) {
                case 2 -> { dx = sw - 1 - x; dy = y; }
                case 3 -> { dx = sw - 1 - x; dy = sh - 1 - y; }
                case 4 -> { dx = x; dy = sh - 1 - y; }
                case 5 -> { dx = y; dy = x; }
                case 6 -> { dx = sh - 1 - y; dy = x; }
                case 7 -> { dx = sh - 1 - y; dy = sw - 1 - x; }
                case 8 -> { dx = y; dy = sw - 1 - x; }
                default -> throw new IllegalArgumentException("Unsupported EXIF orientation");
            }
            out.setRGB(dx, dy, source.getRGB(x, y));
        }
        return out;
    }
}
