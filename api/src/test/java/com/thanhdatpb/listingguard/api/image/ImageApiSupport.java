package com.thanhdatpb.listingguard.api.image;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Builds the multipart requests and the synthetic images of the image-check tests. Every image is drawn in code (no real
 * product photo, no real mark), and every threshold the tests rely on is in {@code image/*-image-test.yaml}, test-only files
 * with invented numbers.
 */
final class ImageApiSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The settings that turn the endpoint on and point it at the test rulesets. */
    static final String[] ENABLED = {
            "listing-guard.image.enabled=true",
            "listing-guard.image.rulesets.AMAZON=classpath:image/amazon-image-test.yaml",
            "listing-guard.image.rulesets.ETSY=classpath:image/etsy-image-test.yaml"};

    static final String CHECKS = "/api/v1/image-checks";

    private ImageApiSupport() {
    }

    /** A white canvas with a checkerboard block in the middle: white border, sharp, the product fills about a quarter. */
    static byte[] product(int width, int height, String format, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        int left = width / 4;
        int top = height / 4;
        int blockWidth = width / 2;
        int blockHeight = height / 2;
        for (int y = 0; y < blockHeight; y += 4) {
            for (int x = 0; x < blockWidth; x += 4) {
                g.setColor(((x / 4 + y / 4 + seed) & 1) == 0 ? new Color(20, 20, 20) : new Color(200, 60, 60));
                g.fillRect(left + x, top + y, 4, 4);
            }
        }
        g.dispose();
        return encode(image, format);
    }

    /** One flat grey colour all over: no white border, nothing sharp. */
    static byte[] flat(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(120, 120, 120));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return encode(image, format);
    }

    /** Bytes that are no image at all. */
    static byte[] notAnImage() {
        return ("this is not an image " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One image of a request: the bytes and the declared {@code role} and optional {@code source} (null leaves it out). */
    record Part(byte[] bytes, String role, String source) {
        Part withSource(String newSource) {
            return new Part(bytes, role, newSource);
        }

        Part withRole(String newRole) {
            return new Part(bytes, newRole, source);
        }
    }

    static Part main(byte[] bytes) {
        return new Part(bytes, "MAIN", "SELLER");
    }

    static Part additional(byte[] bytes) {
        return new Part(bytes, "ADDITIONAL", null);
    }

    static String metadata(String marketplace, String profile, List<Part> parts) {
        StringBuilder json = new StringBuilder("{\"marketplace\":\"").append(marketplace).append('"');
        if (profile != null) {
            json.append(",\"profile\":\"").append(profile).append('"');
        }
        json.append(",\"images\":[");
        List<String> entries = new ArrayList<>();
        for (Part part : parts) {
            List<String> members = new ArrayList<>();
            if (part.role() != null) {
                members.add("\"role\":\"" + part.role() + "\"");
            }
            if (part.source() != null) {
                members.add("\"source\":\"" + part.source() + "\"");
            }
            entries.add("{" + String.join(",", members) + "}");
        }
        return json.append(String.join(",", entries)).append("]}").toString();
    }

    /** A request with the given metadata text and image parts; the file names are made up and never matter. */
    static MockMultipartHttpServletRequestBuilder requestWith(String metadata, List<Part> parts) {
        MockMultipartHttpServletRequestBuilder builder = multipart(CHECKS);
        builder.file(new MockMultipartFile("metadata", "", "application/json", metadata.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < parts.size(); i++) {
            builder.file(new MockMultipartFile("images", "photo-" + i + ".bin", "application/octet-stream", parts.get(i).bytes()));
        }
        return builder;
    }

    static MockMultipartHttpServletRequestBuilder request(String marketplace, List<Part> parts) {
        return requestWith(metadata(marketplace, null, parts), parts);
    }
}
