package com.thanhdatpb.listingguard.image;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The process must initialize this once before the first decoder is created. */
public final class ImageIoRuntime {
    private static final boolean CACHE_DISABLED;
    static {
        ImageIO.setUseCache(false);
        CACHE_DISABLED = !ImageIO.getUseCache();
    }
    private ImageIoRuntime() { }
    public static boolean cacheDisabled() { return CACHE_DISABLED; }

    static TreeMap<String, String> fingerprintComponents() {
        TreeMap<String, String> values = new TreeMap<>();
        values.put("jdk", System.getProperty("java.runtime.version", "unknown"));
        values.put("vendor", System.getProperty("java.vendor", "unknown"));
        values.put("imageio_cache_disabled", Boolean.toString(cacheDisabled()));
        String readers = providerNames();
        values.put("imageio_readers", readers);
        values.put("color_management", System.getProperty("sun.java2d.cmm", "jdk-default"));
        return values;
    }
    private static String providerNames() {
        var providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        java.util.List<String> names = new java.util.ArrayList<>();
        providers.forEachRemaining(p -> names.add(p.getPluginClassName()));
        return names.stream().sorted().collect(Collectors.joining(","));
    }
}
