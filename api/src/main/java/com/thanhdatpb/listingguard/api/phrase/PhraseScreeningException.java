package com.thanhdatpb.listingguard.api.phrase;

import org.springframework.http.HttpStatus;

final class PhraseScreeningException extends RuntimeException {
    final HttpStatus status;
    final String code;

    PhraseScreeningException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
}
