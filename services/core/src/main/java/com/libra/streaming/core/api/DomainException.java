package com.libra.streaming.core.api;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public DomainException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static DomainException missing() { return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND"); }
    public static DomainException conflict(String code) { return new DomainException(HttpStatus.CONFLICT, code); }
    public static DomainException invalid() { return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"); }
}
