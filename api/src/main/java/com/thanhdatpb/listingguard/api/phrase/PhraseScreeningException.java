package com.thanhdatpb.listingguard.api.phrase;

import org.springframework.http.HttpStatus;

public final class PhraseScreeningException extends RuntimeException {
    final HttpStatus status;

    public HttpStatus status() { return status; }

    public String code() { return code; }
    final String code;

    PhraseScreeningException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
}
