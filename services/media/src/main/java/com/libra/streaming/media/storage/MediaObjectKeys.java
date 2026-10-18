package com.libra.streaming.media.storage;

import java.util.UUID;

/**
 * The one definition of the keys an attempt reserves, shared by the worker that writes them and the cleanup that
 * deletes the unselected ones. The attempt number makes a late object from an old claim unable to land on a key or
 * under a prefix that a successor selects.
 */
final class MediaObjectKeys {
    private MediaObjectKeys() {}

    static String sourceKey(MediaStorageProperties properties, UUID uploadId, int attempt) {
        return properties.sourcePrefix() + attemptPath(uploadId, attempt) + "/source.mp4";
    }

    /** Without a trailing slash. */
    static String outputPrefix(MediaStorageProperties properties, UUID uploadId, int attempt) {
        return properties.hlsPrefix() + attemptPath(uploadId, attempt);
    }

    private static String attemptPath(UUID uploadId, int attempt) {
        if (uploadId == null || attempt < 1 || attempt > 3) { throw new IllegalArgumentException("Invalid attempt"); }
        return uploadId + "/attempt-" + attempt;
    }
}
