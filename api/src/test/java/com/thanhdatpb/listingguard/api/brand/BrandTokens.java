package com.thanhdatpb.listingguard.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the design tokens (ADR-040) the way a browser would resolve them, for tests: {@code static/brand/tokens.css} (light, and
 * dark from the {@code prefers-color-scheme: dark} block) plus the shades {@code app.css} derives with {@code color-mix(in srgb, ...)}.
 * Colors are {@code int[]{r, g, b}}; the mix is the browser's, a straight interpolation of the sRGB channels.
 */
public final class BrandTokens {

    public static final Path BRAND = Path.of("src/main/resources/static/brand");
    public static final Path APP_CSS = Path.of("src/main/resources/static/console/app.css");
    public static final Path DESIGN = Path.of("..", "docs", "design");

    private static final Pattern DECLARATION = Pattern.compile("--([a-z0-9-]+)\\s*:\\s*([^;]+);");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})\\b");
    private static final Pattern MIX = Pattern.compile(
            "color-mix\\(in srgb,\\s*var\\(--([a-z0-9-]+)\\)\\s*(\\d+)%,\\s*var\\(--([a-z0-9-]+)\\)\\)");

    private final Map<String, int[]> light = new LinkedHashMap<>();
    private final Map<String, int[]> dark = new LinkedHashMap<>();

    private BrandTokens() {
    }

    public static BrandTokens load() {
        BrandTokens tokens = new BrandTokens();
        String css = read(BRAND.resolve("tokens.css"));
        int dark = css.indexOf("@media screen and (prefers-color-scheme: dark)");
        if (dark < 0) {
            throw new IllegalStateException("tokens.css has no dark block");
        }
        collect(css.substring(css.indexOf(":root"), dark), tokens.light);
        tokens.dark.putAll(tokens.light);
        collect(css.substring(dark), tokens.dark);
        tokens.derive(read(APP_CSS));
        return tokens;
    }

    public static BrandTokens fromDesign() {
        var tokens = new BrandTokens();
        try {
            var definitions = new ObjectMapper().readTree(DESIGN.resolve("tokens.json").toFile()).path("color").path("tokens");
            for (var definition : definitions) {
                String name = definition.path("name").asText();
                var value = definition.path("value");
                tokens.light.put(name, rgb(value.path("light").asText().substring(1)));
                tokens.dark.put(name, rgb(value.path("dark").asText().substring(1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        tokens.derive(read(APP_CSS));
        return tokens;
    }

    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void collect(String block, Map<String, int[]> into) {
        Matcher matcher = DECLARATION.matcher(block);
        while (matcher.find()) {
            Matcher hex = HEX.matcher(matcher.group(2).strip());
            if (hex.matches()) {
                into.put(matcher.group(1), rgb(hex.group(1)));
            }
        }
    }

    private void derive(String appCss) {
        int root = appCss.indexOf(":root");
        String block = appCss.substring(root, appCss.indexOf("\n}", root));
        Matcher matcher = DECLARATION.matcher(block);
        while (matcher.find()) {
            Matcher mix = MIX.matcher(matcher.group(2).strip());
            if (mix.matches()) {
                String name = matcher.group(1);
                light.put(name, mix(light, mix));
                dark.put(name, mix(dark, mix));
            }
        }
    }

    private static int[] mix(Map<String, int[]> scheme, Matcher mix) {
        int[] a = scheme.get(mix.group(1));
        int[] b = scheme.get(mix.group(3));
        if (a == null || b == null) {
            throw new IllegalStateException("color-mix names a token that is not a color: " + mix.group());
        }
        int percent = Integer.parseInt(mix.group(2));
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = (int) Math.round(a[i] * percent / 100.0 + b[i] * (100 - percent) / 100.0);
        }
        return out;
    }

    private static int[] rgb(String hex) {
        return new int[] {Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)};
    }

    public int[] color(boolean darkScheme, String name) {
        int[] color = (darkScheme ? dark : light).get(name);
        if (color == null) {
            throw new IllegalArgumentException("no color token named " + name);
        }
        return color;
    }

    public boolean has(String name) {
        return light.containsKey(name);
    }

    /** WCAG 2.x contrast ratio of two colors. */
    public static double contrast(int[] a, int[] b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int[] color) {
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = color[i] / 255.0;
            linear[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }
}
