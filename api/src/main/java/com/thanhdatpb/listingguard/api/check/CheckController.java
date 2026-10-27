package com.thanhdatpb.listingguard.api.check;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CheckController {

    private final CheckService service;

    public CheckController(CheckService service) {
        this.service = service;
    }

    @PostMapping("/checks")
    public CheckResponse check(@Valid @RequestBody CheckRequest request) {
        return service.check(request);
    }
}
