package com.libra.streaming.media.upload.application;

/** Stable internal failure code; no storage key or request data is exposed. */
public class UploadFailure extends RuntimeException {
    private final String code;

    public UploadFailure(String code) { super(code); this.code = code; }

    public String code() { return code; }
}
