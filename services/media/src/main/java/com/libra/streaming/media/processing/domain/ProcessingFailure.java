package com.libra.streaming.media.processing.domain;

/** Stable public failure codes; remote messages and exceptions never become persisted reasons. */
public enum ProcessingFailure {
    SOURCE_MISSING, SIZE_MISMATCH, CHECKSUM_MISMATCH, UNSUPPORTED_MEDIA, CORRUPT_INPUT,
    PROCESSING_FAILED;

    public boolean retryable() { return this == PROCESSING_FAILED; }
}
