package com.thanhdatpb.listingguard.api.review;

import org.springframework.http.HttpStatus;

final class ReviewCaseException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    ReviewCaseException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
