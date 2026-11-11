package com.thanhdatpb.listingguard.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class ImageCheckerTest {
    @Test void shippedRulesetsAreUnverifiedAndHashPinned() {
        ImageRuleset amazon = ImageRuleSetLoader.loadClasspath(ImageMarketplace.AMAZON);
        ImageRuleset etsy = ImageRuleSetLoader.loadClasspath(ImageMarketplace.ETSY);
        assertThat(amazon.version()).isEqualTo("amazon-image-2026-11");
        assertThat(etsy.version()).isEqualTo("etsy-image-2026-11");
        assertThat(amazon.checkedAt()).isEqualTo("2026-11-11");
        assertThat(amazon.contentHash()).isEqualTo("dca68e68006b461e7bf843a3d1e593c3f5cba4da87a1cbdaa899ad13547581d2");
        assertThat(etsy.contentHash()).isEqualTo("48684d693f918232229a9385888c030280db5aa8d6678ee1e8e874408c3683d3");
        for (ImageRuleset ruleset : List.of(amazon, etsy)) {
            JsonNode rules = ruleset.document().path("rules");
            rules.fields().forEachRemaining(entry -> {
                assertThat(entry.getValue().path("source").path("status").asText()).isEqualTo("unverified");
                assertThat(Severity.valueOf(entry.getValue().path("severity").asText())).isNotEqualTo(Severity.ERROR);
            });
        }
    }

    @Test void sourceWarningIsEmittedEvenWhenBytesCannotBeDecoded() {
        ImageChecker checker = checker(ImageMarketplace.ETSY);
        ImageCheckResult result = checker.check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(new byte[]{1, 2, 3}, ImageRole.MAIN, ImageSource.CUSTOMER_CONTENT))));
        assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
        assertThat(result.images()).singleElement().satisfies(image -> {
            assertThat(image.completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
            assertThat(image.failedRules()).contains("image_decode");
            assertThat(image.findings()).filteredOn(f -> f.ruleId().equals("customer_content"))
                    .singleElement().extracting(ImageFinding::severity).isEqualTo(Severity.WARNING);
        });
        assertThat(result.summary()).isEqualTo("INCOMPLETE");
    }

    @Test void missingShippedMarketplaceThresholdsProduceIncompleteInsteadOfInventedFindings() throws Exception {
        BufferedImage image = solid(Color.WHITE);
        for (ImageMarketplace market : ImageMarketplace.values()) {
            ImageCheckResult result = new ImageChecker(ImageRuleSetLoader.loadClasspath(market), "default")
                    .check(input(market, List.of(new ImageItem(png(image), ImageRole.MAIN, ImageSource.SELLER))));
            assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
            assertThat(result.summary()).isEqualTo("INCOMPLETE");
            assertThat(result.failedRules()).isNotEmpty();
            assertThat(result.images().getFirst().findings()).isEmpty();
        }
    }

    @Test void configuredCountDimensionsAndAspectRulesReportWarnings() throws Exception {
        BufferedImage image = new BufferedImage(101, 10, BufferedImage.TYPE_INT_RGB);
        ImageCheckResult tooLarge = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(png(image), ImageRole.ADDITIONAL, ImageSource.SELLER))));
        assertThat(tooLarge.images().getFirst().findings()).extracting(ImageFinding::ruleId).contains("image_dimensions", "aspect_ratio");
        ImageCheckResult wrongCount = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY, List.of()));
        assertThat(wrongCount.findings()).extracting(ImageFinding::ruleId).contains("image_count");
    }

    @Test void generatedPngRunsConfiguredChecksAndCompositesAlphaOnWhite() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) image.setRGB(x, y, 0x00ff0000);
        for (int y = 3; y < 7; y++) for (int x = 3; x < 7; x++) image.setRGB(x, y, 0xff000000);
        ImageCheckResult result = checker(ImageMarketplace.AMAZON).check(input(ImageMarketplace.AMAZON,
                List.of(new ImageItem(png(image), ImageRole.MAIN, ImageSource.SELLER))));
        assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.COMPLETE);
        assertThat(result.summary()).isEqualTo("READY");
        assertThat(result.images()).singleElement().satisfies(item -> {
            assertThat(item.metadata().hadAlpha()).isTrue();
            assertThat(item.metadata().width()).isEqualTo(10);
            assertThat(item.metadata().height()).isEqualTo(10);
            assertThat(item.findings()).isEmpty();
        });
    }

    @Test void grayMainImageProducesBackgroundWarningWithoutClaimingIncomplete() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) image.setRGB(x, y, Color.GRAY.getRGB());
        ImageCheckResult result = checker(ImageMarketplace.AMAZON).check(input(ImageMarketplace.AMAZON,
                List.of(new ImageItem(png(image), ImageRole.MAIN, ImageSource.UNKNOWN))));
        assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.COMPLETE);
        assertThat(result.images().getFirst().findings()).extracting(ImageFinding::ruleId).contains("main_background");
        assertThat(result.summary()).isEqualTo("REVIEW");
    }

    @Test void blurUsesVarianceOfLaplacianSignal() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) image.setRGB(x, y, Color.WHITE.getRGB());
        String rules = syntheticRules(ImageMarketplace.ETSY).replace("min_laplacian_variance: 0.0", "min_laplacian_variance: 1.0");
        ImageChecker checker = new ImageChecker(ImageRuleSetLoader.parse(rules, ImageMarketplace.ETSY), "default");
        ImageCheckResult result = checker.check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(png(image), ImageRole.ADDITIONAL, ImageSource.SELLER))));
        assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.COMPLETE);
        assertThat(result.images().getFirst().findings()).extracting(ImageFinding::ruleId).contains("blur");
    }

    @Test void exifOrientationIsAppliedBeforeDimensions() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = jpegWithOrientation(image, 6);
        ImageCheckResult result = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(jpeg, ImageRole.ADDITIONAL, ImageSource.SELLER))));
        assertThat(result.images().getFirst().metadata().orientation()).isEqualTo(6);
        assertThat(result.images().getFirst().metadata().width()).isEqualTo(2);
        assertThat(result.images().getFirst().metadata().height()).isEqualTo(3);
    }

    @Test void invalidExifOrientationMakesOnlyThatImageIncomplete() throws Exception {
        byte[] jpeg = jpegWithOrientation(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), 9);
        ImageCheckResult result = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(jpeg, ImageRole.MAIN, ImageSource.SELLER))));
        assertThat(result.completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
        assertThat(result.images().getFirst().failedRules()).contains("image_decode");
    }

    @Test void validEmbeddedIccProfileIsRecordedAndInvalidProfileIsIncomplete() throws Exception {
        byte[] embedded = pngWithIcc(png(solid(Color.WHITE)), ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        ImageCheckResult valid = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(embedded, ImageRole.ADDITIONAL, ImageSource.SELLER))));
        assertThat(valid.images().getFirst().metadata().hadIccProfile()).isTrue();
        assertThat(valid.images().getFirst().completeness()).isEqualTo(ImageItemResult.Completeness.COMPLETE);

        byte[] invalid = pngWithIcc(png(solid(Color.WHITE)), new byte[]{1, 2, 3, 4});
        ImageCheckResult incomplete = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(invalid, ImageRole.ADDITIONAL, ImageSource.SELLER))));
        assertThat(incomplete.images().getFirst().completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
    }

    @Test void unsupportedFormatsAndCmykAreNotDecodedAsComplete() throws Exception {
        ByteArrayOutputStream gif = new ByteArrayOutputStream(); ImageIO.write(solid(Color.WHITE), "GIF", gif);
        ImageCheckResult result = checker(ImageMarketplace.ETSY).check(input(ImageMarketplace.ETSY,
                List.of(new ImageItem(gif.toByteArray(), ImageRole.MAIN, ImageSource.SELLER))));
        assertThat(result.images().getFirst().completeness()).isEqualTo(ImageItemResult.Completeness.INCOMPLETE);
        ColorSpace cmyk = new ColorSpace(ColorSpace.TYPE_CMYK, 4) {
            public float[] toRGB(float[] c) { return new float[]{c[0], c[1], c[2]}; }
            public float[] fromRGB(float[] c) { return new float[]{c[0], c[1], c[2], 0}; }
            public float[] toCIEXYZ(float[] c) { return new float[]{c[0], c[1], c[2]}; }
            public float[] fromCIEXYZ(float[] c) { return new float[]{c[0], c[1], c[2], 0}; }
        };
        assertThat(ImageDecoder.isCmyk(cmyk)).isTrue();
    }

    @Test void changedBytesOrderRoleSourceMarketplaceOrProfileChangesCanonicalHash() throws Exception {
        byte[] a = png(solid(Color.WHITE)), b = png(solid(Color.BLACK));
        String base = CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash();
        assertThat(CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(b, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash()).isNotEqualTo(base);
        assertThat(CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.ADDITIONAL, ImageSource.UNKNOWN)))).hash()).isNotEqualTo(base);
        assertThat(CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.CUSTOMER_CONTENT)))).hash()).isNotEqualTo(base);
        assertThat(CanonicalImageInput.of(input(ImageMarketplace.AMAZON, List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash()).isNotEqualTo(base);
        assertThat(CanonicalImageInput.of(new ImageCheckInput(ImageMarketplace.ETSY, "profile-b", List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash()).isNotEqualTo(base);
        String ordered = CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN), new ImageItem(b, ImageRole.ADDITIONAL, ImageSource.SELLER)))).hash();
        String reversed = CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(b, ImageRole.ADDITIONAL, ImageSource.SELLER), new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash();
        assertThat(ordered).isNotEqualTo(reversed);
        assertThat(CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.MAIN, null)))).hash())
                .isEqualTo(CanonicalImageInput.of(input(ImageMarketplace.ETSY, List.of(new ImageItem(a, ImageRole.MAIN, ImageSource.UNKNOWN)))).hash());
    }

    @Test void unverifiedRuleCannotBeConfiguredAsErrorAndImageIoCacheIsDisabledOnce() {
        String bad = syntheticRules(ImageMarketplace.ETSY).replace("severity: WARNING", "severity: ERROR");
        assertThatThrownBy(() -> ImageRuleSetLoader.parse(bad, ImageMarketplace.ETSY)).isInstanceOf(ImageCheckException.class);
        assertThat(ImageIoRuntime.cacheDisabled()).isTrue();
    }

    @Test void analysisFingerprintIncludesRulesetAndImageIoColorRuntime() {
        ImageAnalysisFingerprint fingerprint = ImageAnalysisFingerprint.of(ImageRuleSetLoader.loadClasspath(ImageMarketplace.AMAZON));
        assertThat(fingerprint.value()).matches("[0-9a-f]{64}");
        assertThat(fingerprint.descriptor()).contains("runtime.imageio_cache_disabled=true", "runtime.imageio_readers=", "runtime.color_management=")
                .contains("icc_conversion=java2d-srgb-v1", "ruleset_hash=");
    }

    private static ImageChecker checker(ImageMarketplace marketplace) {
        return new ImageChecker(ImageRuleSetLoader.parse(syntheticRules(marketplace), marketplace), "default");
    }
    private static ImageCheckInput input(ImageMarketplace market, List<ImageItem> items) { return new ImageCheckInput(market, "default", items); }
    private static BufferedImage solid(Color color) { BufferedImage i = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB); for (int y=0;y<10;y++) for(int x=0;x<10;x++) i.setRGB(x,y,color.getRGB()); return i; }
    private static byte[] png(BufferedImage image) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", out); return out.toByteArray(); }
    private static byte[] jpegWithOrientation(BufferedImage image, int orientation) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); ImageIO.write(image, "JPEG", encoded);
        byte[] tiff = new byte[]{'I','I',42,0,8,0,0,0,1,0,0x12,0x01,3,0,1,0,0,0,(byte)orientation,0,0,0,0,0,0,0};
        byte[] payload = new byte[6 + tiff.length]; byte[] exif = new byte[]{'E','x','i','f',0,0}; System.arraycopy(exif,0,payload,0,6); System.arraycopy(tiff,0,payload,6,tiff.length);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] jpg=encoded.toByteArray(); result.write(jpg,0,2); result.write(0xff); result.write(0xe1); int length=payload.length+2; result.write(length>>>8); result.write(length); result.write(payload); result.write(jpg,2,jpg.length-2); return result.toByteArray();
    }
    private static byte[] pngWithIcc(byte[] png, byte[] profile) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(profile); }
        ByteArrayOutputStream chunkData = new ByteArrayOutputStream(); chunkData.write("ICC".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); chunkData.write(0); chunkData.write(0); chunkData.write(compressed.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(png, 0, 8);
        int pos = 8;
        while (pos + 12 <= png.length) {
            int size = ((png[pos]&255)<<24)|((png[pos+1]&255)<<16)|((png[pos+2]&255)<<8)|(png[pos+3]&255);
            String type = new String(png, pos+4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals("IDAT")) writePngChunk(out, "iCCP", chunkData.toByteArray());
            out.write(png, pos, size + 12); pos += size + 12;
            if (type.equals("IDAT")) break;
        }
        out.write(png, pos, png.length - pos);
        return out.toByteArray();
    }
    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        DataOutputStream d = new DataOutputStream(out); d.writeInt(data.length); byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII); d.write(name); d.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data); d.writeInt((int)crc.getValue());
    }
    private static String syntheticRules(ImageMarketplace market) {
        String version = market.name().toLowerCase() + "-image-test";
        String source = "source: {status: unverified, urls: [https://example.invalid/policy]}";
        String rule = "severity: WARNING\n    message: Synthetic advisory\n    " + source;
        StringBuilder yaml = new StringBuilder("""
                pack_id: image-compliance
                version: %s
                marketplace: %s
                checked_at: 2026-11-11
                supported_formats: [JPEG, PNG]
                limits: {max_encoded_bytes: 100000, max_decoded_pixels: 100000}
                profiles:
                  default:
                    image_count: {min: 1, max: 3}
                    width: {min: 1, max: 100}
                    height: {min: 1, max: 100}
                    aspect_ratio: {min: 0.1, max: 10.0}
                    main_background: {edge_rgb_tolerance: 0, min_white_ratio: 0.7}
                    occupancy: {min_ratio: 0.01, white_rgb_tolerance: 0}
                    blur: {min_laplacian_variance: 0.0}
                rules:
                """.formatted(version, market.name()));
        for (String id : List.of("image_count", "image_dimensions", "aspect_ratio", "main_background", "occupancy", "blur", "customer_content")) yaml.append("  ").append(id).append(":\n    ").append(rule).append('\n');
        yaml.append("summary:\n  by_severity: {ERROR: BLOCK, WARNING: REVIEW, INFO: REVIEW, NONE: READY}\n  incomplete: INCOMPLETE\n");
        return yaml.toString();
    }
}
