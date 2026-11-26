package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

record DistinctivenessThresholds(String version, String sourceStatus, int weakSerialMinimum, int weakClassMinimum,
                                 int strongWordMinimum, int strongSerialMaximum, int strongClassMaximum,
                                 int weakOwnerMinimum, int dictionaryOwnerMaximum, int strongOwnerCount) {
    static final String LEGACY = "distinctiveness-2026-11";
    static final String DEFAULT_VERSION = "distinctiveness-2026-12";

    DistinctivenessThresholds {
        if (!"unverified".equals(sourceStatus) || strongWordMinimum < 2 || strongWordMinimum > 300) {
            throw new HfConvertException("Invalid distinctiveness threshold configuration.");
        }
        if (LEGACY.equals(version)) {
            if (weakSerialMinimum <= strongSerialMaximum || weakClassMinimum <= strongClassMaximum
                    || strongSerialMaximum < 1 || strongClassMaximum < 1 || weakClassMinimum > 45) {
                throw new HfConvertException("Invalid serial thresholds.");
            }
        } else if (!DEFAULT_VERSION.equals(version) || strongOwnerCount < 1
                || dictionaryOwnerMaximum < strongOwnerCount || weakOwnerMinimum <= dictionaryOwnerMaximum) {
            throw new HfConvertException("Invalid owner thresholds.");
        }
    }

    boolean usesOwners() {
        return !LEGACY.equals(version);
    }

    static DistinctivenessThresholds load(String version) throws IOException {
        if (!LEGACY.equals(version) && !DEFAULT_VERSION.equals(version)) {
            throw new HfConvertException("Unknown --distinctiveness version: " + version);
        }
        try (var input = DistinctivenessThresholds.class.getResourceAsStream("/hf/" + version + ".json")) {
            if (input == null) {
                throw new IOException("Missing distinctiveness thresholds: " + version);
            }
            DistinctivenessThresholds thresholds = new ObjectMapper().readValue(input, DistinctivenessThresholds.class);
            if (!version.equals(thresholds.version())) {
                throw new HfConvertException("Distinctiveness threshold version does not match its file name.");
            }
            return thresholds;
        }
    }
}
