package com.thanhdatpb.listingguard.api.check;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checks")
public class CheckController {

    private final CheckService service;

    public CheckController(CheckService service) {
        this.service = service;
    }

    @PostMapping
    public CheckResult check(@Valid @RequestBody CheckRequest request) {
        return service.check(request);
    }

    @GetMapping("/{id}")
    public CheckResult get(@PathVariable UUID id) {
        return service.get(id);
    }
}
