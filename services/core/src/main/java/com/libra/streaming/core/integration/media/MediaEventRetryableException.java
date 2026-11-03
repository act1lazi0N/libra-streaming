package com.libra.streaming.core.integration.media;

public final class MediaEventRetryableException extends RuntimeException {
    public MediaEventRetryableException() {
        super("Media event processing temporarily unavailable", null, false, false);
    }
}
