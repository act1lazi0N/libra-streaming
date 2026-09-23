package com.libra.streaming.core.identity;

import org.springframework.http.HttpStatus;

public class IdentityException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;

    public IdentityException(HttpStatus status, String code) {
        this(status, code, 0);
    }

    public IdentityException(HttpStatus status, String code, long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }

    public static IdentityException invalidToken() {
        return new IdentityException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN");
    }

    public static IdentityException unauthenticated() {
        return new IdentityException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }
}
