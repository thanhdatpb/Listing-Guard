package com.thanhdatpb.listingguard.api.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness and readiness in one: 200 only when the database can be queried, 503 otherwise. No API key needed. */
@RestController
public class HealthController {

    private final HealthProbe probe;

    public HealthController(HealthProbe probe) {
        this.probe = probe;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        if (probe.isUp()) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN", "reason", "database unavailable"));
    }
}
