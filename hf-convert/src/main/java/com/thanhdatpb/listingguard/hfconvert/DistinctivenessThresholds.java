package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

record DistinctivenessThresholds(String version, String sourceStatus, int weakSerialMinimum, int weakClassMinimum,
                                 int strongWordMinimum, int strongSerialMaximum, int strongClassMaximum) {
    private static final String RESOURCE = "/hf/distinctiveness-2026-11.json";

    DistinctivenessThresholds {
        if (version == null || version.isBlank() || !"unverified".equals(sourceStatus)
                || weakSerialMinimum <= strongSerialMaximum || weakClassMinimum <= strongClassMaximum
                || strongWordMinimum < 2 || strongSerialMaximum < 1 || strongClassMaximum < 1
                || weakClassMinimum > 45 || strongWordMinimum > 300) {
            throw new HfConvertException("Invalid distinctiveness threshold configuration.");
        }
    }

    static DistinctivenessThresholds load() throws IOException {
        try (var input = DistinctivenessThresholds.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing " + RESOURCE);
            }
            return new ObjectMapper().readValue(input, DistinctivenessThresholds.class);
        }
    }
}
