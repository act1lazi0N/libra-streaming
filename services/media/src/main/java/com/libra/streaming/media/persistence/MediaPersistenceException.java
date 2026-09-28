package com.libra.streaming.media.persistence;

/** Stable internal failure code; no storage key or request data is exposed. */
public class MediaPersistenceException extends RuntimeException {
    private final String code;

    public MediaPersistenceException(String code) { super(code); this.code = code; }

    public String code() { return code; }
}
